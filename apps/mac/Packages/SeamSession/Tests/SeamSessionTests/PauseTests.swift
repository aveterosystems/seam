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

    private func pause(_ untilMs: Int64) -> Seam_V1_Payload { .with { $0.control = .with { $0.pause = .with { $0.untilMs = untilMs } } } }

    private func clips(_ rig: Rig) throws -> [String] {
        try rig.onRelay().compactMap { if case .clipboard(let c)? = $0.body { c.text } else { nil } }
    }

    private func ms(_ date: Date) -> Int64 { Int64(date.timeIntervalSince1970 * 1000) }

    @Test func aPauseFromThePhoneStopsCopiesAndSaysUntilWhen() async throws {
        let rig = try await Rig()
        await rig.relayUp()
        let until = rig.timers.now().addingTimeInterval(3600)
        #expect(await rig.session.relayReceived(try rig.fromPhone(pause(ms(until)))))
        let shown = try #require(await rig.session.state.pausedUntil)
        #expect(abs(shown.timeIntervalSince(until)) < 0.001)
        rig.pasteboard.copy("copied while paused")
        await rig.timers.advance(1)
        #expect(try clips(rig).isEmpty)
    }

    @Test func untilResumedHasNoEndTime() async throws {
        let rig = try await Rig()
        await rig.relayUp()
        _ = await rig.session.relayReceived(try rig.fromPhone(pause(.max)))
        await rig.timers.advance(3600)
        #expect(await rig.session.state.pausedUntil == .distantFuture)
    }

    @Test func aCopyMadeWhilePausedNeverGoesAfterTheResume() async throws {
        let rig = try await Rig()
        await rig.relayUp()
        _ = await rig.session.relayReceived(try rig.fromPhone(pause(.max)))
        rig.pasteboard.copy("copied while paused")
        await rig.timers.advance(1)
        _ = await rig.session.relayReceived(try rig.fromPhone(pause(0)))
        #expect(await rig.session.state.pausedUntil == nil)
        await rig.timers.advance(1)
        #expect(try clips(rig).isEmpty)
        rig.pasteboard.copy("after the resume")
        await rig.timers.advance(1)
        #expect(try clips(rig) == ["after the resume"])
    }

    @Test func aTimedPauseEndsOnTheMacAtTheSameTime() async throws {
        let rig = try await Rig()
        await rig.relayUp()
        let start = rig.timers.now()
        _ = await rig.session.relayReceived(try rig.fromPhone(pause(ms(start.addingTimeInterval(60)))))
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
        await rig.relayUp()
        _ = await rig.session.relayReceived(try rig.fromPhone(pause(.max)))
        let restarted = try await rig.restart()
        #expect(await restarted.state.pausedUntil == .distantFuture)
    }

    @Test func forgettingThePhoneEndsThePause() async throws {
        let rig = try await Rig()
        await rig.relayUp()
        _ = await rig.session.relayReceived(try rig.fromPhone(pause(.max)))
        #expect(await rig.session.unpairPhone())
        #expect(await rig.session.state.pausedUntil == nil)
    }
}
