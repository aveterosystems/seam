// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import SeamCore
import SeamProtocol

/// Delivery to the phone, what arrives from it, and forgetting it.
extension Session {
    // MARK: Delivery

    /// Sealed, saved, then sent: never sends what a failed save would forget (SR-2-11).
    func sendToPhone(_ payload: Seam_V1_Payload, ttl: UInt32, hello: Bool = false) async {
        guard let phone else { return }
        let envelope: Seam_V1_Envelope
        do {
            let sealed = try device.seal(peerId: phone.id, payload: try payload.serializedData(), ttlS: ttl)
            do { try storage.saveState() } catch { return saveFailed() }
            envelope = try Seam_V1_Envelope(serializedBytes: sealed)
        } catch {
            log.error("couldn't seal a message for the phone")
            return
        }
        if hello { hellos.insert(envelope.msgID) } // may take any path this Mac allows (protocol/README.md)
        // Saved to the outbox first, then onto the best path; otherwise it waits there
        outbox.add(envelope)
        guard saveOutbox() else { return } // stays in memory; the next flush tries again
        attempt(envelope)
    }

    func send(_ outgoing: [Outgoing]) async {
        for out in outgoing { await sendToPhone(out.payload, ttl: out.ttl) }
    }

    /// Never a disallowed path, and never a path before it's on disk. Kept in the outbox until the
    /// phone's Delivered; resent if the link drops first.
    private func attempt(_ envelope: Seam_V1_Envelope) {
        let hello = hellos.contains(envelope.msgID)
        guard let phone, let path = device.route(enabled: enabledPaths, up: upPaths, hello: hello),
              links[path]?.send(.with { $0.deliver = envelope }, to: phone.id) == true else { return }
        outbox.markSent(envelope.msgID)
        saveOutbox()
        let id = envelope.msgID
        ackWaits[id]?.timer.cancel()
        ackWaits[id] = (path, timers.after(Self.ackTimeout) { [weak self] in await self?.ackOverdue(id, on: path) })
    }

    /// No ack for this long on `path` while another link is up: that link is dead without having
    /// said so. Closed, so what it never confirmed goes on the other one (and the phone is told).
    private func ackOverdue(_ id: Data, on path: PeerPath) async {
        guard ackWaits[id]?.path == path else { return } // acked, or sent again since on another link
        ackWaits[id] = nil
        guard outbox.sent.contains(where: { $0.msgID == id }), up.contains(path), upPaths.count > 1, let phone else { return }
        links[path]?.close(peerId: phone.id)
        await pathClosed(path)
    }

    /// Everything waiting, oldest first, now that a path may have opened.
    func flush() async {
        _ = outbox.dropExpired(now: timers.now())
        guard saveOutbox() else { return } // nothing goes out that isn't on disk (an earlier save may have failed)
        for envelope in outbox.waiting { attempt(envelope) }
    }

    @discardableResult
    func saveOutbox() -> Bool {
        do {
            try storage.outbox.save(outbox)
            return true
        } catch {
            saveFailed()
            return false
        }
    }

    /// Anything sent but not acked goes again, on whatever path is allowed now.
    func pathClosed(_ path: PeerPath) async {
        guard up.remove(path) != nil else { return }
        if !outbox.sent.isEmpty {
            outbox.requeueSent()
            saveOutbox()
            await flush()
        }
        // The phone may not know this link is gone: the Hello lists the ones still open
        if !upPaths.isEmpty { await sendHello() }
    }

    /// The phone's Hello lists the links it has open. One of ours that it doesn't list is dead on
    /// its side (it left the Wi‑Fi): close it, so nothing more is sent into it.
    func closeLinks(missingFrom open: Seam_V1_OpenLinks) async {
        guard let phone else { return }
        let listed = Set(open.transports)
        for path in upPaths where !listed.contains(path.transport) {
            links[path]?.close(peerId: phone.id)
            await pathClosed(path)
        }
    }

    /// A path to the phone came up: what waited, then this Mac's switches, then the features'.
    func pathCameUp() async {
        guard phone != nil else { return }
        await flush()
        await sendHello()
        for feature in features { await send(await feature.onConnected(now: timers.now())) }
        await ownStatusChanged()
    }

    /// Its seq is counted in the core's app data and saved with the seal, before it leaves: a
    /// Hello never goes with a seq a restart could hand out again.
    func sendHello() async {
        guard phone != nil, let next = device.nextHello(enabled: enabledPaths) else { return }
        let hello = Seam_V1_Hello.with {
            $0.appVersion = appVersion
            $0.platform = "macos"
            $0.seq = next.seq
            $0.allowedTransports = next.allowedTransports.map { Seam_V1_Transport(rawValue: Int($0)) ?? .unspecified }
            $0.openLinks = .with { $0.transports = upPaths.map(\.transport) }
        }
        await sendToPhone(.with { $0.control = .with { $0.hello = hello } }, ttl: Self.settingsTTL, hello: true)
    }

    // MARK: Receiving

