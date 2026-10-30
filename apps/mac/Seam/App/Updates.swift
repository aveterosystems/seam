// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import Observation
import Sparkle

/// In-app updates through Sparkle: as often as the user picks (a day by default) if the user agrees
/// (Sparkle asks once), and only in signed Release builds. Debug builds have no feed and never replace
/// themselves with a release. Sparkle installs an update only if the feed and the archive are signed
/// with Seam's update key, which is the root of trust for Mac updates, and only when the user says so:
/// one click on Install Update, then Seam downloads, installs and relaunches without asking again.
/// An update found in the background doesn't open a window: the menu bar icon turns teal and the
/// menu offers it.
@MainActor @Observable
final class Updates: NSObject, SPUStandardUserDriverDelegate, SPUUpdaterDelegate {
    static let shared = Updates()

    #if DEBUG
    let available = false
    #else
    let available = true
    #endif

    /// The check intervals Settings offers, shortest first.
    static let intervals: [TimeInterval] = [1800, 3600, 86400, 604_800]

    /// The version of a newer release Sparkle found, until it's installed or skipped.
    private(set) var found: String?

    @ObservationIgnored private lazy var driver = OneClickDriver(hostBundle: .main, delegate: self)
    @ObservationIgnored private lazy var updater = SPUUpdater(hostBundle: .main, applicationBundle: .main, userDriver: driver, delegate: self)

    func start() {
        guard available else { return }
        try? updater.start() // if it can't start, Check Now stays disabled
        // Sparkle schedules no more often than hourly: Seam adds the half-hour checks itself
        Task {
            while true {
                try? await Task.sleep(for: .seconds(1800))
                if checksAutomatically && checkInterval < 3600 && canCheck { updater.checkForUpdatesInBackground() }
            }
        }
    }

    /// The Settings switch: Sparkle keeps it in Seam's defaults. A development build saves it but never checks.
    var checksAutomatically: Bool {
        get { updater.automaticallyChecksForUpdates }
        set { updater.automaticallyChecksForUpdates = newValue }
    }

    /// Seconds between checks, one of `intervals`; Sparkle keeps it in Seam's defaults.
    var checkInterval: TimeInterval {
        get { updater.updateCheckInterval }
        set { updater.updateCheckInterval = newValue }
    }

    var canCheck: Bool { available && updater.canCheckForUpdates }

    func checkNow() { updater.checkForUpdates() }

    // A menu bar app: a background check never steals focus. Seam shows the update in the menu bar
    // instead, and Update there opens Sparkle's window.
    nonisolated var supportsGentleScheduledUpdateReminders: Bool { true }
    nonisolated func standardUserDriverShouldHandleShowingScheduledUpdate(_ update: SUAppcastItem, andInImmediateFocus immediateFocus: Bool) -> Bool { false }

    func updater(_ updater: SPUUpdater, didFindValidUpdate item: SUAppcastItem) { found = item.displayVersionString }
    func updaterDidNotFindUpdate(_ updater: SPUUpdater) { found = nil }
    func updater(_ updater: SPUUpdater, userDidMake choice: SPUUserUpdateChoice, forUpdate updateItem: SUAppcastItem, state: SPUUserUpdateState) {
        if choice == .skip { found = nil }
    }
}

/// Sparkle's own windows, minus the second question: it asks "Install and Relaunch?" only after the
/// user chose Install Update, so the answer is already yes.
private final class OneClickDriver: SPUStandardUserDriver {
    override func showReady(toInstallAndRelaunch installUpdateHandler: @escaping (SPUUserUpdateChoice) -> Void) {
        installUpdateHandler(.install)
    }
}
