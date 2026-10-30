// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import SeamCore
import SeamProtocol
import Testing

@testable import SeamSession

/// Getting a message to the phone (spec 02), through the session's interface only.
struct SessionDeliveryTests {
    @Test func goesOnSameWifiFirstAndOnce() async throws {
        let rig = try await Rig()
        await rig.relayUp()
        await rig.lanUp()
        await rig.sendSomething(1)
        #expect(try rig.onLan().numbers == [1])
        #expect(try rig.onRelay().numbers == [])
    }

    @Test func aMessageThatCouldntBeSavedIsNeverSent() async throws {
        let rig = try await Rig()
        await rig.relayUp()
        rig.saved.failing = true
        await rig.sendSomething(1)
        #expect(try rig.onRelay().numbers == [])
        #expect(await rig.session.state.warning != nil)
    }

    @Test func nothingGoesThatIsntInTheSavedOutboxFirst() async throws {
        let rig = try await Rig()
        await rig.sendSomething(1) // no path: it waits
        #expect(await rig.session.state.waiting == 1)
        #expect(rig.outbox.load(now: rig.timers.now()).waiting.count == 1)
    }

    @Test func unackedOnSameWifiGoesAgainOnTheRelayInOrderWhenTheLinkDrops() async throws {
        let rig = try await Rig()
        await rig.lanUp()
        await rig.sendSomething(1)
        await rig.sendSomething(2)
        #expect(try rig.onLan().numbers == [1, 2])
        await rig.session.lanEvent(.closed(peerId: rig.phoneID))
        await rig.relayUp()
        #expect(try rig.onRelay().numbers == [1, 2])
        #expect(await rig.session.state.waiting == 0)
    }

    @Test func whatThePhoneAckedIsntResent() async throws {
        let rig = try await Rig()
        await rig.lanUp()
        await rig.sendSomething(1)
        let msgID = try #require(rig.lan.frames.last?.deliver.msgID)
        await rig.session.lanEvent(.delivered(peerId: rig.phoneID, msgID: msgID))
        await rig.session.lanEvent(.closed(peerId: rig.phoneID))
        await rig.relayUp()
        #expect(try rig.onRelay().numbers == [])
    }

    @Test func aNewLinkPicksUpWhatTheOldOneNeverGotAcked() async throws {
        let rig = try await Rig()
        await rig.lanUp()
        await rig.sendSomething(1)
        await rig.session.lanEvent(.authenticated(peerId: rig.phoneID)) // replaced without a close
        #expect(rig.lan.frames.filter { $0.deliver.ciphertext.count > 0 }.count >= 2)
        #expect(try rig.onLan().numbers == [1, 1])
    }

    @Test func aFailedRelaySendIsRetriedWhenTheRelayIsBack() async throws {
        let rig = try await Rig(wifi: false)
        rig.relay.failing = true
        await rig.relayUp()
        await rig.sendSomething(1)
        #expect(try rig.onRelay().isEmpty)
        rig.relay.failing = false
        await rig.session.relayDisconnected()
        await rig.session.relayConnected()
        #expect(try rig.onRelay().numbers == [1])
        #expect(await rig.session.state.waiting == 0)
    }

    @Test func whatTheRelayTookIsntSentAgain() async throws {
        let rig = try await Rig()
        await rig.relayUp()
        await rig.sendSomething(1)
        await rig.session.relayDisconnected()
        await rig.session.relayConnected()
        #expect(try rig.onRelay().numbers == [1])
    }

    @Test func aPathSwitchedOffIsNeverUsed() async throws {
        let rig = try await Rig(wifi: false)
        await rig.lanUp()
        await rig.sendSomething(1)
        #expect(rig.lan.frames.isEmpty)
        await rig.session.setSwitches(wifi: true, relay: false)
        await rig.relayUp()
        #expect(try rig.onRelay().isEmpty)
    }

    @Test func aPathThePhoneSwitchedOffIsNeverUsedButAHelloStillGetsThrough() async throws {
        let rig = try await Rig()
        await rig.relayUp()
        // The phone says Same Wi‑Fi only, and it's away: only the relay is up
        #expect(await rig.session.relayReceived(try rig.phoneHello(wifi: true, relay: false)))
        #expect(await rig.session.state.allowed == [.lan])
        let before = try rig.onRelay().count
        await rig.sendSomething(1)
        #expect(await rig.session.state.waiting == 1, "a message waits")
        await rig.session.setSwitches(wifi: true, relay: true)
        let after = try rig.onRelay().dropFirst(before)
        #expect(after.count == 1 && after.first?.isHello == true, "the Hello announcing the change goes")
        #expect(after.first?.control.hello.allowedTransports == [.lan, .relay])
    }

    @Test func anOlderPhoneThatSentAnEmptyListAllowsEverything() async throws {
        let rig = try await Rig()
        let empty = try rig.fromPhone(.with { $0.control = .with { $0.hello = .with { $0.seq = 1 } } })
        #expect(await rig.session.relayReceived(empty))
        #expect(Set(await rig.session.state.allowed) == [.lan, .relay])
    }

    @Test func anExpiredMessageIsDroppedNotSentLate() async throws {
        let rig = try await Rig()
        await rig.sendSomething(1) // 10 minutes to live
        await rig.timers.advance(601)
        await rig.relayUp()
        #expect(try rig.onRelay().numbers == [])
        #expect(await rig.session.state.waiting == 0)
    }

    @Test func theOutboxIsCapped() async throws {
        let rig = try await Rig()
        for n in 1...Int64(Outbox.cap + 5) { await rig.sendSomething(n) }
        #expect(await rig.session.state.waiting == Outbox.cap)
        await rig.relayUp()
        #expect(try rig.onRelay().numbers == Array(6...Int64(Outbox.cap + 5)), "the oldest went")
    }

    @Test func whatWaitedIsSentAfterARestart() async throws {
        let rig = try await Rig()
        await rig.sendSomething(1)
        let session = try await rig.restart()
        #expect(await session.state.waiting == 1)
        await rig.relayUp()
        #expect(try rig.onRelay().numbers == [1])
    }

    @Test func aHelloGoesWhenAPathComesUpAndItsSeqSurvivesARestart() async throws {
        let rig = try await Rig()
        await rig.relayUp()
        #expect(try rig.onRelay().map(\.control.hello.seq) == [1])
        _ = try await rig.restart()
        await rig.relayUp()
        #expect(try rig.onRelay().map(\.control.hello.seq) == [1, 2])
    }

    @Test func showsTheRouteForTheStatusLine() async throws {
        let rig = try await Rig()
        #expect(await rig.session.state.route == nil)
        await rig.relayUp()
        #expect(await rig.session.state.route == .relay)
        await rig.lanUp()
        #expect(await rig.session.state.route == .lan)
        await rig.session.setLan(nil)
        #expect(await rig.session.state.route == .relay)
    }
}
