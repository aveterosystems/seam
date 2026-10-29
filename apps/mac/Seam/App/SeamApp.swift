// SPDX-License-Identifier: AGPL-3.0-only
import AppKit
import SwiftUI

/// A menu bar app (no Dock icon): the menu shows the paired phone and its battery (board M01).
@main
struct SeamApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    var body: some Scene {
        MenuBarExtra("Seam", systemImage: "rectangle.split.2x1") {
            MenuContent(controller: appDelegate.controller)
        }
    }
}

@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate {
    let controller = SeamController()

    func applicationDidFinishLaunching(_ notification: Notification) {
        controller.start()
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

struct MenuContent: View {
    let controller: SeamController

    var body: some View {
        if let error = controller.startupError {
            Text(error)
            if controller.needsReset {
                Button("Reset Seam…") { ResetPrompt.run(controller) }
            }
        } else {
            if let phone = controller.phone {
                Text(phone.name.isEmpty ? "Your phone" : phone.name)
                if let s = controller.status {
                    Text("Battery \(s.batteryPct)%\(s.charging ? " · charging" : "")")
                } else {
                    Text("Waiting for battery status…")
                }
            }
            Text(controller.connected ? "Connected · encrypted" : "Not connected")
            if let warning = controller.warning {
                Label(warning, systemImage: "exclamationmark.triangle")
            }
            Divider()
            Button(controller.phone == nil ? "Pair a phone…" : "Pair new phone…") { PairingWindow.show(controller) }
        }
        Divider()
        Button("Quit Seam") { NSApplication.shared.terminate(nil) }
            .keyboardShortcut("q")
    }
}
