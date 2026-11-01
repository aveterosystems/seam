// SPDX-License-Identifier: AGPL-3.0-only
import AppKit
import Observation
import OSLog
import SeamCore
import SeamProtocol
import SeamSession

/// The app's one live object: opens the store, runs the connections to the phone, and shows what
/// the session (SeamSession's `Session`, which owns delivery, features and pairing) reports.
@MainActor @Observable
final class SeamController {
    /// The connection switches (Settings › Connection; decision 0006). At least one stays on.
    private(set) var wifiOn = UserDefaults.standard.object(forKey: "transport.wifi") as? Bool ?? true
    private(set) var torOn = UserDefaults.standard.object(forKey: "transport.tor") as? Bool ?? true
    /// Tor's own state, for the link's words (connecting, blocked, clock).
    private(set) var torState: TorState = .off
    private(set) var phone: PeerInfo?
    private(set) var status: Seam_V1_Status?
    private(set) var startupError: String?
    /// Saved data exists but can't be opened: the menu offers a reset instead of pairing.
    private(set) var needsReset = false
    /// Something the user should see in the menu (a refused login, a save that failed).
    private(set) var warning: String?
    private(set) var pairing: PairingState = .idle
    /// Clips that crossed either way in the last day, newest first, for the main window (memory only).
    private(set) var clips: [Clip] = []
    /// The phone's notifications that are still up, newest first, for the main window (memory only).
    private(set) var phoneNotifications: [Seam_V1_Notification] = []
    /// The phone's notification settings as it last sent them (the phone owns them). Memory only.
    private(set) var notificationSettings: Seam_V1_NotificationSettings?
    /// Set to open Settings at a pane (the popover's "Notification settings"); Settings clears it.
    var requestedSettingsPane: SettingsView.Pane?
    /// Set to open the main window at a pane (the popover's links); the window clears it.
    var requestedMainPane: MainWindow.Pane?
    /// Phone apps' icons, from the phone when asked for. Memory only.
    private(set) var appIcons: [String: NSImage] = [:]
    /// Clipboard both ways (menu toggle, on by default).
    private(set) var clipboardSync = UserDefaults.standard.object(forKey: "clipboardSync") as? Bool ?? true
    /// How the link looks everywhere it's shown: the stitch, the icon and the words.
    private(set) var link = LinkLook.away
    /// Paused on the phone (only the phone pauses and resumes); the link says until when.
    private(set) var paused = false
    /// When the link last went from connected to not, for "Last seen 12 min ago". This run only.
    private(set) var lastSeen: Date?
    /// When the phone's status arrived, for "as of 14:02" once it's gone.
    private(set) var statusAt: Date?
    /// Envelopes for the phone not yet delivered, for the popover's queue bar.
    private(set) var waiting = 0
    /// Counts notifications arriving from the phone, so the bell rings once for each.
    private(set) var notificationArrivals = 0
    /// When this phone was paired, if it was paired since Seam started keeping the date (a preference, not a secret).
    private(set) var pairedAt = UserDefaults.standard.object(forKey: "pairedAt") as? Date {
        didSet { UserDefaults.standard.set(pairedAt, forKey: "pairedAt") }
    }

    @ObservationIgnored private var store: Store?
    @ObservationIgnored private(set) var session: Session?
    @ObservationIgnored private var updatesTask: Task<Void, Never>?
    /// Runs Same Wi‑Fi's listener and the Mac's onion services as the switches allow (SeamSession `Paths`).
    @ObservationIgnored private(set) var paths: Paths?
    /// Tor, once per app run: started when first needed and never put to sleep (the phone reaches the Mac).
    @ObservationIgnored private var tor: SeamTor?
    @ObservationIgnored private let notifications = PhoneNotifications()
    @ObservationIgnored private var iconPNGs: [String: Data] = [:]
    /// A pairing from before Tor was dropped on opening: shown until the phone pairs again.
    @ObservationIgnored private var pairAgainWarning: String?
    @ObservationIgnored private var lastState: Session.State?
    @ObservationIgnored private var wakeObserver: NSObjectProtocol?
    /// Tells the phone when this Mac's battery changes (once per app run).
    @ObservationIgnored private var batteryWatch: CFRunLoopSource?
    private let log = Logger(subsystem: "com.avetero.seam", category: "session")

