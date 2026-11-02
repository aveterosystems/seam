// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import SeamCore
import SeamProtocol

/// The phone's notifications as banners, as the core's notification centre.
final class BannerCentre: NotificationCentre, @unchecked Sendable {
    let banners: PhoneNotifications

    init(_ banners: PhoneNotifications) {
        self.banners = banners
    }

    func show(notification: Data, icon: Data?) async {
        guard let n = try? Seam_V1_Notification(serializedBytes: notification) else { return }
        await MainActor.run { banners.show(n, icon: icon) }
    }

    func dismiss(key: String) async {
        await MainActor.run { banners.dismiss(key: key) }
    }

    func removeAll() async {
        await MainActor.run { banners.removeAll() }
    }
}
