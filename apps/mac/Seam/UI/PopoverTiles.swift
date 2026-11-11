// SPDX-License-Identifier: AGPL-3.0-only
import SeamCore
import SeamSession
import SwiftUI

/// The popover's two tiles: Clipboard and Notifications, each with its state in words. The
/// Notifications row's ⋯ menu holds what used to be on right-click: pause, resume, off, settings.
struct PopoverTiles: View {
    let controller: SeamController
    let openSettings: (SettingsView.Pane?) -> Void
    @State private var clipBounce = 0
    @State private var bellBounce = 0
    @State private var bellRing = 0

    var body: some View {
        let sync = controller.clipboardSync
        let n = Looks.notificationsTile(controller.notifications.settings, showing: controller.notifications.shown.count)
        // Control Centre's connectivity block: one rounded module, a row per feature
        VStack(spacing: 0) {
            Tile(title: "Clipboard", subtitle: clipboardSubtitle(syncOn: sync, connected: controller.connected),
                 symbol: "doc.on.clipboard", tint: sync ? .on : .off, bounce: clipBounce) {
                controller.setClipboardSync(!sync)
                clipBounce += 1
            } accessory: {
                options("Clipboard options") {
                    Button(sync ? "Turn off sync" : "Turn on sync") { controller.setClipboardSync(!sync); clipBounce += 1 }
                    Divider()
                    Button("Clipboard settings") { openSettings(.clipboard) }
                }
            }
            // The phone holds the setting, so until it has sent it the row waits, and says so
            Tile(title: "Notifications", subtitle: n.subtitle, symbol: n.icon.symbol,
                 tint: n.mode == .on ? .on : n.mode == .paused ? .paused : .off,
                 ring: bellRing + controller.notifications.arrivals, bounce: bellBounce) {
                toggle(n.mode)
            } accessory: {
                menu(n.mode)
            }
            .opacity(n.mode == .waiting ? 0.55 : 1)
        }
        .padding(4)
        // Still on, but nothing flows while the phone is out of reach: the module quietens
        .saturation(controller.connected ? 1 : 0.35)
        .animation(Motion.ease(0.5), value: controller.connected)
        .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(SeamStyle.Pop.module))
        .padding(EdgeInsets(top: 12, leading: 12, bottom: 4, trailing: 12))
    }

    private func toggle(_ mode: NotificationsTileMode) {
        switch mode {
        case .on:
            controller.notifications.setEnabled(false)
            bellBounce += 1
        case .off:
            controller.notifications.setEnabled(true)
            bellRing += 1
        case .paused:
            controller.notifications.pause(until: nil)
            bellRing += 1
        case .waiting:
            break
        }
    }

    /// The system menu (catalogue 9): it brings its own animation.
    private func menu(_ mode: NotificationsTileMode) -> some View {
        options("Notification options") {
            if mode == .paused {
                Button("Resume now") { toggle(.paused) }
            } else {
                Button("Pause for 1 hour") { pause(until: Date().addingTimeInterval(3600)) }.disabled(mode != .on)
                Button("Pause until tomorrow") { pause(until: Self.tomorrowMorning()) }.disabled(mode != .on)
            }
            Divider()
            if mode == .off {
                Button("Turn on") { toggle(.off) }
            } else {
                Button("Turn off") {
                    controller.notifications.setEnabled(false)
                    bellBounce += 1
                }
                .disabled(mode == .waiting)
            }
            Button("Notification settings") { openSettings(.notifications) }
        }
    }

    /// A row's ⋯: the system menu with what the row's own click doesn't do.
    private func options<Items: View>(_ label: String, @ViewBuilder _ items: () -> Items) -> some View {
        Menu(content: items) {
            Image(systemName: "ellipsis").font(.system(size: 12, weight: .semibold))
        }
        .menuStyle(.borderlessButton)
        .menuIndicator(.hidden)
        .fixedSize()
        .frame(width: 28, height: 28)
        .contentShape(Rectangle())
        .tint(SeamStyle.Pop.fg) // a menu's label takes the tint, not the foreground
        .help(label)
        .accessibilityLabel(label)
    }

    private func pause(until: Date) {
        controller.notifications.pause(until: until)
        bellBounce += 1
    }

    /// 8 in the morning, tomorrow.
    private static func tomorrowMorning() -> Date {
        let cal = Calendar.current
        let tomorrow = cal.date(byAdding: .day, value: 1, to: Date()) ?? Date()
        return cal.date(bySettingHour: 8, minute: 0, second: 0, of: tomorrow) ?? tomorrow
    }
}
