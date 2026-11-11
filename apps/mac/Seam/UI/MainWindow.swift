// SPDX-License-Identifier: AGPL-3.0-only
import AppKit
import SeamCore
import SeamProtocol
import SeamSession
import SwiftUI

/// The main window (boards M05 and M06, with what's built): the phone's notifications and the
/// clipboard history. Messages and Files join the sidebar when they exist. Opens from the popover
/// or with ⌘O; Seam shows in the Dock only while it's open.
struct MainWindow: View {
    let controller: SeamController
    @Environment(\.openWindow) private var openWindow
    @State private var pane: Pane = .notifications
    @State private var cardHover = false

    enum Pane: Hashable { case notifications, clipboard }

    var body: some View {
        SidebarWindow(title: pane == .notifications ? "Notifications" : "Clipboard") {
            // The same icon squares as Settings' entries for these features
            SidebarItem(title: "Notifications", selected: pane == .notifications, count: controller.notifications.shown.count) {
                TintedIcon(symbol: SettingsView.Pane.notifications.symbol, color: SettingsView.Pane.notifications.color, size: 20)
            } action: { pane = .notifications }
            SidebarItem(title: "Clipboard", selected: pane == .clipboard) {
                TintedIcon(symbol: SettingsView.Pane.clipboard.symbol, color: SettingsView.Pane.clipboard.color, size: 20)
            } action: { pane = .clipboard }
            Spacer()
            if let phone = controller.phone { phoneCard(phone) }
        } toolbar: {
            switch pane {
            case .notifications:
                if !controller.notifications.shown.isEmpty {
                    Button("Clear all") { controller.notifications.shown.forEach { controller.notifications.dismiss($0) } }
                        .buttonStyle(CapButtonStyle())
                }
            case .clipboard:
                if !controller.clipboard.clips.isEmpty {
                    Button("Clear history") { controller.clipboard.clearClips() }.buttonStyle(CapButtonStyle())
                }
            }
        } content: {
            switch pane {
            case .notifications: NotificationsPane(controller: controller, openSettings: openSettings)
            case .clipboard: ClipboardPane(controller: controller)
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

    private func openSettings(_ p: SettingsView.Pane) {
        controller.requestedSettingsPane = p
        openWindow(id: "settings")
    }

    /// Board M04's device card, at the bottom of the sidebar: the phone, its battery, and the stitch.
    /// Its name opens the phone's settings.
    private func phoneCard(_ phone: Phone) -> some View {
        let name = phone.name.isEmpty ? "Your phone" : phone.name
        return VStack(alignment: .leading, spacing: 8) {
            Button { openSettings(.phone) } label: {
                HStack(spacing: 8) {
                    Image(systemName: "iphone").foregroundStyle(SeamStyle.teal)
                    Text(name).font(.system(size: 12, weight: .semibold)).lineLimit(1).layoutPriority(-1)
                    Image(systemName: "chevron.right").font(.system(size: 9, weight: .semibold)).foregroundStyle(SeamStyle.Win.fg3)
                        .opacity(cardHover ? 1 : 0)
                    Spacer(minLength: 0)
                    if let s = controller.status.status.flatMap({ Looks.phone($0, link: controller.link, at: controller.status.statusAt) }) { BatteryPill(look: s).fixedSize() }
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(PressableStyle(scale: 0.98))
            .onHover { h in withAnimation(Motion.ease(0.15)) { cardHover = h } }
            .help("\(name) settings")
            .accessibilityHint("Opens \(name)'s settings")
            Stitch(line: controller.link.line)
            LinkStatus(look: controller.link)
                .font(.system(size: 11)).foregroundStyle(SeamStyle.Win.fg2)
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 10).fill(cardHover ? SeamStyle.Win.fill2 : SeamStyle.Win.group))
    }
}

/// A pane's empty state, centred in the space it has: what's there (nothing, and why) and the one
/// thing to do about it.
private struct EmptyPane: View {
    let symbol: String
    let title: String
    let detail: String
    var action: (title: String, run: () -> Void)?

    var body: some View {
        ContentUnavailableView {
            Label(title, systemImage: symbol)
        } description: {
            Text(detail)
        } actions: {
            if let action { Button(action.title, action: action.run).buttonStyle(CapButtonStyle(kind: .primary)) }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .transition(.opacity)
    }
}

/// The scrolling column the lists sit in: under the title bar, 24 pt in.
private struct ListScroll<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) { content }
                .padding(.horizontal, 24).padding(.bottom, 24)
        }
    }
}

/// Board M05: the phone's notifications that are still up, grouped by app, newest first.
private struct NotificationsPane: View {
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
            } else if controller.notifications.shown.isEmpty {
                EmptyPane(symbol: "bell", title: "No notifications from \(phoneName)",
                          detail: "New ones from the apps you chose show here and in Notification Centre.",
                          action: ("Choose apps", { openSettings(.notifications) }))
            } else {
                ListScroll {
                    // Board M05: one card per app, its icon and name at the top
                    ForEach(groups, id: \.appID) { group in
                        SettingsGroup {
                            HStack(spacing: 8) {
                                AppIcon(image: controller.notifications.images[group.appID], size: 20, name: group.items[0].appName)
                                    .clipShape(RoundedRectangle(cornerRadius: 5))
                                Text(group.items[0].appName).font(.system(size: 12, weight: .semibold))
                                Spacer()
                                if group.items.count > 1 {
                                    Text("\(group.items.count)").font(.system(size: 11)).monospacedDigit().foregroundStyle(SeamStyle.Win.fg3)
                                }
                            }
                            .padding(EdgeInsets(top: 11, leading: 14, bottom: 3, trailing: 14))
                            ForEach(group.items, id: \.key) { n in
                                NotificationRow(notification: n, controller: controller).transition(.arrive(reduce))
                            }
                        }
                        .transition(.arrive(reduce))
                    }
                    Text(controller.notifications.settings?.dismissBoth == false
                        ? "Notifications you clear here stay on your phone."
                        : "Notifications you clear here are also cleared on your phone.")
                        .font(.system(size: 11)).foregroundStyle(SeamStyle.Win.fg3)
                        .frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 14)
                }
            }
        }
        .animation(Motion.spatial(reduce), value: controller.notifications.shown.map(\.key))
    }

    private var phoneName: String {
        let name = controller.phone?.name ?? ""
        return name.isEmpty ? "Your phone" : name
    }

    /// Apps in the order of their newest notification.
    private var groups: [(appID: String, items: [Seam_V1_Notification])] {
        var order: [String] = []
        var byApp: [String: [Seam_V1_Notification]] = [:]
        for n in controller.notifications.shown {
            if byApp[n.appID] == nil { order.append(n.appID) }
            byApp[n.appID, default: []].append(n)
        }
        return order.map { ($0, byApp[$0]!) }
    }
}

