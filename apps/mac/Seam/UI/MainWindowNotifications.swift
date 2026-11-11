// SPDX-License-Identifier: AGPL-3.0-only
import AppKit
import SeamCore
import SeamProtocol
import SeamSession
import SwiftUI

/// Board M05: the phone's notifications that are still up, grouped by app, newest first.
struct NotificationsPane: View {
    let controller: SeamController
    let openSettings: (SettingsView.Pane) -> Void
    @Environment(\.accessibilityReduceMotion) private var reduce

    var body: some View {
        Group {
            if controller.phone == nil {
                EmptyPane(symbol: "iphone", title: "No phone paired", detail: "Pair your phone to see its notifications here.",
                          action: ("Show pairing code", { PairingWindow.show(controller) }))
            } else if controller.notifications.settings?.enabled == false {
                EmptyPane(symbol: "bell.slash", title: "Notifications are off", detail: "\(phoneName) isn't sending notifications to this Mac.",
                          action: ("Turn on", { controller.notifications.setEnabled(true) }))
            } else if controller.notifications.shown.isEmpty, mode == .paused {
                EmptyPane(symbol: "bell.slash", title: "Notifications are paused", detail: Looks.notificationsTile(controller.notifications.settings, showing: 0).subtitle,
                          action: ("Resume now", { controller.notifications.pause(until: nil) }))
            } else if controller.notifications.shown.isEmpty, mode == .waiting || !controller.connected {
                EmptyPane(symbol: "bell", title: "Waiting for \(phoneName)",
                          detail: "Its notifications show here once it connects. Turn on its screen to connect now.")
            } else if controller.notifications.shown.isEmpty {
                EmptyPane(symbol: "bell", title: "No notifications",
                          detail: "New ones from \(phoneName) show here and in Notification Centre.",
                          action: ("Choose apps", { openSettings(.notifications) }))
            } else {
                // An app's list, as Mail and Reminders draw theirs; whether clearing reaches the phone is said where it happens
                // The same layout as the clipboard's page: a header, the rows, a note
                List {
                    Section("Recent notifications") {
                        ForEach(controller.notifications.shown, id: \.key) { n in
                            NotificationRow(notification: n, controller: controller).transition(.arrive(reduce))
                        }
                        Text(controller.notifications.settings?.dismissBoth == false
                            ? "Notifications you clear here stay on \(lowerName)."
                            : "Notifications you clear here are also cleared on \(lowerName).")
                            .font(.callout).foregroundStyle(.secondary)
                            .frame(maxWidth: 480, alignment: .leading)
                            .padding(.top, 6)
                            .listRowSeparator(.hidden)
                    }
                }
                .listStyle(.inset(alternatesRowBackgrounds: false))
            }
        }
        .animation(Motion.spatial(reduce), value: controller.notifications.shown.map(\.key))
    }

    private var phoneName: String {
        let name = controller.phone?.name ?? ""
        return name.isEmpty ? "Your phone" : name
    }

    private var lowerName: String { controller.phone.map { $0.name.isEmpty ? "your phone" : $0.name } ?? "your phone" }

    private var mode: NotificationsTileMode {
        Looks.notificationsTile(controller.notifications.settings, showing: 0).mode
    }
}

/// One notification, as Notification Centre draws it: the app's icon, who or what, the text, the
/// app and when; its phone buttons and Reply (unfolding a field in place, as in the popover); and
/// dismiss, which shows on hover or keyboard focus.
private struct NotificationRow: View {
    let notification: Seam_V1_Notification
    let controller: SeamController
    @State private var replying = false
    @State private var hovering = false
    /// Which of the row's controls has keyboard focus: any of them shows the row's actions.
    @FocusState private var focus: String?
    @Environment(\.accessibilityReduceMotion) private var reduce

    var body: some View {
        let n = notification
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .top, spacing: 12) {
                AppIcon(image: controller.notifications.images[n.appID], size: 32, name: n.appName)
                    .clipShape(RoundedRectangle(cornerRadius: 7, style: .continuous))
                VStack(alignment: .leading, spacing: 2) {
                    // The time on the right of the title, as in the popover and Notification Centre
                    HStack(alignment: .firstTextBaseline) {
                        Text(n.title.isEmpty ? n.appName : n.title).fontWeight(.semibold).lineLimit(1)
                            // The row's separator starts at the text, as Mail's do
                            .alignmentGuide(.listRowSeparatorLeading) { $0[.leading] }
                        Spacer(minLength: 8)
                        // Dismiss takes the time's place on hover or focus, as in Notification Centre
                        ZStack(alignment: .trailing) {
                            TimelineView(.periodic(from: .now, by: 30)) { tl in
                                Text(n.postedAt > 0 ? "\(n.appName) · \(Looks.ago(Date(ms: n.postedAt), now: tl.date, justNow: "now"))" : n.appName)
                            }
                            .font(.callout).foregroundStyle(.secondary).lineLimit(1)
                            .opacity(hovering || focus != nil ? 0 : 1)
                            Button { controller.notifications.dismiss(n) } label: {
                                Image(systemName: "xmark.circle.fill").font(.system(size: 16)).foregroundStyle(.secondary)
                                    .frame(width: 24, height: 20).contentShape(Rectangle())
                            }
                            .buttonStyle(.borderless)
                            .focused($focus, equals: "dismiss")
                            .opacity(hovering || focus != nil ? 1 : 0)
                            .help("Dismiss")
                            .accessibilityLabel("Dismiss")
                        }
                        .animation(Motion.ease(0.15), value: hovering || focus != nil)
                    }
                    if !n.text.isEmpty { Text(n.text).foregroundStyle(.secondary).lineLimit(4) }
                    if n.canReply || !n.buttons.isEmpty {
                        HStack(spacing: 6) {
                            if n.canReply { Button(replying ? "Cancel" : "Reply") { replying.toggle() }.focused($focus, equals: "reply") }
                            ForEach(n.buttons, id: \.id) { b in
                                Button(b.title) { controller.notifications.send(.with { $0.key = n.key; $0.buttonID = b.id }) }
                                    .focused($focus, equals: b.id)
                            }
                        }
                        .rowActions(shown: hovering || focus != nil || replying)
                        .padding(.top, 2)
                    }
                }
            }
            if replying {
                ReplyField(prompt: "Reply to \(n.title.isEmpty ? n.appName : n.title)", send: { text in
                    controller.notifications.send(.with { $0.key = n.key; $0.replyText = text })
                }, close: { replying = false }, sent: { replying = false })
                .padding(.leading, 44)
                .transition(.unfold(reduce))
            }
        }
        .padding(.vertical, 3)
        .contentShape(Rectangle())
        .onHover { hovering = $0 }
        .contextMenu {
            if n.canReply { Button("Reply") { replying = true } }
            Button("Dismiss") { controller.notifications.dismiss(n) }
        }
        .animation(Motion.spatial(reduce), value: replying)
    }
}
