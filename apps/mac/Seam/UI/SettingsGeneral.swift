// SPDX-License-Identifier: AGPL-3.0-only
import AppKit
import ServiceManagement
import SwiftUI

/// General: open at login, updates, quit, and about at the bottom. The prototype's "Show in the
/// menu bar" isn't here: with it off, nothing could reopen Seam's window (see the PR).
struct GeneralPane: View {
    let shown: Bool
    @State private var login = SMAppService.mainApp.status
    @State private var loginError: String?
    @State private var checksAutomatically = Updates.shared.checksAutomatically
    @State private var checkInterval = Updates.shared.checkInterval
    @State private var updatesInfo = false
    @Environment(\.accessibilityReduceMotion) private var reduce
    private var updates: Updates { .shared }

    var body: some View {
        PaneScroll {
            SettingsGroup {
                SettingsRow("Open at login", loginDetail) {
                    Toggle("Open at login", isOn: Binding(get: { login == .enabled || login == .requiresApproval }, set: setLogin)).labelsHidden()
                }
                if login == .requiresApproval {
                    SettingsRow("Seam needs your OK in Login Items", nil) {
                        Button("Open Login Items") { SMAppService.openSystemSettingsLoginItems() }.buttonStyle(CapButtonStyle())
                    }
                }
            }
            .rise(0, shown, pane: true)
            GroupTitle("Updates").rise(1, shown, pane: true)
            SettingsGroup {
                // Development builds show and save these too, so they can be tried; only checking is off
                SettingsRow("Check automatically", nil, info: $updatesInfo) {
                    Toggle("Check automatically", isOn: Binding(get: { checksAutomatically }, set: { updates.checksAutomatically = $0; checksAutomatically = $0 })).labelsHidden()
                }
                if updatesInfo {
                    InfoMore("Seam asks GitHub for a newer release \(Updates.often(checkInterval)). GitHub sees this Mac's IP address and Seam's version when it does.")
                        .transition(.unfold(reduce))
                }
                // Not in the prototype: the interval people could already choose stays (see the PR)
                SettingsRow("How often", nil) {
                    Picker("How often", selection: Binding(get: { checkInterval }, set: { updates.checkInterval = $0; checkInterval = $0 })) {
                        Text("Every 30 minutes").tag(Updates.intervals[0])
                        Text("Every hour").tag(Updates.intervals[1])
                        Text("Every day").tag(Updates.intervals[2])
                        Text("Every week").tag(Updates.intervals[3])
                    }
                    .labelsHidden().pickerStyle(.menu).fixedSize()
                    .disabled(!checksAutomatically)
                }
                versionRow
            }
            .animation(Motion.reveal, value: updatesInfo)
            .rise(2, shown, pane: true)
            SettingsGroup {
                SettingsRow("Quit Seam", "Copies not yet delivered wait here and send next time Seam opens") {
                    Button("Quit") { NSApplication.shared.terminate(nil) }.buttonStyle(CapButtonStyle())
                }
            }
            .rise(3, shown, pane: true)
            about.rise(4, shown, pane: true)
        }
        .onAppear { login = SMAppService.mainApp.status }
    }

    /// "Seam 0.2.3", what the last check found (with the prototype's spinner while checking), and Check now.
    private var versionRow: some View {
        let upToDate = updates.checkFailed == false && updates.found == nil
        return HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text("Seam \(Bundle.main.shortVersion)").foregroundStyle(SeamStyle.Win.fg)
                TimelineView(.periodic(from: .now, by: 60)) { _ in
                    HStack(spacing: 5) {
                        if updates.checking { ProgressView().controlSize(.mini) }
                        SwapText(updateState, pane: true)
                            .font(.system(size: upToDate ? 12 : 11.5, weight: upToDate ? .medium : .regular))
                            .foregroundStyle(upToDate ? SeamStyle.ok : SeamStyle.Win.fg2)
                    }
                }
            }
            Spacer(minLength: 12)
            if let version = updates.found {
                Button("Update to \(version)") { updates.checkNow() }.buttonStyle(CapButtonStyle(kind: .primary))
            } else {
                Button("Check now") { updates.checkQuietly() }.buttonStyle(CapButtonStyle()).disabled(!updates.canCheck || updates.checking)
            }
        }
        .font(.system(size: 13))
        .padding(.horizontal, 14).padding(.vertical, 9)
        .frame(minHeight: 42)
        .overlay(alignment: .top) { Rectangle().fill(SeamStyle.Win.line).frame(height: 0.5) }
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
        if !updates.available { return "Development builds don't update themselves" }
        if updates.checking { return "Checking" }
        if let version = updates.found { return "Seam \(version) is available" }
        if updates.checkFailed == true { return "Couldn't reach GitHub. Try again later" }
        if updates.checkFailed == false { return "✓ Seam is up to date" }
        guard let at = updates.lastChecked else { return "Not checked yet" }
        let day = Calendar.current.isDateInToday(at) ? "today" : Calendar.current.isDateInYesterday(at) ? "yesterday" : at.formatted(date: .abbreviated, time: .omitted)
        return "Last checked \(day) at \(at.formatted(date: .omitted, time: .shortened))"
    }

    /// The prototype's `.about`: the icon, the version, the licence and the source.
    private var about: some View {
        HStack(spacing: 12) {
            Image(nsImage: NSApp.applicationIconImage).resizable().frame(width: 34, height: 34)
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 0) {
                    Text("Seam \(Bundle.main.shortVersion) · open source, AGPL‑3.0 · ")
                    Link("Source code", destination: URL(string: "https://github.com/aveterosystems/seam")!)
                        .foregroundStyle(SeamStyle.tealInk)
                }
                Text("© 2026 Avetero")
            }
            Spacer(minLength: 0)
        }
        .font(.system(size: 11.5))
        .foregroundStyle(SeamStyle.Win.fg2)
        .padding(EdgeInsets(top: 16, leading: 4, bottom: 4, trailing: 4))
    }
}
