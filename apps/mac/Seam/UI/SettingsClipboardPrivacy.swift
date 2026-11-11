// SPDX-License-Identifier: AGPL-3.0-only
import SwiftUI

/// Clipboard: sync, what never goes, how long copies wait, and phone → Mac.
struct ClipboardSettingsPane: View {
    let controller: SeamController
    private var phoneName: String { controller.phone.map { $0.name.isEmpty ? "your phone" : $0.name } ?? "your phone" }

    var body: some View {
        Form {
            Section {
                Toggle(isOn: Binding(get: { controller.clipboardSync }, set: { controller.setClipboardSync($0) })) {
                    RowLabel("Sync the clipboard", "What you copy on this Mac goes to \(phoneName) by itself")
                }
                // Always on: there's no setting behind it, so it reads as a fact, with a status glyph
                // A fact, not a setting: it has no control, so it says "Always" where one would be
                LabeledContent {
                    Text("Always").foregroundStyle(.secondary)
                } label: {
                    RowLabel("Passwords stay on this Mac", "Copies a password manager marks private are never sent")
                }
            } footer: {
                Text("While \(phoneName) is asleep, a copy waits here for up to 10 minutes, then it's dropped, so an old copy never lands by surprise.")
            }
            Section {
                // Whether the phone's own automatic clipboard is on isn't sent, so no "Off" here
                LabeledContent {
                    Button("Set up") { AutoClipboardSetup.run() }
                } label: {
                    RowLabel("Copy automatically", "Copies arrive here without using Share. Turn it on here, or in Seam on your phone.")
                }
            } header: {
                Text("From \(phoneName) to this Mac")
            }
        }
        .formStyle(.grouped)
    }
}

/// Privacy & Security: what protects what passes between the devices, and what Seam asks GitHub.
struct PrivacyPane: View {
    let controller: SeamController
    @State private var checks = Updates.shared.checksAutomatically

    var body: some View {
        Form {
            Section {
                fact("checkmark.shield.fill", "Always", "End-to-end encrypted", "Signal Protocol with post-quantum keys. Only your Mac and phone can read what passes between them.")
                // Its switch is on the phone's tab; here, what it's set to
                fact("globe", controller.torOn ? "On" : "Off", "Internet, through Tor", "Away from home, neither Tor nor your network can read anything, and Tor hides where you are")
                // Its switch is in General; here, what it tells GitHub
                LabeledContent {
                    Text(checks && Updates.shared.available ? "On" : "Off").foregroundStyle(.secondary)
                } label: {
                    RowLabel("Update checks", Updates.privacyLine(checks) + (Updates.shared.available ? ". Change it in General." : ""))
                }
            } footer: {
                // The Tor Project's trademark guidelines ask for this wording: a footnote, not a setting
                Text("Seam uses Tor through Arti. It is produced independently from the Tor® anonymity software and carries no guarantee from The Tor Project about quality, suitability or anything else. It isn't endorsed by The Tor Project.")
            }
        }
        .formStyle(.grouped)
        .onAppear { checks = Updates.shared.checksAutomatically } // General may have changed it
    }

    /// A fact rather than a setting: its words, and its state where a control would be.
    private func fact(_ symbol: String, _ state: String, _ title: String, _ detail: String) -> some View {
        LabeledContent {
            Label(state, systemImage: symbol).foregroundStyle(.secondary)
        } label: {
            RowLabel(title, detail)
        }
    }
}
