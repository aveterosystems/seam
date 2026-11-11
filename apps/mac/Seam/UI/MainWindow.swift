// SPDX-License-Identifier: AGPL-3.0-only
import AppKit
import SeamCore
import SeamProtocol
import SeamSession
import SwiftUI

/// The main window (boards M05 and M06, with what's built): the phone's notifications and the
/// clipboard history, in a standard sidebar window. Messages and Files join the sidebar when they
/// exist. Opens from the popover or with ⌘O; Seam shows in the Dock only while it's open.
struct MainWindow: View {
    let controller: SeamController
    @Environment(\.openWindow) private var openWindow
    @Environment(\.openSettings) private var openSettingsWindow
    @State private var pane: Pane? = .notifications
    @State private var confirmClear = false

    enum Pane: Hashable { case notifications, clipboard }

    var body: some View {
        NavigationSplitView {
            List(selection: $pane) {
                // Plain symbols in the accent, as Mail and Notes draw their sidebars
                Label("Notifications", systemImage: "bell")
                    .badge(controller.notifications.shown.count)
                    .tag(Pane.notifications)
                Label("Clipboard", systemImage: "doc.on.clipboard")
                    .tag(Pane.clipboard)
            }
            .safeAreaInset(edge: .bottom) {
                if let phone = controller.phone { PhoneCard(controller: controller, phone: phone) { openSettings(.phone) }.padding(10) }
            }
            .navigationSplitViewColumnWidth(min: 190, ideal: 200, max: 260)
        } detail: {
            Group {
                switch pane ?? .notifications {
                case .notifications: NotificationsPane(controller: controller, openSettings: openSettings)
                case .clipboard: ClipboardPane(controller: controller)
                }
            }
            .navigationTitle(pane == .clipboard ? "Clipboard" : "Notifications")
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    if pane == .clipboard {
                        Button("Clear history") { controller.clipboard.clearClips() }
                            .disabled(controller.clipboard.clips.isEmpty)
                            .help("Clears the list here. Copies waiting to send still send.")
                    } else {
                        // Clearing here can clear the phone too, so it asks first
                        Button("Clear all") { confirmClear = true }
                            .disabled(controller.notifications.shown.isEmpty)
                            .confirmationDialog(clearTitle, isPresented: $confirmClear) {
                                Button("Clear all", role: .destructive) { controller.notifications.shown.forEach { controller.notifications.dismiss($0) } }
                                Button("Cancel", role: .cancel) {}
                            }
                    }
                }
                ToolbarItem(placement: .primaryAction) {
                    Button { openSettings(pane == .clipboard ? .clipboard : .notifications) } label: {
                        Label("Settings", systemImage: "gearshape")
                    }
                    .help("Settings for \(pane == .clipboard ? "the clipboard" : "notifications")")
                }
            }
        }
        .frame(minWidth: 720, minHeight: 480)
        // A window needs a Dock icon and ⌘-Tab, or it's lost behind other apps; back to menu bar only when it closes
        .onAppear {
            NSApp.setActivationPolicy(.regular)
            NSApp.activate()
            openRequestedPane()
        }
        .onChange(of: controller.requestedMainPane) { openRequestedPane() }
        .onDisappear { NSApp.setActivationPolicy(.accessory) }
    }

    private var clearTitle: String {
        let count = controller.notifications.shown.count
        let what = count == 1 ? "1 notification" : "\(count) notifications"
        let name = controller.phone.map { $0.name.isEmpty ? "your phone" : $0.name } ?? "your phone"
        return controller.notifications.settings?.dismissBoth == false ? "Clear \(what) on this Mac?" : "Clear \(what) on this Mac and \(name)?"
    }

    private func openRequestedPane() {
        guard let p = controller.requestedMainPane else { return }
        pane = p
        controller.requestedMainPane = nil
    }

    private func openSettings(_ p: SettingsView.Pane) {
        controller.requestedSettingsPane = p
        openSettingsWindow()
    }
}

/// Board M04's device card, at the bottom of the sidebar: the phone, its battery, and the stitch.
/// Clicking it opens the phone's settings.
private struct PhoneCard: View {
    let controller: SeamController
    let phone: Phone
    let open: () -> Void
    @State private var hovering = false

    var body: some View {
        let name = phone.name.isEmpty ? "Your phone" : phone.name
        Button(action: open) {
            VStack(alignment: .leading, spacing: 8) {
                HStack(spacing: 8) {
                    PhoneDisc(connected: controller.link.connected, size: 22)
                    Text(name).font(.system(size: 12, weight: .semibold)).lineLimit(1).layoutPriority(-1)
                    Spacer(minLength: 0)
                    if let s = controller.status.status.flatMap({ Looks.phone($0, link: controller.link, at: controller.status.statusAt) }) {
                        BatteryPill(look: s).fixedSize()
                    }
                }
                // The popover's own status line, word for word, then the stitch under it
                TimelineView(.periodic(from: .now, by: 30)) { tl in
                    HStack(spacing: 8) {
                        StatusDot(on: controller.link.connected)
                        Text(controller.link(at: tl.date).statusLine).lineLimit(1)
                        if controller.link.connected, let symbol = controller.link.icon?.symbol { Image(systemName: symbol) }
                    }
                }
                .font(.system(size: 11)).foregroundStyle(.secondary)
                Stitch(line: controller.link.line)
            }
            .padding(12)
            .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(.quaternary.opacity(hovering ? 1 : 0.6)))
            .contentShape(Rectangle())
        }
        .buttonStyle(PressableStyle(scale: 0.98))
        .onHover { h in withAnimation(Motion.ease(0.15)) { hovering = h } }
        .help("\(name) settings")
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(name), \(controller.link.sentence)")
        .accessibilityHint("Opens \(name)'s settings")
        .accessibilityAddTraits(.isButton)
    }
}

/// A pane's empty state, centred in the space it has, with the one thing to do about it.
struct EmptyPane: View {
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
            if let action { Button(action.title, action: action.run).buttonStyle(.borderedProminent) }
        }
    }
}

extension View {
    /// A list row's actions, as Mail and Notification Centre show them: borderless accent words,
    /// faded in on hover or keyboard focus (catalogue 12), and always there for VoiceOver.
    func rowActions(shown: Bool) -> some View {
        buttonStyle(.borderless)
            .foregroundStyle(SeamStyle.tealInk)
            .fontWeight(.medium)
            .opacity(shown ? 1 : 0)
            .animation(Motion.ease(0.2), value: shown)
    }
}
