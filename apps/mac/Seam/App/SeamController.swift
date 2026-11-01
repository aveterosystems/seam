// SPDX-License-Identifier: AGPL-3.0-only
import AppKit
import Observation
import OSLog
import SeamCore
import SeamProtocol
import SeamSession

/// The app's one live object: opens the store, builds the session (SeamSession's `Session`, which
/// owns delivery, the pause, paths and pairing) and its features, keeps the settings, and shows
/// the session's state. The screens watch the features (`clipboard`, `notifications`, `status`)
/// directly.
@MainActor @Observable
final class SeamController {
    /// The connection switches (Settings › Connection). At least one stays on.
    private(set) var wifiOn = UserDefaults.standard.object(forKey: "transport.wifi") as? Bool ?? true
    private(set) var torOn = UserDefaults.standard.object(forKey: "transport.tor") as? Bool ?? true
    /// Tor's own state, for the link's words (connecting, blocked, clock).
    private(set) var torState: TorState = .off
    private(set) var phone: PeerInfo?
    private(set) var startupError: String?
    /// Saved data exists but can't be opened: the menu offers a reset instead of pairing.
    private(set) var needsReset = false
    /// Something the user should see in the menu (a refused login, a save that failed).
    private(set) var warning: String?
    private(set) var pairing: PairingState = .idle
    /// The features, new with each session: until one starts they're empty (and in SEAM_DEMO, made up).
    private(set) var clipboard: ClipboardFeature
    private(set) var notifications: NotificationsFeature
    private(set) var status: StatusFeature
    /// Clipboard both ways (menu toggle, on by default).
    private(set) var clipboardSync = SeamController.savedClipboardSync
    private static var savedClipboardSync: Bool { UserDefaults.standard.object(forKey: "clipboardSync") as? Bool ?? true }
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
    /// The phone's notifications as banners: one for the app's life.
    @ObservationIgnored private let banners = PhoneNotifications()
    /// A pairing from before Tor was dropped on opening: shown until the phone pairs again.
    @ObservationIgnored private var pairAgainWarning: String?
    @ObservationIgnored private var lastState: Session.State?
    /// A Reconnect under way, until the phone is back or it gives up. The button waits for it (SR-55-03).
    private(set) var reconnecting: LinkLook.Reconnect?
    @ObservationIgnored private var reconnectEnd: Task<Void, Never>?
    @ObservationIgnored private var wakeObserver: NSObjectProtocol?
    /// Tells the phone when this Mac's battery changes (once per app run).
    @ObservationIgnored private var batteryWatch: CFRunLoopSource?
    private let log = Logger(subsystem: "com.avetero.seam", category: "session")

    init() {
        (clipboard, notifications, status) = Self.features(banners: banners, sync: Self.savedClipboardSync)
    }

    private static func features(banners: PhoneNotifications, sync: Bool) -> (ClipboardFeature, NotificationsFeature, StatusFeature) {
        (ClipboardFeature(pasteboard: SystemPasteboard(), syncOn: sync), NotificationsFeature(centre: banners),
         StatusFeature(timers: SystemTimers(), own: MacBattery.read))
    }

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
        (clipboard, notifications, status) = Self.features(banners: banners, sync: clipboardSync)
        clipboard.onArrival = { [weak self] in self?.announce($0) }
        notifications.onArrival = { [weak self] in self?.announce($0) }
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
            features: [clipboard, notifications, status],
            wifi: wifiOn, tor: torOn,
            appVersion: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? ""
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
        banners.start { [weak self] action in
            guard let notifications = self?.notifications else { return }
            Task { await notifications.act(action) }
        }
        Task {
            await session.start() // what waited from before, ahead of any path
            let s = await session.state
            paths.start(linksWanted: s.wantsLinks, services: s.torServices)
        }
    }

    private func show(_ s: Session.State) {
        if s.phone == nil, phone != nil { pairedAt = nil } // unpaired, here or by the phone
        if case .paired = s.pairing, pairing != s.pairing { pairedAt = Date() }
        phone = s.phone
        waiting = s.waiting
        paused = s.pausedUntil != nil
        lastState = s
        refreshLink()
        if case .confirm = s.pairing, pairing != s.pairing { NSApp.activate() } // the safety code needs the user's eyes now
        pairing = s.pairing
        if s.phone != nil { pairAgainWarning = nil }
        warning = s.warning ?? pairAgainWarning
        // A phone may have come or gone, or a code shows: the links and services follow
        paths?.update(linksWanted: s.wantsLinks, services: s.torServices)
    }

    private func torChanged(_ state: TorState) {
        torState = state
        refreshLink()
    }

    private func refreshLink() {
        guard let s = lastState else { return }
        let new = LinkLook.of(route: s.route, allowed: s.allowed, tor: torState, pausedUntil: s.pausedUntil, reconnect: reconnecting)
        if new.connected { endReconnect() }
        if link.connected, !new.connected { lastSeen = Date() }
        link = new
    }

    /// "Reconnect" in the popover's ⓘ: the phone is asked to dial again; out of reach, this Mac's
    /// own side starts again and the line says to wake the phone. Shows for the usual connect time.
    func reconnect() {
        guard reconnecting == nil else { return } // a second tap would restart this Mac's side as the phone dials back (SR-55-03)
        reconnecting = .asked
        Task {
            guard let session else { return endReconnect() }
            let asked = await session.reconnect()
            // Never under a pairing in progress: its channel would close with the carrier (SR-55-10)
            if !asked, !pairingInProgress { paths?.restart() }
            reconnecting = asked ? .asked : .phoneOffline
            lastState = await session.state // not the update still on its way, or "connected" would end it at once (SR-55-05)
            refreshLink()
            reconnectEnd?.cancel()
            reconnectEnd = Task { [weak self, tor = torOn] in
                try? await Task.sleep(for: .seconds(tor ? 60 : 15))
                guard !Task.isCancelled else { return }
                self?.endReconnect()
                self?.refreshLink()
            }
        }
    }

    private var pairingInProgress: Bool {
        switch pairing {
        case .waiting, .confirm, .confirming: true
        default: false
        }
    }

    private func endReconnect() {
        reconnecting = nil
        reconnectEnd?.cancel()
        reconnectEnd = nil
    }

    /// Something new from the phone: the menu bar icon pops once and VoiceOver says what (catalogue 5).
    private func announce(_ what: String) {
        MenuBarPing.ping()
        NSAccessibility.post(element: NSApp as Any, notification: .announcementRequested,
                             userInfo: [.announcement: what, .priority: NSAccessibilityPriorityLevel.high.rawValue])
    }

    /// After sleep the services' descriptors may have lapsed: published again, once per wake.
    private func watchWake() {
        if batteryWatch == nil {
            batteryWatch = MacBattery.watch { [weak self] in
                guard let status = self?.status else { return }
                Task { await status.ownChanged() }
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
        let clipboard = clipboard
        Task { await clipboard.setSync(on) }
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
        self.status.showDemo(status, at: Date().addingTimeInterval(-12 * 60))
        link = look
        self.waiting = waiting
        self.notifications.showDemo(settings: settings, shown: notifications)
        clipboard.showDemo(clips: clips)
        demoCode = "482 913"
        pairedAt = Calendar.current.date(from: DateComponents(year: 2026, month: 9, day: 14))
    }
    #endif
}
