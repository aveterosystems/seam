// SPDX-License-Identifier: AGPL-3.0-only
import AppKit
import SeamProtocol
import SwiftUI

/// Board M03, with the panes that exist today. Drawn to the board: a floating sidebar, a bold title
/// on the left, grouped rows with a line of explanation, teal switches.
struct SettingsView: View {
    let controller: SeamController
    @State private var pane: Pane = .phone
    @State private var appQuery = ""
    @State private var selecting = false
    @State private var selectedApps: Set<String> = []
    @State private var checksAutomatically = Updates.shared.checksAutomatically
    @State private var checkInterval = Updates.shared.checkInterval

    enum Pane: Hashable { case general, phone, connection, notifications, clipboard, privacy, about }

    var body: some View {
        HStack(spacing: 0) {
            sidebar
            VStack(alignment: .leading, spacing: 0) {
                Text(title).font(.system(size: 15, weight: .bold))
                    .frame(height: 52)
                    .padding(.leading, 24)
                ScrollView {
                    VStack(alignment: .leading, spacing: 18) { content }
                        .padding(.horizontal, 24).padding(.bottom, 24)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .frame(width: 800, height: 560)
        .tint(SeamStyle.teal)
        .toggleStyle(.switch)
        .ignoresSafeArea()
        .onAppear {
            NSApp.activate()
            openRequestedPane()
        }
        .onChange(of: controller.requestedSettingsPane) { openRequestedPane() }
    }

    private func openRequestedPane() {
        guard let p = controller.requestedSettingsPane else { return }
        pane = p
        controller.requestedSettingsPane = nil
    }

    // MARK: Sidebar

    private var sidebar: some View {
        VStack(alignment: .leading, spacing: 1) {
            Spacer().frame(height: 30) // room for the traffic lights
            nav(.general, "General", "gearshape")
            nav(.phone, controller.phone?.name ?? "Phone", "iphone")
            nav(.connection, "Connection", "network")
            section("Features")
            nav(.notifications, "Notifications", "bell.badge")
            nav(.clipboard, "Clipboard", "doc.on.clipboard")
            section("Seam")
            nav(.privacy, "Privacy & Security", "checkmark.shield")
            nav(.about, "About", "info.circle")
            Spacer()
        }
        .padding(8)
        .frame(width: 220)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(Color.primary.opacity(0.09), lineWidth: 0.5))
        .padding(EdgeInsets(top: 8, leading: 8, bottom: 8, trailing: 0))
    }

    private func nav(_ p: Pane, _ title: String, _ symbol: String) -> some View {
        Button { pane = p } label: {
            HStack(spacing: 8) {
                Image(systemName: symbol).foregroundStyle(SeamStyle.teal).frame(width: 18)
                Text(title).lineLimit(1)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 8)
            .frame(height: 28)
            .background(RoundedRectangle(cornerRadius: 8).fill(pane == p ? Color.primary.opacity(0.08) : .clear))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(pane == p ? .isSelected : [])
    }

    private func section(_ title: String) -> some View {
        Text(title).font(.system(size: 11, weight: .semibold)).foregroundStyle(.tertiary)
            .padding(EdgeInsets(top: 12, leading: 8, bottom: 4, trailing: 8))
    }

    // MARK: Panes

    private var title: String {
        switch pane {
        case .general: "General"
        case .phone: controller.phone?.name ?? "Phone"
        case .connection: "Connection"
        case .notifications: "Notifications"
        case .clipboard: "Clipboard"
        case .privacy: "Privacy & Security"
        case .about: "About"
        }
    }

    @ViewBuilder private var content: some View {
        switch pane {
        case .general: general
        case .phone: phone
        case .connection: connection
        case .notifications: notifications
        case .clipboard: clipboard
        case .privacy: privacy
        case .about: about
        }
    }

    @ViewBuilder private var general: some View {
        SettingsGroup {
            SettingsRow("Seam lives in the menu bar", "Click the stitched icon for your phone, its battery and the clipboard") { EmptyView() }
            SettingsRow("Quit Seam", "Copies not yet delivered wait on this Mac for up to 10 minutes, and go once Seam is open again") {
                Button("Quit") { NSApplication.shared.terminate(nil) }.buttonStyle(.bordered).buttonBorderShape(.capsule)
            }
        }
    }

    @ViewBuilder private var phone: some View {
        if let p = controller.phone {
            SettingsGroup {
                SettingsRow("Status", nil) {
                    LinkStatus(look: controller.link)
                }
                if let s = controller.status {
                    SettingsRow("Battery", nil) { Text("\(s.batteryPct)%\(s.charging ? " · charging" : "")").foregroundStyle(.secondary) }
                }
                if let code = controller.phoneCode() {
                    SettingsRow("Verification code", "The code both screens showed when you paired") {
                        Text(code).font(.system(.body, design: .monospaced)).foregroundStyle(.secondary)
                    }
                }
            }
            SettingsGroup {
                SettingsRow("Unpair \(p.name.isEmpty ? "phone" : p.name)", "Both devices forget each other's keys. You can pair again any time.") {
                    Button("Unpair…") { UnpairPrompt.run(controller) }
                        .buttonStyle(.bordered).buttonBorderShape(.capsule)
                        .foregroundStyle(.red)
                }
            }
        } else {
            SettingsGroup {
                SettingsRow("No phone is paired with this Mac", "Scan a code with Seam on your Android phone") {
                    Button("Pair your phone") { PairingWindow.show(controller) }.buttonStyle(.borderedProminent).buttonBorderShape(.capsule)
                }
            }
        }
    }

    /// Which paths may reach the phone (decision 0006).
    @ViewBuilder private var connection: some View {
        if controller.phone != nil {
            SettingsGroup {
                SettingsRow("Status", nil) {
                    LinkStatus(look: controller.link)
                }
            }
        }
        VStack(alignment: .leading, spacing: 8) {
            SettingsGroup {
                SettingsRow("Same Wi‑Fi", "Straight to your phone when it's on the same network. Nothing leaves your network.") {
                    Toggle("", isOn: Binding(get: { controller.wifiOn }, set: { controller.setWifi($0) }))
                        .labelsHidden().disabled(controller.wifiOn && !controller.torOn)
                }
                SettingsRow("Internet (Tor)", "Reaches your phone anywhere through Tor, which hides where you are. Only sealed messages travel") {
                    Toggle("", isOn: Binding(get: { controller.torOn }, set: { controller.setTor($0) }))
                        .labelsHidden().disabled(controller.torOn && !controller.wifiOn)
                }
            }
            Text("One always stays on. Seam only uses a connection that's on here and on your phone, and the encryption is the same on every one.")
                .font(.system(size: 12)).foregroundStyle(.secondary)
        }
    }

    @ViewBuilder private var notifications: some View {
        if controller.phone == nil {
            SettingsGroup { SettingsRow("No phone is paired with this Mac", "Pair your phone to see its notifications here") { EmptyView() } }
        } else if let s = controller.notificationSettings {
            SettingsGroup {
                SettingsRow("Show on this Mac", "Apps that are off never send anything: your phone filters them before they leave it") {
                    Toggle("", isOn: Binding(get: { s.enabled }, set: { controller.setNotifications(enabled: $0) })).labelsHidden()
                }
                SettingsRow("Dismiss on both devices", "Clearing a notification here clears it on your phone, and the other way round") {
                    Toggle("", isOn: Binding(get: { s.dismissBoth }, set: { controller.setNotifications(dismissBoth: $0) })).labelsHidden()
                }
                SettingsRow("Quiet while I'm using my phone", "Nothing comes to this Mac while your phone is unlocked and in your hand") {
                    Toggle("", isOn: Binding(get: { s.quietWhileUsingPhone }, set: { controller.setNotifications(quietWhileUsingPhone: $0) })).labelsHidden()
                }
                SettingsRow("Banners, sounds and previews", "Set by macOS, like any app's notifications") {
                    Button("Open System Settings") {
                        NSWorkspace.shared.open(URL(string: "x-apple.systempreferences:com.apple.Notifications-Settings.extension?id=\(Bundle.main.bundleIdentifier ?? "")")!)
                    }
                    .buttonStyle(.bordered).buttonBorderShape(.capsule)
                }
            }
            let shown = s.apps.filter { appQuery.isEmpty || $0.appName.localizedCaseInsensitiveContains(appQuery) }
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    VStack(alignment: .leading, spacing: 1) {
                        Text("Apps").font(.headline)
                        Text("\(s.apps.filter { $0.mode == .on || $0.mode == .titleOnly }.count) of \(s.apps.count) on · each starts off")
                            .font(.system(size: 12)).foregroundStyle(.secondary)
                    }
                    Spacer()
                    if s.apps.count > 8 {
                        TextField("Search", text: $appQuery).textFieldStyle(.roundedBorder).frame(width: 180)
                    }
                    if !s.apps.isEmpty {
                        Button(selecting ? "Done" : "Select") { selecting.toggle(); selectedApps = [] }
                            .buttonStyle(.bordered).buttonBorderShape(.capsule)
                    }
                }
                if selecting {
                    HStack(spacing: 8) {
                        Text(selectedApps.isEmpty ? "Select apps" : "\(selectedApps.count) selected").foregroundStyle(.secondary)
                        let all = !shown.isEmpty && shown.allSatisfy { selectedApps.contains($0.appID) }
                        Button(all ? "Select None" : "Select All") {
                            if all { selectedApps.subtract(shown.map(\.appID)) } else { selectedApps.formUnion(shown.map(\.appID)) }
                        }
                        .buttonStyle(.borderless)
                        Spacer()
                        ForEach([(Seam_V1_NotificationMode.on, "On"), (.titleOnly, "Title Only"), (.off, "Off")], id: \.0) { mode, label in
                            Button(label) {
                                controller.setNotificationMode(mode, for: selectedApps)
                                selecting = false
                                selectedApps = []
                            }
                            .buttonStyle(.bordered).buttonBorderShape(.capsule)
                            .disabled(selectedApps.isEmpty)
                        }
                    }
                    .padding(.horizontal, 14).padding(.vertical, 8)
                    .background(SeamStyle.teal.opacity(0.08), in: RoundedRectangle(cornerRadius: 12))
                }
                SettingsGroup {
                    if s.apps.isEmpty {
                        SettingsRow("No apps yet", "Apps that can notify show here once your phone allows notification access") { EmptyView() }
                    }
                    ForEach(shown, id: \.appID) { app in appRow(app) }
                }
                Text("Title only shows who a notification is from, never what it says.")
                    .font(.system(size: 12)).foregroundStyle(.secondary)
            }
        } else {
            SettingsGroup {
                SettingsRow("Waiting for your phone", "Its notification settings show here the next time it connects. Turn on its screen to connect now.") {
                    ProgressView().controlSize(.small)
                }
            }
        }
    }

    /// One phone app: a SettingsRow with its icon (and a checkbox while selecting), and its mode.
    private func appRow(_ app: Seam_V1_NotificationApp) -> some View {
        let selected = Binding(
            get: { selectedApps.contains(app.appID) },
            set: { if $0 { selectedApps.insert(app.appID) } else { selectedApps.remove(app.appID) } }
        )
        return SettingsRow(app.appName, nil, badge: app.canReply && app.mode == .on ? "Reply on Mac" : nil) {
            HStack(spacing: 10) {
                if selecting { Toggle("", isOn: selected).toggleStyle(.checkbox).labelsHidden() }
                AppIcon(image: controller.appIcons[app.appID])
            }
        } trailing: {
            if selecting {
                Text(app.mode.label).foregroundStyle(.secondary)
            } else {
                Picker("", selection: Binding(get: { app.mode == .unspecified ? .off : app.mode }, set: { controller.setNotificationMode($0, for: [app.appID]) })) {
                    Text("On").tag(Seam_V1_NotificationMode.on)
                    Text("Title only").tag(Seam_V1_NotificationMode.titleOnly)
                    Text("Off").tag(Seam_V1_NotificationMode.off)
                }
                .labelsHidden().pickerStyle(.menu).fixedSize()
            }
        }
        .contentShape(Rectangle())
        .onTapGesture { if selecting { selected.wrappedValue.toggle() } }
    }

    @ViewBuilder private var clipboard: some View {
        SettingsGroup {
            SettingsRow("Sync the clipboard with your phone", "Copy on one, paste on the other") {
                Toggle("", isOn: Binding(get: { controller.clipboardSync }, set: { controller.setClipboardSync($0) })).labelsHidden()
            }
            SettingsRow("Never send passwords", "Copies that password managers mark as private stay on this Mac") {
                Image(systemName: "lock.fill").foregroundStyle(.secondary)
            }
            SettingsRow("Copies wait 10 minutes", "If your phone is asleep, a copy waits up to 10 minutes, then it's dropped") { EmptyView() }
        }
        VStack(alignment: .leading, spacing: 8) {
            Text("Phone → Mac").font(.headline)
            SettingsGroup {
                SettingsRow("Automatic clipboard from your phone", "Mac → phone is always automatic. For phone → Mac, open Seam on the phone and turn on Automatic clipboard in its Settings (Seam build from GitHub). Rather not use Android's Accessibility? Set it up from here instead, with the phone connected by USB or wireless debugging.") {
                    Button("Set Up with USB…") { AutoClipboardSetup.run() }.buttonStyle(.bordered).buttonBorderShape(.capsule)
                }
            }
        }
    }

    @ViewBuilder private var privacy: some View {
        SettingsGroup {
            SettingsRow("End-to-end encrypted", "Signal Protocol with post-quantum keys. Only your Mac and phone can read what passes between them.") {
                Image(systemName: "checkmark.shield.fill").foregroundStyle(SeamStyle.teal)
            }
            SettingsRow("Away from home, through Tor", "End-to-end on every path: neither Tor nor your network can read anything. Tor also hides where you are") {
                EmptyView()
            }
            if let code = controller.phoneCode() {
                SettingsRow("Verification code", "Matches the code on your phone if nobody came between them") {
                    Text(code).font(.system(.body, design: .monospaced)).foregroundStyle(.secondary)
                }
            }
        }
    }

    @ViewBuilder private var about: some View {
        SettingsGroup {
            HStack(spacing: 14) {
                Image(nsImage: NSApp.applicationIconImage).resizable().frame(width: 64, height: 64)
                VStack(alignment: .leading, spacing: 3) {
                    Text("Seam").font(.title2.weight(.semibold))
                    Text("Version \(Bundle.main.version)").foregroundStyle(.secondary)
                    Text("© 2026 Avetero · open source, AGPL-3.0").font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
            }
            .padding(.horizontal, 14).padding(.vertical, 12)
            SettingsRow("Source code", nil) { Link("github.com/aveterosystems/seam", destination: URL(string: "https://github.com/aveterosystems/seam")!) }
            // The Tor Project's trademark guidelines ask for this (docs/tor/09-sources.md)
            SettingsRow("Tor", "Seam uses Tor through Arti. It is produced independently from the Tor® anonymity software and carries no guarantee from The Tor Project about quality, suitability or anything else. It isn't endorsed by The Tor Project.") {
                Link("torproject.org", destination: URL(string: "https://www.torproject.org")!)
            }
        }
        SettingsGroup {
            // Development builds show and save these too, so they can be tried; only checking is off
            SettingsRow("Check for updates automatically", "Seam asks GitHub for a newer release. GitHub sees this Mac's IP address and Seam's version when it does.") {
                Toggle("", isOn: Binding(get: { checksAutomatically }, set: { Updates.shared.checksAutomatically = $0; checksAutomatically = $0 }))
                    .labelsHidden()
            }
            SettingsRow("How often", nil) {
                Picker("", selection: Binding(get: { checkInterval }, set: { Updates.shared.checkInterval = $0; checkInterval = $0 })) {
                    Text("Every 30 minutes").tag(Updates.intervals[0])
                    Text("Every hour").tag(Updates.intervals[1])
                    Text("Every day").tag(Updates.intervals[2])
                    Text("Every week").tag(Updates.intervals[3])
                }
                .labelsHidden().pickerStyle(.menu).fixedSize()
                .disabled(!checksAutomatically)
            }
            SettingsRow("Updates", Updates.shared.available ? "Installed only if signed with Seam's update key, and only when you say" : "Development builds don't update themselves") {
                Button("Check Now") { Updates.shared.checkNow() }
                    .buttonStyle(.bordered).buttonBorderShape(.capsule)
                    .disabled(!Updates.shared.canCheck)
            }
        }
    }
}

private extension Seam_V1_NotificationMode {
    var label: String {
        switch self {
        case .on: "On"
        case .titleOnly: "Title only"
        default: "Off"
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
}
