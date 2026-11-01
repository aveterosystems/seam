// SPDX-License-Identifier: AGPL-3.0-only
#if DEBUG
import Foundation
import SeamProtocol

// SEAM_DEMO (the app's Demo.swift): made-up phone state for looking at every screen without a
// phone. Debug builds only; the session never starts, so nothing is saved or sent.

extension StatusFeature {
    public func showDemo(_ status: Seam_V1_Status, at: Date) {
        self.status = status
        statusAt = at
    }
}

extension NotificationsFeature {
    public func showDemo(settings: Seam_V1_NotificationSettings, shown: [Seam_V1_Notification]) {
        self.settings = settings
        self.shown = shown
    }
}

extension ClipboardFeature {
    public func showDemo(clips: [Clip]) { self.clips = clips }
}
#endif