/// One notification: title, text and time, its phone buttons, Reply (unfolding a field in place,
/// as in the popover) and dismiss.
private struct NotificationRow: View {
    let notification: Seam_V1_Notification
    let controller: SeamController
    @State private var replying = false
    @Environment(\.accessibilityReduceMotion) private var reduce

    var body: some View {
        let n = notification
        VStack(spacing: 0) {
            SettingsRow(n.title.isEmpty ? n.appName : n.title, n.text.isEmpty ? nil : n.text) {
                HStack(spacing: 8) {
                    if n.postedAt > 0 {
                        Text(Date(ms: n.postedAt), style: .time)
                            .font(.system(size: 11)).foregroundStyle(SeamStyle.Win.fg3)
                    }
                    ForEach(n.buttons, id: \.id) { b in
                        Button(b.title) { controller.notifications.send(.with { $0.key = n.key; $0.buttonID = b.id }) }
                            .buttonStyle(CapButtonStyle())
                    }
                    if n.canReply {
                        Button { replying.toggle() } label: { SwapText(replying ? "Cancel" : "Reply", pane: true) }
                            .buttonStyle(CapButtonStyle())
                    }
                    Button { controller.notifications.dismiss(n) } label: {
                        Image(systemName: "xmark").font(.system(size: 10, weight: .semibold)).frame(width: 22, height: 22)
                            .background(Circle().fill(SeamStyle.Win.fill2))
                            .foregroundStyle(SeamStyle.Win.fg2)
                    }
                    .buttonStyle(PressableStyle(scale: 0.9))
                    .help("Dismiss")
                    .accessibilityLabel("Dismiss")
                }
            }
            if replying {
                ReplyField(prompt: "Reply to \(n.title.isEmpty ? n.appName : n.title)", send: { text in
                    controller.notifications.send(.with { $0.key = n.key; $0.replyText = text })
                }, close: { replying = false }, sent: { replying = false })
                .padding(EdgeInsets(top: 0, leading: 14, bottom: 10, trailing: 14))
                .transition(.unfold(reduce))
            }
        }
        .clipped()
        .animation(Motion.spatial(reduce), value: replying)
    }
}

