// SPDX-License-Identifier: AGPL-3.0-only
#if DEBUG
import AppKit
import SeamCore
import SeamProtocol
import SeamSession
import SwiftUI

/// Debug builds only: `SEAM_DEMO=online|tor|offline|low|paused` shows made-up phone state in the
/// popover and Settings, each in a plain window, so every state can be looked at (and captured)
/// without a phone. `SEAM_DEMO_PANE` picks the Settings pane and `SEAM_DEMO_LOOK=dark` the theme.
/// The session never starts, so nothing is saved or sent.
@MainActor
enum Demo {
    private static var windows: [NSWindow] = []

    static func run(_ mode: String, _ controller: SeamController) {
        let env = ProcessInfo.processInfo.environment
        if env["SEAM_DEMO_LOOK"] == "dark" { NSApp.appearance = NSAppearance(named: .darkAqua) }
        if env["SEAM_DEMO_LOOK"] == "light" { NSApp.appearance = NSAppearance(named: .aqua) }
        // SEAM_DEMO_FLIP=<seconds>: switch theme then, to watch the crossfade
        if let flip = env["SEAM_DEMO_FLIP"].flatMap(Double.init) {
            DispatchQueue.main.asyncAfter(deadline: .now() + flip) {
                let dark = NSApp.effectiveAppearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua
                NSApp.appearance = NSAppearance(named: dark ? .aqua : .darkAqua)
            }
        }
        let now = Date()
        let minute: TimeInterval = 60
        let phone = Phone(id: "demo", name: "Pixel 9", safetyCode: "482 913", pairedAt: nil)
        let status = Seam_V1_Status.with {
            $0.batteryPct = mode == "low" ? 14 : 82
            $0.charging = mode == "online"
            $0.network = mode == "tor" ? "cellular" : "wifi"
            $0.dnd = mode == "online"
        }
        let settings = Seam_V1_NotificationSettings.with { s in
            s.enabled = true
            s.dismissBoth = true
            if mode == "paused" { s.pausedUntil = Int64(now.addingTimeInterval(3600).timeIntervalSince1970 * 1000) }
            let apps: [(String, Seam_V1_NotificationMode, Bool)] = [
                ("WhatsApp", .on, true), ("Messages", .on, true), ("Slack", .on, true), ("Gmail", .titleOnly, false),
                ("Calendar", .on, false), ("Monzo", .titleOnly, false), ("Uber", .off, false), ("Instagram", .off, true),
                ("Duolingo", .off, false), ("Spotify", .off, false),
            ]
            s.apps = apps.map { a in .with { $0.appID = "demo." + a.0.lowercased(); $0.appName = a.0; $0.mode = a.1; $0.canReply = a.2 } }
        }
        let notifications: [Seam_V1_Notification] = [
            ("WhatsApp", "Sam", "on my way, 10 min", true, 0.0),
            ("Gmail", "Invoice #2041", "from Northwind", false, 12),
        ].enumerated().map { i, n in .with {
            $0.key = "demo\(i)"; $0.appID = "demo." + n.0.lowercased(); $0.appName = n.0; $0.title = n.1; $0.text = n.2
            $0.canReply = n.3; $0.postedAt = Int64(now.addingTimeInterval(-n.4 * minute).timeIntervalSince1970 * 1000)
        } }
        let clips = [
            Clip(text: "https://github.com/aveterosystems/seam/pull/37", at: now.addingTimeInterval(-2 * minute), fromPhone: true),
            Clip(text: "Meeting moved to 3pm, room 4B", at: now.addingTimeInterval(-40 * minute), fromPhone: true),
        ]
        let look: LinkLook = mode == "tor" ? .throughTor : .sameWifi
        controller.showDemo(phone: phone, status: status, look: look, waiting: 0, settings: settings, notifications: notifications, clips: clips)
        if mode == "offline" || mode == "low" {
            // Connected, then not: the popover says when it was last seen
            controller.showDemo(phone: phone, status: status, look: .away, waiting: 3, settings: settings, notifications: notifications, clips: clips)
        }
        controller.requestedSettingsPane = env["SEAM_DEMO_PANE"].flatMap { name in SettingsView.Pane.allCases.first { "\($0)" == name } }

        // The popover in a plain window, and from it the real Settings window (its scene, its toolbar)
        let popover = open(PopoverView(controller: controller).background(OpenSettings()), at: NSPoint(x: 80, y: 120))
        print("SEAM_DEMO_WINDOW \(popover.windowNumber) popover")
        // SEAM_DEMO_REPLAY=<seconds>: open the popover again then (a fresh view in the same window), to watch it open
        if let replay = env["SEAM_DEMO_REPLAY"].flatMap(Double.init) {
            DispatchQueue.main.asyncAfter(deadline: .now() + replay) {
                popover.contentViewController = NSHostingController(rootView: PopoverView(controller: controller))
            }
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 1) {
            NSApp.activate(ignoringOtherApps: true) // captured active, as people see it
            NSApp.windows.first { $0.isVisible && $0 !== popover && $0.frame.width > 400 }?.makeKeyAndOrderFront(nil)
            for w in NSApp.windows where w.isVisible && w !== popover && w.frame.width > 400 { print("SEAM_DEMO_WINDOW \(w.windowNumber) settings") }
            fflush(stdout)
        }
    }

    /// Borderless, like the popover, but it takes clicks and keys.
    private final class KeyWindow: NSWindow {
        override var canBecomeKey: Bool { true }
    }

    private struct OpenSettings: View {
        @Environment(\.openWindow) private var openWindow
        var body: some View {
            Color.clear.onAppear {
                openWindow(id: "settings")
                if ProcessInfo.processInfo.environment["SEAM_DEMO_MAIN"] != nil { openWindow(id: "main") }
            }
        }
    }

    private static func open<V: View>(_ view: V, at origin: NSPoint) -> NSWindow {
        let window = KeyWindow(contentViewController: NSHostingController(rootView: view))
        window.styleMask = [.borderless]
        window.backgroundColor = .windowBackgroundColor
        window.setFrameOrigin(origin)
        // SEAM_DEMO_FLOAT: above other windows, so macOS keeps drawing it while it's recorded
        if ProcessInfo.processInfo.environment["SEAM_DEMO_FLOAT"] != nil { window.level = .floating }
        window.makeKeyAndOrderFront(nil)
        windows.append(window)
        return window
    }
}
#endif
