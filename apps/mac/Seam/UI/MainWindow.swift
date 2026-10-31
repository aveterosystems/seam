// SPDX-License-Identifier: AGPL-3.0-only
import AppKit
import SeamCore
import SeamProtocol
import SwiftUI

/// The main window (boards M05 and M06, with what's built): the phone's notifications and the
/// clipboard history. Messages and Files join the sidebar when they exist. Opens from the popover
/// or with ⌘O; Seam shows in the Dock only while it's open.
struct MainWindow: View {
    let controller: SeamController
    @State private var pane: Pane = .notifications

    enum Pane: Hashable { case notifications, clipboard }

    var body: some View {
        SidebarWindow(title: pane == .notifications ? "Notifications" : "Clipboard") {
            SidebarItem(title: "Notifications", symbol: "bell.badge", selected: pane == .notifications, count: controller.phoneNotifications.count) {
                pane = .notifications
            }
            SidebarItem(title: "Clipboard", symbol: "doc.on.clipboard", selected: pane == .clipboard) { pane = .clipboard }
            Spacer()
            if let phone = controller.phone { phoneCard(phone) }
        } toolbar: {
            switch pane {
            case .notifications:
                if !controller.phoneNotifications.isEmpty {
                    Button("Clear All") { controller.phoneNotifications.forEach(controller.dismiss) }
                        .buttonStyle(.bordered).buttonBorderShape(.capsule)
                }
            case .clipboard:
                if !controller.clips.isEmpty {
                    Button("Clear History") { controller.clearClips() }.buttonStyle(.bordered).buttonBorderShape(.capsule)
                }
            }
        } content: {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    switch pane {
                    case .notifications: NotificationsPane(controller: controller)
                    case .clipboard: ClipboardPane(controller: controller)
                    }
                }
                // Board M05: a readable column, not the whole width of a big window
                .frame(maxWidth: 760, alignment: .leading)
                .padding(.horizontal, 24).padding(.bottom, 24)
            }
        }
        .frame(minWidth: 760, minHeight: 520)
        // A window needs a Dock icon and ⌘-Tab, or it's lost behind other apps; back to menu bar only when it closes
        .onAppear {
            NSApp.setActivationPolicy(.regular)
            NSApp.activate()
            openRequestedPane()
        }
        .onChange(of: controller.requestedMainPane) { openRequestedPane() }
        .onDisappear { NSApp.setActivationPolicy(.accessory) }
    }

    private func openRequestedPane() {
        guard let p = controller.requestedMainPane else { return }
        pane = p
        controller.requestedMainPane = nil
    }

    /// Board M04's device card, at the bottom of the sidebar: the phone, its battery, and the stitch.
    private func phoneCard(_ phone: PeerInfo) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                Image(systemName: "iphone").foregroundStyle(SeamStyle.teal)
                Text(phone.name.isEmpty ? "Your phone" : phone.name).font(.system(size: 12, weight: .semibold)).lineLimit(1)
                Spacer(minLength: 0)
                if let s = controller.status { BatteryPill(status: s) }
            }
            Stitch(line: controller.link.line)
            LinkStatus(look: controller.link)
                .font(.system(size: 11)).foregroundStyle(.secondary)
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 10).fill(Color.primary.opacity(0.045)))
    }
}

/// Board M05: the phone's notifications that are still up, grouped by app, newest first.
private struct NotificationsPane: View {
    let controller: SeamController

    var body: some View {
        if controller.phone == nil {
            SettingsGroup {
                SettingsRow("No phone is paired with this Mac", "Pair your phone to see its notifications here") {
                    Button("Pair…") { PairingWindow.show(controller) }.buttonStyle(.bordered).buttonBorderShape(.capsule)
                }
            }
        } else if controller.notificationSettings?.enabled == false {
            SettingsGroup {
                SettingsRow("Notifications are off", "Your phone isn't sending notifications to this Mac") {
                    Button("Turn On") { controller.setNotifications(enabled: true) }.buttonStyle(.bordered).buttonBorderShape(.capsule)
                }
            }
        } else if controller.phoneNotifications.isEmpty {
            SettingsGroup {
                SettingsRow("No notifications from \(phoneName)", "New ones from the apps you chose show here and in Notification Centre. Choose apps in Settings › Notifications.") { EmptyView() }
            }
        } else {
            // Board M05: one card per app, its icon and name at the top
            ForEach(groups, id: \.appID) { group in
                SettingsGroup {
                    HStack(spacing: 8) {
                        AppIcon(image: controller.appIcons[group.appID], size: 20)
                        Text(group.items[0].appName).font(.system(size: 12, weight: .semibold))
                        Spacer()
                        if group.items.count > 1 {
                            Text("\(group.items.count)").font(.system(size: 11)).monospacedDigit().foregroundStyle(.tertiary)
                        }
                    }
                    .padding(EdgeInsets(top: 11, leading: 14, bottom: 3, trailing: 14))
                    ForEach(group.items, id: \.key) { NotificationRow(notification: $0, controller: controller) }
                }
            }
            Text(controller.notificationSettings?.dismissBoth == false
                ? "Notifications you clear here stay on your phone."
                : "Notifications you clear here are also cleared on your phone.")
                .font(.system(size: 11)).foregroundStyle(.tertiary)
                .frame(maxWidth: .infinity)
        }
    }

