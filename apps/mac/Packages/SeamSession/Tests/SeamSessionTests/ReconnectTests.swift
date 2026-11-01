// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import SeamCore
import SeamProtocol
import Testing

@testable import SeamSession

/// "Reconnect" on the Mac (specs/feature-parity/01-small-items.md, 1): the phone is asked on every
/// open link, never later, and this Mac closes its end so the phone dials again at once.
struct ReconnectTests {
    private func asks(_ payloads: [Seam_V1_Payload]) -> Int {
        payloads.filter { if case .control(let c)? = $0.body, case .reconnect? = c.body { true } else { false } }.count
    }

    @Test func thePhoneIsAskedOnEveryOpenLinkThenTheyClose() async throws {
        let rig = try await Rig()
        await rig.lanUp()
        await rig.torUp()
        #expect(await rig.session.reconnect())
        let (onLan, onTor) = (try rig.onLan(), try rig.onTor())
        #expect(asks(onLan) == 1 && asks(onTor) == 1)
        #expect(rig.lan.closed == [rig.phoneID] && rig.tor.closed == [rig.phoneID])
        #expect(await rig.session.state.route == nil)
    }

    @Test func withThePhoneOutOfReachNothingIsSent() async throws {
        let rig = try await Rig()
        #expect(await !rig.session.reconnect(), "only this Mac's side can start again")
        await rig.lanUp()
        #expect(asks(try rig.onLan()) == 0, "and nothing waits to cut the next link")
    }

    @Test func theAskIsNeverSentAgainOnTheNewLink() async throws {
        let rig = try await Rig()
        await rig.lanUp()
        _ = await rig.session.reconnect()
        await rig.lanUp() // the phone dialled again
        await rig.timers.advance(60)
        #expect(asks(try rig.onLan()) == 1)
    }

    @Test func sr_2_11_anAskThatCantBeSavedIsNotSentButTheLinksStillClose() async throws {
        let rig = try await Rig()
        await rig.lanUp()
        rig.saved.failing = true
        #expect(await rig.session.reconnect())
        #expect(asks(try rig.onLan()) == 0)
        #expect(rig.lan.closed == [rig.phoneID])
    }
}
