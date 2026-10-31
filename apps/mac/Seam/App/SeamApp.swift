// SPDX-License-Identifier: AGPL-3.0-only
import AppKit
import SeamSession
import SwiftUI

/// A menu bar app (no Dock icon): the popover (board M01) and Settings (board M03, ⌘,).
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

/// Settings › Connection › Relay: a relay link to use instead of Seam's relay (decision 0007).
@MainActor
enum RelayPrompt {
    static func run(_ controller: SeamController) {
        let alert = NSAlert()
        alert.messageText = "Use your own relay"
        alert.informativeText = "Paste the relay link from your Seam relay (it starts seam-relay://). It contains your relay's key, so keep it private. "
            + (controller.phone.map { "\($0.name.isEmpty ? "Your phone" : $0.name) is unpaired, and pairs again through the new relay." } ?? "")
        let field = NSTextField(frame: NSRect(x: 0, y: 0, width: 340, height: 24))
        field.placeholderString = "seam-relay://relay.example.com#…"
        alert.accessoryView = field
        alert.addButton(withTitle: "Cancel")
        alert.addButton(withTitle: "Use This Relay")
        if controller.phone != nil { alert.buttons[1].hasDestructiveAction = true }
        alert.window.initialFirstResponder = field
        NSApp.activate()
        guard alert.runModal() == .alertSecondButtonReturn else { return }
        guard let link = RelayLink.parse(field.stringValue, allowInsecure: Store.allowInsecureRelay) else {
            let error = NSAlert()
            error.messageText = "That isn't a relay link"
            error.informativeText = "It should look like seam-relay://relay.example.com#key, from your relay's setup. Nothing was changed."
            error.runModal()
            return
        }
        controller.useRelay(link)
    }

    static func reset(_ controller: SeamController) {
        let alert = NSAlert()
        alert.messageText = "Go back to Seam's relay?"
        alert.informativeText = controller.phone == nil ? "New pairings go through Seam's relay." : "Your phone is unpaired, and pairs again through Seam's relay."
        alert.addButton(withTitle: "Cancel")
        alert.addButton(withTitle: "Use Seam's Relay")
        NSApp.activate()
        guard alert.runModal() == .alertSecondButtonReturn else { return }
        controller.useRelay(nil)
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
