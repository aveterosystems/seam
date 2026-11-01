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
        { rig in try rig.onTor().compactMap { if case .clipboard(let c)? = $0.body { c.text } else { nil } } }
    }

    @Test func aClipFromThePhoneIsPastedHereAndNotSentBack() async throws {
        let rig = try await Rig()
        await rig.torUp()
        #expect(await rig.session.received(try rig.fromPhone(clip("from the phone"))))
        #expect(rig.pasteboard.text == "from the phone")
        #expect(await rig.clipboard.clips.map(\.text) == ["from the phone"])
        #expect(await rig.clipboard.clips.allSatisfy(\.fromPhone))
        await rig.timers.advance(1)
        #expect(try clips(rig).isEmpty)
    }

    @Test func aCopyHereGoesToThePhoneOnlyWithSyncOn() async throws {
        let rig = try await Rig()
        await rig.torUp()
        rig.pasteboard.copy("one")
        await rig.timers.advance(1)
        #expect(try clips(rig) == ["one"])
        await rig.clipboard.setSync(false)
        rig.pasteboard.copy("private")
        await rig.timers.advance(1)
        await rig.clipboard.setSync(true)
        await rig.timers.advance(1)
        #expect(try clips(rig) == ["one"], "nothing copied while off goes, even after it's back on")
        #expect(await rig.clipboard.clips.isEmpty, "turning sync off takes the history off the screen (SR-10-04)")
    }

    @Test func sr_29_05_turningSyncOffMidReadStopsThatCopy() async throws {
        let rig = try await Rig()
        await rig.torUp()
        let clipboard = rig.clipboard
        rig.pasteboard.whileReading = { await clipboard.setSync(false) }
        rig.pasteboard.copy("private")
        await rig.timers.advance(1)
        #expect(try clips(rig).isEmpty)
    }

    @Test func aPasswordManagersCopyIsNeverSent() async throws {
        let rig = try await Rig()
        await rig.torUp()
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
        await rig.torUp()
        #expect(try clips(rig) == ["latest"])
    }

    @Test func copyingAPhoneClipAgainDoesntSendItBack() async throws {
        let rig = try await Rig()
        await rig.torUp()
        await rig.clipboard.copyAgain("again")
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
        _ = await rig.session.received(try rig.fromPhone(.with { $0.notificationSettings = settings }))
        _ = await rig.session.received(try rig.fromPhone(.with { $0.notification = .with { $0.key = "k"; $0.appID = "chat" } }))
        #expect(rig.centre.shown.map(\.notification.key) == ["k"])
        #expect(rig.centre.shown.first?.icon == png)
        #expect(await rig.notifications.icons["chat"] == png)
        _ = await rig.session.received(try rig.fromPhone(.with { $0.notificationAction = .with { $0.key = "k"; $0.dismissed = true } }))
        #expect(rig.centre.dismissed == ["k"], "dismissed on the phone, dismissed here")
    }

    @Test func iconsAreAskedForFortyAtATimeAndOnlyOnce() async throws {
        let rig = try await Rig()
        await rig.torUp()
        let settings = Seam_V1_NotificationSettings.with { s in s.apps = (0..<45).map { i in .with { $0.appID = "app\(i)" } } }
        let asked = { () throws -> [[String]] in
            try rig.onTor().compactMap { if case .notificationSettingsChange(let c)? = $0.body { c.needIcons } else { nil } }
        }
        _ = await rig.session.received(try rig.fromPhone(.with { $0.notificationSettings = settings }))
        #expect(try asked().map(\.count) == [40])
        _ = await rig.session.received(try rig.fromPhone(.with { $0.notificationSettings = settings }))
        #expect(try asked().map(\.count) == [40, 5])
        _ = await rig.session.received(try rig.fromPhone(.with { $0.notificationSettings = settings }))
        #expect(try asked().map(\.count) == [40, 5], "not asked again")
    }

    @Test func dismissingHereDismissesOnThePhoneOnlyWithDismissBothOn() async throws {
        let rig = try await Rig()
        await rig.torUp()
        let dismissed = Seam_V1_NotificationAction.with { $0.key = "k"; $0.dismissed = true }
        let actions = { () throws -> Int in try rig.onTor().filter { if case .notificationAction? = $0.body { true } else { false } }.count }
        _ = await rig.session.received(try rig.fromPhone(.with { $0.notificationSettings = .with { $0.dismissBoth = false } }))
        await rig.notifications.act(dismissed)
        #expect(try actions() == 0)
        await rig.notifications.change(.with { $0.dismissBoth = true })
        #expect(await rig.notifications.settings?.dismissBoth == true, "shown at once")
        await rig.notifications.act(dismissed)
        #expect(try actions() == 1)
    }

    @Test func aCopyHereIsInTheHistoryUntilCleared() async throws {
        let rig = try await Rig()
        await rig.torUp()
        rig.pasteboard.copy("mine")
        await rig.timers.advance(1)
        #expect(await rig.clipboard.clips.map { "\($0.text) \($0.fromPhone)" } == ["mine false"])
        await rig.clipboard.clearClips()
        #expect(await rig.clipboard.clips.isEmpty)
    }

    @Test func theMainWindowListsThePhonesNotificationsUntilDismissed() async throws {
        let rig = try await Rig()
        await rig.torUp()
        let note = { (key: String, title: String) in Seam_V1_Payload.with { $0.notification = .with { $0.key = key; $0.title = title } } }
        _ = await rig.session.received(try rig.fromPhone(note("a", "first")))
        _ = await rig.session.received(try rig.fromPhone(note("b", "second")))
        _ = await rig.session.received(try rig.fromPhone(note("a", "first, updated")))
        #expect(await rig.notifications.shown.map(\.title) == ["first, updated", "second"], "an update replaces it, newest first")
        await rig.notifications.dismiss(key: "a")
        #expect(await rig.notifications.shown.map(\.key) == ["b"])
        #expect(rig.centre.dismissed == ["a"], "its banner goes too")
        _ = await rig.session.received(try rig.fromPhone(.with { $0.notificationAction = .with { $0.key = "b"; $0.dismissed = true } }))
        #expect(await rig.notifications.shown.isEmpty, "dismissed on the phone")
    }

    @Test func onlyWhatArrivesFromThePhoneIsAnnounced() async throws {
        let rig = try await Rig()
        await rig.torUp()
        let heard = EventLog()
        await MainActor.run {
            rig.clipboard.onArrival = { heard.add($0) }
            rig.notifications.onArrival = { heard.add($0) }
        }
        rig.pasteboard.copy("mine")
        await rig.timers.advance(1)
        _ = await rig.session.received(try rig.fromPhone(clip("theirs")))
        let note = Seam_V1_Payload.with { $0.notification = .with { $0.key = "a"; $0.appName = "Chat"; $0.postedAt = 1 } }
        _ = await rig.session.received(try rig.fromPhone(note))
        _ = await rig.session.received(try rig.fromPhone(note)) // the same one again isn't new
        #expect(heard.all == ["Copied from your phone", "Notification from Chat"])
        #expect(await rig.notifications.arrivals == 1, "the bell rings once")
    }

    @Test func theBatteryNeverShowsMoreThanAHundred() async throws {
        let rig = try await Rig()
        _ = await rig.session.received(try rig.fromPhone(.with { $0.status = .with { $0.batteryPct = 250 } }))
        #expect(await rig.status.status?.batteryPct == 100)
    }

    @Test func thePhonesStatusSaysWhenItArrived() async throws {
        let rig = try await Rig()
        let sent = rig.timers.now()
        _ = await rig.session.received(try rig.fromPhone(.with { $0.status = .with { $0.batteryPct = 40; $0.network = "wifi"; $0.dnd = true } }))
        let (status, at) = await (rig.status.status, rig.status.statusAt)
        #expect(at == sent && status?.network == "wifi" && status?.dnd == true)
    }

    private func statuses(_ rig: Rig) throws -> [UInt32] {
        try rig.onTor().compactMap { if case .status(let s)? = $0.body { s.batteryPct } else { nil } }
    }

    @Test func thisMacsBatteryGoesWhenThePhoneConnectsAndWhenItChanges() async throws {
        let rig = try await Rig()
        rig.saved.ownStatus = .with { $0.batteryPct = 64; $0.charging = true }
        await rig.status.ownChanged()
        #expect(try statuses(rig).isEmpty, "nothing is queued while the phone is away")
        await rig.torUp()
        #expect(try statuses(rig) == [64])
        rig.saved.ownStatus = .with { $0.batteryPct = 63 }
        await rig.status.ownChanged()
        #expect(try statuses(rig) == [64, 63])
    }

    @Test func aMacWithoutABatterySendsNoStatus() async throws {
        let rig = try await Rig()
        await rig.torUp()
        await rig.status.ownChanged()
        #expect(try statuses(rig).isEmpty)
    }

    @Test func whenThePhoneUnpairsEveryFeaturesDataGoes() async throws {
        let rig = try await Rig()
        await rig.torUp()
        _ = await rig.session.received(try rig.fromPhone(.with { $0.status = .with { $0.batteryPct = 50 } }))
        _ = await rig.session.received(try rig.fromPhone(clip("a clip")))
        _ = await rig.session.received(try rig.fromPhone(.with { $0.notificationSettings = .with { $0.enabled = true } }))
        _ = await rig.session.received(try rig.fromPhone(.with { $0.notification = .with { $0.key = "k" } }))
        _ = await rig.session.received(try rig.fromPhone(.with { $0.control = .with { $0.unpair = .init() } }))
        let state = await rig.session.state
        #expect(state.phone == nil)
        #expect(await rig.status.status == nil)
        #expect(await rig.clipboard.clips.isEmpty)
        #expect(await rig.notifications.shown.isEmpty)
        #expect(await rig.notifications.settings == nil)
        #expect(rig.centre.shown.isEmpty)
        #expect(rig.mac.peers().isEmpty)
        #expect(state.torServices.isEmpty && rig.mac.appData() == nil, "its onion service goes too")
        #expect(rig.tor.closed == [rig.phoneID])
    }

    @Test func sr_7_04_anUnpairThatCantBeSavedSendsNoGoodbye() async throws {
        let rig = try await Rig()
        await rig.torUp()
        await rig.lanUp()
        let (torBefore, lanBefore) = (rig.tor.frames.count, rig.lan.frames.count)
        rig.saved.failing = true
        #expect(await rig.session.unpairPhone() == false)
        #expect(rig.tor.frames.count == torBefore)
        #expect(rig.lan.frames.count == lanBefore)
    }

    @Test func sr_29_06_unpairingWithNoPhoneHasNothingToForget() async throws {
        let rig = try await Rig(paired: false)
        await rig.torUp()
        #expect(await rig.session.unpairPhone())
        #expect(rig.tor.frames.isEmpty, "and nothing is sent")
    }

    @Test func unpairingTellsThePhoneOnceOnThePathThatsUp() async throws {
        let rig = try await Rig()
        await rig.torUp()
        _ = await rig.session.received(try rig.fromPhone(.with { $0.status = .with { $0.batteryPct = 50 } }))
        let before = rig.tor.frames.count
        #expect(await rig.session.unpairPhone())
        let after = Array(rig.tor.frames.dropFirst(before))
        #expect(after.count == 1)
        let goodbye = try Seam_V1_Payload(serializedBytes: try rig.phone.open(envelope: after[0].deliver.serializedData()).payload)
        #expect(goodbye.control.unpair == .init())
        #expect(rig.tor.closed == [rig.phoneID], "then the link closes")
        #expect(await rig.session.state.torServices.isEmpty, "and the service goes")
        #expect(rig.lan.frames.isEmpty)
        #expect(await rig.status.status == nil && rig.mac.peers().isEmpty)
    }

    /// A 1×1 PNG.
    static func png() -> Data {
        let image = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: 1, pixelsHigh: 1, bitsPerSample: 8, samplesPerPixel: 4,
                                     hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 4, bitsPerPixel: 32)!
        return image.representation(using: .png, properties: [:])!
    }
}
