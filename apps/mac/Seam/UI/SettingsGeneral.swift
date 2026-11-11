// SPDX-License-Identifier: AGPL-3.0-only
import AppKit
import SeamCore
import ServiceManagement
import SwiftUI

/// General: open at login, updates, and About at the bottom. The prototype's "Show in the
/// menu bar" isn't here: with it off, nothing could reopen Seam's window.
struct GeneralPane: View {
    @State private var login = SMAppService.mainApp.status
    @State private var loginError: String?
    @State private var checksAutomatically = Updates.shared.checksAutomatically
    @State private var checkInterval = Updates.shared.checkInterval
    @State private var nightly = Updates.shared.nightly
    @State private var confirmNightly = false
    private var updates: Updates { .shared }

    var body: some View {
        Form {
            Section {
                Toggle(isOn: Binding(get: { login == .enabled || login == .requiresApproval }, set: { setLogin($0) })) { // a closure, not the method: Xcode 26.6's compiler crashes on that thunk
                    RowLabel("Open at login", loginDetail)
                }
                if login == .requiresApproval {
                    LabeledContent {
                        Button("Open Login Items") { SMAppService.openSystemSettingsLoginItems() }
                    } label: {
                        RowLabel("Seam needs your OK in Login Items")
                    }
                }
            }
            Section {
                // Development builds show and save these too, so they can be tried; only checking is off
                // A development build never checks, so its switch shows off rather than a promise it won't keep
                Toggle(isOn: Binding(get: { checksAutomatically && updates.available }, set: { updates.checksAutomatically = $0; checksAutomatically = $0 })) {
                    RowLabel("Check automatically for updates", Updates.privacyLine(checksAutomatically))
                }
                .disabled(!updates.available)
                Picker("How often", selection: Binding(get: { checkInterval }, set: { updates.checkInterval = $0; checkInterval = $0 })) {
                    Text("Every 30 minutes").tag(Updates.intervals[0])
                    Text("Every hour").tag(Updates.intervals[1])
                    Text("Every day").tag(Updates.intervals[2])
                    Text("Every week").tag(Updates.intervals[3])
                }
                .disabled(!checksAutomatically)
                // Turning on asks first: nightlies are less tested (the same words as on the phone)
                Toggle(isOn: Binding(get: { nightly }, set: { on in
                    if on { confirmNightly = true } else { updates.nightly = false; nightly = false }
                })) {
                    RowLabel("Nightly builds", "The newest work each day, less tested. Off waits for the next release.")
                }
                .alert(nightlyWarningTitle(), isPresented: $confirmNightly) {
                    Button("Cancel", role: .cancel) {}
                    Button("Turn on") { updates.nightly = true; nightly = true }
                } message: {
                    Text(nightlyWarning())
                }
            } header: {
                Text("Updates")
            }
            .disabled(!updates.available)
            Section {
                about
                versionRow
            }
        }
        .formStyle(.grouped)
        .onAppear { login = SMAppService.mainApp.status }
    }

    /// The version, what the last check found, and Check now (or Update).
    private var versionRow: some View {
        LabeledContent {
            if let version = updates.found {
                Button("Update to \(version)") { updates.checkNow() }.buttonStyle(.borderedProminent)
            } else {
                HStack(spacing: 8) {
                    if updates.checking { ProgressView().controlSize(.small) }
                    Button("Check now") { updates.checkQuietly() }.disabled(!updates.canCheck || updates.checking)
                }
            }
        } label: {
            VStack(alignment: .leading, spacing: 2) {
                Text("Version \(Bundle.main.shortVersion)").textSelection(.enabled)
                TimelineView(.periodic(from: .now, by: 60)) { _ in
                    SwapText(updateState, pane: true)
                        .font(.callout)
                        .foregroundStyle(updates.checkFailed == false && updates.found == nil ? AnyShapeStyle(SeamStyle.ok) : AnyShapeStyle(.secondary))
                }
            }
        }
    }

    private var loginDetail: String {
        if let loginError { return loginError }
        if login == .requiresApproval { return "Allow Seam in System Settings › General › Login Items" }
        return "Seam waits in the menu bar from the moment you log in"
    }

    private func setLogin(_ on: Bool) {
        do {
            if on { try SMAppService.mainApp.register() } else { try SMAppService.mainApp.unregister() }
            loginError = nil
        } catch {
            loginError = on ? "macOS didn't add Seam to your login items" : "macOS didn't remove Seam from your login items"
        }
        login = SMAppService.mainApp.status
    }

    /// The last check, the one running, or what it found.
    private var updateState: String {
        if !updates.available { return "Development build" }
        if updates.checking { return "Checking" }
        if let version = updates.found { return "Seam \(version) is available" }
        if updates.checkFailed == true { return "Couldn't reach GitHub. Try again later" }
        if updates.checkFailed == false { return "✓ Seam is up to date" }
        guard let at = updates.lastChecked else { return "Not checked yet" }
        let day = Calendar.current.isDateInToday(at) ? "today" : Calendar.current.isDateInYesterday(at) ? "yesterday" : at.formatted(date: .abbreviated, time: .omitted)
        return "Last checked \(day) at \(at.formatted(date: .omitted, time: .shortened))"
    }

    /// About: the icon, the name, the licence with a link to the source, and the copyright. One
    /// `Text` per line, so they wrap as words.
    private var about: some View {
        HStack(alignment: .center, spacing: 14) {
            Image(nsImage: NSApp.applicationIconImage).resizable().frame(width: 48, height: 48)
            VStack(alignment: .leading, spacing: 2) {
                Text("Seam").font(.title3.weight(.semibold))
                Text("Open source under AGPL‑3.0 · [Source code](https://github.com/aveterosystems/seam)")
                    .foregroundStyle(.secondary)
                    .tint(SeamStyle.tealInk)
                Text("© 2026 Avetero").foregroundStyle(.secondary)
            }
            .font(.callout)
            .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
        }
        .padding(.vertical, 4)
    }
}
