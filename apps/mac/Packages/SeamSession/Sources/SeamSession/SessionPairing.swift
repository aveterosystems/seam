// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import SeamCore
import SeamProtocol

/// Pairing around the core's state machine and `PairingFlow`: the code's 5 minutes, the relay's
/// pairing slot, PairAccept retried then undone, and which relay keys may go.
extension Session {
    /// Tries for PairAccept, with backoff between them: about a minute in all.
    static let acceptAttempts = 6

    /// A fresh QR code. `relayURL` and `name` go into it.
    public func startPairing(relayURL: String, name: String) async {
        // Pairing goes through the relay for now
        guard relayOn, !flow.isConfirming, let data = device.appData() else { return }
        let offer = device.startPairing()
        openTokenHash = offer.tokenHash
        storage.setNeedsBundles(true)
        let qr = PairingQR(
            relayURL: relayURL, groupID: data.groupId, token: offer.token,
            identityKey: device.identityKey(), relayKey: device.relayKey(), name: name
        )
        flow.start(qr: qr.url, now: timers.now())
        expiry?.cancel()
        expiry = timers.after(PairingDeadline.lifetime) { [weak self] in await self?.pairingExpired() }
        publish()
        await announcePairing()
    }

    /// Pair was clicked. Safe to call twice: only the first call does anything.
    public func confirmPairing() async {
        guard let request = flow.beginConfirming() else { return }
        publish()
        await sendAccept(request)
    }

    /// Cancel, or the window closed. Does nothing while PairAccept is already on its way.
    public func cancelPairing() async {
        guard let previous = flow.cancel() else { return }
        switch previous {
        case .confirm(let request): await reject(request)
        case .waiting: await closeSlot()
        default: break
        }
        publish()
    }

    /// Saved before the phone hears it's paired: never tell it so unless this Mac will remember.
    private func sendAccept(_ request: PairingRequest) async {
        let frame: Seam_V1_ClientFrame
        do {
            try device.confirmPairing(peerId: request.peerId)
            let accept = Seam_V1_Payload.with { $0.control = .with { $0.pairAccept = .init() } }
            let sealed = try device.seal(peerId: request.peerId, payload: try accept.serializedData(), ttlS: 0)
            device.forgetHello() // a new pairing starts the Hello counts again, on both devices
            try storage.saveState()
            frame = Frames.send(try Seam_V1_Envelope(serializedBytes: sealed))
        } catch {
            log.error("pairing confirm failed")
            await undoPairing(request.peerId)
            flow.fail("Seam couldn't save the pairing on this Mac. Show a new code and scan it again.")
            return publish()
        }
        await tryAccept(frame, request, attempt: 0)
    }

    /// The relay may be reconnecting: retried with backoff, then the pairing is undone.
    private func tryAccept(_ frame: Seam_V1_ClientFrame, _ request: PairingRequest, attempt: Int) async {
        guard attempt < Self.acceptAttempts else {
            log.error("pairing accept not delivered")
            await undoPairing(request.peerId)
            flow.fail("Couldn't reach your phone. Check both are online, then show a new code and scan it again.")
            return publish()
        }
        if let relay, (try? await relay.send(frame)) != nil { return await finishPairing(request) }
        let delay = Backoff().delay(attempt: attempt, jitter: .random(in: 0..<1))
        _ = timers.after(delay) { [weak self] in await self?.tryAccept(frame, request, attempt: attempt + 1) }
    }

    private func finishPairing(_ request: PairingRequest) async {
        // Only a phone the core really holds as confirmed counts as paired
        guard let peer = device.peers().first(where: { $0.id == request.peerId && $0.confirmed }) else {
            flow.fail("Pairing didn't finish. Show a new code and scan it again.")
            return publish()
        }
        expiry?.cancel()
        openTokenHash = nil
        for feature in features { await feature.reset() } // anything held for a previous phone
        phone = peer
        flow.finish(name: request.deviceName)
        publish()
        await sendHello()
        await uploadBundlesIfNeeded()
        publish()
    }

    /// The user said no to a verified request: forget the phone and take it out of the group.
    private func reject(_ request: PairingRequest) async {
        let key = removableRelayKey(request.peerId)
        device.rejectPairing(peerId: request.peerId)
        await closeSlot()
        if let key { try? await relay?.send(Frames.removeMember(key)) }
    }

    /// A phone confirmed here whose PairAccept never got out.
    private func undoPairing(_ peerId: String) async {
        let key = removableRelayKey(peerId)
        await clipboard.reset()
        device.removePeer(peerId: peerId)
        do { try storage.saveState() } catch { saveFailed() }
        await closeSlot()
        if let key { try? await relay?.send(Frames.removeMember(key)) }
    }

    private func pairingExpired() async {
        guard flow.expire() else { return }
        await closeSlot()
        publish()
    }

    /// Closes the pairing slot in the core and on the relay, so the code can't be used later.
    private func closeSlot() async {
        expiry?.cancel()
        device.cancelPairing()
        guard openTokenHash != nil else { return }
        openTokenHash = nil
        try? await relay?.send(Frames.closePairing())
    }

    /// The slot on the relay, for what's left of the code's life: a reconnect never extends it.
    func announcePairing() async {
        guard let hash = openTokenHash, case .waiting(_, let deadline) = flow.state else { return }
        let ttl = deadline.ttlSeconds(at: timers.now())
        guard ttl > 0 else { return }
        try? await relay?.send(Frames.openPairing(tokenHash: hash, ttl: ttl))
    }

    /// A phone's first envelope while a code is showing.
    func acceptPairing(_ envelope: Data) {
        do {
            let request = try device.acceptPairing(envelope: envelope)
            guard flow.received(request) else { return device.rejectPairing(peerId: request.peerId) }
        } catch {
            // A wrong or stale code doesn't use up the slot; keep waiting for the real phone
            log.error("pairing attempt refused")
        }
    }

    func removableRelayKey(_ peerId: String) -> Data? {
        Self.removableRelayKey(peerId, among: device.peers())
    }

    /// The peer's relay key, unless another (already paired) device shares it: a pairing request
    /// claiming the paired phone's key must not take the phone out of the group when it's rejected.
    static func removableRelayKey(_ peerId: String, among peers: [PeerInfo]) -> Data? {
        guard let key = peers.first(where: { $0.id == peerId })?.relayKey else { return nil }
        return peers.contains { $0.id != peerId && $0.confirmed && $0.relayKey == key } ? nil : key
    }

    func uploadBundlesIfNeeded() async {
        guard storage.needsBundles(), let relay else { return }
        do {
            let bundles = try device.prekeyBundles(count: 20)
            try storage.saveState()
            try await relay.send(Frames.putBundles(bundles))
            storage.setNeedsBundles(false)
        } catch {
            log.error("bundle upload failed; retrying on next connect")
        }
    }
}
