// SPDX-License-Identifier: AGPL-3.0-only
import AppKit
import SeamCore
import SeamSession
import SwiftUI

/// Board M01: everything daily, two clicks away. Only what's built is shown: no dead buttons.
struct PopoverView: View {
    let controller: SeamController
    @Environment(\.openWindow) private var openWindow
    @Environment(\.openSettings) private var openSettingsWindow
    @Environment(\.accessibilityReduceMotion) private var reduce
    /// Catalogue 1: sections rise in a stagger, and the stitch draws, each time the popover opens.
    @State private var appeared = false
    @State private var opens = 0
    @State private var toast: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if let banner { BannerView(banner: banner, controller: controller).transition(.unfold(reduce)) }
            if controller.startupError != nil {
                Spacer().frame(height: 10)
            } else if let phone = controller.phone {
                // The prototype's seven stagger slots: header, stitch, tiles, then each heading and its list
                PopoverHeader(controller: controller, phone: phone, rise: (0, appeared), replay: opens)
                if controller.waiting > 0, !controller.connected {
                    QueueBar(count: controller.waiting, phone: phone.name.isEmpty ? "your phone" : phone.name).transition(.unfold(reduce))
                }
                PopoverTiles(controller: controller, openSettings: openSettings).rise(2, appeared)
                PopoverNotifications(controller: controller, openMain: openMain, appeared: appeared)
                PopoverClips(controller: controller, openMain: openMain, toast: show, appeared: appeared)
            } else {
                notPaired
            }
            footer
        }
        .frame(width: 320)
        .overlay(alignment: .bottom) {
            if let toast {
                ToastPill(text: toast).padding(.bottom, 112)
                    .transition(reduce ? .opacity : .offset(y: 10).combined(with: .opacity).animation(Motion.css(0.35)))
            }
        }
        .animation(Motion.spatial(reduce), value: banner)
        .animation(Motion.spatial(reduce), value: controller.waiting > 0 && !controller.connected)
        .animation(Motion.spatial(reduce), value: toast)
        .foregroundStyle(SeamStyle.Pop.fg)
        .font(.system(size: 12))
        // Catalogue 1: the surface grows from the menu bar icon (scale 0.94, 6 pt, 85% across)
        .scaleEffect(appeared || reduce ? 1 : 0.94, anchor: UnitPoint(x: 0.85, y: 0))
        .offset(y: appeared || reduce ? 0 : -6)
        .opacity(appeared ? 1 : 0)
        .animation(appeared ? Motion.spatial(reduce) : nil, value: appeared)
        // No fill of its own: the menu bar's glass shows through, as in Apple's own menu bar extras
        .tint(SeamStyle.teal)
        .onAppear(perform: opened)
        .onDisappear { appeared = false }
        .onReceive(NotificationCenter.default.publisher(for: NSWindow.didBecomeKeyNotification)) { note in
            // MenuBarExtra keeps its view between openings: becoming key again is the popover opening
            if (note.object as? NSWindow)?.className.contains("MenuBarExtra") == true { opened() }
        }
    }

    private func opened() {
        opens += 1
        guard appeared else { appeared = true; return } // first open: straight in, no frame lost
        appeared = false
        DispatchQueue.main.async { appeared = true }
    }

    /// One slot at the top: a startup error, then a warning, then an update (catalogue 7).
    private var banner: BannerView.Banner? {
        if let error = controller.startupError { return .error(error) }
        if let warning = controller.warning { return .warning(warning) }
        if let version = Updates.shared.found { return .update(version) }
        return nil
    }

    private var notPaired: some View {
        VStack(spacing: 10) {
            Image(nsImage: NSApp.applicationIconImage).resizable().frame(width: 56, height: 56)
            Text("Pair your phone").font(.headline)
            Text("Scan a code with Seam on your Android phone. It takes a minute.")
                .font(.callout).foregroundStyle(SeamStyle.Pop.fg2).multilineTextAlignment(.center)
            Button("Show pairing code") { PairingWindow.show(controller) }.buttonStyle(.borderedProminent)
        }
        .frame(maxWidth: .infinity)
        .padding(20)
    }

    /// The bottom of the popover, as every menu bar extra ends: a separator, then menu items with
    /// their shortcuts. The security line sits above them, quiet.
    private var footer: some View {
        VStack(spacing: 0) {
            Label("End-to-end encrypted", systemImage: "checkmark.shield")
                .font(.system(size: 11)).foregroundStyle(SeamStyle.Pop.fg2)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(EdgeInsets(top: 10, leading: 14, bottom: 6, trailing: 14))
            Rectangle().fill(SeamStyle.Pop.line).frame(height: 0.5).padding(.horizontal, 14)
            VStack(spacing: 0) {
                if controller.phone != nil {
                    MenuRow(title: "Open Seam", keys: "⌘O") { openMain(nil) }.keyboardShortcut("o")
                }
                MenuRow(title: "Settings", keys: "⌘,") { openSettings(nil) }.keyboardShortcut(",")
                if Updates.shared.found == nil, Updates.shared.available {
                    MenuRow(title: "Check for updates") { NSApp.activate(); Updates.shared.checkNow() }
                        .disabled(!Updates.shared.canCheck)
                }
                MenuRow(title: "Quit Seam", keys: "⌘Q") { NSApp.terminate(nil) }.keyboardShortcut("q")
            }
            .padding(EdgeInsets(top: 5, leading: 6, bottom: 6, trailing: 6))
        }
        .padding(.top, 4)
    }

    private func show(_ text: String) {
        toast = text
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.4) { if toast == text { toast = nil } }
    }

    private func openMain(_ pane: MainWindow.Pane?) {
        controller.requestedMainPane = pane
        NSApp.activate()
        openWindow(id: "main")
    }

    private func openSettings(_ pane: SettingsView.Pane?) {
        controller.requestedSettingsPane = pane
        NSApp.activate() // a menu bar app's windows otherwise open behind the frontmost app
        openSettingsWindow()
    }
}

