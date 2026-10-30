// SPDX-License-Identifier: AGPL-3.0-only
import AppKit
import Foundation
import SeamCore
import SeamProtocol
import Testing

@testable import SeamSession

/// The features behind the session (spec 04): what arrives from the phone, what goes back, and
/// that forgetting the phone clears them all.
struct SessionFeatureTests {
    private func clip(_ text: String) -> Seam_V1_Payload { .with { $0.clipboard = .with { $0.text = text } } }

    private var clips: (Rig) throws -> [String] {
        { rig in try rig.onRelay().compactMap { if case .clipboard(let c)? = $0.body { c.text } else { nil } } }
    }

    @Test func aClipFromThePhoneIsPastedHereAndNotSentBack() async throws {
        let rig = try await Rig()
        await rig.relayUp()
        #expect(await rig.session.relayReceived(try rig.fromPhone(clip("from the phone"))))
        #expect(rig.pasteboard.text == "from the phone")
        #expect(await rig.session.state.recentFromPhone.map(\.text) == ["from the phone"])
        await rig.timers.advance(1)
        #expect(try clips(rig).isEmpty)
    }

    @Test func aCopyHereGoesToThePhoneOnlyWithSyncOn() async throws {
        let rig = try await Rig()
        await rig.relayUp()
        rig.pasteboard.copy("one")
        await rig.timers.advance(1)
        #expect(try clips(rig) == ["one"])
        await rig.session.setClipboardSync(false)
        rig.pasteboard.copy("private")
        await rig.timers.advance(1)
        await rig.session.setClipboardSync(true)
        await rig.timers.advance(1)
        #expect(try clips(rig) == ["one"], "nothing copied while off goes, even after it's back on")
    }

    @Test func aPasswordManagersCopyIsNeverSent() async throws {
        let rig = try await Rig()
        await rig.relayUp()
        rig.pasteboard.copy("hunter2", types: ["public.utf8-plain-text", "org.nspasteboard.ConcealedType"])
        await rig.timers.advance(1)
        #expect(try clips(rig).isEmpty)
    }

    @Test func aCopyWhileThePhoneIsAwayGoesWhenItsBack() async throws {
        let rig = try await Rig()
        rig.pasteboard.copy("older")
        await rig.timers.advance(1)
        rig.pasteboard.copy("latest")
        await rig.timers.advance(1)
        await rig.relayUp()
        #expect(try clips(rig) == ["latest"])
    }

    @Test func copyingAPhoneClipAgainDoesntSendItBack() async throws {
        let rig = try await Rig()
        await rig.relayUp()
        await rig.session.copyAgain("again")
        await rig.timers.advance(1)
        #expect(rig.pasteboard.text == "again")
        #expect(try clips(rig).isEmpty)
    }

    @Test func notificationsShowWithTheirAppsIcon() async throws {
        let rig = try await Rig()
        let png = Self.png()
        let settings = Seam_V1_NotificationSettings.with {
            $0.apps = [.with { $0.appID = "chat"; $0.iconPng = png }]
        }
        _ = await rig.session.relayReceived(try rig.fromPhone(.with { $0.notificationSettings = settings }))
        _ = await rig.session.relayReceived(try rig.fromPhone(.with { $0.notification = .with { $0.key = "k"; $0.appID = "chat" } }))
        #expect(rig.centre.shown.map(\.notification.key) == ["k"])
        #expect(rig.centre.shown.first?.icon == png)
        #expect(await rig.session.state.appIcons["chat"] == png)
        _ = await rig.session.relayReceived(try rig.fromPhone(.with { $0.notificationAction = .with { $0.key = "k"; $0.dismissed = true } }))
        #expect(rig.centre.dismissed == ["k"], "dismissed on the phone, dismissed here")
    }

    @Test func iconsAreAskedForFortyAtATimeAndOnlyOnce() async throws {
        let rig = try await Rig()
        await rig.relayUp()
        let settings = Seam_V1_NotificationSettings.with { s in s.apps = (0..<45).map { i in .with { $0.appID = "app\(i)" } } }
        let asked = { () throws -> [[String]] in
            try rig.onRelay().compactMap { if case .notificationSettingsChange(let c)? = $0.body { c.needIcons } else { nil } }
        }
        _ = await rig.session.relayReceived(try rig.fromPhone(.with { $0.notificationSettings = settings }))
        #expect(try asked().map(\.count) == [40])
        _ = await rig.session.relayReceived(try rig.fromPhone(.with { $0.notificationSettings = settings }))
        #expect(try asked().map(\.count) == [40, 5])
        _ = await rig.session.relayReceived(try rig.fromPhone(.with { $0.notificationSettings = settings }))
        #expect(try asked().map(\.count) == [40, 5], "not asked again")
    }

