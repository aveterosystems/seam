// SPDX-License-Identifier: AGPL-3.0-only
import AppKit
import Observation
import OSLog
import SeamCore
import SeamProtocol
import SeamSession

/// The app's one live object. Seam itself runs in the Rust core's `Computer` (seam-computer:
/// delivery, the pause, paths, pairing and the features' logic); this supplies the Mac's side of
/// it, keeps the settings, and turns its snapshots into what the screens show. The screens watch
/// the features (`clipboard`, `notifications`, `status`) directly.
@MainActor @Observable
final class SeamController {
    /// The connection switches (Settings › Connection), as the core keeps them. At least one stays on.
    private(set) var wifiOn = true
    private(set) var torOn = true
    /// Tor's own state, for the link's words (connecting, blocked, clock).
    private(set) var torState: TorState = .off
    private(set) var phone: Phone?
    private(set) var startupError: String?
    /// Saved data exists but can't be opened: the menu offers a reset instead of pairing.
    private(set) var needsReset = false
    /// Something the user should see in the menu (a refused login, a save that failed).
    private(set) var warning: String?
    private(set) var pairing: PairingState = .idle
    let clipboard = ClipboardFeature()
    let notifications = NotificationsFeature()
    let status = StatusFeature()
    /// Clipboard both ways (menu toggle, on by default).
    private(set) var clipboardSync = true
    /// Set to open Settings at a pane (the popover's "Notification settings"); Settings clears it.
    var requestedSettingsPane: SettingsView.Pane?
    /// Set to open the main window at a pane (the popover's links); the window clears it.
    var requestedMainPane: MainWindow.Pane?
    /// How the link looks everywhere it's shown: the stitch, the icon and the words.
    private(set) var link = LinkLook.away
    /// Paused on the phone (only the phone pauses and resumes); the link says until when.
    private(set) var paused = false
    /// When the link last went from connected to not, for "Last seen 12 min ago". This run only.
    private(set) var lastSeen: Date?
    /// Envelopes for the phone not yet delivered, for the popover's queue bar.
    private(set) var waiting = 0
    /// A Reconnect under way, until the phone is back or it gives up. The button waits for it (SR-55-03).
    private(set) var reconnecting: Reconnect?
    /// When this phone was paired, if it was paired since Seam started keeping the date.
    private(set) var pairedAt: Date?

    @ObservationIgnored private var computer: Computer?
    /// The phone's notifications as banners: one for the app's life.
    @ObservationIgnored private let banners = PhoneNotifications()
    @ObservationIgnored private var last: ComputerSnapshot?
    /// Told once the saved data has opened (or couldn't), for the launch's first window.
    @ObservationIgnored private var whenOpen: ((SeamController) -> Void)?
    /// The opening `whenOpen` waits for, and whether it's a reset (for the words if it fails).
    @ObservationIgnored private var waitingFor: (opens: UInt64, reset: Bool) = (1, false)
    @ObservationIgnored private var wakeObserver: NSObjectProtocol?
    /// Tells the core when this Mac's battery changes (once per app run).
    @ObservationIgnored private var batteryWatch: CFRunLoopSource?
    private let log = Logger(subsystem: "com.avetero.seam", category: "session")

    /// The phone can be reached now, on a path both ends allow.
    var connected: Bool { link.connected }

    /// Starts Seam; `whenOpen` hears once the saved data has opened (or couldn't).
    func start(whenOpen: @escaping (SeamController) -> Void = { _ in }) {
        guard computer == nil else { return }
        self.whenOpen = whenOpen
        let folder: URL
        do {
            folder = try Self.dataFolder()
        } catch {
            startupError = "Seam couldn't start: \(error.localizedDescription)"
            return whenOpen(self)
        }
        let settings = ComputerSettings(
            folder: folder.path, appVersion: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "",
            platform: "macos", legacy: Self.legacy()
        )
        let computer = Computer(
            settings: settings, keys: KeychainStore(), pasteboard: SystemPasteboard(), notifications: BannerCentre(banners),
            battery: MacBatteryReader(), sameWifi: MacSameWifi(), listener: Listener(self)
        )
        self.computer = computer
        clipboard.computer = computer
        notifications.computer = computer
        banners.start { [weak self] action in self?.notifications.act(action) }
        watchSystem()
    }

    /// Only after the user agreed: a new identity, the old files kept aside.
    func reset(whenOpen: @escaping (SeamController) -> Void = { _ in }) {
        self.whenOpen = whenOpen
        waitingFor = ((last?.opens ?? 0) + 1, true)
        computer?.reset()
    }

