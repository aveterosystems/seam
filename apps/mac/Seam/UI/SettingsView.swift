// SPDX-License-Identifier: AGPL-3.0-only
import AppKit
import SeamProtocol
import SeamSession
import SwiftUI

/// Board M03 as a standard Mac settings window: a tab per pane in the toolbar, each a grouped
/// form. The phone's tab carries its name.
struct SettingsView: View {
    let controller: SeamController
    @State private var pane: Pane = .phone

    enum Pane: Hashable, CaseIterable { case general, phone, notifications, clipboard, privacy }

    var body: some View {
        TabView(selection: $pane) {
            // Each tab is as tall as its content, as Mac settings windows are; long ones scroll
            GeneralPane().frame(height: 450)
                .tabItem { Label("General", systemImage: Pane.general.symbol) }.tag(Pane.general)
            PhonePane(controller: controller).frame(height: 560)
                .tabItem { Label(phoneTitle, systemImage: Pane.phone.symbol) }.tag(Pane.phone)
            NotificationsSettingsPane(controller: controller).frame(height: 600)
                .tabItem { Label("Notifications", systemImage: Pane.notifications.symbol) }.tag(Pane.notifications)
            ClipboardSettingsPane(controller: controller).frame(height: 290)
                .tabItem { Label("Clipboard", systemImage: Pane.clipboard.symbol) }.tag(Pane.clipboard)
            PrivacyPane(controller: controller).frame(height: 300)
                .tabItem { Label("Privacy", systemImage: Pane.privacy.symbol) }.tag(Pane.privacy)
        }
        .frame(width: 640)
        .onAppear {
            NSApp.activate()
            openRequestedPane()
        }
        .onChange(of: controller.requestedSettingsPane) { openRequestedPane() }
    }

    private var phoneTitle: String {
        controller.phone.map { $0.name.isEmpty ? "Phone" : $0.name } ?? "Phone"
    }

    private func openRequestedPane() {
        guard let p = controller.requestedSettingsPane else { return }
        pane = p
        controller.requestedSettingsPane = nil
    }
}

extension SettingsView.Pane { // the main window draws its entries with the same icons
    var symbol: String {
        switch self {
        case .general: "gearshape"
        case .phone: "iphone"
        case .notifications: "bell"
        case .clipboard: "doc.on.clipboard"
        case .privacy: "hand.raised"
        }
    }

    var color: Color {
        switch self {
        case .general: Color(light: 0x8E8E93, dark: 0x8E8E93)
        case .phone: SeamStyle.teal
        case .notifications: Color(light: 0xFF3B30, dark: 0xFF453A)
        case .clipboard: Color(light: 0x007AFF, dark: 0x0A84FF)
        case .privacy: Color(light: 0x007AFF, dark: 0x0A84FF)
        }
    }
}

/// A white symbol on a coloured rounded square, as System Settings draws its panes.
struct TintedIcon: View {
    let symbol: String
    let color: Color
    var size: CGFloat = 24

    var body: some View {
        Image(systemName: symbol)
            .font(.system(size: size * 0.52, weight: .medium))
            .foregroundStyle(.white)
            .frame(width: size, height: size)
            .background(RoundedRectangle(cornerRadius: size * 0.25, style: .continuous).fill(color.gradient))
            .accessibilityHidden(true)
    }
}

/// A form row's title with a line of explanation under it, the way System Settings writes them.
struct RowLabel: View {
    let title: String
    let detail: String?

    init(_ title: String, _ detail: String? = nil) {
        self.title = title
        self.detail = detail
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
            if let detail { Text(detail).font(.subheadline).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true) }
        }
    }
}

extension Bundle {
    /// "0.1.0 (3)": the marketing version and build number.
    var version: String {
        let short = infoDictionary?["CFBundleShortVersionString"] as? String ?? "?"
        let build = infoDictionary?["CFBundleVersion"] as? String ?? "?"
        return "\(short) (\(build))"
    }

    /// "0.1.0".
    var shortVersion: String { infoDictionary?["CFBundleShortVersionString"] as? String ?? "?" }
}
