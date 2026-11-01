// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import SeamCore
import SeamProtocol

/// The PairAccept on its way to the pairing phone.
struct PendingAccept {
    let msgID: Data
    let request: PairingRequest
    /// The app data from before the pairing, to put back if it's undone.
    let previous: AppData?
}

/// Pairing over the direct channel (docs/tor/13-implementation-contract.md, "Pairing exchange"),
/// around the core's state machine and `PairingFlow`. A code names a new onion service, so a phone
/// removed later only knows a dead address (SR-33-01). The phone reaches this Mac on Same Wi‑Fi or
/// through that service; over that pairing channel it gets the bundle, sends its request, and hears
/// the PairAccept.
extension Session {
    /// How long the PairAccept may take to be acked before the pairing is undone.
    static let acceptDeadline: TimeInterval = 60

    var pairingOpen: Bool {
        switch flow.state {
        case .waiting, .confirm, .confirming: true
        default: false
        }
    }

    /// A fresh QR code naming a new onion service only the phone holding it can find.
    public func startPairing(name: String) async {
        guard !flow.isConfirming else { return }
        closePairing() // an earlier code's service and slot
        let offer = device.startPairing()
        let secret = torNewSecret()
        let discovery = torNewSecret()
        pairingService = TorServiceSpec(secret: secret, clients: [torDiscoveryPublic(discoverySecret: discovery)])
        let qr = PairingQR(
            onionAddress: torOnionAddress(serviceSecret: secret), discoverySecret: discovery, token: offer.token,
            identityKey: device.identityKey(), relayKey: device.relayKey(), name: name
        )
        flow.start(qr: qr.url, now: timers.now())
        expiry = timers.after(PairingDeadline.lifetime) { [weak self] in await self?.pairingExpired() }
        publish()
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
        if case .confirm(let request) = previous { device.rejectPairing(peerId: request.peerId) }
        closePairing()
        publish()
    }

    // MARK: The pairing channel

    func pairingLinked(on path: PeerPath) {
        // Only while a code is showing, and only one pairing link: a second carrier's goes
        guard pairingOpen, pairingPath == nil || pairingPath == path else {
            return links[path]?.close(peerId: LanServer.pairingPeer) ?? ()
        }
        pairingPath = path
    }

    func pairingUnlinked(on path: PeerPath) async {
        guard pairingPath == path else { return }
        pairingPath = nil
        // The PairAccept may not have arrived: never leave a half-paired phone (the phone acks
        // before it closes, so a finished pairing is never here)
        if let pending = pendingAccept { await failAccept(pending) }
    }

    /// Steps 1–2: this Mac's bundle, fresh, saved as used before it goes.
    func sendBundle(on path: PeerPath) {
        guard path == pairingPath, case .waiting = flow.state else { return }
        do {
            let bundle: Data
            if let sent = pairingBundle {
                bundle = sent
            } else {
                guard let fresh = try device.prekeyBundles(count: 1).first else { return }
                try storage.saveState()
                pairingBundle = fresh
                bundle = fresh
            }
            let key = device.relayKey()
            _ = links[path]?.send(.with { $0.bundle = .with { $0.deviceKey = key; $0.bundle = bundle } }, to: LanServer.pairingPeer)
        } catch {
            log.error("pairing bundle failed")
        }
    }

    /// Steps 3–4: the phone's request, checked by the core, then the safety code for the user.
    func pairingEnvelope(_ envelope: Seam_V1_Envelope, on path: PeerPath) {
        guard path == pairingPath, case .waiting = flow.state, let bytes = try? envelope.serializedData() else { return }
        do {
            let request = try device.acceptPairing(envelope: bytes)
            guard flow.received(request) else { return device.rejectPairing(peerId: request.peerId) }
            _ = links[path]?.send(.with { $0.delivered = .with { $0.msgID = envelope.msgID } }, to: LanServer.pairingPeer)
        } catch {
            log.error("pairing attempt refused")
            links[path]?.close(peerId: LanServer.pairingPeer)
        }
    }

