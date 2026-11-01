// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import SeamProtocol

// The words the popover and Settings show for each state (docs/design/mac-screens.md, M01 and M03).
// Like `LinkLook`, each look is one value the views draw from, so no view decides its own words.

extension LinkLook {
    /// The status line under the phone's name. Plain "offline" says when the phone was last here;
    /// any other reason (Tor blocked, a pause, no shared path) stays in words, never hidden behind
    /// a time.
    public func statusLine(lastSeen: Date?, now: Date = Date(), calendar: Calendar = .current) -> String {
        guard self == .away, let lastSeen else { return sentence }
        return "Last seen " + LastSeen.text(lastSeen, now: now, calendar: calendar)
    }

    /// Settings' phone card has room to say what happens next, too.
    public func heroLine(lastSeen: Date?, now: Date = Date(), calendar: Calendar = .current) -> String {
        let line = statusLine(lastSeen: lastSeen, now: now, calendar: calendar)
        return line.hasPrefix("Last seen") ? line + " · will send when it's back" : line
    }
}

/// "just now", "12 min ago", "today at 14:02", "yesterday at 09:15", or a date. Notifications
/// say "now" for the first minute (the popover prototype's format).
public enum LastSeen {
    public static func text(_ at: Date, now: Date = Date(), calendar: Calendar = .current, justNow: String = "just now") -> String {
        let seconds = now.timeIntervalSince(at)
        if seconds < 60 { return justNow }
        if seconds < 3600 { return "\(Int(seconds / 60)) min ago" }
        let time = at.formatted(date: .omitted, time: .shortened)
        if calendar.isDate(at, inSameDayAs: now) { return "today at \(time)" }
        if let yesterday = calendar.date(byAdding: .day, value: -1, to: now), calendar.isDate(at, inSameDayAs: yesterday) {
            return "yesterday at \(time)"
        }
        return at.formatted(date: .abbreviated, time: .omitted)
    }
}

/// The phone's own status beside its name: the battery pill and the line under it.
public struct PhoneStatusLook: Equatable, Sendable {
    public let percent: Int
    public let charging: Bool
    /// 15% or below and not charging: the pill turns `warn`.
    public let low: Bool
    /// "Wi‑Fi" or "Mobile data", only while connected (never a network's name).
    public let network: String?
    /// The line under the pill: the network while connected, "as of 14:02" when the status is from before.
    public let meta: String?
    /// The phone is in Do Not Disturb (only while connected: an old status may be wrong).
    public let dnd: Bool

    public init(_ s: Seam_V1_Status, connected: Bool, at: Date?) {
        percent = Int(s.batteryPct)
        charging = s.charging
        low = s.batteryPct <= 15 && !s.charging
        dnd = connected && s.dnd
        let names = ["wifi": "Wi‑Fi", "cellular": "Mobile data"]
        network = connected ? names[s.network] : nil
        meta = connected ? network : at.map { "as of " + $0.formatted(date: .omitted, time: .shortened) }
    }

    public var spoken: String {
        "battery \(percent) percent" + (charging ? ", charging" : "") + (dnd ? ", Do Not Disturb" : "")
    }
}

/// The popover's Notifications tile and its menu. The phone owns the settings, so until it has
/// sent them the tile waits.
public struct NotificationsLook: Equatable, Sendable {
    public enum Mode: Equatable, Sendable { case waiting, on, paused, off }

    public let mode: Mode
    public let subtitle: String

    public init(_ s: Seam_V1_NotificationSettings?, showing: Int, now: Date = Date(), calendar: Calendar = .current) {
        guard let s else {
            mode = .waiting
            subtitle = "Waiting for phone"
            return
        }
        let until = Date(timeIntervalSince1970: TimeInterval(s.pausedUntil) / 1000)
        if s.enabled, until > now {
            mode = .paused
            // A later day fits the tile only as a word; the menu bar tile has no room for both
            subtitle = "Paused · " + (calendar.isDate(until, inSameDayAs: now) ? until.formatted(date: .omitted, time: .shortened) : "tomorrow")
        } else if s.enabled {
            mode = .on
            subtitle = showing > 0 ? "On · \(showing) showing" : "On"
        } else {
            mode = .off
            subtitle = "Off"
        }
    }

    public var symbol: String { mode == .on ? "bell" : "bell.slash" }
}

/// The popover's Clipboard tile.
public enum ClipboardLook {
    public static func subtitle(syncOn: Bool, connected: Bool) -> String {
        syncOn ? (connected ? "Syncing" : "On") : "Off"
    }
}

/// Small sentences that depend on a count or a switch.
public enum Words {
    /// The popover's queue bar.
    public static func waiting(_ n: Int) -> String { "\(n) waiting" }

    /// Settings' Apps header: "6 of 10 on". Title only counts as on.
    public static func appsOn(_ apps: [Seam_V1_NotificationApp]) -> String {
        "\(appsOnCount(apps)) of \(apps.count) on"
    }

    public static func appsOnCount(_ apps: [Seam_V1_NotificationApp]) -> Int {
        apps.filter { $0.mode == .on || $0.mode == .titleOnly }.count
    }

    /// Under the connection switches: why the last one on can't be turned off.
    public static func connectionsFootnote(wifi: Bool, tor: Bool) -> String {
        switch (wifi, tor) {
        case (true, false): "Your phone connects only on this Mac's network. Turn on Tor to reach it anywhere."
        case (false, true): "Through Tor even at home. Same Wi‑Fi is faster for big files."
        default: "One always stays on. The encryption is the same on both."
        }
    }
}

/// What just arrived from the phone, for the menu bar icon's bounce and VoiceOver.
public enum Arrival {
    /// A notification that's new, or updated (a new message in a chat), since `old`. Dismissals aren't arrivals.
    public static func notification(old: [Seam_V1_Notification], new: [Seam_V1_Notification]) -> Seam_V1_Notification? {
        new.first { n in !old.contains { $0.key == n.key && $0.postedAt == n.postedAt } }
    }

    /// A clip from the phone that wasn't there before.
    public static func clip(old: [Clip], new: [Clip]) -> Clip? {
        guard let newest = new.first(where: \.fromPhone), !old.contains(where: { $0.id == newest.id }) else { return nil }
        return newest
    }
}