/// Board M06's clipboard column: what crossed either way in the last day.
private struct ClipboardPane: View {
    let controller: SeamController
    @Environment(\.accessibilityReduceMotion) private var reduce

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            SettingsGroup {
                SettingsRow("Sync the clipboard with \(phoneName)", controller.clipboardSync
                    ? "What you copy on this Mac is on your phone automatically, and the other way round"
                    : "Off: nothing you copy goes to your phone, or comes from it") {
                    Toggle("Sync the clipboard", isOn: Binding(get: { controller.clipboardSync }, set: { controller.setClipboardSync($0) })).labelsHidden()
                }
            }
            .padding(.horizontal, 24)
            if controller.phone == nil {
                EmptyPane(symbol: "iphone", title: "No phone paired", detail: "Pair your phone to share the clipboard.",
                          action: ("Show pairing code", { PairingWindow.show(controller) }))
            } else if controller.clipboard.clips.isEmpty {
                EmptyPane(symbol: "doc.on.clipboard", title: "Nothing copied yet",
                          detail: controller.clipboardSync
                              ? "Copies to and from \(phoneName) show here for a day."
                              : "Turn on sync to share copies with \(phoneName).")
            } else {
                ListScroll {
                    SettingsGroup {
                        ForEach(controller.clipboard.clips) { clip in
                            ClipRow(clip: clip, controller: controller).transition(.arrive(reduce))
                        }
                    }
                    Text("Kept in memory for a day, and gone when Seam quits. Passwords and anything apps mark as private are never synced.")
                        .font(.system(size: 11)).foregroundStyle(SeamStyle.Win.fg3)
                        .frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 14)
                }
            }
        }
        .animation(Motion.spatial(reduce), value: controller.clipboard.clips.map(\.id))
    }

    private var phoneName: String {
        let name = controller.phone?.name ?? ""
        return name.isEmpty ? "your phone" : name
    }
}

/// One copy: where it came from, enough of it to recognise, when, and Open or Copy. Copy says
/// "✓ Copied" in place for 1.3 s (catalogue 8).
private struct ClipRow: View {
    let clip: Clip
    let controller: SeamController
    @State private var copied = false

    var body: some View {
        SettingsRow(preview, nil, leading: {
            Image(systemName: clip.fromPhone ? "iphone" : "laptopcomputer")
                .foregroundStyle(SeamStyle.Win.fg2).frame(width: 18)
                .help(clip.fromPhone ? "From your phone" : "From this Mac")
                .accessibilityLabel(clip.fromPhone ? "From your phone" : "From this Mac")
        }) {
            HStack(spacing: 8) {
                Text(clip.at, format: .relative(presentation: .named, unitsStyle: .abbreviated)).font(.system(size: 11)).foregroundStyle(SeamStyle.Win.fg3)
                if let url = clip.openableURL {
                    Button("Open") { NSWorkspace.shared.open(url) }.buttonStyle(CapButtonStyle())
                }
                Button {
                    Task { await controller.clipboard.copyAgain(clip.text) }
                    copied = true
                    DispatchQueue.main.asyncAfter(deadline: .now() + 1.3) { copied = false }
                } label: {
                    SwapText(copied ? "✓ Copied" : "Copy", pane: true)
                        .foregroundStyle(copied ? SeamStyle.ok : SeamStyle.Win.fg)
                        .frame(minWidth: 58)
                }
                .buttonStyle(CapButtonStyle())
                .accessibilityValue(copied ? "Copied" : "")
            }
        }
    }

    /// A clip can be 200 KB: the row shows enough to recognise it, on one line.
    private var preview: String {
        let flat = clip.text.prefix(200).split(whereSeparator: \.isNewline).joined(separator: " ")
        return clip.text.count > 200 ? flat + "…" : flat
    }
}
