// SPDX-License-Identifier: AGPL-3.0-only
import AppKit
import Observation
import SeamCore
import SeamProtocol

// The features as the screens see them. Their logic is the Rust core's (seam-computer, decision
// 0014): these only mirror what its snapshot says, and pass what the user does back to it.

/// One clip that crossed between the Mac and the phone.
public struct Clip: Identifiable, Sendable, Equatable {
    public let id: String
    public let text: String
    public let at: Date
    public let fromPhone: Bool

    public init(text: String, at: Date, fromPhone: Bool) {
        // Stable across snapshots, so a list doesn't redraw every row
        id = "\(at.timeIntervalSince1970)-\(fromPhone)-\(text.hashValue)"
        self.text = text
        self.at = at
        self.fromPhone = fromPhone
    }

    /// The web address it may be opened as: only a clip that is exactly one (SR-10-03).
    public var openableURL: URL? { clipOpenableUrl(text: text).flatMap(URL.init(string:)) }
}

/// Clipboard both ways.
@MainActor @Observable
public final class ClipboardFeature {
    /// Clips that crossed either way in the last day, newest first (memory only).
    public internal(set) var clips: [Clip] = []
    @ObservationIgnored public var computer: Computer?

    public nonisolated init() {}

    /// Puts an earlier clip back on this Mac's clipboard, without sending it back.
    public func copyAgain(_ text: String) async {
        computer?.feature(command: .copyAgain(text: text))
    }

    public func clearClips() {
        clips = []
        computer?.feature(command: .clearClips)
    }

    public func show(_ view: ClipboardView?) {
        let records = view?.clips ?? []
        let new = records.map { Clip(text: $0.text, at: Date(timeIntervalSince1970: TimeInterval($0.atMs) / 1000), fromPhone: $0.fromPhone) }
        if new != clips { clips = new }
    }

    #if DEBUG
    public func showDemo(clips: [Clip]) { self.clips = clips }
    #endif
}

/// The phone's notifications here, and its notification settings (the phone owns them).
@MainActor @Observable
public final class NotificationsFeature {
    public internal(set) var settings: Seam_V1_NotificationSettings?
    /// The phone's notifications that are still up, newest first.
    public internal(set) var shown: [Seam_V1_Notification] = []
    /// Phone apps' icons, ready to draw.
    public private(set) var images: [String: NSImage] = [:]
    /// Counts notifications arriving from the phone, so the bell rings once for each.
    public private(set) var arrivals = 0
    @ObservationIgnored public var computer: Computer?
    @ObservationIgnored private var iconsVersion: UInt64?

    public nonisolated init() {}

    /// Asks the phone to change its settings; shown here at once.
    public func change(_ change: Seam_V1_NotificationSettingsChange) {
        guard let bytes = try? change.serializedData() else { return }
        computer?.feature(command: .changeNotificationSettings(change: bytes))
    }

    /// A button, reply or dismiss on one of the phone's notifications, here.
    public func act(_ action: Seam_V1_NotificationAction) {
        guard let bytes = try? action.serializedData() else { return }
        computer?.feature(command: .actOnNotification(action: bytes))
    }

    /// The main window's dismiss: the banner goes too.
    public func dismiss(key: String) {
        shown.removeAll { $0.key == key }
        computer?.feature(command: .dismissNotification(key: key))
    }

    public func show(_ view: NotificationsView?) {
        let new = (view?.shown ?? []).compactMap { try? Seam_V1_Notification(serializedBytes: $0) }
        if new != shown { shown = new }
        let settings = view?.settings.flatMap { try? Seam_V1_NotificationSettings(serializedBytes: $0) }
        if settings != self.settings { self.settings = settings }
        if Int(view?.arrivals ?? 0) != arrivals { arrivals = Int(view?.arrivals ?? 0) }
        if let view, view.iconsVersion != iconsVersion, let computer {
            iconsVersion = view.iconsVersion
            images = computer.icons().compactMapValues(NSImage.init(data:))
        }
    }

    #if DEBUG
    public func showDemo(settings: Seam_V1_NotificationSettings, shown: [Seam_V1_Notification]) {
        self.settings = settings
        self.shown = shown
    }
    #endif
}

/// The phone's battery and the like.
@MainActor @Observable
public final class StatusFeature {
    public internal(set) var status: Seam_V1_Status?
    /// When the phone last sent it, so a stale level can say how old it is.
    public internal(set) var statusAt: Date?

    public nonisolated init() {}

    public func show(_ view: StatusView?) {
        let status = view?.status.flatMap { try? Seam_V1_Status(serializedBytes: $0) }
        if status != self.status { self.status = status }
        let at = view?.atMs.map { Date(timeIntervalSince1970: TimeInterval($0) / 1000) }
        if at != statusAt { statusAt = at }
    }

    #if DEBUG
    public func showDemo(_ status: Seam_V1_Status, at: Date) {
        self.status = status
        statusAt = at
    }
    #endif
}
