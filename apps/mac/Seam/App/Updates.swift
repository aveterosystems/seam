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
    /// Settings' Check now is running.
    private(set) var checking = false
    /// What the last Check now found when it found nothing new: up to date, or it couldn't ask.
    private(set) var checkFailed: Bool?

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

    /// "once a day": how often an interval is, in words.
    static func often(_ interval: TimeInterval) -> String {
        switch interval {
        case ..<3600: "every 30 minutes"
        case ..<86400: "once an hour"
        case ..<604_800: "once a day"
        default: "once a week"
        }
    }

    var canCheck: Bool { available && updater.canCheckForUpdates }

    func checkNow() { updater.checkForUpdates() }

    /// When Sparkle last asked GitHub.
    var lastChecked: Date? { available ? updater.lastUpdateCheckDate : nil }

    /// Settings' Check now: asks without opening a window and shows the answer in place. A newer
    /// release still installs only through `checkNow` (Sparkle's window, the user's click).
    func checkQuietly() {
        guard canCheck, !checking else { return }
        checking = true
        checkFailed = nil
        updater.checkForUpdateInformation()
    }

    // A menu bar app: a background check never steals focus. Seam shows the update in the menu bar
    // instead, and Update there opens Sparkle's window.
    nonisolated var supportsGentleScheduledUpdateReminders: Bool { true }
    nonisolated func standardUserDriverShouldHandleShowingScheduledUpdate(_ update: SUAppcastItem, andInImmediateFocus immediateFocus: Bool) -> Bool { false }

    func updater(_ updater: SPUUpdater, didFindValidUpdate item: SUAppcastItem) {
        found = item.displayVersionString
        checking = false
    }
    func updaterDidNotFindUpdate(_ updater: SPUUpdater) {
        found = nil
        if checking { checkFailed = false }
        checking = false
    }
    func updater(_ updater: SPUUpdater, didFinishUpdateCycleFor updateCheck: SPUUpdateCheck, error: (any Error)?) {
        // Still checking: neither callback came. "No update" is an error here too, and means up to date
        if checking { checkFailed = error.map { ($0 as NSError).code != Int(SUError.noUpdateError.rawValue) } ?? false }
        checking = false
    }
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
