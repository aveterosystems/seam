// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import SeamCore
import SeamProtocol

// What each state shows is decided once, in Rust, for every app (seam-looks, decision 0015). This
// file only gives it this Mac's names and time words, and turns each icon meaning into an SF
// Symbol (docs/design/design-system.md, "Icons"). No view decides a word or an icon itself.

extension LookNames {
    /// How the Mac names itself and the phone.
    public static let mac = LookNames(side: .computer, this: "this Mac", peer: "your phone")
}

/// This Mac's clock and calendar, in the user's locale and time zone.
public final class SystemTime: TimeWords {
    public static let shared = SystemTime()

    public func time(ms: Int64) -> String { Date(ms: ms).formatted(date: .omitted, time: .shortened) }
    public func date(ms: Int64) -> String { Date(ms: ms).formatted(date: .abbreviated, time: .omitted) }
    public func daysBetween(ms: Int64, nowMs: Int64) -> Int32 {
        let c = Calendar.current
        return Int32(c.dateComponents([.day], from: c.startOfDay(for: Date(ms: ms)), to: c.startOfDay(for: Date(ms: nowMs))).day ?? 0)
    }
}

extension Date {
    public init(ms: Int64) { self.init(timeIntervalSince1970: TimeInterval(ms) / 1000) }
    public var ms: Int64 { Int64((timeIntervalSince1970 * 1000).rounded()) }
}

extension LinkInput {
    /// Not connected, both paths allowed, nothing else going on: before the first snapshot.
    public static let offline = LinkInput(route: nil, lanAllowed: true, torAllowed: true, tor: .ready,
                                          pausedUntilMs: nil, pausedHere: false, reconnect: nil, idle: false)
}

/// The shared looks with this Mac's names and clock filled in.
public enum Looks {
    public static func link(_ input: LinkInput, lastSeen: Date? = nil, now: Date = Date()) -> LinkLook {
        linkLook(input: input, names: .mac, lastSeenMs: lastSeen?.ms, nowMs: now.ms, time: SystemTime.shared)
    }

    /// The phone's battery, network, storage and Do Not Disturb, as `link` affects them.
    public static func phone(_ status: Seam_V1_Status, link: LinkLook, at: Date?) -> DeviceStatusLook? {
        guard let bytes = try? status.serializedData() else { return nil }
        return deviceStatusLook(status: bytes, connected: link.connected, atMs: at?.ms, sameWifi: link.kind == .sameWifi, time: SystemTime.shared)
    }

    public static func notificationsTile(_ settings: Seam_V1_NotificationSettings?, showing: Int, now: Date = Date()) -> NotificationsTile {
        SeamCore.notificationsTile(settingsBytes: settings.flatMap { try? $0.serializedData() }, showing: UInt32(showing), nowMs: now.ms, time: SystemTime.shared)
    }

    /// "6 of 10 on".
    public static func appsOn(_ settings: Seam_V1_NotificationSettings) -> String {
        notificationAppsOn(settingsBytes: (try? settings.serializedData()) ?? Data())
    }

    public static func appsOnCount(_ settings: Seam_V1_NotificationSettings) -> Int {
        Int(notificationAppsOnCount(settingsBytes: (try? settings.serializedData()) ?? Data()))
    }

    /// On, Sender only or Off.
    public static func modeWord(_ mode: Seam_V1_NotificationMode) -> String {
        notificationModeWord(mode: Int32(mode.rawValue))
    }

    /// "12 min ago", "today at 14:02".
    public static func ago(_ at: Date, now: Date = Date(), justNow: String = "just now") -> String {
        agoWords(atMs: at.ms, nowMs: now.ms, justNow: justNow, time: SystemTime.shared)
    }

    public static func connectionsFootnote(wifi: Bool, tor: Bool) -> String {
        SeamCore.connectionsFootnote(lanOn: wifi, torOn: tor, names: .mac)
    }
}

extension LookIcon {
    /// The SF Symbol for this meaning.
    public var symbol: String {
        switch self {
        case .sameWifi, .wifi: "wifi"
        case .throughTor: "globe"
        case .paused: "pause.circle"
        case .battery(let level, let charging): charging ? "battery.100percent.bolt" : "battery.\(level)percent"
        case .mobileSignal: "cellularbars"
        case .storage: "internaldrive"
        case .doNotDisturb: "moon.fill"
        case .cpu: "cpu"
        case .memory: "memorychip"
        case .notifications: "bell"
        case .notificationsOff: "bell.slash"
        }
    }

    /// For symbols that fill to a value (the mobile signal's bars).
    public var variableValue: Double? {
        if case .mobileSignal(let fraction) = self { Double(fraction) } else { nil }
    }
}
