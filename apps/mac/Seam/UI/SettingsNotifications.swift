// SPDX-License-Identifier: AGPL-3.0-only
import AppKit
import SeamProtocol
import SeamSession
import SwiftUI

/// Notifications: the phone's settings (it owns them; every change is asked of it), and its apps,
/// one at a time or several at once in Select mode.
struct NotificationsSettingsPane: View {
    let controller: SeamController
    let shown: Bool
    @State private var filter = ""
    @State private var selecting = false
    @State private var selected: Set<String> = []
    @Environment(\.accessibilityReduceMotion) private var reduce

    var body: some View {
        PaneScroll {
            if controller.phone == nil {
                SettingsGroup { SettingsRow("No phone is paired with this Mac", "Pair your phone to see its notifications here") { EmptyView() } }
                    .rise(0, shown, pane: true)
            } else if let s = controller.notificationSettings {
                switches(s).rise(0, shown, pane: true)
                appsTitle(s).rise(1, shown, pane: true)
                apps(s).rise(2, shown, pane: true)
                GroupFootnote { Text("Title only shows who a notification is from, never what it says.") }.rise(3, shown, pane: true)
                Spacer().frame(height: 40) // room above the bulk bar
            } else {
                SettingsGroup {
                    SettingsRow("Waiting for your phone", "Its notification settings show here the next time it connects. Turn on its screen to connect now.") {
                        ProgressView().controlSize(.small)
                    }
                }
                .rise(0, shown, pane: true)
            }
        }
        // The bulk bar rises 70 pt on a 450 ms spring; the checkboxes widen in on a 300 ms one
        .overlay(alignment: .bottom) {
            if selecting { bulkBar.transition(reduce ? .opacity : .offset(y: 70).animation(Motion.css(0.45))) }
        }
        .animation(reduce ? Motion.fade : Motion.css(0.3), value: selecting)
        .onExitCommand { if selecting { endSelect() } }
    }

    private func switches(_ s: Seam_V1_NotificationSettings) -> some View {
        SettingsGroup {
            SettingsRow("Show on this Mac", "Your phone filters apps that are off before anything leaves it") {
                Toggle("Show on this Mac", isOn: Binding(get: { s.enabled }, set: { controller.setNotifications(enabled: $0) })).labelsHidden()
            }
            SettingsRow("Dismiss on both devices", "Clear one here and it's cleared on your phone") {
                Toggle("Dismiss on both devices", isOn: Binding(get: { s.dismissBoth }, set: { controller.setNotifications(dismissBoth: $0) })).labelsHidden()
            }
            SettingsRow("Quiet while I'm using my phone", "Nothing comes here while it's unlocked in your hand") {
                Toggle("Quiet while I'm using my phone", isOn: Binding(get: { s.quietWhileUsingPhone }, set: { controller.setNotifications(quietWhileUsingPhone: $0) })).labelsHidden()
            }
            SettingsRow("Banners, sounds and previews", "Set by macOS, like any app") {
                Button("System Settings ↗") {
                    NSWorkspace.shared.open(URL(string: "x-apple.systempreferences:com.apple.Notifications-Settings.extension?id=\(Bundle.main.bundleIdentifier ?? "")")!)
                }
                .buttonStyle(CapButtonStyle())
            }
        }
    }

    /// "Apps 6 of 10 on", the filter and Select (the prototype's `.gt` with its controls).
    private func appsTitle(_ s: Seam_V1_NotificationSettings) -> some View {
        GroupTitle(title: "Apps") {
            SwapText(Words.appsOn(s.apps), pane: true).font(.system(size: 12)).foregroundStyle(SeamStyle.Win.fg2).monospacedDigit()
            Spacer()
            HStack(spacing: 6) {
                Image(systemName: "magnifyingglass").font(.system(size: 10, weight: .medium)).foregroundStyle(SeamStyle.Win.fg3)
                TextField("Filter apps", text: $filter, prompt: Text("Filter apps").foregroundStyle(SeamStyle.Win.fg3))
                    .labelsHidden().textFieldStyle(.plain).font(.system(size: 12))
            }
            .padding(.horizontal, 9).padding(.vertical, 5)
            .background(RoundedRectangle(cornerRadius: 8).fill(SeamStyle.Win.fill))
            .frame(width: 150)
            Button(selecting ? "Done" : "Select") { selecting ? endSelect() : (selecting = true) }
                .buttonStyle(CapButtonStyle())
                .disabled(s.apps.isEmpty)
        }
    }

    private func apps(_ s: Seam_V1_NotificationSettings) -> some View {
        let shownApps = s.apps.filter { filter.isEmpty || $0.appName.localizedCaseInsensitiveContains(filter) }
        return SettingsGroup {
            if s.apps.isEmpty {
                SettingsRow("No apps yet", "Apps that can notify show here once your phone allows notification access") { EmptyView() }
            } else if shownApps.isEmpty {
                Text("No apps match").font(.system(size: 12)).foregroundStyle(SeamStyle.Win.fg3).frame(maxWidth: .infinity).padding(14)
            }
            ForEach(shownApps, id: \.appID) { app in appRow(app) }
        }
        .opacity(s.enabled ? 1 : 0.45)
        .animation(Motion.ease(0.3), value: s.enabled)
        .animation(Motion.css(0.4), value: shownApps.map(\.appID))
    }