    /// Step 5: the phone has the PairAccept.
    func pairingAcked(_ msgID: Data, on path: PeerPath) async {
        guard path == pairingPath, let pending = pendingAccept, pending.msgID == msgID else { return }
        pendingAccept = nil
        await finishPairing(pending.request)
    }

    // MARK: Private

    /// Saved before the phone hears it's paired: never tell it so unless this Mac will remember.
    /// The new service becomes the pairing's; the old one stops once this is saved.
    private func sendAccept(_ request: PairingRequest) async {
        let previous = device.appData()
        // The phone left while the user compared codes: nothing to tell
        guard let service = pairingService, let path = pairingPath, links[path] != nil else {
            device.rejectPairing(peerId: request.peerId)
            closePairing()
            flow.fail("Couldn't reach your phone. Show a new code and scan it again.")
            return publish()
        }
        let envelope: Seam_V1_Envelope
        do {
            try device.confirmPairing(peerId: request.peerId)
            let accept = Seam_V1_Payload.with { $0.control = .with { $0.pairAccept = .init() } }
            let sealed = try device.seal(peerId: request.peerId, payload: try accept.serializedData(), ttlS: 0)
            device.forgetHello() // a new pairing starts the Hello counts again, on both devices
            try device.setAppData(data: AppData(
                torSecret: service.secret, onionAddress: "", peerDiscovery: service.clients[0],
                helloSeqSent: 0, helloSeqApplied: 0, peerAllowed: nil
            ))
            try storage.saveState()
            envelope = try Seam_V1_Envelope(serializedBytes: sealed)
        } catch {
            log.error("pairing confirm failed")
            await undoPairing(request.peerId, previous: previous)
            flow.fail("Seam couldn't save the pairing on this Mac. Show a new code and scan it again.")
            return publish()
        }
        // Anything held for a previous phone goes before the new one can send (SR-29-10)
        for feature in features { await feature.reset() }
        let pending = PendingAccept(msgID: envelope.msgID, request: request, previous: previous)
        pendingAccept = pending
        guard links[path]?.send(.with { $0.deliver = envelope }, to: LanServer.pairingPeer) == true else {
            return await failAccept(pending)
        }
        expiry?.cancel()
        expiry = timers.after(Self.acceptDeadline) { [weak self] in await self?.acceptTimedOut(pending.msgID) }
    }

    private func acceptTimedOut(_ msgID: Data) async {
        guard let pending = pendingAccept, pending.msgID == msgID else { return }
        await failAccept(pending)
    }

    private func failAccept(_ pending: PendingAccept) async {
        pendingAccept = nil
        log.error("pairing accept not delivered")
        await undoPairing(pending.request.peerId, previous: pending.previous)
        flow.fail("Couldn't reach your phone. Show a new code and scan it again.")
        publish()
    }

    private func finishPairing(_ request: PairingRequest) async {
        // Only a phone the core really holds as confirmed counts as paired
        guard let peer = device.peers().first(where: { $0.id == request.peerId && $0.confirmed }) else {
            flow.fail("Pairing didn't finish. Show a new code and scan it again.")
            return publish()
        }
        if let old = phone, old.id != peer.id { up = [] } // the old phone's links no longer count
        phone = peer
        flow.finish(name: request.deviceName)
        closePairing() // the phone comes back as itself, on the same service
        await sendHello()
        publish()
    }

    /// A phone confirmed here whose PairAccept never got out: forgotten, the app data put back.
    /// It never became `phone`, so no feature holds anything of it.
    private func undoPairing(_ peerId: String, previous: AppData?) async {
        device.removePeer(peerId: peerId)
        if let previous { try? device.setAppData(data: previous) } else { device.clearAppData() }
        do { try storage.saveState() } catch { saveFailed() }
        closePairing()
    }

    private func pairingExpired() async {
        guard flow.expire() else { return }
        closePairing()
        publish()
    }

    /// The code can't be used any more: the core's slot, the new service (unless it became the
    /// pairing's) and the pairing link all go.
    func closePairing() {
        expiry?.cancel()
        device.cancelPairing()
        pairingService = nil
        if let path = pairingPath { links[path]?.close(peerId: LanServer.pairingPeer) }
        pairingPath = nil
        pairingBundle = nil
    }
}
