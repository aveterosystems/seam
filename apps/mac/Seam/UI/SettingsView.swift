// SPDX-License-Identifier: AGPL-3.0-only
import AppKit
import SwiftUI

/// Board M03, the panes that exist today: the phone, clipboard, privacy and about.
struct SettingsView: View {
    let controller: SeamController
    @State private var pane: Pane? = .phone

    enum Pane: Hashable { case phone, clipboard, privacy, about }

    var body: some View {
        NavigationSplitView {
            List(selection: $pane) {
                Label(controller.phone?.name ?? "Phone", systemImage: "iphone").tag(Pane.phone)
                Section("Features") {
                    Label("Clipboard", systemImage: "doc.on.clipboard").tag(Pane.clipboard)
                }
                Section("Seam") {
                    Label("Privacy & Security", systemImage: "checkmark.shield").tag(Pane.privacy)
                    Label("About", systemImage: "info.circle").tag(Pane.about)
                }
            }
            .navigationSplitViewColumnWidth(190)
        } detail: {
            Form { detail }
                .formStyle(.grouped)
                .navigationTitle(title)
        }
        .tint(SeamStyle.teal)
        .frame(minWidth: 640, minHeight: 420)
    }

    private var title: String {
        switch pane ?? .phone {
        case .phone: controller.phone?.name ?? "Phone"
        case .clipboard: "Clipboard"
        case .privacy: "Privacy & Security"
        case .about: "About"
        }
    }

    @ViewBuilder private var detail: some View {
        switch pane ?? .phone {
        case .phone: phone
        case .clipboard: clipboard
        case .privacy: privacy
        case .about: about
        }
    }

    @ViewBuilder private var phone: some View {
        if let p = controller.phone {
            Section {
                LabeledContent("Status", value: controller.connected ? "Connected · Through Seam relay" : "Offline · will send when your phone is back")
                if let s = controller.status {
                    LabeledContent("Battery", value: "\(s.batteryPct)%\(s.charging ? " · charging" : "")")
                }
                if let code = controller.phoneCode() {
                    LabeledContent("Verification code") { Text(code).monospaced() }
                }
            }
            Section {
                Button("Unpair \(p.name.isEmpty ? "Phone" : p.name)…", role: .destructive) { UnpairPrompt.run(controller) }
            } footer: {
                Text("Both devices forget each other's keys. You can pair again any time.")
            }
        } else {
            Section {
                Text("No phone is paired with this Mac.")
                Button("Pair a Phone…") { PairingWindow.show(controller) }
            }
        }
    }

    @ViewBuilder private var clipboard: some View {
        Section {
            Toggle("Sync the clipboard with your phone", isOn: Binding(get: { controller.clipboardSync }, set: { controller.setClipboardSync($0) }))
            LabeledContent("Passwords") {
                Label("Never sent", systemImage: "lock.fill").foregroundStyle(.secondary)
            }
        } footer: {
            Text("Copies marked as passwords by password managers stay on this Mac. A copy waits at most 10 minutes for your phone.")
        }
        Section {
            Button("Turn On Automatic Clipboard from Phone…") { AutoClipboardSetup.run() }
        } header: {
            Text("Phone → Mac")
        } footer: {
            Text("Mac → phone is always automatic. Phone → Mac is automatic once set up here, with the phone connected to this Mac by USB or wireless debugging. It needs the Seam build from GitHub.")
        }
    }

    @ViewBuilder private var privacy: some View {
        Section {
            LabeledContent("Encryption", value: "End-to-end, Signal Protocol with post-quantum keys")
            LabeledContent("Relay") { Text(controller.relayHost).monospaced() }
            if let code = controller.phoneCode() {
                LabeledContent("Verification code") { Text(code).monospaced() }
            }
        } footer: {
            Text("The relay only ever sees sealed messages. Keys never leave your devices, and the code above is the one you compared when you paired.")
        }
    }

    @ViewBuilder private var about: some View {
        Section {
            HStack(spacing: 14) {
                Image(nsImage: NSApp.applicationIconImage).resizable().frame(width: 64, height: 64)
                VStack(alignment: .leading, spacing: 3) {
                    Text("Seam").font(.title2.weight(.semibold))
                    Text("Version \(Bundle.main.version)").foregroundStyle(.secondary)
                    Text("© 2026 Avetero. Open source under AGPL-3.0.").font(.caption).foregroundStyle(.secondary)
                }
            }
            Link("Source code on GitHub", destination: URL(string: "https://github.com/aveterosystems/seam")!)
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
