// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import SeamProtocol
import SeamSession
import Testing

@Suite struct LooksTests {
    private let now = Date(timeIntervalSince1970: 1_793_455_200) // a Saturday afternoon
    private var cal: Calendar { var c = Calendar(identifier: .gregorian); c.timeZone = TimeZone(identifier: "UTC")!; return c }

    @Test func plainOfflineSaysWhenThePhoneWasLastHere() {
        #expect(LinkLook.away.statusLine(lastSeen: now.addingTimeInterval(-20), now: now) == "Last seen just now")
        #expect(LinkLook.away.statusLine(lastSeen: now.addingTimeInterval(-12 * 60), now: now) == "Last seen 12 min ago")
        #expect(LinkLook.away.statusLine(lastSeen: nil, now: now) == LinkLook.away.label, "never seen this run: the reason")
        #expect(LinkLook.sameWifi.statusLine(lastSeen: now, now: now) == "Connected · Same Wi‑Fi")
        #expect(LinkLook.away.heroLine(lastSeen: now, now: now) == "Last seen just now · will send when it's back")
        #expect(LinkLook.torBlocked.heroLine(lastSeen: now, now: now) == LinkLook.torBlocked.label)
    }

    // A reason the user can act on is never replaced by a time
    @Test func otherReasonsStayInWords() {
        for look in [LinkLook.torBlocked, .torClock, .noSharedPath, .waitingOnWifi, .connectingTor] {
            #expect(look.statusLine(lastSeen: now.addingTimeInterval(-60), now: now) == look.label)
        }
    }

    @Test func lastSeenGetsCoarserWithAge() {
        #expect(LastSeen.text(now.addingTimeInterval(-59), now: now, calendar: cal) == "just now")
        #expect(LastSeen.text(now.addingTimeInterval(-5), now: now, calendar: cal, justNow: "now") == "now")
        #expect(LastSeen.text(now.addingTimeInterval(-61), now: now, calendar: cal) == "1 min ago")
        #expect(LastSeen.text(now.addingTimeInterval(-2 * 3600), now: now, calendar: cal).hasPrefix("today at "))
        #expect(LastSeen.text(now.addingTimeInterval(-24 * 3600), now: now, calendar: cal).hasPrefix("yesterday at "))
        #expect(!LastSeen.text(now.addingTimeInterval(-5 * 86400), now: now, calendar: cal).contains("ago"))
    }

    @Test func batteryWarnsAtFifteenOnlyWhenNotCharging() {
        let low = Seam_V1_Status.with { $0.batteryPct = 15 }
        #expect(PhoneStatusLook(low, connected: true, at: now).low)
        #expect(!PhoneStatusLook(.with { $0.batteryPct = 15; $0.charging = true }, connected: true, at: now).low)
        #expect(!PhoneStatusLook(.with { $0.batteryPct = 16 }, connected: true, at: now).low)
        #expect(PhoneStatusLook(low, connected: true, at: now).spoken == "battery 15 percent")
    }

    @Test func metaIsTheNetworkWhileConnectedAndTheTimeWhenNot() {
        let wifi = Seam_V1_Status.with { $0.batteryPct = 82; $0.network = "wifi"; $0.dnd = true }
        let on = PhoneStatusLook(wifi, connected: true, at: now)
        #expect(on.meta == "Wi‑Fi" && on.network == "Wi‑Fi" && on.dnd)
        #expect(PhoneStatusLook(.with { $0.network = "cellular" }, connected: true, at: now).meta == "Mobile data")
        #expect(PhoneStatusLook(.with { $0.network = "" }, connected: true, at: now).meta == nil)
        let off = PhoneStatusLook(wifi, connected: false, at: now)
        #expect(off.meta?.hasPrefix("as of ") == true)
        #expect(off.network == nil && !off.dnd, "an old status doesn't claim the network or DND now")
    }