    private var phoneName: String {
        let name = controller.phone?.name ?? ""
        return name.isEmpty ? "your phone" : name
    }

    /// Apps in the order of their newest notification.
    private var groups: [(appID: String, items: [Seam_V1_Notification])] {
        var order: [String] = []
        var byApp: [String: [Seam_V1_Notification]] = [:]
        for n in controller.phoneNotifications {
            if byApp[n.appID] == nil { order.append(n.appID) }
            byApp[n.appID, default: []].append(n)
        }
        return order.map { ($0, byApp[$0]!) }
    }
}

/// One notification: title, text and time, dismiss, and its reply field and buttons when it has them.
private struct NotificationRow: View {
    let notification: Seam_V1_Notification
    let controller: SeamController
    @State private var reply = ""

    var body: some View {
        let n = notification
        SettingsRow(n.title.isEmpty ? n.appName : n.title, n.text.isEmpty ? nil : n.text) {
            HStack(spacing: 8) {
                if n.postedAt > 0 {
                    Text(Date(timeIntervalSince1970: TimeInterval(n.postedAt) / 1000), style: .time)
                        .font(.system(size: 11)).foregroundStyle(.tertiary)
                }
                Button { controller.dismiss(n) } label: {
                    Image(systemName: "xmark").font(.system(size: 10, weight: .semibold)).foregroundStyle(.secondary).frame(width: 14, height: 14)
                }
                .buttonStyle(.bordered).buttonBorderShape(.circle).tint(.secondary)
                .help("Dismiss")
                .accessibilityLabel("Dismiss")
            }
        }
        if n.canReply || !n.buttons.isEmpty {
            // No hairline above: it belongs to the row above
            HStack(spacing: 8) {
                if n.canReply {
                    TextField("Reply", text: $reply)
                        .textFieldStyle(.roundedBorder)
                        .onSubmit(send)
                    Button("Send", action: send).buttonStyle(.bordered).buttonBorderShape(.capsule)
                        .disabled(reply.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
                ForEach(n.buttons, id: \.id) { b in
                    Button(b.title) { controller.act(.with { $0.key = n.key; $0.buttonID = b.id }) }
                        .buttonStyle(.bordered).buttonBorderShape(.capsule)
                }
                if !n.canReply { Spacer(minLength: 0) }
            }
            .padding(EdgeInsets(top: 0, leading: 14, bottom: 10, trailing: 14))
        }
    }

    private func send() {
        let text = reply.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        controller.act(.with { $0.key = notification.key; $0.replyText = text })
        reply = ""
    }
}

/// Board M06's clipboard column: what crossed either way in the last day.
private struct ClipboardPane: View {
    let controller: SeamController

    var body: some View {
        SettingsGroup {
            SettingsRow("Sync the clipboard with your phone", controller.clipboardSync
                ? "What you copy on this Mac is on your phone automatically, and the other way round"
                : "Off: nothing you copy goes to your phone, or comes from it") {
                Toggle("", isOn: Binding(get: { controller.clipboardSync }, set: { controller.setClipboardSync($0) })).labelsHidden()
            }
        }
        if controller.phone == nil {
            SettingsGroup { SettingsRow("No phone is paired with this Mac", "Pair your phone to share the clipboard") { EmptyView() } }
        } else if controller.clips.isEmpty {
            SettingsGroup { SettingsRow("Nothing copied yet", "Copies to and from your phone show here for a day") { EmptyView() } }
        } else {
            SettingsGroup {
                ForEach(controller.clips) { clip in
                    SettingsRow(Self.preview(clip.text), nil, leading: {
                        Image(systemName: clip.fromPhone ? "iphone" : "laptopcomputer")
                            .foregroundStyle(.secondary).frame(width: 18)
                            .accessibilityLabel(clip.fromPhone ? "From your phone" : "From this Mac")
                    }) {
                        HStack(spacing: 8) {
                            Text(clip.at, style: .relative).font(.system(size: 11)).foregroundStyle(.tertiary)
                            if let url = clip.openableURL {
                                Button("Open") { NSWorkspace.shared.open(url) }.buttonStyle(.bordered).buttonBorderShape(.capsule)
                            }
                            Button("Copy") { controller.copyAgain(clip.text) }.buttonStyle(.bordered).buttonBorderShape(.capsule)
                        }
                    }
                }
            }
        }
        Text("Kept in memory for a day, and gone when Seam quits. Passwords and anything apps mark as private are never synced.")
            .font(.system(size: 11)).foregroundStyle(.tertiary)
            .frame(maxWidth: .infinity)
    }

    /// A clip can be 200 KB: the row shows enough to recognise it, on one or two lines.
    private static func preview(_ text: String) -> String {
        let flat = text.prefix(200).split(whereSeparator: \.isNewline).joined(separator: " ")
        return text.count > 200 ? flat + "…" : flat
    }
}