    /// The phone can be reached now, on a path both ends allow.
    var connected: Bool { link.connected }

    func start() {
        guard store == nil else { return }
        do {
            begin(try Store.open())
        } catch is Store.Unreadable {
            needsReset = true
            startupError = "Seam's data on this Mac can't be opened."
            log.error("saved state unreadable")
        } catch {
            startupError = "Seam couldn't start: \(error.localizedDescription)"
            log.error("startup failed")
        }
    }

    /// Only after the user agreed: a new identity and group, the old files kept aside.
    func reset() {
        do {
            begin(try Store.reset())
            pairedAt = nil
            needsReset = false
            startupError = nil
        } catch {
            startupError = "Seam couldn't reset: \(error.localizedDescription)"
            log.error("reset failed")
        }
    }

    private func begin(_ store: Store) {
        self.store = store
        pairAgainWarning = store.pairAgain ? "Seam now connects through Tor. Pair your phone again." : nil
        paths?.stop()
        if let old = session { Task { await old.stop() } }
        let session = Session(
            device: store.device,
            storage: SessionStorage(
                saveState: { try store.save() }, outbox: store.outboxFile,
                // The phone's pause, so a restarted Mac still shows it (a preference, not a secret)
                pausedUntil: {
                    let t = UserDefaults.standard.double(forKey: "pausedUntil")
                    return t > 0 ? Date(timeIntervalSince1970: t) : nil
                },
                setPausedUntil: { UserDefaults.standard.set($0?.timeIntervalSince1970 ?? 0, forKey: "pausedUntil") }
            ),
            pasteboard: SystemPasteboard(), notifications: notifications,
            wifi: wifiOn, tor: torOn, clipboardSync: clipboardSync,
            appVersion: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "",
            ownStatus: MacBattery.read
        )
        self.session = session
        phone = store.device.peers().first { $0.confirmed }
        // One Tor for the app's life: its cache isn't tied to the identity, so a reset keeps it
        let tor = self.tor ?? SeamTor(appDir: store.folder)
        self.tor = tor
        let paths = Paths(
            session: session, wifi: wifiOn, tor: torOn, torRunner: tor,
            makeLan: { LanServer(device: store.device) },
            makeTorLan: { LanServer.tor(device: store.device) },
            lanFailed: { [log] in log.error("same Wi‑Fi listener didn't start") },
            onTorState: { [weak self] state in self?.torChanged(state) }
        )
        self.paths = paths
        watchWake()
        updatesTask?.cancel()
        updatesTask = Task { [weak self] in
            for await state in session.updates { self?.show(state) }
        }
        notifications.start { action in Task { await session.notificationAction(action) } }
        Task {
            await session.start() // what waited from before, ahead of any path
            let s = await session.state
            paths.start(linksWanted: s.wantsLinks, services: s.torServices)
        }
    }

    private func show(_ s: Session.State) {
        if s.phone == nil, phone != nil { pairedAt = nil } // unpaired, here or by the phone
        if case .paired = s.pairing, pairing != s.pairing { pairedAt = Date() }
        if let old = lastState { announceArrivals(old: old, new: s) }
        phone = s.phone
        status = s.status
        statusAt = s.statusAt
        waiting = s.waiting
        clips = s.clips
        phoneNotifications = s.phoneNotifications
        notificationSettings = s.notificationSettings
        if s.appIcons.isEmpty { iconPNGs = [:]; appIcons = [:] }
        for (id, png) in s.appIcons where iconPNGs[id] != png {
            iconPNGs[id] = png
            appIcons[id] = NSImage(data: png)
        }
        paused = s.pausedUntil != nil
        lastState = s
        setLink(LinkLook.of(route: s.route, allowed: s.allowed, tor: torState, pausedUntil: s.pausedUntil))
        if case .confirm = s.pairing, pairing != s.pairing { NSApp.activate() } // the safety code needs the user's eyes now
        pairing = s.pairing
        if s.phone != nil { pairAgainWarning = nil }
        warning = s.warning ?? pairAgainWarning
        // A phone may have come or gone, or a code shows: the links and services follow
        paths?.update(linksWanted: s.wantsLinks, services: s.torServices)
    }

