// SPDX-License-Identifier: AGPL-3.0-only
import AppKit
import Observation
import OSLog
import SeamCore
import SeamProtocol
import SeamSession

/// The app's one live object: owns the device, the relay connection and what the UI shows.
@MainActor @Observable
final class SeamController: RelayHandler {
    private(set) var connected = false
    private(set) var phone: PeerInfo?
    private(set) var status: Seam_V1_Status?
    private(set) var startupError: String?
    /// Saved data exists but can't be opened: the menu offers a reset instead of pairing.
    private(set) var needsReset = false
    /// Something the user should see in the menu (a refused login, a save that failed).
    private(set) var warning: String?
    private(set) var flow = PairingFlow()

    @ObservationIgnored private var store: Store?
    @ObservationIgnored private var relay: RelayClient?
    @ObservationIgnored private var relayTask: Task<Void, Never>?
    @ObservationIgnored private var openTokenHash: Data?
    @ObservationIgnored private var expiryTask: Task<Void, Never>?
    // Logs codes and counts only, never contents or keys (threat model, rule 7)
    private let log = Logger(subsystem: "com.avetero.seam", category: "session")

    /// For the pairing screens: the host the phone will be told to trust with its traffic.
    var relayHost: String { store?.relayURL.host() ?? "" }

    func start() {
        guard store == nil else { return }
        do {
            begin(try Store.open())
        } catch is Store.Unreadable {
            needsReset = true
            startupError = "Seam's data on this Mac can't be opened."
            log.error("saved state unreadable")
        } catch {
            startupError = "Seam couldn't start: \(error.localizedDescription)"
            log.error("startup failed")
        }
    }

    /// Only after the user agreed: a new identity and group, the old files kept aside.
    func reset() {
        do {
            begin(try Store.reset())
            needsReset = false
            startupError = nil
        } catch {
            startupError = "Seam couldn't reset: \(error.localizedDescription)"
            log.error("reset failed")
        }
    }

    private func begin(_ store: Store) {
        relayTask?.cancel()
        self.store = store
        phone = store.device.peers().first { $0.confirmed }
        let relay = RelayClient(device: store.device, groupID: store.appData.groupID, relayURL: store.relayURL, handler: self)
        self.relay = relay
        relayTask = Task { await relay.run() }
    }

    // MARK: Pairing

