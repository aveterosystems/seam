// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import SeamCore
import SeamProtocol
import Testing

@testable import SeamSession

/// A link that dies without closing (the phone left the Wi‑Fi) is given up quickly: the phone
/// says which links it has open, and an envelope left unacked on one link goes on the other.
struct LinkFailoverTests {
    private func clip(_ text: String) -> Seam_V1_Payload { .with { $0.clipboard = .with { $0.text = text } } }

    private func clips(_ payloads: [Seam_V1_Payload]) -> [String] {
        payloads.compactMap { if case .clipboard(let c)? = $0.body { c.text } else { nil } }
    }

    private func hellos(_ payloads: [Seam_V1_Payload]) -> [Seam_V1_Hello] {
        payloads.compactMap { if case .control(let c)? = $0.body, case .hello(let h)? = c.body { h } else { nil } }
    }

    /// The phone acks everything that reached it on `path` so far, as a live phone does.
    private func ackAll(_ rig: Rig, on path: PeerPath) async {
        let carrier = path == .lan ? rig.lan : rig.tor
        for frame in carrier.frames {
            if case .deliver(let e)? = frame.frame {
                await rig.session.linkEvent(.delivered(peerId: rig.phoneID, msgID: e.msgID), on: path)
            }
        }
    }

    /// Both links up, and what they carried so far acked.
    private func bothUp(_ rig: Rig) async {
        await rig.lanUp()
        await rig.torUp()
        await ackAll(rig, on: .lan)
        await ackAll(rig, on: .tor)
    }

    @Test func whenThePhoneSaysItsWifiLinkIsGoneItsClosedAndWhatItHeldGoesThroughTor() async throws {
        let rig = try await Rig()
        await bothUp(rig)
        rig.pasteboard.copy("held by the dead link")
        await rig.timers.advance(1)
        #expect(clips(try rig.onLan()) == ["held by the dead link"])
        let hello = try rig.phoneHello(wifi: true, tor: true, openLinks: [.tor])
        #expect(await rig.session.received(hello))
        #expect(rig.lan.closed == [rig.phoneID])
        #expect(clips(try rig.onTor()) == ["held by the dead link"])
        #expect(await rig.session.state.route == .tor)
    }

    @Test func anOlderPhoneThatDoesntListItsLinksClosesNothing() async throws {
        let rig = try await Rig()
        await rig.lanUp()
        await rig.torUp()
        #expect(await rig.session.received(try rig.phoneHello(wifi: true, tor: true)))
        #expect(rig.lan.closed.isEmpty && rig.tor.closed.isEmpty)
        #expect(await rig.session.state.route == .lan)
    }

    @Test func aLinkThatLeavesACopyUnackedIsGivenUpForTheOther() async throws {
        let rig = try await Rig()
        await bothUp(rig)
        rig.pasteboard.copy("into the void")
        await rig.timers.advance(1)
        await rig.timers.advance(Session.ackTimeout - 2)
        #expect(rig.lan.closed.isEmpty, "not yet")
        await rig.timers.advance(2)
        #expect(rig.lan.closed == [rig.phoneID])
        #expect(clips(try rig.onTor()) == ["into the void"])
        #expect(hellos(try rig.onTor()).last?.openLinks.transports == [.tor], "and the phone hears the link is gone")
    }

    @Test func anAckInTimeKeepsTheLink() async throws {
        let rig = try await Rig()
        await bothUp(rig)
        rig.pasteboard.copy("arrived")
        await rig.timers.advance(1)
        let delivered = rig.lan.frames.compactMap { frame -> Seam_V1_Envelope? in if case .deliver(let e)? = frame.frame { e } else { nil } }
        let sent = try #require(delivered.last)
        await rig.session.linkEvent(.delivered(peerId: rig.phoneID, msgID: sent.msgID), on: .lan)
        await rig.timers.advance(Session.ackTimeout * 2)
        #expect(rig.lan.closed.isEmpty)
    }

    @Test func theOnlyLinkIsntClosedForBeingSlow() async throws {
        let rig = try await Rig()
        await rig.torUp()
        await ackAll(rig, on: .tor)
        rig.pasteboard.copy("slow but alone")
        await rig.timers.advance(1)
        await rig.timers.advance(Session.ackTimeout * 2)
        #expect(rig.tor.closed.isEmpty, "with nothing to fall back to, the link's own timeouts decide")
    }

    @Test func everyHelloListsTheLinksOpenNow() async throws {
        let rig = try await Rig()
        await rig.lanUp()
        await rig.torUp()
        #expect(hellos(try rig.onLan() + rig.onTor()).contains { Set($0.openLinks.transports) == [.lan, .tor] })
    }
}
