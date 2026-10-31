// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import SeamCore
import SeamProtocol
import Testing

@testable import SeamSession

/// Pairing over the direct channel (docs/tor/13-implementation-contract.md): the code's life, the
/// exchange on a pairing link, the new onion service, and what's undone when it fails.
struct SessionPairingTests {
    private let pairing = LanServer.pairingPeer

    private enum Failure: Error { case noCode, noBundle, noRequest, noAccept }

    private func showCode(_ rig: Rig) async throws -> PairingQR {
        await rig.session.startPairing(name: "Mac")
        guard case .waiting(let qr, _) = await rig.session.state.pairing else { throw Failure.noCode }
        return try PairingQR.parse(qr)
    }

    private func link(_ rig: Rig, _ path: PeerPath) -> FakeLan { path == .lan ? rig.lan : rig.tor }

    /// The phone's half, up to the safety code: it reaches the Mac on `path`, gets the bundle and
    /// sends its request.
    private func scan(_ rig: Rig, _ qr: PairingQR, on path: PeerPath = .tor, phone: SeamDevice? = nil) async throws {
        let link = link(rig, path)
        await rig.session.setLink(link, for: path)
        await rig.session.linkEvent(.authenticated(peerId: pairing), on: path)
        await rig.session.linkEvent(.bundleRequest(peerId: pairing), on: path)
        guard case .bundle(let b)? = link.frames.last?.frame else { throw Failure.noBundle }
        #expect(b.deviceKey == qr.relayKey)
        let start = try (phone ?? rig.phone).pairWith(
            identityKey: qr.identityKey, relayKey: qr.relayKey, name: qr.name,
            bundle: b.bundle, token: qr.token, myName: "Pixel", myModel: "Test"
        )
        let request = try Seam_V1_Envelope(serializedBytes: start.envelope)
        await rig.session.linkEvent(.envelope(peerId: pairing, request), on: path)
        guard case .confirm = await rig.session.state.pairing else { throw Failure.noRequest }
        #expect(link.frames.last?.delivered.msgID == request.msgID, "the request is acked on the pairing link")
    }

    /// The user clicks Pair; returns the PairAccept the phone got.
    private func confirm(_ rig: Rig, on path: PeerPath = .tor, phone: SeamDevice? = nil) async throws -> Seam_V1_Envelope {
        await rig.session.confirmPairing()
        guard case .deliver(let accept)? = link(rig, path).frames.last?.frame else { throw Failure.noAccept }
        let opened = try (phone ?? rig.phone).open(envelope: accept.serializedData())
        #expect(try Seam_V1_Payload(serializedBytes: opened.payload).control.pairAccept == .init())
        return accept
    }