    /// What this app kept in UserDefaults before the core kept it, copied across once. A pause
    /// "until resumed" was saved as `distantFuture`.
    private static func legacy() -> LegacyPreferences {
        let d = UserDefaults.standard
        let paused = d.double(forKey: "pausedUntil")
        let pausedMs: Int64? = paused <= 0 ? nil : paused >= Date.distantFuture.timeIntervalSince1970 ? .max : Int64(paused * 1000)
        return LegacyPreferences(
            wifi: d.object(forKey: "transport.wifi") as? Bool, tor: d.object(forKey: "transport.tor") as? Bool,
            clipboardSync: d.object(forKey: "clipboardSync") as? Bool, pausedUntilMs: pausedMs,
            pairedAtMs: (d.object(forKey: "pairedAt") as? Date).map { Int64($0.timeIntervalSince1970 * 1000) }
        )
    }

    // MARK: Snapshots

    fileprivate func show(_ s: ComputerSnapshot) {
        switch s.health {
        case .starting: return
        case .ready:
            needsReset = false
            startupError = nil
        case .unreadable:
            needsReset = true
            startupError = "Seam's data on this Mac can't be opened."
        case .failed(let detail):
            startupError = waitingFor.reset ? "Seam couldn't reset: \(detail)" : "Seam couldn't start: \(detail)"
        }
        last = s
        let session = s.session
        let newPairing = Self.pairing(session?.pairing)
        if case .confirm = newPairing, pairing != newPairing { NSApp.activate() } // the safety code needs the user's eyes now
        if case .waiting(let qr, _) = newPairing, pairing != newPairing { Self.handOver(qr) }
        if pairing != newPairing { pairing = newPairing }
        if phone != session?.phone { phone = session?.phone }
        pairedAt = session?.phone?.pairedAt.map(Self.date)
        lastSeen = session?.lastSeenMs.map(Self.date)
        waiting = Int(session?.waiting ?? 0)
        paused = session?.pausedUntilMs != nil
        warning = session?.warning.map(Self.words) ?? (s.pairAgain ? "Seam now connects through Tor. Pair your phone again." : nil)
        torState = s.torState
        reconnecting = session?.reconnect
        if let session { (wifiOn, torOn) = (session.wifi, session.tor) }
        clipboardSync = s.features?.clipboard.syncOn ?? clipboardSync
        clipboard.show(s.features?.clipboard)
        notifications.show(s.features?.notifications)
        status.show(s.features?.status)
        refreshLink()
        if let whenOpen, s.opens >= waitingFor.opens {
            self.whenOpen = nil
            whenOpen(self)
        }
    }

    private static func date(_ ms: Int64) -> Date {
        ms == .max ? .distantFuture : Date(timeIntervalSince1970: TimeInterval(ms) / 1000)
    }

    fileprivate func heard(_ event: Event) {
        switch event {
        case .problem(let problem): log.error("\(String(describing: problem), privacy: .public)") // a code, never contents
        case .clipFromPhone: announce("Copied from your phone")
        case .notificationFromPhone(let app): announce("Notification from \(app)")
        }
    }

    private func refreshLink() {
        guard let session = last?.session else { return }
        let until = session.pausedUntilMs.map(Self.date)
        let new = LinkLook.of(route: session.route, allowed: session.allowed, tor: torState, pausedUntil: until, reconnect: reconnecting)
        if new != link { link = new }
    }

    private static func pairing(_ step: PairingStep?) -> PairingState {
        switch step {
        case nil, .idle?: .idle
        case .waiting(let qr, let ms)?: .waiting(qr: qr, deadline: PairingDeadline(expiresAt: Date(timeIntervalSince1970: TimeInterval(ms) / 1000)))
        case .confirm(let r)?: .confirm(r)
        case .confirming(let r)?: .confirming(r)
        case .paired(let name)?: .paired(name: name)
        case .expired?: .expired
        case .failed(let reason)?: .failed(words(reason))
        }
    }

    private static func words(_ f: PairingFailure) -> String {
        switch f {
        case .unreachable: "Couldn't reach your phone. Show a new code and scan it again."
        case .notSaved: "Seam couldn't save the pairing on this Mac. Show a new code and scan it again."
        case .didntFinish: "Pairing didn't finish. Show a new code and scan it again."
        }
    }

    private static func words(_ w: Warning) -> String {
        switch w {
        case .saveFailed: "Seam couldn't save to disk. Check there's free space; messages wait until it can."
        case .untrustedIdentity: "A device tried to talk to this Mac with keys that don't match your paired phone. It was ignored."
        }
    }

    /// Debug device tests: hand the code to an emulator, which has no camera to scan with.
    private static func handOver(_ qr: String) {
        #if DEBUG
        if let path = ProcessInfo.processInfo.environment["SEAM_PAIRING_CODE_FILE"] {
            FileManager.default.createFile(atPath: path, contents: Data(qr.utf8), attributes: [.posixPermissions: 0o600])
        }
        #endif
    }

