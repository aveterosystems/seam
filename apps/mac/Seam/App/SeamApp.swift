// SPDX-License-Identifier: AGPL-3.0-only
import AppKit
import SeamSession
import SwiftUI

/// A menu bar app (no Dock icon): the popover (board M01), the main window (⌘O) and Settings
/// (board M03, ⌘,).
@main
struct SeamApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    var body: some Scene {
        MenuBarExtra {
            PopoverView(controller: appDelegate.controller)
        } label: {
            Image(nsImage: SeamMark.image(connected: appDelegate.controller.connected && appDelegate.controller.phone != nil, paused: appDelegate.controller.paused, update: Updates.shared.found != nil))
        }
        .menuBarExtraStyle(.window)

        Window("Seam", id: "main") {
            MainWindow(controller: appDelegate.controller)
        }
        .windowStyle(.hiddenTitleBar)
        .defaultSize(width: 900, height: 620)

        // Board M03 draws its own title and sidebar (SidebarWindow), so the window's title bar is hidden
        Window("Seam Settings", id: "settings") {
            SettingsView(controller: appDelegate.controller)
        }
        .windowStyle(.hiddenTitleBar)
        .windowResizability(.contentMinSize)
        .commands {
            CommandGroup(replacing: .appSettings) {
                SettingsCommand()
            }
            CommandGroup(replacing: .newItem) {
                OpenMainCommand()
            }
        }
    }
}

@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate {
    let controller = SeamController()

    func applicationDidFinishLaunching(_ notification: Notification) {
        AppearanceFade.start()
        #if DEBUG
        if let demo = ProcessInfo.processInfo.environment["SEAM_DEMO"] { return Demo.run(demo, controller) }
        #endif
        controller.start()
        Updates.shared.start()
        if controller.needsReset {
            ResetPrompt.run(controller)
        } else if controller.phone == nil, controller.startupError == nil {
            // First launch, or nothing paired yet: go straight to the QR code
            PairingWindow.show(controller)
        }
    }
}

/// Asks before throwing anything away: a reset means a new identity, and pairing again.
@MainActor
enum ResetPrompt {
    static func run(_ controller: SeamController) {
        let alert = NSAlert()
        alert.messageText = "Seam's data on this Mac can't be opened. Reset?"
        alert.informativeText = "Seam will start over with new keys, and you'll pair your phone again. The unreadable files are kept in Application Support/Seam."
        alert.alertStyle = .warning
        alert.addButton(withTitle: "Not Now")
        alert.addButton(withTitle: "Reset")
        NSApp.activate()
        guard alert.runModal() == .alertSecondButtonReturn else { return }
        controller.reset()
        if controller.startupError == nil { PairingWindow.show(controller) }
    }
}

/// ⌘O opens the main window.
private struct OpenMainCommand: View {
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        Button("Open Seam") {
            NSApp.activate()
            openWindow(id: "main")
        }
        .keyboardShortcut("o")
    }
}

/// ⌘, opens Seam's Settings window.
private struct SettingsCommand: View {
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        Button("Settings") {
            NSApp.activate()
            openWindow(id: "settings")
        }
        .keyboardShortcut(",")
    }
}