    @Test(arguments: [PeerPath.lan, .tor])
    func pairsOverEitherCarrier(path: PeerPath) async throws {
        let rig = try await Rig(paired: false)
        let qr = try await showCode(rig)
        try await scan(rig, qr, on: path)
        let accept = try await confirm(rig, on: path)
        #expect(await rig.session.state.pairing == .confirming(try #require(rig.mac.peers().first.map {
            PairingRequest(peerId: $0.id, deviceName: "Pixel", model: "Test", safetyCode: try rig.mac.safetyCode(peerId: $0.id))
        })))
        await rig.session.linkEvent(.delivered(peerId: pairing, msgID: accept.msgID), on: path)
        #expect(await rig.session.state.pairing == .paired(name: "Pixel"))
        #expect(await rig.session.state.phone?.id == rig.phoneID)
        #expect(link(rig, path).closed.contains(pairing), "both close the pairing link")
        // The code's service is now the pairing's: only that phone can find it
        let data = try #require(rig.mac.appData())
        #expect(torOnionAddress(serviceSecret: data.torSecret) == qr.onionAddress)
        #expect(data.peerDiscovery == torDiscoveryPublic(discoverySecret: qr.discoverySecret))
    }

    @Test func theCodeIsVersion2AndNamesANewServiceEachTime() async throws {
        let rig = try await Rig(paired: false)
        let first = try await showCode(rig)
        let second = try await showCode(rig)
        #expect(first.onionAddress.hasSuffix(".onion") && first.onionAddress != second.onionAddress)
        #expect(first.discoverySecret != second.discoverySecret && first.token != second.token)
        #expect(first.identityKey == rig.mac.identityKey() && first.relayKey == rig.mac.relayKey())
        #expect(await rig.session.state.torServices.count == 1, "the earlier code's service went")
    }

    @Test func theCodeLastsFiveMinutesThenItsServiceStops() async throws {
        let rig = try await Rig(paired: false)
        _ = try await showCode(rig)
        #expect(await rig.session.state.torServices.count == 1)
        #expect(await rig.session.state.wantsLinks, "Same Wi‑Fi listens while a code shows")
        await rig.timers.advance(299)
        #expect(await rig.session.state.torServices.count == 1)
        await rig.timers.advance(1)
        #expect(await rig.session.state.pairing == .expired)
        #expect(await rig.session.state.torServices.isEmpty)
        #expect(await !rig.session.state.wantsLinks)
    }

    @Test func cancellingStopsTheNewServiceAndClosesThePairingLink() async throws {
        let rig = try await Rig(paired: false)
        try await scan(rig, try await showCode(rig))
        await rig.session.cancelPairing()
        #expect(await rig.session.state.pairing == .idle)
        #expect(rig.mac.peers().isEmpty, "the requester is forgotten")
        #expect(await rig.session.state.torServices.isEmpty)
        #expect(rig.tor.closed.contains(pairing))
    }

    @Test func confirmingSwapsTheOldServiceForTheNewOne() async throws {
        let rig = try await Rig() // paired, hosting its service
        let old = TorServiceSpec(secret: Rig.macData.torSecret, clients: [Rig.macData.peerDiscovery])
        #expect(await rig.session.state.torServices == [old])
        let qr = try await showCode(rig)
        #expect(await rig.session.state.torServices.count == 2, "the old one stays while the code shows")
        let newPhone = try SeamDevice.generate()
        try await scan(rig, qr, phone: newPhone)
        let accept = try await confirm(rig, phone: newPhone)
        let services = await rig.session.state.torServices
        #expect(services.count == 1 && services != [old], "saved, so the old one stops")
        #expect(torOnionAddress(serviceSecret: try #require(services.first).secret) == qr.onionAddress)
        await rig.session.linkEvent(.delivered(peerId: pairing, msgID: accept.msgID), on: .tor)
        #expect(await rig.session.state.phone?.id == newPhone.id())
        #expect(await rig.session.state.torServices == services)
    }

    @Test func aPairAcceptNeverAckedIsUndoneAndTheOldDataPutBack() async throws {
        let rig = try await Rig()
        let qr = try await showCode(rig)
        let newPhone = try SeamDevice.generate()
        try await scan(rig, qr, phone: newPhone)
        _ = try await confirm(rig, phone: newPhone)
        await rig.timers.advance(Session.acceptDeadline)
        guard case .failed = await rig.session.state.pairing else { throw Failure.noRequest }
        #expect(rig.mac.peers().map(\.id) == [rig.phoneID], "only the phone from before")
        #expect(rig.mac.appData() == Rig.macData)
        #expect(await rig.session.state.phone?.id == rig.phoneID)
    }

    @Test func aPairingLinkThatDropsBeforeTheAckUndoesThePairing() async throws {
        let rig = try await Rig(paired: false)
        try await scan(rig, try await showCode(rig))
        _ = try await confirm(rig)
        await rig.session.linkEvent(.closed(peerId: pairing), on: .tor)
        guard case .failed = await rig.session.state.pairing else { throw Failure.noRequest }
        #expect(rig.mac.peers().isEmpty)
        #expect(rig.mac.appData() == nil)
        #expect(await rig.session.state.torServices.isEmpty)
    }

    @Test func aPhoneThatLeftWhileTheCodesWereComparedIsNotPaired() async throws {
        let rig = try await Rig(paired: false)
        try await scan(rig, try await showCode(rig))
        await rig.session.linkEvent(.closed(peerId: pairing), on: .tor)
        await rig.session.confirmPairing()
        guard case .failed = await rig.session.state.pairing else { throw Failure.noRequest }
        #expect(rig.mac.peers().isEmpty)
    }

    @Test func noPairingLinkWithoutACodeAndOnlyOneWhileShowing() async throws {
        let rig = try await Rig(paired: false)
        await rig.session.setLink(rig.tor, for: .tor)
        await rig.session.setLink(rig.lan, for: .lan)
        await rig.session.linkEvent(.authenticated(peerId: pairing), on: .tor)
        #expect(rig.tor.closed == [pairing], "no code showing")
        _ = try await showCode(rig)
        await rig.session.linkEvent(.authenticated(peerId: pairing), on: .tor)
        await rig.session.linkEvent(.authenticated(peerId: pairing), on: .lan)
        #expect(rig.lan.closed == [pairing], "the first carrier to authenticate wins")
        await rig.session.linkEvent(.bundleRequest(peerId: pairing), on: .lan)
        #expect(rig.lan.frames.isEmpty, "nothing on the losing link")
    }

    @Test func noServiceWithTorSwitchedOffButSameWifiStillPairs() async throws {
        let rig = try await Rig(paired: false, tor: false)
        let qr = try await showCode(rig)
        #expect(await rig.session.state.torServices.isEmpty)
        try await scan(rig, qr, on: .lan)
    }
}
