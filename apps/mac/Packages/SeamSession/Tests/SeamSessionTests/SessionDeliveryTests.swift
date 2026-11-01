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
        await rig.torUp()
        await rig.lanUp()
        await rig.sendSomething(1)
        #expect(try rig.onLan().numbers == [1])
        #expect(try rig.onTor().numbers == [])
    }

    @Test func aMessageThatCouldntBeSavedIsNeverSent() async throws {
        let rig = try await Rig()
        await rig.torUp()
        rig.saved.failing = true
        await rig.sendSomething(1)
        #expect(try rig.onTor().numbers == [])
        #expect(await rig.session.state.warning != nil)
    }

    @Test func nothingGoesThatIsntInTheSavedOutboxFirst() async throws {
        let rig = try await Rig()
        await rig.sendSomething(1) // no path: it waits
        #expect(await rig.session.state.waiting == 1)
        #expect(rig.outbox.load(now: rig.timers.now()).waiting.count == 1)
    }

    @Test func unackedOnSameWifiGoesAgainThroughTorInOrderWhenTheLinkDrops() async throws {
        let rig = try await Rig()
        await rig.lanUp()
        await rig.sendSomething(1)
        await rig.sendSomething(2)
        #expect(try rig.onLan().numbers == [1, 2])
        await rig.session.linkEvent(.closed(peerId: rig.phoneID), on: .lan)
        await rig.torUp()
        #expect(try rig.onTor().numbers == [1, 2])
    }

    @Test func whatThePhoneAckedIsntResent() async throws {
        let rig = try await Rig()
        await rig.lanUp()
        await rig.sendSomething(1)
        let msgID = try #require(rig.lan.frames.last?.deliver.msgID)
        await rig.session.linkEvent(.delivered(peerId: rig.phoneID, msgID: msgID), on: .lan)
        await rig.session.linkEvent(.closed(peerId: rig.phoneID), on: .lan)
        await rig.torUp()
        #expect(try rig.onTor().numbers == [])
    }

    @Test func aNewLinkPicksUpWhatTheOldOneNeverGotAcked() async throws {
        let rig = try await Rig()
        await rig.lanUp()
        await rig.sendSomething(1)
        await rig.session.linkEvent(.authenticated(peerId: rig.phoneID), on: .lan) // replaced without a close
        #expect(rig.lan.frames.filter { $0.deliver.ciphertext.count > 0 }.count >= 2)
        #expect(try rig.onLan().numbers == [1, 1])
    }

    @Test func aSendTheTorStreamRefusedWaitsForThePhoneToReconnect() async throws {
        let rig = try await Rig(wifi: false)
        rig.tor.failing = true
        await rig.torUp()
        await rig.sendSomething(1)
        await rig.sendSomething(2)
        #expect(try rig.onTor().isEmpty)
        #expect(await rig.session.state.waiting == 3, "both, and this Mac's Hello")
        rig.tor.failing = false
        await rig.session.linkEvent(.authenticated(peerId: rig.phoneID), on: .tor)
        #expect(try rig.onTor().numbers == [1, 2], "in order")
    }

    @Test func throughTorAMessageStaysUntilThePhoneAcksIt() async throws {
        let rig = try await Rig(wifi: false)
        await rig.torUp()
        await rig.sendSomething(1)
        await rig.sendSomething(2)
        #expect(try rig.onTor().numbers == [1, 2])
        let ids = rig.tor.frames.map(\.deliver.msgID)
        await rig.session.linkEvent(.delivered(peerId: rig.phoneID, msgID: ids[1]), on: .tor) // 1 acked (after the Hello)
        await rig.session.linkEvent(.closed(peerId: rig.phoneID), on: .tor)
        let before = rig.tor.frames.count
        await rig.torUp()
        let again = rig.tor.frames.dropFirst(before).map(\.deliver.msgID)
        #expect(!again.contains(ids[1]), "acked: not resent")
        #expect(again.contains(ids[2]), "not acked: resent")
    }

    @Test func aPathSwitchedOffIsNeverUsed() async throws {
        let rig = try await Rig(wifi: false)
        await rig.lanUp()
        await rig.sendSomething(1)
        #expect(rig.lan.frames.isEmpty)
        await rig.session.setSwitches(wifi: true, tor: false)
        await rig.torUp()
        #expect(try rig.onTor().isEmpty)
    }

    @Test func aPathThePhoneSwitchedOffIsNeverUsedButAHelloStillGetsThrough() async throws {
        let rig = try await Rig()
        await rig.torUp()
        // The phone says Same Wi‑Fi only, and it's away: only Tor is up
        #expect(await rig.session.received(try rig.phoneHello(wifi: true, tor: false)))
        #expect(await rig.session.state.allowed == [.lan])
        let before = try rig.onTor().count
        await rig.sendSomething(1)
        #expect(try rig.onTor().numbers.isEmpty, "a message waits")
        await rig.session.setSwitches(wifi: true, tor: true)
        let after = try rig.onTor().dropFirst(before)
        #expect(after.count == 1 && after.first?.isHello == true, "the Hello announcing the change goes")
        #expect(after.first?.control.hello.allowedTransports == [.lan, .tor])
    }

    @Test func anOlderPhoneThatSentAnEmptyListAllowsEverything() async throws {
        let rig = try await Rig()
        let empty = try rig.fromPhone(.with { $0.control = .with { $0.hello = .with { $0.seq = 1 } } })
        #expect(await rig.session.received(empty))
        #expect(Set(await rig.session.state.allowed) == [.lan, .tor])
    }

    @Test func anExpiredMessageIsDroppedNotSentLate() async throws {
        let rig = try await Rig()
        await rig.sendSomething(1) // 10 minutes to live
        await rig.timers.advance(660) // the core stamps sentAt with the real clock, a few seconds after the rig began
        await rig.torUp()
        #expect(try rig.onTor().numbers == [])
        #expect(await rig.session.state.waiting == 1, "only the new Hello, until the phone acks it")
    }

    @Test func theOutboxIsCapped() async throws {
        let rig = try await Rig()
        for n in 1...Int64(Outbox.cap + 5) { await rig.sendSomething(n) }
        #expect(await rig.session.state.waiting == Outbox.cap)
        await rig.torUp()
        #expect(try rig.onTor().numbers == Array(6...Int64(Outbox.cap + 5)), "the oldest went")
    }

    @Test func whatWaitedIsSentAfterARestart() async throws {
        let rig = try await Rig()
        await rig.sendSomething(1)
        let session = try await rig.restart()
        #expect(await session.state.waiting == 1)
        await rig.torUp()
        #expect(try rig.onTor().numbers == [1])
    }

    @Test func aHelloGoesWhenAPathComesUpAndItsSeqSurvivesARestart() async throws {
        let rig = try await Rig()
        await rig.torUp()
        #expect(try rig.onTor().map(\.control.hello.seq) == [1])
        await rig.session.linkEvent(.delivered(peerId: rig.phoneID, msgID: rig.tor.frames[0].deliver.msgID), on: .tor)
        _ = try await rig.restart()
        await rig.torUp()
        #expect(try rig.onTor().map(\.control.hello.seq) == [1, 2])
    }

    @Test func showsTheRouteForTheStatusLine() async throws {
        let rig = try await Rig()
        #expect(await rig.session.state.route == nil)
        await rig.torUp()
        #expect(await rig.session.state.route == .tor)
        await rig.lanUp()
        #expect(await rig.session.state.route == .lan)
        await rig.session.setLink(nil, for: .lan)
        #expect(await rig.session.state.route == .tor)
    }
}
