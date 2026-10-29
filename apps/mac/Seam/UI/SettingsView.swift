// SPDX-License-Identifier: AGPL-3.0-only
import AppKit
import SwiftUI

/// Board M03, with the panes that exist today. Drawn to the board: a floating sidebar, a bold title
/// on the left, grouped rows with a line of explanation, teal switches.
struct SettingsView: View {
    let controller: SeamController
    @State private var pane: Pane = .phone

    enum Pane: Hashable { case general, phone, clipboard, privacy, about }

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
        .onAppear { NSApp.activate() }
    }

    // MARK: Sidebar

    private var sidebar: some View {
        VStack(alignment: .leading, spacing: 1) {
            Spacer().frame(height: 30) // room for the traffic lights
            nav(.general, "General", "gearshape")
            nav(.phone, controller.phone?.name ?? "Phone", "iphone")
            section("Features")
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
        case .clipboard: "Clipboard"
        case .privacy: "Privacy & Security"
        case .about: "About"
        }
    }

    @ViewBuilder private var content: some View {
        switch pane {
        case .general: general
        case .phone: phone
        case .clipboard: clipboard
        case .privacy: privacy
        case .about: about
        }
    }

    @ViewBuilder private var general: some View {
        Group_ {
            Row("Seam lives in the menu bar", "Click the stitched icon for your phone, its battery and the clipboard") { EmptyView() }
            Row("Quit Seam", "Your phone keeps anything you copy for up to 10 minutes") {
                Button("Quit") { NSApplication.shared.terminate(nil) }.buttonStyle(.bordered).buttonBorderShape(.capsule)
            }
        }
    }

    @ViewBuilder private var phone: some View {
        if let p = controller.phone {
            Group_ {
                Row("Status", nil) {
                    HStack(spacing: 6) {
                        Circle().fill(controller.connected ? Color.green : Color.secondary.opacity(0.5)).frame(width: 8, height: 8)
                        Text(controller.connected ? "Connected · Through Seam relay" : "Offline · will send when your phone is back")
                            .foregroundStyle(.secondary)
                    }
                }
                if let s = controller.status {
                    Row("Battery", nil) { Text("\(s.batteryPct)%\(s.charging ? " · charging" : "")").foregroundStyle(.secondary) }
                }
                if let code = controller.phoneCode() {
                    Row("Verification code", "The code both screens showed when you paired") {
                        Text(code).font(.system(.body, design: .monospaced)).foregroundStyle(.secondary)
                    }
                }
            }
            Group_ {
                Row("Unpair \(p.name.isEmpty ? "phone" : p.name)", "Both devices forget each other's keys. You can pair again any time.") {
                    Button("Unpair…") { UnpairPrompt.run(controller) }
                        .buttonStyle(.bordered).buttonBorderShape(.capsule)
                        .foregroundStyle(.red)
                }
            }
        } else {
            Group_ {
                Row("No phone is paired with this Mac", "Scan a code with Seam on your Android phone") {
                    Button("Pair your phone") { PairingWindow.show(controller) }.buttonStyle(.borderedProminent).buttonBorderShape(.capsule)
                }
            }
        }
    }

    @ViewBuilder private var clipboard: some View {
        Group_ {
            Row("Sync the clipboard with your phone", "Copy on one, paste on the other") {
                Toggle("", isOn: Binding(get: { controller.clipboardSync }, set: { controller.setClipboardSync($0) })).labelsHidden()
            }
            Row("Never send passwords", "Copies that password managers mark as private stay on this Mac") {
                Image(systemName: "lock.fill").foregroundStyle(.secondary)
            }
            Row("Copies wait 10 minutes", "If your phone is asleep, a copy waits up to 10 minutes, then it's dropped") { EmptyView() }
        }
        VStack(alignment: .leading, spacing: 8) {
            Text("Phone → Mac").font(.headline)
            Group_ {
                Row("Automatic clipboard from your phone", "Mac → phone is always automatic. Phone → Mac is automatic once set up here, with the phone connected by USB or wireless debugging (Seam build from GitHub).") {
                    Button("Turn On…") { AutoClipboardSetup.run() }.buttonStyle(.bordered).buttonBorderShape(.capsule)
                }
            }
        }
    }

    @ViewBuilder private var privacy: some View {
        Group_ {
            Row("End-to-end encrypted", "Signal Protocol with post-quantum keys. Only your Mac and phone can read what passes between them.") {
                Image(systemName: "checkmark.shield.fill").foregroundStyle(SeamStyle.teal)
            }
            Row("Relay", "Carries sealed messages only. It can't read them.") {
                Text(controller.relayHost).font(.system(.body, design: .monospaced)).foregroundStyle(.secondary)
            }
            if let code = controller.phoneCode() {
                Row("Verification code", "Matches the code on your phone if nobody came between them") {
                    Text(code).font(.system(.body, design: .monospaced)).foregroundStyle(.secondary)
                }
            }
        }
    }

    @ViewBuilder private var about: some View {
        Group_ {
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
            Row("Source code", nil) { Link("github.com/aveterosystems/seam", destination: URL(string: "https://github.com/aveterosystems/seam")!) }
        }
    }
}

/// A group of rows on a faint fill, with hairlines between them (the board's `.group`). Each row
/// draws a hairline above itself; the group clips the first one off.
private struct Group_<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        VStack(spacing: 0) { content }
            .padding(.top, -1)
            .background(Color.primary.opacity(0.045))
            .clipShape(RoundedRectangle(cornerRadius: 12))
    }
}

/// One row: a title, an optional line of explanation, and what goes on the right.
private struct Row<Trailing: View>: View {
    let title: String
    let detail: String?
    @ViewBuilder let trailing: Trailing

    init(_ title: String, _ detail: String?, @ViewBuilder trailing: () -> Trailing) {
        self.title = title
        self.detail = detail
        self.trailing = trailing()
    }

    var body: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                if let detail { Text(detail).font(.system(size: 12)).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true) }
            }
            Spacer(minLength: 12)
            trailing
        }
        .padding(.horizontal, 14).padding(.vertical, 9)
        .frame(minHeight: 42)
        .overlay(alignment: .top) { Divider().padding(.leading, 14) }
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
