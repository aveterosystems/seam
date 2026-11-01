// SPDX-License-Identifier: AGPL-3.0-only
import SwiftUI

/// Clipboard: sync, what never goes, how long copies wait, and phone → Mac.
struct ClipboardSettingsPane: View {
    let controller: SeamController
    let shown: Bool
    @State private var waitInfo = false
    @State private var autoInfo = false
    @Environment(\.accessibilityReduceMotion) private var reduce

    var body: some View {
        PaneScroll {
            SettingsGroup {
                SettingsRow("Sync the clipboard", "Copy on one, paste on the other") {
                    Toggle("Sync the clipboard", isOn: Binding(get: { controller.clipboardSync }, set: { controller.setClipboardSync($0) })).labelsHidden()
                }
                // Always on: there's no setting behind it, so it shows as a fact, not a switch (see the PR)
                SettingsRow("Never send passwords", "Copies a password manager marks private stay on this Mac") {
                    Image(systemName: "lock.fill").foregroundStyle(SeamStyle.Win.fg2).accessibilityLabel("Always on")
                }
                SettingsRow("Waiting copies", "Kept 10 minutes while your phone is asleep", info: $waitInfo) { EmptyView() }
                if waitInfo {
                    InfoMore("When your phone is asleep, a copy waits on this Mac for up to 10 minutes and sends as soon as the phone wakes. After that it's dropped, so an old copy never lands on your phone by surprise.")
                        .transition(.unfold(reduce))
                }
            }
            .animation(Motion.reveal, value: waitInfo)
            .rise(0, shown, pane: true)
            GroupTitle("Phone → Mac").rise(1, shown, pane: true)
            SettingsGroup {
                // Whether the phone's own automatic clipboard is on isn't sent, so no "Off ·" here (see the PR)
                SettingsRow("Automatic from your phone", "Copies on your phone come here when you share them", info: $autoInfo) {
                    Button("Set up") { AutoClipboardSetup.run() }.buttonStyle(CapButtonStyle(kind: .primary))
                }
                if autoInfo {
                    InfoMore("Mac → phone is always automatic. For phone → Mac, turn on Automatic clipboard in Seam on your phone. Rather not use Android's Accessibility? Set it up from here with the phone on USB or wireless debugging.")
                        .transition(.unfold(reduce))
                }
            }
            .animation(Motion.reveal, value: autoInfo)
            .rise(2, shown, pane: true)
        }
    }
}

/// Privacy & Security: what protects what passes between the devices, and what Seam asks GitHub.
struct PrivacyPane: View {
    let shown: Bool
    /// Change on the update-checks row: General has the switch.
    let openGeneral: () -> Void

    var body: some View {
        PaneScroll {
            SettingsGroup {
                SettingsRow("End-to-end encrypted", "Signal Protocol with post-quantum keys. Only your Mac and phone can read what passes between them.") {
                    TintedIcon(symbol: "checkmark", color: Color(light: 0x30A46C, dark: 0x30A46C))
                } trailing: { EmptyView() }
                SettingsRow("Away from home, through Tor", "Neither Tor nor your network can read anything, and Tor hides where you are") {
                    TintedIcon(symbol: "circle.circle", color: Color(light: 0x6E56CF, dark: 0x6E56CF))
                } trailing: { EmptyView() }
                SettingsRow("Update checks", updateChecks) {
                    TintedIcon(symbol: "arrow.down", color: Color(light: 0x8E8E93, dark: 0x8E8E93))
                } trailing: {
                    Button("Change", action: openGeneral).buttonStyle(CapButtonStyle())
                }
            }
            .rise(0, shown, pane: true)
            GroupTitle("Tor").rise(1, shown, pane: true)
            SettingsGroup {
                // The Tor Project's trademark guidelines ask for this wording, so it's longer than the prototype's
                HStack {
                    RowDetail("Seam uses Tor through Arti. It is produced independently from the Tor® anonymity software and carries no guarantee from The Tor Project about quality, suitability or anything else. It isn't endorsed by The Tor Project.")
                    Spacer(minLength: 0)
                }
                .padding(.horizontal, 14).padding(.vertical, 9)
                .frame(minHeight: 42)
            }
            .rise(2, shown, pane: true)
        }
    }

    private var updateChecks: String {
        guard Updates.shared.checksAutomatically else { return "Off: Seam asks GitHub only when you click Check now" }
        return "GitHub sees this Mac's IP address and Seam's version \(Updates.often(Updates.shared.checkInterval))"
    }
}
