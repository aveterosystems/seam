// SPDX-License-Identifier: AGPL-3.0-only
import SeamCore
import SwiftUI

/// M0 shell: a menu bar app that proves the Rust core loads. The real menu bar popover
/// (design board M01) and the feature registry arrive in M1.
@main
struct SeamApp: App {
    private let coreStatus: String = {
        do {
            let device = try SeamDevice(deviceId: "self")
            return "Crypto core loaded · identity key \(device.identityKey().count) bytes"
        } catch {
            return "Crypto core failed to load: \(error)"
        }
    }()

    var body: some Scene {
        MenuBarExtra("Seam", systemImage: "rectangle.split.2x1") {
            Text(coreStatus)
            Divider()
            Button("Quit Seam") { NSApplication.shared.terminate(nil) }
                .keyboardShortcut("q")
        }
    }
}
