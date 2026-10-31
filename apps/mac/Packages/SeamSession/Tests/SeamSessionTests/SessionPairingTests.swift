// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import SeamCore
import SeamProtocol
import Testing

@testable import SeamSession

/// Pairing around the core (spec 05): the code's life, the relay slot, PairAccept's retries and
/// what's undone when it fails.
struct SessionPairingTests {
    private func showCode(_ rig: Rig) async throws -> PairingQR {
        await rig.relayUp()
        await rig.session.startPairing(relayURL: "wss://relay.example.com", name: "Mac")
        guard case .waiting(let qr, _) = await rig.session.state.pairing else { throw Failure.noCode }
        return try PairingQR.parse(qr)
    }

    /// The phone scans the code; the Mac shows the safety code.
    private func scan(_ rig: Rig, _ qr: PairingQR) async throws {
        let start = try rig.phone.pairWith(
            identityKey: qr.identityKey, relayKey: qr.relayKey, name: qr.name,
            bundle: try rig.mac.prekeyBundles(count: 1)[0], token: qr.token, myName: "Pixel", myModel: "Test"
        )
        #expect(await rig.session.relayReceived(try Seam_V1_Envelope(serializedBytes: start.envelope)))
        guard case .confirm = await rig.session.state.pairing else { throw Failure.noRequest }
    }

    private enum Failure: Error { case noCode, noRequest }

    private func pairingFrames(_ rig: Rig) -> [Seam_V1_ClientFrame.OneOf_Frame] {
        rig.relay.frames.compactMap(\.frame).filter {
            switch $0 {
            case .openPairing, .closePairing, .removeMember: true
            default: false
            }
        }
    }

    @Test func theCodeOpensASlotForFiveMinutesThenItCloses() async throws {
        let rig = try await Rig(paired: false)
        _ = try await showCode(rig)
        #expect(pairingFrames(rig).first?.openPairingTTL == 300)
        await rig.timers.advance(299)
        #expect(pairingFrames(rig).count == 1)
        await rig.timers.advance(1)
        #expect(await rig.session.state.pairing == .expired)
        #expect(pairingFrames(rig).count == 2 && pairingFrames(rig).last?.isClosePairing == true)
    }

    @Test func aReconnectReannouncesOnlyWhatsLeft() async throws {
        let rig = try await Rig(paired: false)
        _ = try await showCode(rig)
        await rig.timers.advance(120)
        await rig.session.relayDisconnected()
        await rig.session.relayConnected()
        #expect(pairingFrames(rig).map(\.openPairingTTL) == [300, 180])
    }

    @Test func cancellingClosesTheSlotAtOnce() async throws {
        let rig = try await Rig(paired: false)
        _ = try await showCode(rig)
        await rig.session.cancelPairing()
        #expect(await rig.session.state.pairing == .idle)
        #expect(pairingFrames(rig).last?.isClosePairing == true)
    }

    @Test func pairingFinishesWhenTheRelayTakesPairAccept() async throws {
        let rig = try await Rig(paired: false)
        try await scan(rig, try await showCode(rig))
        await rig.session.confirmPairing()
        #expect(await rig.session.state.pairing == .paired(name: "Pixel"))
        #expect(await rig.session.state.phone?.id == rig.phoneID)
        let sent = try rig.onRelay()
        #expect(sent.first?.control.pairAccept == .init())
        #expect(sent.dropFirst().first?.control.hello.seq == 1, "then a Hello, counting from 1 again")
    }

    @Test func pairAcceptIsRetriedThenThePairingIsUndone() async throws {
        let rig = try await Rig(paired: false)
        try await scan(rig, try await showCode(rig))
        rig.relay.failing = true
        await rig.session.confirmPairing()
        #expect(rig.relay.attempts > 0)
        guard case .confirming = await rig.session.state.pairing else { throw Failure.noRequest }
        await rig.timers.advance(30)
        guard case .confirming = await rig.session.state.pairing else { throw Failure.noRequest } // still trying
        await rig.timers.advance(60)
        guard case .failed = await rig.session.state.pairing else { throw Failure.noRequest }
        #expect(rig.mac.peers().isEmpty)
        #expect(await rig.session.state.phone == nil)
        rig.relay.failing = false
        #expect(rig.relay.attempts >= Session.acceptAttempts + 2, "PairAccept tried \(Session.acceptAttempts) times, then removeMember and closePairing")
    }

    @Test func rejectingTakesTheRequesterOutOfTheRelayGroup() async throws {
        let rig = try await Rig(paired: false)
        try await scan(rig, try await showCode(rig))
        await rig.session.cancelPairing()
        #expect(rig.mac.peers().isEmpty)
        let frames = pairingFrames(rig)
        #expect(frames.contains { if case .removeMember(let m) = $0 { m.deviceKey == rig.phone.relayKey() } else { false } })
        #expect(frames.last?.isClosePairing == true || frames.dropLast().last?.isClosePairing == true)
    }

    @Test func noCodeWithTheRelaySwitchedOff() async throws {
        let rig = try await Rig(paired: false, relay: false)
        await rig.session.startPairing(relayURL: "wss://relay.example.com", name: "Mac")
        #expect(await rig.session.state.pairing == .idle)
    }
    @Test func aRelayKeyAnotherPairedDeviceSharesIsKept() {
        let shared = Data(repeating: 7, count: 32)
        let phone = PeerInfo(id: "phone", name: "Phone", relayKey: shared, confirmed: true)
        let claimant = PeerInfo(id: "claimant", name: "Not the phone", relayKey: shared, confirmed: false)
        #expect(Session.removableRelayKey("claimant", among: [phone, claimant]) == nil, "rejecting it keeps the phone in the group")
        #expect(Session.removableRelayKey("phone", among: [phone]) == shared)
        let unconfirmed = PeerInfo(id: "phone", name: "Phone", relayKey: shared, confirmed: false)
        #expect(Session.removableRelayKey("claimant", among: [unconfirmed, claimant]) == shared, "only a paired device counts")
        #expect(Session.removableRelayKey("nobody", among: [phone]) == nil)
    }
}

extension Seam_V1_ClientFrame.OneOf_Frame {
    var openPairingTTL: UInt32? { if case .openPairing(let o) = self { o.ttlS } else { nil } }
    var isClosePairing: Bool { if case .closePairing = self { true } else { false } }
}