    func received(_ envelope: Seam_V1_Envelope) async -> Bool {
        guard let bytes = try? envelope.serializedData() else { return true }
        let message: OpenedMessage
        do {
            message = try device.open(envelope: bytes)
        } catch SeamError.Duplicate {
            return true // already handled before a reconnect
        } catch SeamError.UntrustedIdentity {
            warning = "A device tried to talk to this Mac with keys that don't match your paired phone. It was ignored."
            log.error("untrusted identity")
            return true
        } catch {
            log.error("dropped an envelope that failed its checks")
            return true // dropped for good: the phone can stop resending it
        }
        let payload = try? Seam_V1_Payload(serializedBytes: message.payload)
        // Only a newer Hello counts (the core checks): a resent old one must not undo a newer one.
        // Applied before the save just below, with the ratchet
        if case .control(let c)? = payload?.body, case .hello(let h)? = c.body, isConfirmedPhone(message.peerId),
           device.applyHello(seq: h.seq, allowedTransports: h.allowedTransports.map { Int32($0.rawValue) }) {
            // The phone's pause rides on the same counter, so a late or replayed Hello can't
            // re-pause a resumed Mac, and every new connection brings the current state (SR-32-01)
            setPause(until: Self.pauseEnd(h.pausedUntilMs))
            if h.hasOpenLinks { await closeLinks(missingFrom: h.openLinks) }
        }
        let saved = (try? storage.saveState()) != nil
        // Handled even if the save failed: the ratchet has moved, so a redelivery in this run opens
        // as a duplicate and would be lost (SR-2-59). Not acked, so after a restart the saved state
        // opens it again.
        if let payload { await handle(payload, from: message.peerId) }
        if !saved {
            saveFailed()
            return false
        }
        return true
    }

    private func handle(_ payload: Seam_V1_Payload, from peerId: String) async {
        // Features only from the paired, confirmed phone
        guard isConfirmedPhone(peerId), let body = payload.body else { return }
        guard case .control(let c) = body else {
            for feature in features { await send(await feature.receive(body)) }
            return
        }
        if case .hello = c.body { await flush() } // a path may have opened
        if case .unpair = c.body {
            await forget(peerId)
            for link in links.values { link.close(peerId: peerId) }
        }
    }

    /// A Hello's `paused_until_ms`: 0 (or an older phone that doesn't send it) is not paused.
    static func pauseEnd(_ ms: Int64) -> Date? {
        switch ms {
        case ...0: nil
        case .max: .distantFuture
        default: Date(timeIntervalSince1970: TimeInterval(ms) / 1000)
        }
    }

    func isConfirmedPhone(_ peerId: String) -> Bool {
        peerId == phone?.id && device.peers().contains { $0.id == peerId && $0.confirmed }
    }

    // MARK: Forgetting the phone

    /// "Unpair <phone>": tells the phone (sealed) on every link it's on, then forgets it here, its
    /// onion service with it. A phone that's away finds its Mac's address gone, and pairs again.
    /// False if forgetting it couldn't be saved (then nothing is sent).
    @discardableResult
    public func unpairPhone() async -> Bool {
        guard let phone else { return true } // nothing to forget (SR-29-06: the caller's copy may lag)
        let unpair = Seam_V1_Payload.with { $0.control = .with { $0.unpair = .init() } }
        // Not saved before sending, unlike other messages (SR-2-11): the phone's keys are deleted
        // right after, so a crash can't reuse them
        let goodbye = (try? device.seal(peerId: phone.id, payload: unpair.serializedData(), ttlS: 0))
            .flatMap { try? Seam_V1_Envelope(serializedBytes: $0) }
        // Every up path this Mac's own switches allow, whatever the phone's last list said; never a
        // path switched off
        let via = upPaths.filter { enabledPaths.contains($0) }
        // Only once forgetting it is saved: otherwise a restart would reload the phone with the
        // ratchet from before the goodbye was sealed, and could reuse its key (SR-7-04)
        guard await forget(phone.id) else { return false }
        for path in via {
            if let goodbye { _ = links[path]?.send(.with { $0.deliver = goodbye }, to: phone.id) }
            links[path]?.close(peerId: phone.id) // gracefully: the goodbye is written first
        }
        publish()
        return true
    }

    /// The phone is gone (unpaired here, or it unpaired us): its keys, Hello state, every
    /// feature's data and anything held for it. False if that couldn't be saved.
    @discardableResult
    func forget(_ peerId: String) async -> Bool {
        device.forgetHello()
        device.removePeer(peerId: peerId)
        device.clearAppData() // its onion service goes: a forgotten phone only knows a dead address
        let saved: Bool
        do {
            try storage.saveState()
            saved = true
        } catch {
            saveFailed()
            saved = false
        }
        if phone?.id == peerId { phone = nil }
        up = []
        setPause(until: nil) // nobody left to resume it
        outbox.clear()
        hellos = []
        storage.outbox.delete()
        for feature in features { await feature.reset() }
        return saved
    }
}
