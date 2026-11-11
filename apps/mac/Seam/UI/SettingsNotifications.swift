// SPDX-License-Identifier: AGPL-3.0-only
import AppKit
import SeamCore
import SeamProtocol
import SeamSession
import SwiftUI

/// Notifications: the phone's settings (it owns them; every change is asked of it), and its apps,
/// each On, Sender only or Off, or all of the shown ones at once.
struct NotificationsSettingsPane: View {
    let controller: SeamController
    @State private var filter = ""

    var body: some View {
        Form {
            if controller.phone == nil {
                Section { RowLabel("No phone is paired with this Mac", "Pair your phone to choose which of its notifications show here") }
            } else if let s = controller.notifications.settings {
                switches(s)
                apps(s)
            } else {
                Section {
                    LabeledContent {
                        ProgressView().controlSize(.small)
                    } label: {
                        RowLabel("Waiting for your phone", "Its notification settings show here the next time it connects. Turn on its screen to connect now.")
                    }
                }
            }
        }
        .formStyle(.grouped)
    }

    private func switches(_ s: Seam_V1_NotificationSettings) -> some View {
        Section {
            Toggle(isOn: Binding(get: { s.enabled }, set: { controller.notifications.setEnabled($0) })) {
                RowLabel("Show on this Mac", "Your phone's notifications appear here as banners and in Notification Centre")
            }
            Toggle(isOn: Binding(get: { s.dismissBoth }, set: { controller.notifications.setDismissBoth($0) })) {
                RowLabel("Dismiss on both devices", "Clear one here and it's cleared on your phone")
            }
            Toggle(isOn: Binding(get: { s.quietWhileUsingPhone }, set: { controller.notifications.setQuietWhileUsingPhone($0) })) {
                RowLabel("Quiet while I'm using my phone", "Nothing comes here while it's unlocked in your hand")
            }
            LabeledContent {
                Button("Open System Settings") {
                    NSWorkspace.shared.open(URL(string: "x-apple.systempreferences:com.apple.Notifications-Settings.extension?id=\(Bundle.main.bundleIdentifier ?? "")")!)
                }
            } label: {
                RowLabel("Banners, sounds and previews", "Set by macOS, like any app")
            }
        }
    }

    private func apps(_ s: Seam_V1_NotificationSettings) -> some View {
        let shown = s.apps.filter { filter.isEmpty || $0.appName.localizedCaseInsensitiveContains(filter) }
        return Section {
            if s.apps.isEmpty {
                RowLabel("No apps yet", "Apps that can notify show here once your phone allows notification access")
            } else if shown.isEmpty {
                Text("No apps match “\(filter)”").foregroundStyle(.secondary)
            }
            ForEach(shown, id: \.appID) { app in
                Picker(selection: Binding(get: { app.mode == .unspecified ? .off : app.mode }, set: { controller.notifications.setMode($0, for: [app.appID]) })) {
                    ForEach(Self.modes, id: \.0) { m, word in Text(word).tag(m) }
                } label: {
                    HStack(spacing: 10) {
                        AppIcon(image: controller.notifications.images[app.appID], size: 22, name: app.appName)
                            .clipShape(RoundedRectangle(cornerRadius: 5, style: .continuous))
                        Text(app.appName)
                        if app.canReply && app.mode == .on {
                            Image(systemName: "arrowshape.turn.up.left").font(.caption).foregroundStyle(.secondary)
                                .help("You can reply from this Mac")
                                .accessibilityLabel("Reply on Mac")
                        }
                    }
                }
                .pickerStyle(.menu)
            }
        } header: {
            HStack(spacing: 8) {
                Text("Apps")
                Text(Looks.appsOn(s)).foregroundStyle(.secondary).fontWeight(.regular).monospacedDigit()
                Spacer()
                if s.apps.count > 8 { FilterField(text: $filter) }
                Menu("Set all") {
                    ForEach(Self.modes, id: \.0) { m, word in
                        Button(word) { controller.notifications.setMode(m, for: Set(shown.map(\.appID))) }
                    }
                }
                .menuStyle(.button).controlSize(.small).fixedSize()
                .disabled(shown.isEmpty)
                .help(filter.isEmpty ? "Set every app at once" : "Set the apps that match the filter")
            }
        } footer: {
            Text("\(Image(systemName: "arrowshape.turn.up.left")) You can reply to these apps from this Mac. Apps that are off are filtered on your phone, before anything leaves it. \(senderOnlyFootnote())")
        }
    }

    private static let modes: [(Seam_V1_NotificationMode, String)] = [Seam_V1_NotificationMode.on, .titleOnly, .off].map { ($0, Looks.modeWord($0)) }
}

/// A search field for a list, as Finder and Mail draw theirs: a magnifier, the words, and a clear
/// button once there's something to clear.
private struct FilterField: View {
    @Binding var text: String

    var body: some View {
        HStack(spacing: 5) {
            Image(systemName: "magnifyingglass").foregroundStyle(.secondary).accessibilityHidden(true)
            TextField("Filter apps", text: $text, prompt: Text("Filter apps")).textFieldStyle(.plain).labelsHidden()
            if !text.isEmpty {
                Button { text = "" } label: { Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary) }
                    .buttonStyle(.borderless)
                    .help("Clear")
                    .accessibilityLabel("Clear filter")
            }
        }
        .font(.callout)
        .padding(.horizontal, 7).padding(.vertical, 3)
        .background(RoundedRectangle(cornerRadius: 6, style: .continuous).fill(.quaternary))
        .frame(width: 160)
    }
}
