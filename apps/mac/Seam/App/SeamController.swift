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
    /// The last two things that came from the phone's clipboard, for the popover (memory only).
    private(set) var recentFromPhone: [FromPhone] = []
    /// The phone's notification settings as it last sent them (the phone owns them). Memory only.
    private(set) var notificationSettings: Seam_V1_NotificationSettings?
    /// Set to open Settings at a pane (the popover's "Notification Settings…"); Settings clears it.
    var requestedSettingsPane: SettingsView.Pane?
    /// Phone apps' icons, from the phone when asked for. Memory only.
    private(set) var appIcons: [String: NSImage] = [:]
    /// Clipboard both ways (menu toggle, on by default).
    private(set) var clipboardSync = UserDefaults.standard.object(forKey: "clipboardSync") as? Bool ?? true
    /// How the link looks everywhere it's shown: the stitch, the icon and the words.
    private(set) var link = LinkLook.away
    /// Paused on the phone (only the phone pauses and resumes); the link says until when.
    private(set) var paused = false

    @ObservationIgnored private var store: Store?
    @ObservationIgnored private var session: Session?
    @ObservationIgnored private var updatesTask: Task<Void, Never>?
    /// Runs Same Wi‑Fi's listener and the Mac's onion services as the switches allow (SeamSession `Paths`).
    @ObservationIgnored private var paths: Paths?
    /// Tor, once per app run: started when first needed and never put to sleep (the phone reaches the Mac).
    @ObservationIgnored private var tor: SeamTor?
    @ObservationIgnored private let notifications = PhoneNotifications()
    @ObservationIgnored private var iconPNGs: [String: Data] = [:]
    /// A pairing from before Tor was dropped on opening: shown until the phone pairs again.
    @ObservationIgnored private var pairAgainWarning: String?
    @ObservationIgnored private var lastState: Session.State?
    @ObservationIgnored private var wakeObserver: NSObjectProtocol?
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
        notifications.start { action in Task { await session.notificationAction(action) } }
        Task {
            await session.start() // what waited from before, ahead of any path
            let s = await session.state
            paths.start(linksWanted: s.wantsLinks, services: s.torServices)
        }
    }

    private func show(_ s: Session.State) {
        phone = s.phone
        status = s.status
        recentFromPhone = s.recentFromPhone
        notificationSettings = s.notificationSettings
        if s.appIcons.isEmpty { iconPNGs = [:]; appIcons = [:] }
        for (id, png) in s.appIcons where iconPNGs[id] != png {
            iconPNGs[id] = png
            appIcons[id] = NSImage(data: png)
        }
        paused = s.pausedUntil != nil
        lastState = s
        link = LinkLook.of(route: s.route, allowed: s.allowed, tor: torState, pausedUntil: s.pausedUntil)
        if case .confirm = s.pairing, pairing != s.pairing { NSApp.activate() } // the safety code needs the user's eyes now
        pairing = s.pairing
        if s.phone != nil { pairAgainWarning = nil }
        warning = s.warning ?? pairAgainWarning
        // A phone may have come or gone, or a code shows: the links and services follow
        paths?.update(linksWanted: s.wantsLinks, services: s.torServices)
    }

    private func torChanged(_ state: TorState) {
        torState = state
        if let s = lastState { link = LinkLook.of(route: s.route, allowed: s.allowed, tor: state, pausedUntil: s.pausedUntil) }
    }

    /// After sleep the services' descriptors may have lapsed: published again, once per wake.
    private func watchWake() {
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
        guard let store, let phone else { return nil }
        return try? store.device.safetyCode(peerId: phone.id)
    }

    // MARK: Clipboard

    func setClipboardSync(_ on: Bool) {
        clipboardSync = on
        UserDefaults.standard.set(on, forKey: "clipboardSync")
        Task { await session?.setClipboardSync(on) }
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

    // MARK: Notifications

    func setNotifications(enabled: Bool) {
        changeNotificationSettings(.with { $0.enabled = enabled })
    }

    func setNotifications(quietWhileUsingPhone on: Bool) {
        changeNotificationSettings(.with { $0.quietWhileUsingPhone = on })
    }

    func setNotifications(dismissBoth on: Bool) {
        changeNotificationSettings(.with { $0.dismissBoth = on })
    }

    /// Until a time, or nil to resume. The phone holds it, so a paused phone sends nothing.
    func pauseNotifications(until: Date?) {
        let ms = until.map { Int64($0.timeIntervalSince1970 * 1000) } ?? 0
        changeNotificationSettings(.with { $0.pausedUntil = ms })
    }

    func setNotificationMode(_ mode: Seam_V1_NotificationMode, for appIDs: Set<String>) {
        guard !appIDs.isEmpty else { return }
        changeNotificationSettings(.with { $0.apps = appIDs.sorted().map { id in .with { $0.appID = id; $0.mode = mode } } })
    }

    private func changeNotificationSettings(_ change: Seam_V1_NotificationSettingsChange) {
        Task { await session?.changeNotificationSettings(change) }
    }

    // MARK: Pairing

    func startPairing() {
        guard let session else { return }
        let name = Host.current().localizedName ?? "Mac"
        let paths = paths
        Task {
            await paths?.settled() // a switch changed just before: the session hears of it first
            await session.startPairing(name: name)
            #if DEBUG
            // Device tests: hand the code to an emulator, which has no camera to scan with
            if let path = ProcessInfo.processInfo.environment["SEAM_PAIRING_CODE_FILE"], case .waiting(let qr, _) = await session.state.pairing {
                FileManager.default.createFile(atPath: path, contents: Data(qr.utf8), attributes: [.posixPermissions: 0o600])
            }
            #endif
        }
    }

    /// Pair was clicked. Safe to call twice: only the first call does anything.
    func confirmPairing() {
        Task { await session?.confirmPairing() }
    }

    /// Cancel, or the window closed. Does nothing while PairAccept is already on its way.
    func cancelPairing() {
        Task { await session?.cancelPairing() }
    }
}