    /// Something new from the phone: the menu bar icon pops once and VoiceOver says what (catalogue 5).
    private func announce(_ what: String) {
        MenuBarPing.ping()
        NSAccessibility.post(element: NSApp as Any, notification: .announcementRequested,
                             userInfo: [.announcement: what, .priority: NSAccessibilityPriorityLevel.high.rawValue])
    }

    /// After sleep the services' descriptors may have lapsed: published again, once per wake. And
    /// the phone hears when this Mac's battery changes.
    private func watchSystem() {
        if batteryWatch == nil {
            batteryWatch = MacBattery.watch { [weak self] in self?.computer?.feature(command: .batteryChanged) }
        }
        guard wakeObserver == nil else { return }
        wakeObserver = NSWorkspace.shared.notificationCenter.addObserver(
            forName: NSWorkspace.didWakeNotification, object: nil, queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated { self?.computer?.woke() }
        }
    }

    // MARK: Phone

    /// "Reconnect" in the popover's ⓘ: the phone is asked to dial again; out of reach, this Mac's
    /// own side starts again and the line says to wake the phone.
    func reconnect() {
        computer?.reconnect() // a second tap while one is under way does nothing (SR-55-03)
    }

    /// "Unpair <phone>": the phone is told and forgotten, its onion service with it.
    func unpairPhone() {
        computer?.unpair()
    }

    /// The code both devices showed at pairing, for re-checking in Settings.
    func phoneCode() -> String? {
        #if DEBUG
        if let demoCode { return demoCode }
        #endif
        return phone?.safetyCode
    }

    func startPairing() {
        computer?.startPairing(name: Host.current().localizedName ?? "Mac")
    }

    /// Pair was clicked. Safe to call twice: only the first call does anything.
    func confirmPairing() {
        computer?.confirmPairing()
    }

    /// Cancel, or the window closed. Does nothing while PairAccept is already on its way.
    func cancelPairing() {
        computer?.cancelPairing()
    }

    // MARK: Clipboard

    func setClipboardSync(_ on: Bool) {
        clipboardSync = on
        if !on { clipboard.show(nil) } // off the screen at once (SR-10-04)
        computer?.feature(command: .setClipboardSync(on: on))
    }

    // MARK: Connection

    /// The core keeps one switch on: turning off the last one comes back on in the next snapshot.
    func setWifi(_ on: Bool) {
        wifiOn = on
        computer?.setWifi(on: on)
    }

    func setTor(_ on: Bool) {
        torOn = on
        computer?.setTor(on: on)
    }

    // MARK: Private

    /// Application Support/Seam. Debug builds keep their own, so they run next to the installed
    /// app without touching it.
    private static func dataFolder() throws -> URL {
        #if DEBUG
        // SEAM_DATA_FOLDER picks another folder, to run a second Debug Mac with its own identity (device tests)
        let name = ProcessInfo.processInfo.environment["SEAM_DATA_FOLDER"].flatMap { $0.contains("/") ? nil : $0 } ?? "Seam Debug"
        #else
        let name = "Seam"
        #endif
        return try FileManager.default
            .url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
            .appendingPathComponent(name, isDirectory: true)
    }

    #if DEBUG
    @ObservationIgnored private var demoCode: String?

    /// SEAM_DEMO (Demo.swift): made-up phone state for looking at every state without a phone.
    /// Nothing is saved or sent: Seam never starts.
    func showDemo(phone: Phone, status: Seam_V1_Status, look: LinkLook, waiting: Int, settings: Seam_V1_NotificationSettings,
                  notifications: [Seam_V1_Notification], clips: [Clip]) {
        self.phone = phone
        self.status.showDemo(status, at: Date().addingTimeInterval(-12 * 60))
        if link.connected, !look.connected { lastSeen = Date() }
        link = look
        self.waiting = waiting
        self.notifications.showDemo(settings: settings, shown: notifications)
        clipboard.showDemo(clips: clips)
        demoCode = "482 913"
        pairedAt = Calendar.current.date(from: DateComponents(year: 2026, month: 9, day: 14))
    }
    #endif
}

/// The core's listener: called on its threads, handed to the main thread in order.
private final class Listener: ComputerListener, @unchecked Sendable {
    weak var controller: SeamController?

    @MainActor init(_ controller: SeamController) {
        self.controller = controller
    }

    func onSnapshot(snapshot: ComputerSnapshot) {
        DispatchQueue.main.async { MainActor.assumeIsolated { self.controller?.show(snapshot) } }
    }

    func onEvent(event: Event) {
        DispatchQueue.main.async { MainActor.assumeIsolated { self.controller?.heard(event) } }
    }
}
