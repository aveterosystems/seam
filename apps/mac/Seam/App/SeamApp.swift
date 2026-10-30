// SPDX-License-Identifier: AGPL-3.0-only
import AppKit
import SwiftUI

/// A menu bar app (no Dock icon): the popover (board M01) and Settings (board M03, ⌘,).
@main
struct SeamApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    var body: some Scene {
        MenuBarExtra {
            PopoverView(controller: appDelegate.controller)
        } label: {
            Image(nsImage: SeamMark.image(connected: appDelegate.controller.connected && appDelegate.controller.phone != nil))
        }
        .menuBarExtraStyle(.window)

        // Board M03 draws its own title and sidebar, so this is a plain window with a hidden title
        // bar rather than the system Settings window (which centres a title and adds a toolbar)
        Window("Seam Settings", id: "settings") {
            SettingsView(controller: appDelegate.controller)
        }
        .windowStyle(.hiddenTitleBar)
        .windowResizability(.contentSize)
        .commands {
            CommandGroup(replacing: .appSettings) {
                SettingsCommand()
            }
        }
    }
}

@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate {
    let controller = SeamController()

    func applicationDidFinishLaunching(_ notification: Notification) {
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

/// Asks before unpairing: both devices forget each other's keys.
@MainActor
enum UnpairPrompt {
    static func run(_ controller: SeamController) {
        let name = controller.phone?.name ?? "your phone"
        let alert = NSAlert()
        alert.messageText = "Unpair \(name)?"
        alert.informativeText = "This Mac stops syncing with \(name) and forgets its keys. \(name) forgets this Mac too: now if it's online, otherwise it's asked to pair again when it next connects. You can pair again any time."
        alert.addButton(withTitle: "Cancel")
        alert.addButton(withTitle: "Unpair")
        alert.buttons[1].hasDestructiveAction = true
        NSApp.activate()
        guard alert.runModal() == .alertSecondButtonReturn else { return }
        controller.unpairPhone()
    }
}

/// ⌘, opens Seam's Settings window.
private struct SettingsCommand: View {
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        Button("Settings…") {
            NSApp.activate()
            openWindow(id: "settings")
        }
        .keyboardShortcut(",")
    }
}
