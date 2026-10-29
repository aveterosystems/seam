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
    enum Pairing: Equatable {
        case idle
        case waiting(qr: String)
        case confirm(PairingRequest)
        case paired(name: String)
        case failed(String)
    }

    private(set) var connected = false
    private(set) var phone: PeerInfo?
    private(set) var status: Seam_V1_Status?
    private(set) var startupError: String?
    var pairing: Pairing = .idle

    @ObservationIgnored private var store: Store?
    @ObservationIgnored private var device: SeamDevice?
    @ObservationIgnored private var relay: RelayClient?
    @ObservationIgnored private var relayTask: Task<Void, Never>?
    @ObservationIgnored private var openTokenHash: Data?
    // Logs codes and counts only, never contents or keys (threat model, rule 7)
    private let log = Logger(subsystem: "com.avetero.seam", category: "session")

    var relayURL: String { store?.relayURL.absoluteString ?? Store.defaultRelayURL }

    func start() {
        guard device == nil else { return }
        do {
            let store = try Store()
            let device = try store.loadOrCreateDevice()
            self.store = store
            self.device = device
            phone = device.peers().first
            let relay = RelayClient(device: device, groupID: store.groupID, relayURL: store.relayURL, handler: self)
            self.relay = relay
            relayTask = Task { await relay.run() }
        } catch {
            startupError = "Seam couldn't start: \(error)"
            log.error("startup failed")
        }
    }

    // MARK: Pairing

    func startPairing() {
        guard let device, let store else { return }
        let offer = device.startPairing()
        openTokenHash = offer.tokenHash
        store.needsBundles = true
        let qr = PairingQR(
            relayURL: store.relayURL.absoluteString, groupID: store.groupID, token: offer.token,
            identityKey: device.identityKey(), relayKey: device.relayKey(), name: Host.current().localizedName ?? "Mac"
        )
        pairing = .waiting(qr: qr.url)
        Task { await announcePairing() }
    }

    func confirmPairing() {
        guard case .confirm(let request) = pairing, let device else { return }
        let accept = Seam_V1_Payload.with { $0.control = .with { $0.pairAccept = .init() } }
        Task {
            do {
                let envelope = try device.seal(peerId: request.peerId, payload: try accept.serializedData(), ttlS: 0)
                save()
                // shortcut: no outbox yet; if this send fails the phone keeps waiting and pairing is retried
                try await relay?.send(Frames.send(try Seam_V1_Envelope(serializedBytes: envelope)))
                openTokenHash = nil
                phone = device.peers().first { $0.id == request.peerId }
                pairing = .paired(name: request.deviceName)
                await uploadBundlesIfNeeded()
            } catch {
                pairing = .failed("Couldn't reach your phone. Check the connection and try again.")
            }
        }
    }

    func cancelPairing() {
        if case .confirm(let request) = pairing {
            device?.removePeer(peerId: request.peerId)
            save()
        }
        openTokenHash = nil
        pairing = .idle
    }

    // MARK: RelayHandler

    func relayConnected(members: [Data]) async {
        connected = true
        await announcePairing()
        await uploadBundlesIfNeeded()
    }

    func relayDisconnected() async {
        connected = false
    }

    func relayReceived(_ envelope: Seam_V1_Envelope) async -> Bool {
        guard let device, let bytes = try? envelope.serializedData() else { return true }
        do {
            let message = try device.open(envelope: bytes)
            save()
            handle(try Seam_V1_Payload(serializedBytes: message.payload))
        } catch SeamError.NotPaired {
            if case .waiting = pairing { acceptPairing(bytes, on: device) }
        } catch SeamError.Duplicate {
            // Already handled before a reconnect: just ack it
        } catch {
            log.error("dropped an envelope that failed its checks")
        }
        return true // handled or dropped for good: either way the relay can delete it
    }

    func relayPeerJoined(_ deviceKey: Data) async {}
    func relayBundle(deviceKey: Data, bundle: Data) async {}

    func relayError(_ code: String) async {
        log.error("relay error \(code, privacy: .public)")
    }

    // MARK: Private

    private func acceptPairing(_ envelope: Data, on device: SeamDevice) {
        do {
            let request = try device.acceptPairing(envelope: envelope)
            save()
            pairing = .confirm(request)
        } catch {
            // A wrong or stale code doesn't use up the slot; keep waiting for the real phone
            log.error("pairing attempt refused")
        }
    }

    private func handle(_ payload: Seam_V1_Payload) {
        switch payload.body {
        case .status(let s): status = s
        default: break // other features arrive from M2
        }
    }

    private func announcePairing() async {
        guard let hash = openTokenHash, case .waiting = pairing else { return }
        try? await relay?.send(Frames.openPairing(tokenHash: hash))
    }

    private func uploadBundlesIfNeeded() async {
        guard let store, let device, store.needsBundles else { return }
        do {
            let bundles = try device.prekeyBundles(count: 20)
            save()
            try await relay?.send(Frames.putBundles(bundles))
            store.needsBundles = false
        } catch {
            log.error("bundle upload failed; retrying on next connect")
        }
    }

    private func save() {
        guard let store, let device else { return }
        do { try store.save(device) } catch { log.error("saving state failed") }
    }
}
