// SPDX-License-Identifier: AGPL-3.0-only
import AppKit
import SeamProtocol
import SeamSession
import SwiftUI

/// Board M03, drawn to docs/design/prototypes/mac-settings.html: the floating sidebar with search,
/// and grouped rows per pane. Typing in Search jumps to the first pane that matches and fades the
/// rest; switching panes fades the old one out, then the new one's sections rise in a stagger.
struct SettingsView: View {
    let controller: SeamController
    @Environment(\.openWindow) private var openWindow
    @State private var pane: Pane = .phone
    @State private var query = ""
    /// False for a moment when a pane changes, so its sections rise in.
    @State private var shown = true

    enum Pane: Hashable, CaseIterable { case general, phone, notifications, clipboard, privacy }

    var body: some View {
        SidebarWindow(title: title(pane), search: $query, size: CGSize(width: 700, height: 540)) {
            row(.general)
            row(.phone)
            SidebarSection("Features")
            row(.notifications)
            row(.clipboard)
            SidebarSection("Seam")
            row(.privacy)
        } toolbar: {
            if pane == .notifications || pane == .clipboard {
                Button("Show history ›") {
                    controller.requestedMainPane = pane == .notifications ? .notifications : .clipboard
                    openWindow(id: "main")
                }
                .buttonStyle(CapButtonStyle())
            }
        } content: {
            ZStack(alignment: .top) {
                detail.id(pane).transition(.asymmetric(insertion: .identity, removal: .opacity.animation(.easeIn(duration: 0.12))))
            }
        }
        .frame(minWidth: 700, minHeight: 300) // SidebarWindow fixes the window at the prototype's 700 × 540
        .onAppear {
            NSApp.activate()
            openRequestedPane()
        }
        .onChange(of: controller.requestedSettingsPane) { openRequestedPane() }
        .onChange(of: query) {
            if let hit = Pane.allCases.first(where: matches) { go(hit) }
        }
    }

    @ViewBuilder private var detail: some View {
        switch pane {
        case .general: GeneralPane(shown: shown)
        case .phone: PhonePane(controller: controller, shown: shown)
        case .notifications: NotificationsSettingsPane(controller: controller, shown: shown)
        case .clipboard: ClipboardSettingsPane(controller: controller, shown: shown)
        case .privacy: PrivacyPane(shown: shown) { go(.general) }
        }
    }

    private func go(_ p: Pane) {
        guard p != pane else { return }
        // The old pane fades out in 120 ms; the new one's sections rise 110 ms in (the prototype's timing)
        shown = false
        withAnimation(.easeIn(duration: 0.12)) { pane = p }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.11) { shown = true }
    }

    private func openRequestedPane() {
        guard let p = controller.requestedSettingsPane else { return }
        go(p)
        controller.requestedSettingsPane = nil
    }

    private func row(_ p: Pane) -> some View {
        SidebarItem(
            title: title(p), selected: pane == p,
            count: p == .notifications ? controller.notifications.settings.map { Words.appsOnCount($0.apps) } ?? 0 : 0,
            dot: p == .phone && controller.phone != nil ? controller.connected : nil,
            miss: !query.isEmpty && !matches(p)
        ) {
            TintedIcon(symbol: p.symbol, color: p.color, size: 20)
        } action: { go(p) }
    }

    private func title(_ p: Pane) -> String {
        switch p {
        case .general: "General"
        case .phone: controller.phone.map { $0.name.isEmpty ? "Your phone" : $0.name } ?? "Phone"
        case .notifications: "Notifications"
        case .clipboard: "Clipboard"
        case .privacy: "Privacy & Security"
        }
    }

    /// What each pane answers to in Search, beyond its title.
    private func matches(_ p: Pane) -> Bool {
        let q = query.trimmingCharacters(in: .whitespaces).lowercased()
        guard !q.isEmpty else { return false }
        let words = switch p {
        case .general: "general login start open launch update quit about version"
        case .phone: "phone battery connection wifi wi-fi tor internet unpair pair verification code network"
        case .notifications: "notifications apps dismiss quiet banners sounds reply title"
        case .clipboard: "clipboard copy paste passwords usb automatic sync"
        case .privacy: "privacy security encryption encrypted tor github keys"
        }
        return (title(p).lowercased() + " " + words).contains(q)
    }
}

private extension SettingsView.Pane {
    var symbol: String {
        switch self {
        case .general: "gearshape"
        case .phone: "iphone"
        case .notifications: "bell"
        case .clipboard: "doc.on.doc"
        case .privacy: "shield"
        }
    }

    var color: Color {
        switch self {
        case .general: Color(light: 0x8E8E93, dark: 0x8E8E93)
        case .phone: SeamStyle.teal
        case .notifications: Color(light: 0xFF453A, dark: 0xFF453A)
        case .clipboard: Color(light: 0x0A84FF, dark: 0x0A84FF)
        case .privacy: Color(light: 0x30A46C, dark: 0x30A46C)
        }
    }
}

/// A pane's scrolling column: 22 pt in from the sides, under the title bar.
struct PaneScroll<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) { content }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(EdgeInsets(top: 0, leading: 22, bottom: 22, trailing: 22))
        }
    }
}

/// A white symbol on a coloured rounded square (the prototype's `.ti` and `.ai`).
struct TintedIcon: View {
    let symbol: String
    let color: Color
    var size: CGFloat = 24

    var body: some View {
        Image(systemName: symbol)
            .font(.system(size: size * 0.55, weight: .semibold))
            .foregroundStyle(SeamStyle.onTint)
            .frame(width: size, height: size)
            .background(RoundedRectangle(cornerRadius: 6).fill(color))
            .accessibilityHidden(true)
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