    @Test func dismissingHereDismissesOnThePhoneOnlyWithDismissBothOn() async throws {
        let rig = try await Rig()
        await rig.relayUp()
        let dismissed = Seam_V1_NotificationAction.with { $0.key = "k"; $0.dismissed = true }
        let actions = { () throws -> Int in try rig.onRelay().filter { if case .notificationAction? = $0.body { true } else { false } }.count }
        _ = await rig.session.relayReceived(try rig.fromPhone(.with { $0.notificationSettings = .with { $0.dismissBoth = false } }))
        await rig.session.notificationAction(dismissed)
        #expect(try actions() == 0)
        await rig.session.changeNotificationSettings(.with { $0.dismissBoth = true })
        #expect(await rig.session.state.notificationSettings?.dismissBoth == true, "shown at once")
        await rig.session.notificationAction(dismissed)
        #expect(try actions() == 1)
    }

    @Test func theBatteryNeverShowsMoreThanAHundred() async throws {
        let rig = try await Rig()
        _ = await rig.session.relayReceived(try rig.fromPhone(.with { $0.status = .with { $0.batteryPct = 250 } }))
        #expect(await rig.session.state.status?.batteryPct == 100)
    }

    @Test func whenThePhoneUnpairsEveryFeaturesDataGoes() async throws {
        let rig = try await Rig()
        await rig.relayUp()
        _ = await rig.session.relayReceived(try rig.fromPhone(.with { $0.status = .with { $0.batteryPct = 50 } }))
        _ = await rig.session.relayReceived(try rig.fromPhone(clip("a clip")))
        _ = await rig.session.relayReceived(try rig.fromPhone(.with { $0.notificationSettings = .with { $0.enabled = true } }))
        _ = await rig.session.relayReceived(try rig.fromPhone(.with { $0.notification = .with { $0.key = "k" } }))
        _ = await rig.session.relayReceived(try rig.fromPhone(.with { $0.control = .with { $0.unpair = .init() } }))
        let state = await rig.session.state
        #expect(state.phone == nil)
        #expect(state.status == nil && state.recentFromPhone.isEmpty && state.notificationSettings == nil)
        #expect(rig.centre.shown.isEmpty)
        #expect(rig.mac.peers().isEmpty)
        #expect(rig.relay.frames.last?.removeMember.deviceKey == rig.phone.relayKey(), "and it's out of the relay group")
    }

    @Test func sr_7_04_anUnpairThatCantBeSavedSendsNoGoodbye() async throws {
        let rig = try await Rig()
        await rig.relayUp()
        await rig.lanUp()
        let (relayBefore, lanBefore) = (rig.relay.frames.count, rig.lan.frames.count)
        rig.saved.failing = true
        #expect(await rig.session.unpairPhone() == false)
        #expect(rig.relay.frames.count == relayBefore, "nothing on the relay, not even the removal")
        #expect(rig.lan.frames.count == lanBefore)
    }

    @Test func unpairingTellsThePhoneOnceOnThePathThatsUp() async throws {
        let rig = try await Rig()
        await rig.relayUp()
        _ = await rig.session.relayReceived(try rig.fromPhone(.with { $0.status = .with { $0.batteryPct = 50 } }))
        let before = rig.relay.frames.count
        #expect(await rig.session.unpairPhone())
        let after = Array(rig.relay.frames.dropFirst(before))
        #expect(after.count == 2)
        let goodbye = try Seam_V1_Payload(serializedBytes: try rig.phone.open(envelope: after[0].send.serializedData()).payload)
        #expect(goodbye.control.unpair == .init())
        #expect(after[1].removeMember.deviceKey == rig.phone.relayKey())
        #expect(rig.lan.frames.isEmpty)
        #expect(await rig.session.state.status == nil && rig.mac.peers().isEmpty)
    }

    /// A 1×1 PNG.
    static func png() -> Data {
        let image = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: 1, pixelsHigh: 1, bitsPerSample: 8, samplesPerPixel: 4,
                                     hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 4, bitsPerPixel: 32)!
        return image.representation(using: .png, properties: [:])!
    }
}
