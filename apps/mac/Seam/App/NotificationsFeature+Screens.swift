// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import SeamProtocol
import SeamSession

/// The phone's notification settings, as the screens change them. The phone owns them; the Mac
/// asks it to change them (`NotificationsFeature.change`).
extension NotificationsFeature {
    func setEnabled(_ enabled: Bool) {
        change(.with { $0.enabled = enabled })
    }

    func setQuietWhileUsingPhone(_ on: Bool) {
        change(.with { $0.quietWhileUsingPhone = on })
    }

    func setDismissBoth(_ on: Bool) {
        change(.with { $0.dismissBoth = on })
    }

    /// Until a time, or nil to resume. The phone holds it, so a paused phone sends nothing.
    func pause(until: Date?) {
        let ms = until.map { Int64($0.timeIntervalSince1970 * 1000) } ?? 0
        change(.with { $0.pausedUntil = ms })
    }

    func setMode(_ mode: Seam_V1_NotificationMode, for appIDs: Set<String>) {
        guard !appIDs.isEmpty else { return }
        change(.with { $0.apps = appIDs.sorted().map { id in .with { $0.appID = id; $0.mode = mode } } })
    }

    /// A button or reply on one of the phone's notifications, here.
    func send(_ action: Seam_V1_NotificationAction) {
        act(action)
    }

    /// The main window's dismiss: the banner goes too.
    func dismiss(_ n: Seam_V1_Notification) {
        dismiss(key: n.key)
    }
}