    private func torChanged(_ state: TorState) {
        torState = state
        if let s = lastState { setLink(LinkLook.of(route: s.route, allowed: s.allowed, tor: state, pausedUntil: s.pausedUntil)) }
    }

    private func setLink(_ new: LinkLook) {
        if link.connected, !new.connected { lastSeen = Date() }
        link = new
    }

    /// Something new from the phone: the menu bar icon pops once and VoiceOver says what (catalogue 5).
    private func announceArrivals(old: Session.State, new: Session.State) {
        let what: String
        if let n = Arrival.notification(old: old.phoneNotifications, new: new.phoneNotifications) {
            notificationArrivals += 1
            what = "Notification from \(n.appName)"
        } else if Arrival.clip(old: old.clips, new: new.clips) != nil {
            what = "Copied from your phone"
        } else {
            return
        }
        MenuBarPing.ping()
        NSAccessibility.post(element: NSApp as Any, notification: .announcementRequested,
                             userInfo: [.announcement: what, .priority: NSAccessibilityPriorityLevel.high.rawValue])
    }

    /// After sleep the services' descriptors may have lapsed: published again, once per wake.
    private func watchWake() {
        if batteryWatch == nil {
            batteryWatch = MacBattery.watch { [weak self] in
                guard let session = self?.session else { return }
                Task { await session.ownStatusChanged() }
            }
        }
        guard wakeObserver == nil else { return }
        wakeObserver = NSWorkspace.shared.notificationCenter.addObserver(
            forName: NSWorkspace.didWakeNotification, object: nil, queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated { self?.paths?.republish() }
        }
    }

    // MARK: Phone

    /// "Unpair <phone>": the session tells the phone and forgets it, its onion service with it.
    func unpairPhone() {
        guard let session else { return }
        Task { await session.unpairPhone() }
    }

    /// The code both devices showed at pairing, for re-checking in Settings.
    func phoneCode() -> String? {
        #if DEBUG
        if let demoCode { return demoCode }
        #endif
        guard let store, let phone else { return nil }
        return try? store.device.safetyCode(peerId: phone.id)
    }

    // MARK: Clipboard

    func setClipboardSync(_ on: Bool) {
        clipboardSync = on
        UserDefaults.standard.set(on, forKey: "clipboardSync")
        Task { await session?.setClipboardSync(on) }
    }

    /// The last two clips from the phone, for the popover.
    var recentFromPhone: [Clip] { Array(clips.filter(\.fromPhone).prefix(2)) }

    func clearClips() {
        Task { await session?.clearClips() }
    }

    /// Puts an earlier clip from the phone back on this Mac's clipboard (popover "Copy").
    func copyAgain(_ text: String) {
        Task { await session?.copyAgain(text) }
    }

    // MARK: Connection

    func setWifi(_ on: Bool) {
        guard on || torOn else { return } // one always stays on
        wifiOn = on
        UserDefaults.standard.set(on, forKey: "transport.wifi")
        paths?.setWifi(on)
    }

    func setTor(_ on: Bool) {
        guard on || wifiOn else { return }
        torOn = on
        UserDefaults.standard.set(on, forKey: "transport.tor")
        paths?.setTor(on)
    }

    #if DEBUG
    @ObservationIgnored private var demoCode: String?

    /// SEAM_DEMO (Demo.swift): made-up phone state for looking at every state without a phone.
    /// Nothing is saved or sent: the session never starts.
    func showDemo(phone: PeerInfo, status: Seam_V1_Status, look: LinkLook, waiting: Int, settings: Seam_V1_NotificationSettings,
                  notifications: [Seam_V1_Notification], clips: [Clip]) {
        self.phone = phone
        self.status = status
        statusAt = Date().addingTimeInterval(-12 * 60)
        setLink(look)
        self.waiting = waiting
        notificationSettings = settings
        phoneNotifications = notifications
        self.clips = clips
        demoCode = "482 913"
        pairedAt = Calendar.current.date(from: DateComponents(year: 2026, month: 9, day: 14))
    }
    #endif
}