    private func appRow(_ app: Seam_V1_NotificationApp) -> some View {
        let isSelected = Binding(
            get: { selected.contains(app.appID) },
            set: { if $0 { selected.insert(app.appID) } else { selected.remove(app.appID) } }
        )
        let replyBadge = app.canReply && app.mode == .on
        return SettingsRow(app.appName, nil, badge: replyBadge ? "Reply on Mac" : nil) {
            if selecting {
                Toggle("", isOn: isSelected).toggleStyle(.checkbox).labelsHidden()
                    .accessibilityLabel("Select \(app.appName)")
                    .transition(reduce ? .opacity : .scale(scale: 0, anchor: .leading).combined(with: .opacity))
            }
            AppIcon(image: controller.appIcons[app.appID], name: app.appName).clipShape(RoundedRectangle(cornerRadius: 6))
        } trailing: {
            ModePicker(mode: Binding(get: { app.mode == .unspecified ? .off : app.mode }, set: { $0.map { controller.setNotificationMode($0, for: [app.appID]) } }))
                .accessibilityLabel(app.appName)
        }
        .contentShape(Rectangle())
        .onTapGesture { if selecting { isSelected.wrappedValue.toggle() } }
        .animation(Motion.springFast, value: replyBadge)
        .transition(.unfold(reduce, height: Motion.css(0.4), fade: 0.25))
    }

    /// Select mode's floating bar: how many, the mode for all of them, and Done.
    private var bulkBar: some View {
        HStack(spacing: 10) {
            SwapText("\(selected.count) selected", pane: true).fontWeight(.semibold).monospacedDigit()
            ModePicker(mode: Binding(get: { nil }, set: { mode in
                guard let mode else { return }
                controller.setNotificationMode(mode, for: selected)
                // Long enough to watch the rows' own pickers move, then out of Select mode
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) { endSelect() }
            }))
            .disabled(selected.isEmpty)
            .accessibilityLabel("Set the selected apps to")
            Button("Done", action: endSelect).buttonStyle(CapButtonStyle())
        }
        .font(.system(size: 12))
        .padding(EdgeInsets(top: 7, leading: 14, bottom: 7, trailing: 8))
        .floatingSurface()
        .padding(.bottom, 16)
        .frame(maxWidth: .infinity)
    }

    private func endSelect() {
        selecting = false
        selected = []
    }
}

/// On / Title only / Off, drawn to the prototype's `.seg`: a faint track and a raised thumb that
/// slides between segments on a 350 ms spring. Nil selects nothing (the bulk bar until a mode is
/// picked). VoiceOver sees a standard picker.
private struct ModePicker: View {
    @Binding var mode: Seam_V1_NotificationMode?
    @Namespace private var thumb
    @Environment(\.isEnabled) private var enabled
    private static let modes: [(Seam_V1_NotificationMode, String)] = [(.on, "On"), (.titleOnly, "Title only"), (.off, "Off")]

    var body: some View {
        HStack(spacing: 0) {
            ForEach(Self.modes, id: \.0) { m, label in
                let on = mode == m
                Text(label)
                    .font(.system(size: 11.5, weight: on ? .medium : .regular))
                    .foregroundStyle(on ? SeamStyle.Win.fg : SeamStyle.Win.fg2)
                    .padding(.horizontal, 9).padding(.vertical, 2)
                    .background {
                        if on {
                            RoundedRectangle(cornerRadius: 5)
                                .fill(Color(light: 0xFFFFFF, dark: 0x1E1F22))
                                .shadow(color: Color(light: 0x000000, dark: 0x000000, lightAlpha: 0.25, darkAlpha: 0.25), radius: 1.5, y: 1)
                                .overlay(RoundedRectangle(cornerRadius: 5).strokeBorder(SeamStyle.Win.line, lineWidth: 0.5))
                                .matchedGeometryEffect(id: "thumb", in: thumb)
                        }
                    }
                    .contentShape(Rectangle())
                    .onTapGesture { if enabled { withAnimation(Motion.css(0.35)) { mode = m } } }
                    .animation(Motion.ease(0.2), value: on)
            }
        }
        .padding(2)
        .background(RoundedRectangle(cornerRadius: 7).fill(SeamStyle.Win.fill2))
        .opacity(enabled ? 1 : 0.45)
        .fixedSize()
        .accessibilityRepresentation {
            Picker("Mode", selection: $mode) {
                ForEach(Self.modes, id: \.0) { m, label in Text(label).tag(Seam_V1_NotificationMode?.some(m)) }
            }
        }
    }
}
