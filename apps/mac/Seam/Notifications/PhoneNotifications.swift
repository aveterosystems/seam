// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import SeamProtocol
import UserNotifications

/// The phone's notifications as macOS banners (board M07). Reply, buttons and dismissing here
/// go back to the phone; a dismiss from the phone removes the banner here.
@MainActor
final class PhoneNotifications: NSObject, UNUserNotificationCenterDelegate {
    private let center = UNUserNotificationCenter.current()
    private var categories: Set<UNNotificationCategory> = []
    private var asked = false
    private var onAction: (Seam_V1_NotificationAction) -> Void = { _ in }

    /// `onAction` gets what the user did to a banner, to send to the phone.
    func start(onAction: @escaping (Seam_V1_NotificationAction) -> Void) {
        self.onAction = onAction
        center.delegate = self
    }

    /// `icon`: the phone app's icon, shown as the banner's picture (macOS keeps Seam's own icon on the left).
    func show(_ n: Seam_V1_Notification, icon: Data?) {
        if !asked {
            asked = true // asked when the first one arrives, not at launch: that's when it makes sense
            center.requestAuthorization(options: [.alert, .sound]) { _, _ in }
        }
        let content = UNMutableNotificationContent()
        content.title = n.title
        content.subtitle = n.appName
        content.body = n.text
        content.sound = .default
        content.threadIdentifier = n.conversationID.isEmpty ? n.appID : n.conversationID
        content.categoryIdentifier = category(for: n)
        if let icon, let attachment = Self.attachment(icon) { content.attachments = [attachment] }
        center.add(UNNotificationRequest(identifier: Self.prefix + n.key, content: content, trigger: nil))
    }

    func dismiss(key: String) {
        center.removeDeliveredNotifications(withIdentifiers: [Self.prefix + key])
    }

    /// The phone was forgotten: none of its notifications stay on this Mac.
    func removeAll() {
        center.getDeliveredNotifications { delivered in
            let ids = delivered.map(\.request.identifier).filter { $0.hasPrefix(Self.prefix) }
            UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: ids)
        }
    }

    /// macOS moves the file into its own store, so each banner gets a fresh copy.
    private static func attachment(_ png: Data) -> UNNotificationAttachment? {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".png")
        guard (try? png.write(to: url)) != nil else { return nil }
        return try? UNNotificationAttachment(identifier: "icon", url: url, options: nil)
    }

    /// One category per distinct set of actions; macOS needs them registered before a banner can show them.
    private func category(for n: Seam_V1_Notification) -> String {
        var actions: [UNNotificationAction] = []
        if n.canReply {
            actions.append(UNTextInputNotificationAction(identifier: Self.reply, title: "Reply", options: [], textInputButtonTitle: "Send", textInputPlaceholder: "Message"))
        }
        actions += n.buttons.map { UNNotificationAction(identifier: Self.button + $0.id, title: $0.title, options: []) }
        let id = "phone" + (n.canReply ? ".r" : "") + n.buttons.map { ".\($0.id)=\($0.title)" }.joined()
        if !categories.contains(where: { $0.identifier == id }) {
            // Titles that change every time ("Copy 482913") would add one each: start over past the cap (SR-19-04)
            if categories.count >= Self.maxCategories { categories.removeAll() }
            // .customDismissAction: clearing a banner tells us, so the phone clears it too
            categories.insert(UNNotificationCategory(identifier: id, actions: actions, intentIdentifiers: [], options: [.customDismissAction]))
            center.setNotificationCategories(categories)
        }
        return id
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        let id = response.notification.request.identifier
        guard id.hasPrefix(Self.prefix) else { return }
        var a = Seam_V1_NotificationAction()
        a.key = String(id.dropFirst(Self.prefix.count))
        switch response.actionIdentifier {
        case UNNotificationDismissActionIdentifier:
            a.dismissed = true
        case Self.reply:
            guard let text = (response as? UNTextInputNotificationResponse)?.userText, !text.isEmpty else { return }
            a.replyText = text
        case let action where action.hasPrefix(Self.button):
            a.buttonID = String(action.dropFirst(Self.button.count))
        default:
            return // clicking the banner itself does nothing yet
        }
        await act(a)
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        [.banner, .list, .sound]
    }

    private func act(_ a: Seam_V1_NotificationAction) {
        onAction(a)
    }

    private static let maxCategories = 64
    private nonisolated static let prefix = "phone:"
    private nonisolated static let reply = "reply"
    private nonisolated static let button = "button:"
}
