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
    /// The last two things that came from the phone's clipboard, for the popover (memory only).
    private(set) var recentFromPhone: [FromPhone] = []
    /// Clipboard both ways (menu toggle, on by default).
    private(set) var clipboardSync = UserDefaults.standard.object(forKey: "clipboardSync") as? Bool ?? true

    @ObservationIgnored private var store: Store?
    @ObservationIgnored private var relay: RelayClient?
    @ObservationIgnored private var relayTask: Task<Void, Never>?
    @ObservationIgnored private var openTokenHash: Data?
    @ObservationIgnored private var expiryTask: Task<Void, Never>?
    @ObservationIgnored private var clipRules = ClipRules()
    @ObservationIgnored private var lastChange = NSPasteboard.general.changeCount
    @ObservationIgnored private var clipTask: Task<Void, Never>?
    /// The latest copy made while the relay was unreachable (SR-4-03).
    @ObservationIgnored private var pendingClip = ClipQueue<Seam_V1_Payload>()
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
        watchClipboard()
    }

    // MARK: Unpairing

    /// "Unpair <phone>": tells the phone (sealed), takes it out of the relay group, and forgets it
    /// here. A phone that's offline finds out when the relay refuses it, and is told to pair again.
    func unpairPhone() {
        guard let store, let phone else { return }
        let unpair = Seam_V1_Payload.with { $0.control = .with { $0.unpair = .init() } }
        // Not saved before sending, unlike other messages (SR-2-11): the phone's keys are deleted
        // right after, so a crash can't reuse them
        let farewell = (try? store.device.seal(peerId: phone.id, payload: unpair.serializedData(), ttlS: 0))
            .flatMap { try? Seam_V1_Envelope(serializedBytes: $0) }
            .map(Frames.send)
        let key = removableRelayKey(phone.id)
        // Only once forgetting it is saved: otherwise a restart would reload the phone with the
        // ratchet from before the farewell was sealed, and could reuse its key (SR-7-04)
        guard forgetPhone(phone.id) else { return }
        Task {
            if let farewell { try? await relay?.send(farewell) } // first, so a connected phone gets it before it's removed
            if let key { try? await relay?.send(Frames.removeMember(key)) }
        }
    }

    /// The phone is gone (unpaired here, or it unpaired us): its keys, status and anything held for it.
    /// False if that couldn't be saved.
    @discardableResult
    private func forgetPhone(_ peerId: String) -> Bool {
        guard let store else { return false }
        store.device.removePeer(peerId: peerId)
        let saved: Bool
        do {
            try store.save()
            saved = true
        } catch {
            saveFailed()
            saved = false
        }
        if phone?.id == peerId {
            phone = nil
            status = nil
            recentFromPhone = []
        }
        pendingClip.clear()
        return saved
    }

    // MARK: Clipboard

    func setClipboardSync(_ on: Bool) {
        clipboardSync = on
        UserDefaults.standard.set(on, forKey: "clipboardSync")
        lastChange = NSPasteboard.general.changeCount // turning it on never sends what was copied while off
        if !on { pendingClip.clear() } // nor does turning it off let a held copy go later
    }

    /// macOS has no change notification for the pasteboard: checking its counter twice a second is the standard way, and cheap.
    private func watchClipboard() {
        clipTask?.cancel()
        clipTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(500))
                self?.checkPasteboard()
            }
        }
    }

    private func checkPasteboard() {
        let pb = NSPasteboard.general
        let change = pb.changeCount
        guard change != lastChange else { return }
        guard clipboardSync, phone != nil, let item = pb.pasteboardItems?.first else {
            lastChange = change
            return
        }
        // Types and text from the same item, then make sure nothing replaced it meanwhile: a password
        // copied a moment later can't be read under the previous item's types (SR-4-05)
        let types = item.types.map(\.rawValue)
        let string = item.string(forType: .string)
        guard pb.changeCount == change else { return } // look again on the next check
        lastChange = change
        guard ClipRules.sendable(types: types), let text = clipRules.outgoing(string) else { return }
        let payload = Seam_V1_Payload.with {
            $0.clipboard = .with {
                $0.text = text
                $0.copiedAt = Int64(Date().timeIntervalSince1970 * 1000)
            }
        }
        guard connected else {
            pendingClip.hold(payload)
            return
        }
        sendToPhone(payload, ttl: Self.clipboardTTL)
    }

    private func receivedClip(_ clip: Seam_V1_Clipboard) {
        guard clipboardSync, let text = clipRules.incoming(clip.text) else { return }
        let pb = NSPasteboard.general
        pb.clearContents()
        pb.setString(text, forType: .string)
        lastChange = pb.changeCount // our own write isn't a new copy
        recentFromPhone = Array(([FromPhone(text: text, at: Date())] + recentFromPhone).prefix(2))
    }

    /// Puts an earlier clip from the phone back on this Mac's clipboard (popover "Copy").
    func copyAgain(_ text: String) {
        let pb = NSPasteboard.general
        pb.clearContents()
        pb.setString(text, forType: .string)
        lastChange = pb.changeCount // and don't send it back
    }

    /// The code both devices showed at pairing, for re-checking in Settings.
    func phoneCode() -> String? {
        guard let store, let phone else { return nil }
        return try? store.device.safetyCode(peerId: phone.id)
    }

    /// A stale clipboard is worse than none: the relay drops it after 10 minutes.
    private static let clipboardTTL: UInt32 = 600

    /// Sealed, saved, then sent: never sends what a failed save would forget (SR-2-11).
    private func sendToPhone(_ payload: Seam_V1_Payload, ttl: UInt32) {
        guard let store, let phone else { return }
        let frame: Seam_V1_ClientFrame
        do {
            let sealed = try store.device.seal(peerId: phone.id, payload: try payload.serializedData(), ttlS: ttl)
            do { try store.save() } catch { return saveFailed() }
            frame = Frames.send(try Seam_V1_Envelope(serializedBytes: sealed))
        } catch {
            log.error("couldn't seal a message for the phone")
            return
        }
        Task { try? await relay?.send(frame) }
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
        pendingClip.clear() // copied for the previous phone, if any
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
        pendingClip.clear()
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
        if let clip = pendingClip.take(syncOn: clipboardSync) {
            sendToPhone(clip, ttl: Self.clipboardTTL)
        }
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
        let saved = (try? store.save()) != nil
        // Handled even if the save failed: the ratchet has moved, so a redelivery in this run opens
        // as a duplicate and would be lost (SR-2-59). Not acked, so after a restart the saved state
        // opens it again.
        if let payload = try? Seam_V1_Payload(serializedBytes: message.payload) { handle(payload, from: message.peerId) }
        if !saved {
            saveFailed()
            return false
        }
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
        case .clipboard(let c):
            receivedClip(c)
        case .control(let c):
            if case .unpair = c.body {
                // It leaves the relay group itself, but only best-effort: make sure (SR-7-02)
                let key = removableRelayKey(peerId)
                if forgetPhone(peerId), let key { Task { try? await relay?.send(Frames.removeMember(key)) } }
            }
        default: break
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

/// One clip that arrived from the phone.
struct FromPhone: Identifiable {
    let id = UUID()
    let text: String
    let at: Date
}
