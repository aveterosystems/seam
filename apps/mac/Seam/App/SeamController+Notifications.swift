// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import SeamProtocol
import SeamSession

/// The phone's notification settings and actions. The phone owns the settings; the Mac asks it to change them.
extension SeamController {
    /// A button or reply on one of the phone's notifications, in the main window.
    func act(_ action: Seam_V1_NotificationAction) {
        Task { await session?.notificationAction(action) }
    }

    /// The main window's dismiss: the banner goes too.
    func dismiss(_ n: Seam_V1_Notification) {
        Task { await session?.dismissNotification(key: n.key) }
    }

    func setNotifications(enabled: Bool) {
        changeNotificationSettings(.with { $0.enabled = enabled })
    }

    func setNotifications(quietWhileUsingPhone on: Bool) {
        changeNotificationSettings(.with { $0.quietWhileUsingPhone = on })
    }

    func setNotifications(dismissBoth on: Bool) {
        changeNotificationSettings(.with { $0.dismissBoth = on })
    }

    /// Until a time, or nil to resume. The phone holds it, so a paused phone sends nothing.
    func pauseNotifications(until: Date?) {
        let ms = until.map { Int64($0.timeIntervalSince1970 * 1000) } ?? 0
        changeNotificationSettings(.with { $0.pausedUntil = ms })
    }

    func setNotificationMode(_ mode: Seam_V1_NotificationMode, for appIDs: Set<String>) {
        guard !appIDs.isEmpty else { return }
        changeNotificationSettings(.with { $0.apps = appIDs.sorted().map { id in .with { $0.appID = id; $0.mode = mode } } })
    }

    private func changeNotificationSettings(_ change: Seam_V1_NotificationSettingsChange) {
        Task { await session?.changeNotificationSettings(change) }
    }
}