/// A menu item, drawn as the system draws them in a menu bar extra: the title, its shortcut on the
/// right, and a rounded highlight under the pointer.
private struct MenuRow: View {
    let title: String
    var keys: String?
    let action: () -> Void
    @State private var hovering = false
    @Environment(\.isEnabled) private var enabled

    var body: some View {
        Button(action: action) {
            HStack {
                Text(title).foregroundStyle(enabled ? SeamStyle.Pop.fg : SeamStyle.Pop.fg3)
                Spacer()
                if let keys { Text(keys).foregroundStyle(SeamStyle.Pop.fg3).accessibilityHidden(true) }
            }
            .font(.system(size: 13))
            .padding(.horizontal, 8)
            .frame(height: 24)
            .background(RoundedRectangle(cornerRadius: 6, style: .continuous).fill(hovering && enabled ? SeamStyle.Pop.fill : .clear))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .onHover { hovering = $0 }
    }
}

/// The banner slot's content: what happened, in words, and the one thing to do about it.
private struct BannerView: View {
    enum Banner: Equatable { case error(String), warning(String), update(String) }

    let banner: Banner
    let controller: SeamController

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: symbol).font(.system(size: 14))
            Text(text).fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
            switch banner {
            case .update:
                Button("Update") { NSApp.activate(); Updates.shared.checkNow() }.buttonStyle(PillButtonStyle())
            case .error where controller.needsReset:
                Button("Reset Seam") { ResetPrompt.run(controller) }.buttonStyle(PillButtonStyle())
            default:
                EmptyView()
            }
        }
        .font(.system(size: 12, weight: .semibold))
        .foregroundStyle(ink)
        .padding(.horizontal, 10).padding(.vertical, 8)
        .background(RoundedRectangle(cornerRadius: 12).fill(tint.opacity(0.14)))
        .padding(EdgeInsets(top: 10, leading: 14, bottom: 0, trailing: 14))
        .accessibilityElement(children: .combine)
    }

    private var text: String {
        switch banner {
        case .error(let s), .warning(let s): s
        case .update(let v): "Seam \(v) is available"
        }
    }

    private var symbol: String {
        switch banner {
        case .update: "arrow.down.circle.fill"
        default: "exclamationmark.triangle.fill"
        }
    }

    /// The banner's fill: teal for an update, as in the prototype; amber and red for trouble.
    private var tint: Color {
        switch banner {
        case .update: SeamStyle.teal
        case .warning: SeamStyle.warn
        case .error: SeamStyle.danger
        }
    }

    private var ink: Color {
        switch banner {
        case .update: SeamStyle.tealInk
        case .warning: SeamStyle.warn
        case .error: SeamStyle.danger
        }
    }
}