    func startPairing() {
        guard let store, !flow.isConfirming else { return }
        let offer = store.device.startPairing()
        openTokenHash = offer.tokenHash
        store.needsBundles = true
        let qr = PairingQR(
            relayURL: store.relayURL.absoluteString, groupID: store.appData.groupID, token: offer.token,
            identityKey: store.device.identityKey(), relayKey: store.device.relayKey(), name: Host.current().localizedName ?? "Mac"
        )
        flow.start(qr: qr.url, now: Date())
        expiryTask?.cancel()
        expiryTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(PairingDeadline.lifetime))
            guard !Task.isCancelled, let self, self.flow.expire() else { return }
            self.closeSlot()
        }
        Task { await announcePairing() }
    }

    /// Pair was clicked. Safe to call twice: only the first call does anything.
    func confirmPairing() {
        guard let request = flow.beginConfirming() else { return }
        Task { await sendAccept(request) }
    }

    /// Cancel, or the window closed. Does nothing while PairAccept is already on its way.
    func cancelPairing() {
        guard let previous = flow.cancel() else { return }
        switch previous {
        case .confirm(let request): reject(request)
        case .waiting: closeSlot()
        default: break
        }
    }

    private func sendAccept(_ request: PairingRequest) async {
        guard let store else { return }
        let device = store.device
        let frame: Seam_V1_ClientFrame
        do {
            try device.confirmPairing(peerId: request.peerId)
            let accept = Seam_V1_Payload.with { $0.control = .with { $0.pairAccept = .init() } }
            let sealed = try device.seal(peerId: request.peerId, payload: try accept.serializedData(), ttlS: 0)
            try store.save() // never tell the phone it's paired unless this Mac will remember it
            frame = Frames.send(try Seam_V1_Envelope(serializedBytes: sealed))
        } catch {
            log.error("pairing confirm failed")
            unpair(request.peerId)
            flow.fail("Seam couldn't save the pairing on this Mac. Show a new code and scan it again.")
            return
        }
        // The relay may be reconnecting: retry for about a minute before giving up
        let backoff = Backoff()
        for attempt in 0..<6 {
            if (try? await relay?.send(frame)) != nil { return finishPairing(request) }
            try? await Task.sleep(for: .seconds(backoff.delay(attempt: attempt, jitter: .random(in: 0..<1))))
        }
        log.error("pairing accept not delivered")
        unpair(request.peerId)
        flow.fail("Couldn't reach your phone. Check both are online, then show a new code and scan it again.")
    }

    private func finishPairing(_ request: PairingRequest) {
        // Only a phone the core really holds as confirmed counts as paired
        guard let peer = store?.device.peers().first(where: { $0.id == request.peerId && $0.confirmed }) else {
            flow.fail("Pairing didn't finish. Show a new code and scan it again.")
            return
        }
        expiryTask?.cancel()
        openTokenHash = nil
        phone = peer
        status = nil
        flow.finish(name: request.deviceName)
        Task { await uploadBundlesIfNeeded() }
    }

    /// The user said no to a verified request: forget the phone and take it out of the group.
    private func reject(_ request: PairingRequest) {
        guard let device = store?.device else { return }
        let key = removableRelayKey(request.peerId)
        device.rejectPairing(peerId: request.peerId)
        closeSlot()
        if let key { Task { try? await relay?.send(Frames.removeMember(key)) } }
    }

    /// A phone confirmed here whose PairAccept never got out.
    private func unpair(_ peerId: String) {
        guard let store else { return }
        let key = removableRelayKey(peerId)
        store.device.removePeer(peerId: peerId)
        do { try store.save() } catch { saveFailed() }
        closeSlot()
        if let key { Task { try? await relay?.send(Frames.removeMember(key)) } }
    }

    /// The peer's relay key, unless another (already paired) device shares it.
    private func removableRelayKey(_ peerId: String) -> Data? {
        let peers = store?.device.peers() ?? []
        guard let key = peers.first(where: { $0.id == peerId })?.relayKey else { return nil }
        return peers.contains { $0.id != peerId && $0.confirmed && $0.relayKey == key } ? nil : key
    }

    /// Closes the pairing slot in the core and on the relay, so the code can't be used later.
    private func closeSlot() {
        expiryTask?.cancel()
        store?.device.cancelPairing()
        guard openTokenHash != nil else { return }
        openTokenHash = nil
        Task { try? await relay?.send(Frames.closePairing()) }
    }

    // MARK: RelayHandler

    func relayConnected(members: [Data]) async {
        connected = true
        if warning == Self.loginRefused { warning = nil }
        await announcePairing()
        await uploadBundlesIfNeeded()
    }

    func relayDisconnected() async {
        connected = false
    }

    func relayReceived(_ envelope: Seam_V1_Envelope) async -> Bool {
        guard let store, let bytes = try? envelope.serializedData() else { return true }
        let message: OpenedMessage
        do {
            message = try store.device.open(envelope: bytes)
        } catch SeamError.NotPaired {
            if case .waiting = flow.state { acceptPairing(bytes) }
            return true
        } catch SeamError.Duplicate {
            return true // already handled before a reconnect
        } catch SeamError.UntrustedIdentity {
            warning = "A device tried to talk to this Mac with keys that don't match your paired phone. It was ignored."
            log.error("untrusted identity")
            return true
        } catch {
            log.error("dropped an envelope that failed its checks")
            return true // dropped for good: the relay can delete it
        }
        do {
            try store.save()
        } catch {
            saveFailed()
            return false // not acked: the relay redelivers it
        }
        if let payload = try? Seam_V1_Payload(serializedBytes: message.payload) { handle(payload, from: message.peerId) }
        return true
    }

    func relayPeerJoined(_ deviceKey: Data) async {}
    func relayBundle(deviceKey: Data, bundle: Data) async {}

    func relayError(_ code: String) async {
        log.error("relay error \(code, privacy: .public)")
        if code == "bad_login" { warning = Self.loginRefused }
    }

    // MARK: Private

    private static let loginRefused = "The relay didn't accept this Mac's login. Seam keeps retrying."

    private func acceptPairing(_ envelope: Data) {
        guard let device = store?.device else { return }
        do {
            let request = try device.acceptPairing(envelope: envelope)
            guard flow.received(request) else { return device.rejectPairing(peerId: request.peerId) }
            NSApp.activate() // the safety code needs the user's eyes now
        } catch {
            // A wrong or stale code doesn't use up the slot; keep waiting for the real phone
            log.error("pairing attempt refused")
        }
    }

    private func handle(_ payload: Seam_V1_Payload, from peerId: String) {
        // Features only from the paired, confirmed phone
        guard peerId == phone?.id, store?.device.peers().contains(where: { $0.id == peerId && $0.confirmed }) == true else { return }
        switch payload.body {
        case .status(var s):
            s.batteryPct = min(s.batteryPct, 100)
            status = s
        default: break // other features arrive from M2
        }
    }

    private func announcePairing() async {
        guard let hash = openTokenHash, case .waiting(_, let deadline) = flow.state else { return }
        let ttl = deadline.ttlSeconds(at: Date()) // a reconnect never extends the code's life
        guard ttl > 0 else { return }
        try? await relay?.send(Frames.openPairing(tokenHash: hash, ttl: ttl))
    }

    private func uploadBundlesIfNeeded() async {
        guard let store, store.needsBundles else { return }
        do {
            let bundles = try store.device.prekeyBundles(count: 20)
            try store.save()
            try await relay?.send(Frames.putBundles(bundles))
            store.needsBundles = false
        } catch {
            log.error("bundle upload failed; retrying on next connect")
        }
    }

    private func saveFailed() {
        warning = "Seam couldn't save to disk. Check there's free space; messages wait on the relay until it can."
        log.error("saving state failed")
    }
}
