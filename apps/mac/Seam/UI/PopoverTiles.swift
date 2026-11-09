// SPDX-License-Identifier: AGPL-3.0-only
import SeamCore
import SeamSession
import SwiftUI

/// The popover's two tiles: Clipboard and Notifications, each with its state in words. The
/// Notifications tile's chevron holds what used to be on right-click: pause, resume, off, settings.
struct PopoverTiles: View {
    let controller: SeamController
    let openSettings: (SettingsView.Pane?) -> Void
    @State private var clipBounce = 0
    @State private var bellBounce = 0
    @State private var bellRing = 0

    var body: some View {
        let sync = controller.clipboardSync
        let n = Looks.notificationsTile(controller.notifications.settings, showing: controller.notifications.shown.count)
        HStack(spacing: 8) {
            Tile(title: "Clipboard", subtitle: clipboardSubtitle(syncOn: sync, connected: controller.connected),
                 symbol: "doc.on.doc", tint: sync ? .on : .off, bounce: clipBounce) {
                controller.setClipboardSync(!sync)
                clipBounce += 1
            }
            .frame(maxWidth: .infinity)
            // The phone holds the setting, so until it has sent it the tile waits, and says so
            Tile(title: "Notifications", subtitle: n.subtitle, symbol: n.icon.symbol,
                 tint: n.mode == .on ? .on : n.mode == .paused ? .paused : .off,
                 ring: bellRing + controller.notifications.arrivals, bounce: bellBounce) {
                toggle(n.mode)
            } accessory: {
                menu(n.mode)
            }
            .opacity(n.mode == .waiting ? 0.55 : 1)
            // Sized to its words, like the prototype's grid: Clipboard takes what's left
            .fixedSize(horizontal: true, vertical: false)
        }
        .padding(EdgeInsets(top: 10, leading: 14, bottom: 6, trailing: 14))
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
        Menu {
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
        } label: {
            Image(systemName: "chevron.down").font(.system(size: 9, weight: .semibold))
        }
        .menuStyle(.borderlessButton)
        .menuIndicator(.hidden)
        .fixedSize()
        .frame(width: 20, height: 20)
        .contentShape(Rectangle())
        .foregroundStyle(SeamStyle.Pop.fg2)
        .help("Pause, turn off, or open notification settings")
        .accessibilityLabel("Notification options")
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