    @Test func notificationsTileSaysItsStateInWords() {
        #expect(NotificationsLook(nil, showing: 0, now: now).subtitle == "Waiting for phone")
        #expect(NotificationsLook(nil, showing: 0, now: now).mode == .waiting)
        #expect(NotificationsLook(.with { $0.enabled = true }, showing: 2, now: now).subtitle == "On · 2 showing")
        #expect(NotificationsLook(.with { $0.enabled = true }, showing: 0, now: now).subtitle == "On")
        let off = NotificationsLook(.with { $0.enabled = false }, showing: 2, now: now)
        #expect(off.subtitle == "Off" && off.symbol == "bell.slash")
        let inAnHour = Int64(now.addingTimeInterval(3600).timeIntervalSince1970 * 1000)
        let paused = NotificationsLook(.with { $0.enabled = true; $0.pausedUntil = inAnHour }, showing: 2, now: now, calendar: cal)
        #expect(paused.mode == .paused && paused.subtitle.hasPrefix("Paused · ") && !paused.subtitle.contains("tomorrow"))
        let tomorrow = Int64(now.addingTimeInterval(18 * 3600).timeIntervalSince1970 * 1000)
        #expect(NotificationsLook(.with { $0.enabled = true; $0.pausedUntil = tomorrow }, showing: 0, now: now, calendar: cal).subtitle == "Paused · tomorrow")
        let over = Int64(now.addingTimeInterval(-1).timeIntervalSince1970 * 1000)
        #expect(NotificationsLook(.with { $0.enabled = true; $0.pausedUntil = over }, showing: 0, now: now).mode == .on)
    }

    @Test func clipboardTileAndCounts() {
        #expect(ClipboardLook.subtitle(syncOn: true, connected: true) == "Syncing")
        #expect(ClipboardLook.subtitle(syncOn: true, connected: false) == "On")
        #expect(ClipboardLook.subtitle(syncOn: false, connected: true) == "Off")
        #expect(Words.waiting(3) == "3 waiting")
        let apps: [Seam_V1_NotificationApp] = [.on, .titleOnly, .off, .unspecified].map { m in .with { $0.mode = m } }
        #expect(Words.appsOn(apps) == "2 of 4 on")
    }

    @Test func theFootnoteExplainsWhyTheLastConnectionLocks() {
        #expect(Words.connectionsFootnote(wifi: true, tor: true).hasPrefix("One always stays on"))
        #expect(Words.connectionsFootnote(wifi: true, tor: false).contains("Turn on Tor"))
        #expect(Words.connectionsFootnote(wifi: false, tor: true).hasPrefix("Through Tor even at home"))
    }

    @Test func onlyNewOrUpdatedNotificationsArrive() {
        let a = Seam_V1_Notification.with { $0.key = "a"; $0.postedAt = 1 }
        let b = Seam_V1_Notification.with { $0.key = "b"; $0.postedAt = 2 }
        #expect(Arrival.notification(old: [a], new: [b, a])?.key == "b")
        #expect(Arrival.notification(old: [b, a], new: [a]) == nil, "a dismissal")
        #expect(Arrival.notification(old: [a], new: [.with { $0.key = "a"; $0.postedAt = 5 }])?.key == "a", "an update")
        #expect(Arrival.notification(old: [a], new: [a]) == nil)
    }

    // No label ends in "…": words say a state, they don't trail off (the user's rule for Seam)
    @Test func noLabelTrailsOff() {
        let looks: [LinkLook] = [.sameWifi, .throughTor, .connectingTor, .torBlocked, .torClock, .noSharedPath, .waitingOnWifi, .away,
                                 .paused(until: .distantFuture, now: now)]
        for look in looks { #expect(!look.label.contains("…"), "\(look.label)") }
        let tiles = [NotificationsLook(nil, showing: 0), NotificationsLook(.with { $0.enabled = true }, showing: 1), NotificationsLook(.with { $0.enabled = false }, showing: 0)]
        for t in tiles { #expect(!t.subtitle.contains("…")) }
    }
}
