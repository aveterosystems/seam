// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import Sparkle

/// In-app updates through Sparkle: once a day, and only in signed Release builds. Debug builds have
/// no feed and never replace themselves with a release. Sparkle installs an update only if the feed
/// and the archive are signed with Seam's update key and the new app is signed by the same
/// certificate as this one.
@MainActor
final class Updates: NSObject, SPUStandardUserDriverDelegate {
    static let shared = Updates()

    #if DEBUG
    let available = false
    #else
    let available = true
    #endif

    private lazy var controller = SPUStandardUpdaterController(
        startingUpdater: available, updaterDelegate: nil, userDriverDelegate: self,
    )

    func start() { _ = controller }

    /// The Settings switch: Sparkle keeps it in Seam's defaults.
    var checksAutomatically: Bool {
        get { available && controller.updater.automaticallyChecksForUpdates }
        set { controller.updater.automaticallyChecksForUpdates = newValue }
    }

    var canCheck: Bool { available && controller.updater.canCheckForUpdates }

    func checkNow() { controller.checkForUpdates(nil) }

    // A menu bar app: let Sparkle show a found update at a good moment instead of stealing focus
    nonisolated var supportsGentleScheduledUpdateReminders: Bool { true }
}
