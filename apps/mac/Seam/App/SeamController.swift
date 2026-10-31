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
    /// Logged in to the relay (pairing and the relay path need it).
    private(set) var relayUp = false
    /// The connection switches (Settings › Connection; decision 0006). At least one stays on.
    private(set) var wifiOn = UserDefaults.standard.object(forKey: "transport.wifi") as? Bool ?? true
    private(set) var relayOn = UserDefaults.standard.object(forKey: "transport.relay") as? Bool ?? true
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

    @ObservationIgnored private var store: Store?
    @ObservationIgnored private var session: Session?
    @ObservationIgnored private var updatesTask: Task<Void, Never>?
    /// Runs the relay connection and the Same Wi‑Fi listener the switches allow (SeamSession `Paths`).
    @ObservationIgnored private var paths: Paths?
    @ObservationIgnored private let notifications = PhoneNotifications()
    @ObservationIgnored private var iconPNGs: [String: Data] = [:]
    /// A save here (moving relays) failed; the session reports its own.
    @ObservationIgnored private var saveWarning: String?
    private let log = Logger(subsystem: "com.avetero.seam", category: "session")

    /// For the pairing screens: the host the phone will be told to trust with its traffic.
    var relayHost: String { store?.relayURL.host() ?? "" }

    var usesOwnRelay: Bool { store?.usesOwnRelay ?? false }

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
        paths?.stop()
        if let old = session { Task { await old.stop() } }
        let session = Session(
            device: store.device,
            storage: SessionStorage(
                saveState: { try store.save() }, outbox: store.outboxFile,
                needsBundles: { store.needsBundles }, setNeedsBundles: { store.needsBundles = $0 }
            ),
            pasteboard: SystemPasteboard(), notifications: notifications,
            wifi: wifiOn, relay: relayOn, clipboardSync: clipboardSync,
            appVersion: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? ""
        )
        self.session = session
        phone = store.device.peers().first { $0.confirmed }
        let paths = Paths(
            session: session, wifi: wifiOn, relay: relayOn,
            makeRelay: { [weak self] handler in self?.makeRelay(handler) },
            makeLan: { LanServer(device: store.device) },
            lanFailed: { [log] in log.error("same Wi‑Fi listener didn't start") }
        )
        self.paths = paths
        updatesTask?.cancel()
        updatesTask = Task { [weak self] in
            for await state in session.updates { self?.show(state) }
        }
        notifications.start { action in Task { await session.notificationAction(action) } }
        let paired = phone != nil
        Task {
            await session.start() // what waited from before, ahead of any path
            paths.start(phonePaired: paired)
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
        relayUp = s.relayUp
        link = LinkLook.of(route: s.route, allowed: s.allowed, ownRelay: usesOwnRelay)
        if case .confirm = s.pairing, pairing != s.pairing { NSApp.activate() } // the safety code needs the user's eyes now
        pairing = s.pairing
        warning = s.warning ?? saveWarning
        paths?.setPhonePaired(s.phone != nil) // the phone may have come or gone
    }

    // MARK: Phone

    /// "Unpair <phone>": the session tells the phone, takes it out of the relay group and forgets it.
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
        guard on || relayOn else { return } // one always stays on
        wifiOn = on
        UserDefaults.standard.set(on, forKey: "transport.wifi")
        paths?.setWifi(on)
    }

    func setRelay(_ on: Bool) {
        guard on || wifiOn else { return }
        relayOn = on
        UserDefaults.standard.set(on, forKey: "transport.relay")
        paths?.setRelay(on)
    }

    /// Moves to another relay (nil: Seam's). The group lives on one relay, so the phone is
    /// unpaired (told so first) and pairs again; this Mac leaves its old group.
    func useRelay(_ link: RelayLink?) {
        guard let store, let session else { return }
        Task {
            await session.cancelPairing()
            guard await session.unpairPhone() else { return } // forgetting it must be saved first (SR-7-04)
            let moved: Store
            do {
                moved = try store.movingRelay(to: link)
            } catch {
                saveWarning = "Seam couldn't save to disk. Check there's free space; messages wait on the relay until it can."
                warning = saveWarning
                return log.error("saving state failed")
            }
            await session.leaveRelayGroup() // on the old relay, before it closes
            self.store = moved
            paths?.restartRelay()
        }
    }

    /// A relay connection for the current store; it reads the store when called, so a move to
    /// another relay takes effect on the next restart.
    private func makeRelay(_ handler: any RelayHandler) -> RelayClient? {
        guard let store else { return nil }
        return RelayClient(
            device: store.device, groupID: store.appData.groupId, relayURL: store.relayURL,
            ownerKey: store.appData.ownerKey, handler: handler
        )
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
        guard let store, let session else { return }
        let relayURL = store.relayURL.absoluteString
        let name = Host.current().localizedName ?? "Mac"
        let paths = paths
        Task {
            await paths?.settled() // "Turn On Relay" then a code: the session hears of the switch first
            await session.startPairing(relayURL: relayURL, name: name)
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
