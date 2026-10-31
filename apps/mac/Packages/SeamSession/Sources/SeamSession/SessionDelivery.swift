// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import SeamCore
import SeamProtocol

/// Delivery to the phone and what arrives from it.
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
        _ = await attempt(envelope)
    }

    func send(_ outgoing: [Outgoing]) async {
        for out in outgoing { await sendToPhone(out.payload, ttl: out.ttl) }
    }

    /// Never a disallowed path, and never a path before it's on disk. False if a relay send
    /// failed, so a flush stops there and nothing later overtakes it.
    private func attempt(_ envelope: Seam_V1_Envelope) async -> Bool {
        guard let phone, !inFlight.contains(envelope.msgID) else { return true }
        let hello = hellos.contains(envelope.msgID)
        switch device.route(enabled: enabledPaths, up: upPaths, hello: hello) {
        case .lan?:
            guard lan?.send(.with { $0.deliver = envelope }, to: phone.id) == true else { return true }
            outbox.markSent(envelope.msgID) // until the phone's Delivered; resent if the link drops first
            saveOutbox()
        case .relay?:
            guard let relay else { return true }
            inFlight.insert(envelope.msgID)
            let ok = (try? await relay.send(Frames.send(envelope))) != nil
            inFlight.remove(envelope.msgID)
            guard ok else { return false } // waits for the next flush
            guard self.phone?.id == phone.id else { return true }
            outbox.remove(envelope.msgID) // the relay holds it now
            hellos.remove(envelope.msgID)
            saveOutbox()
        case nil:
            break
        }
        return true
    }

    /// Everything waiting, oldest first, now that a path may have opened.
    func flush() async {
        _ = outbox.dropExpired(now: timers.now())
        guard saveOutbox() else { return } // nothing goes out that isn't on disk (an earlier save may have failed)
        for envelope in outbox.waiting { guard await attempt(envelope) else { return } }
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

    /// Anything sent directly but not acked goes again, on whatever path is allowed now.
    func lanClosed() async {
        lanUp = false
        guard !outbox.sent.isEmpty else { return }
        outbox.requeueSent()
        saveOutbox()
        await flush()
    }

    /// A path to the phone came up: what waited, then this Mac's switches, then the features'.
    func pathCameUp() async {
        guard phone != nil else { return }
        await flush()
        await sendHello()
        for feature in features { await send(await feature.onConnected(now: timers.now())) }
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
        }
        await sendToPhone(.with { $0.control = .with { $0.hello = hello } }, ttl: Self.settingsTTL, hello: true)
    }

    // MARK: Receiving

    func received(_ envelope: Seam_V1_Envelope) async -> Bool {
        guard let bytes = try? envelope.serializedData() else { return true }
        let message: OpenedMessage
        do {
            message = try device.open(envelope: bytes)
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
        let payload = try? Seam_V1_Payload(serializedBytes: message.payload)
        // Only a newer Hello counts (the core checks): the relay can hold an old one and release
        // it later. Applied before the save just below, with the ratchet
        if case .control(let c)? = payload?.body, case .hello(let h)? = c.body, isConfirmedPhone(message.peerId) {
            _ = device.applyHello(seq: h.seq, allowedTransports: h.allowedTransports.map { Int32($0.rawValue) })
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
            // It leaves the relay group itself, but only best-effort: make sure (SR-7-02)
            let key = removableRelayKey(peerId)
            if await forget(peerId), let key { try? await relay?.send(Frames.removeMember(key)) }
        }
    }

    func isConfirmedPhone(_ peerId: String) -> Bool {
        peerId == phone?.id && device.peers().contains { $0.id == peerId && $0.confirmed }
    }
}
