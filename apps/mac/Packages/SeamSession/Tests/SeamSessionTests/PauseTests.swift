// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import SeamCore
import SeamProtocol
import Testing

@testable import SeamSession

/// Pausing happens on the phone only. The Mac is told, shows it, sends nothing new and captures
/// nothing (so a copy made while paused never goes later), and keeps listening for the resume.
struct PauseTests {
    private func clip(_ text: String) -> Seam_V1_Payload { .with { $0.clipboard = .with { $0.text = text } } }

    /// The phone's Hello carrying its pause (SR-32-01: the pause rides on the Hello's counter).
    private func pause(_ rig: Rig, _ untilMs: Int64) throws -> Seam_V1_Envelope {
        try rig.phoneHello(wifi: true, tor: true, pausedUntilMs: untilMs)
    }

    private func clips(_ rig: Rig) throws -> [String] {
        try rig.onTor().compactMap { if case .clipboard(let c)? = $0.body { c.text } else { nil } }
    }

    private func ms(_ date: Date) -> Int64 { Int64(date.timeIntervalSince1970 * 1000) }

    @Test func aPauseFromThePhoneStopsCopiesAndSaysUntilWhen() async throws {
        let rig = try await Rig()
        await rig.torUp()
        let until = rig.timers.now().addingTimeInterval(3600)
        #expect(await rig.session.received(try pause(rig, ms(until))))
        let shown = try #require(await rig.session.state.pausedUntil)
        #expect(abs(shown.timeIntervalSince(until)) < 0.001)
        rig.pasteboard.copy("copied while paused")
        await rig.timers.advance(1)
        #expect(try clips(rig).isEmpty)
    }

    @Test func thisMacsBatteryStaysHereWhilePaused() async throws {
        let rig = try await Rig()
        await rig.torUp()
        _ = await rig.session.received(try pause(rig, .max))
        rig.saved.ownStatus = .with { $0.batteryPct = 50 }
        await rig.session.ownStatusChanged()
        #expect(try rig.onTor().allSatisfy { if case .status? = $0.body { false } else { true } })
    }

    @Test func untilResumedHasNoEndTime() async throws {
        let rig = try await Rig()
        await rig.torUp()
        _ = await rig.session.received(try pause(rig, .max))
        await rig.timers.advance(3600)
        #expect(await rig.session.state.pausedUntil == .distantFuture)
    }

    @Test func aCopyMadeWhilePausedNeverGoesAfterTheResume() async throws {
        let rig = try await Rig()
        await rig.torUp()
        _ = await rig.session.received(try pause(rig, .max))
        rig.pasteboard.copy("copied while paused")
        await rig.timers.advance(1)
        _ = await rig.session.received(try pause(rig, 0))
        #expect(await rig.session.state.pausedUntil == nil)
        await rig.timers.advance(1)
        #expect(try clips(rig).isEmpty)
        rig.pasteboard.copy("after the resume")
        await rig.timers.advance(1)
        #expect(try clips(rig) == ["after the resume"])
    }

    @Test func aTimedPauseEndsOnTheMacAtTheSameTime() async throws {
        let rig = try await Rig()
        await rig.torUp()
        let start = rig.timers.now()
        _ = await rig.session.received(try pause(rig, ms(start.addingTimeInterval(60))))
        var updates = await rig.session.updates.makeAsyncIterator()
        await rig.timers.advance(61)
        #expect(await rig.session.state.pausedUntil == nil, "no message: the phone ends it at the same time")
        #expect(await updates.next()?.pausedUntil == nil, "and the menu hears it ended")
        rig.pasteboard.copy("after the pause")
        await rig.timers.advance(1)
        #expect(try clips(rig) == ["after the pause"])
    }

    @Test func aPauseIsKeptAcrossARestart() async throws {
        let rig = try await Rig()
        await rig.torUp()
        _ = await rig.session.received(try pause(rig, .max))
        let restarted = try await rig.restart()
        #expect(await restarted.state.pausedUntil == .distantFuture)
    }

    @Test func forgettingThePhoneEndsThePause() async throws {
        let rig = try await Rig()
        await rig.torUp()
        _ = await rig.session.received(try pause(rig, .max))
        #expect(await rig.session.unpairPhone())
        #expect(await rig.session.state.pausedUntil == nil)
    }

    @Test func sr_32_01_aLateOrReplayedPauseCantRepauseAResumedMac() async throws {
        let rig = try await Rig()
        await rig.torUp()
        let paused = try pause(rig, .max) // seq n
        let resumed = try pause(rig, 0) // seq n + 1
        _ = await rig.session.received(resumed) // the resume arrives first
        _ = await rig.session.received(paused) // then the older pause, resent
        #expect(await rig.session.state.pausedUntil == nil, "an older Hello never applies")
    }

    @Test func sr_32_01_anyNewerHelloBringsTheCurrentState() async throws {
        let rig = try await Rig()
        await rig.torUp()
        _ = await rig.session.received(try pause(rig, .max))
        // The resume itself was lost (say, it expired from the outbox): the next connection's Hello says it
        _ = await rig.session.received(try rig.phoneHello(wifi: true, tor: true))
        #expect(await rig.session.state.pausedUntil == nil)
    }
}
