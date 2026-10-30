// SPDX-License-Identifier: AGPL-3.0-only
import AppKit
import SeamCore
import SeamProtocol
import SwiftUI

/// Board M01: everything daily, two clicks away. Only what's built is shown: no dead buttons.
struct PopoverView: View {
    let controller: SeamController
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if let error = controller.startupError {
                VStack(alignment: .leading, spacing: 10) {
                    Label(error, systemImage: "exclamationmark.triangle")
                    if controller.needsReset { Button("Reset Seam…") { ResetPrompt.run(controller) } }
                }
                .padding(14)
            } else if let phone = controller.phone {
                header(phone)
                Stitch(connected: controller.connected).padding(.horizontal, 14)
                modules
                if !controller.recentFromPhone.isEmpty {
                    Divider()
                    fromPhone
                }
            } else {
                notPaired
            }
            if let warning = controller.warning {
                Divider()
                Label(warning, systemImage: "exclamationmark.triangle")
                    .font(.caption).foregroundStyle(SeamStyle.warn)
                    .padding(.horizontal, 14).padding(.vertical, 10)
            }
            if let version = Updates.shared.found {
                Divider()
                HStack(spacing: 10) {
                    Label("Seam \(version) is available", systemImage: "arrow.down.circle.fill")
                        .font(.system(size: 12, weight: .semibold)).foregroundStyle(SeamStyle.tealInk)
                    Spacer(minLength: 0)
                    Button("Update") { NSApp.activate(); Updates.shared.checkNow() }
                        .buttonStyle(.borderedProminent).buttonBorderShape(.capsule).controlSize(.small)
                }
                .padding(.horizontal, 14).padding(.vertical, 10)
            }
            Divider()
            footer
        }
        .frame(width: 360)
        .tint(SeamStyle.teal)
    }

    private func header(_ phone: PeerInfo) -> some View {
        HStack(spacing: 12) {
            Image(systemName: "iphone")
                .font(.system(size: 20))
                .frame(width: 40, height: 40)
                .background(RoundedRectangle(cornerRadius: 10).fill(SeamStyle.teal.opacity(0.13)))
                .foregroundStyle(SeamStyle.tealInk)
            VStack(alignment: .leading, spacing: 2) {
                Text(phone.name.isEmpty ? "Your phone" : phone.name).font(.system(size: 14, weight: .semibold))
                HStack(spacing: 6) {
                    Circle().fill(controller.connected ? Color.green : Color.secondary.opacity(0.5)).frame(width: 8, height: 8)
                    Text(controller.connected ? "Connected · Through Seam relay" : "Offline · will send when your phone is back")
                }
                .font(.caption).foregroundStyle(.secondary)
            }
            Spacer(minLength: 0)
            if let s = controller.status { battery(s) }
            Button { open() } label: { Image(systemName: "slider.horizontal.3").frame(width: 18, height: 16) }
                .buttonStyle(.bordered)
                .buttonBorderShape(.capsule)
                .accessibilityLabel("Seam settings")
        }
        .padding(EdgeInsets(top: 14, leading: 14, bottom: 12, trailing: 14))
    }

    /// Neutral, like the board; amber at 15% or below while not charging.
    private func battery(_ s: Seam_V1_Status) -> some View {
        let low = s.batteryPct <= 15 && !s.charging
        return HStack(spacing: 3) {
            if s.charging { Image(systemName: "bolt.fill") }
            Text("\(s.batteryPct)%").monospacedDigit()
        }
        .font(.caption.weight(.medium))
        .padding(.horizontal, 8)
        .frame(height: 20)
        .background(Capsule().fill(low ? SeamStyle.warn.opacity(0.18) : Color.primary.opacity(0.08)))
        .foregroundStyle(low ? SeamStyle.warn : .secondary)
        .accessibilityLabel("Phone battery \(s.batteryPct) percent\(s.charging ? ", charging" : "")")
    }

    /// Control Centre-style round modules on a four-column grid.
    private var modules: some View {
        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 4), spacing: 8) {
            ModuleButton(title: "Clipboard sync", symbol: "doc.on.clipboard", on: controller.clipboardSync) {
                controller.setClipboardSync(!controller.clipboardSync)
            }
            notificationsModule
        }
        .padding(.horizontal, 14).padding(.vertical, 12)
    }

    /// On or off with a click; pause and settings on right-click. The phone holds the setting,
    /// so it waits (dimmed) until the phone has sent it.
    private var notificationsModule: some View {
        let s = controller.notificationSettings
        let pausedUntil = s.map { Date(timeIntervalSince1970: TimeInterval($0.pausedUntil) / 1000) }.flatMap { $0 > Date() ? $0 : nil }
        let on = s?.enabled == true && pausedUntil == nil
        return ModuleButton(
            title: pausedUntil == nil ? "Notifications" : "Paused",
            symbol: pausedUntil == nil ? "bell" : "bell.slash",
            on: on,
            value: pausedUntil.map { "Paused until \($0.formatted(date: .omitted, time: .shortened))" }
        ) {
            guard let s else { return }
            if pausedUntil != nil { controller.pauseNotifications(until: nil) } else { controller.setNotifications(enabled: !s.enabled) }
        }
        .disabled(s == nil)
        .opacity(s == nil ? 0.5 : 1)
        .help(pausedUntil.map { "Paused until \($0.formatted(date: .omitted, time: .shortened)). Click to resume." } ?? "Right-click to pause")
        .contextMenu {
            if pausedUntil != nil {
                Button("Resume") { controller.pauseNotifications(until: nil) }
            } else {
                Button("Pause for 1 Hour") { controller.pauseNotifications(until: Date().addingTimeInterval(3600)) }
                Button("Pause Until Tomorrow") { controller.pauseNotifications(until: Self.tomorrowMorning()) }
            }
            Divider()
            Button("Notification Settings…") {
                controller.requestedSettingsPane = .notifications
                open()
            }
        }
    }

    /// 8 in the morning, tomorrow.
    private static func tomorrowMorning() -> Date {
        let cal = Calendar.current
        let tomorrow = cal.date(byAdding: .day, value: 1, to: Date()) ?? Date()
        return cal.date(bySettingHour: 8, minute: 0, second: 0, of: tomorrow) ?? tomorrow
    }

    private var fromPhone: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("From your phone's clipboard")
                .font(.system(size: 11, weight: .semibold)).foregroundStyle(.tertiary)
                .padding(EdgeInsets(top: 10, leading: 14, bottom: 4, trailing: 14))
            ForEach(controller.recentFromPhone) { clip in
                HStack(spacing: 10) {
                    VStack(alignment: .leading, spacing: 1) {
                        Text(clip.text).font(.system(size: 12)).lineLimit(1).truncationMode(.middle)
                        Text("Copied on your phone · \(clip.at, style: .relative) ago").font(.system(size: 11)).foregroundStyle(.tertiary)
                    }
                    Spacer(minLength: 0)
                    if let url = clip.openableURL {
                        Button("Open") { NSWorkspace.shared.open(url) }.buttonStyle(.bordered).buttonBorderShape(.capsule).controlSize(.small)
                    } else {
                        Button("Copy") { controller.copyAgain(clip.text) }.buttonStyle(.bordered).buttonBorderShape(.capsule).controlSize(.small)
                    }
                }
                .padding(.horizontal, 18).padding(.vertical, 8)
            }
        }
        .padding(.bottom, 6)
    }

    private var notPaired: some View {
        VStack(spacing: 10) {
            Image(nsImage: NSApp.applicationIconImage).resizable().frame(width: 56, height: 56)
            Text("Pair your phone").font(.headline)
            Text("Scan a code with Seam on your Android phone. It takes a minute.")
                .font(.callout).foregroundStyle(.secondary).multilineTextAlignment(.center)
            Button("Pair your phone") { PairingWindow.show(controller) }.buttonStyle(.borderedProminent)
        }
        .frame(maxWidth: .infinity)
        .padding(20)
    }

    private var footer: some View {
        HStack(spacing: 6) {
            Image(systemName: "checkmark.shield").font(.caption).foregroundStyle(.secondary)
            Text("End-to-end encrypted").font(.system(size: 11)).foregroundStyle(.secondary)
            Spacer()
            Button("Settings") { open() }
                .buttonStyle(.plain)
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(SeamStyle.tealInk)
            Text("⌘,")
                .font(.system(size: 11)).foregroundStyle(.secondary)
                .padding(.horizontal, 4)
                .background(RoundedRectangle(cornerRadius: 4).fill(Color.primary.opacity(0.08)))
        }
        .padding(.horizontal, 14).padding(.vertical, 10)
    }

    private func open() {
        NSApp.activate() // a menu bar app's windows otherwise open behind the frontmost app
        openWindow(id: "settings")
    }
}
