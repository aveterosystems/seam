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
        // First launch, or nothing paired yet: go straight to the QR code
        if controller.phone == nil, controller.startupError == nil { PairingWindow.show(controller) }
    }
}

struct MenuContent: View {
    let controller: SeamController

    var body: some View {
        if let error = controller.startupError {
            Text(error)
        } else if let phone = controller.phone {
            Text(phone.name.isEmpty ? "Your phone" : phone.name)
            if let s = controller.status {
                Text("Battery \(s.batteryPct)%\(s.charging ? " · charging" : "")")
            } else {
                Text("Waiting for battery status…")
            }
        } else {
            Button("Pair a phone…") { PairingWindow.show(controller) }
        }
        Text(controller.connected ? "Connected · encrypted" : "Not connected")
        Divider()
        Button("Pair new phone…") { PairingWindow.show(controller) }
        Button("Quit Seam") { NSApplication.shared.terminate(nil) }
            .keyboardShortcut("q")
    }
}
