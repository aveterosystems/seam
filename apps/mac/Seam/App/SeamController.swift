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
final class SeamController: RelayHandler {
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
    @ObservationIgnored private var relay: RelayClient?
    @ObservationIgnored private var relayTask: Task<Void, Never>?
    @ObservationIgnored private var lan: LanServer?
    @ObservationIgnored private var lanTask: Task<Void, Never>?
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
        updatesTask?.cancel()
        updatesTask = Task { [weak self] in
            for await state in session.updates { self?.show(state) }
        }
        notifications.start { action in Task { await session.notificationAction(action) } }
        Task {
            await session.start() // what waited from before, ahead of any path
            restartRelay()
            updateLan()
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
        updateLan() // the phone may have come or gone
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
        updateLan()
        Task { await session?.setSwitches(wifi: wifiOn, relay: relayOn) }
    }

    func setRelay(_ on: Bool) {
        guard on || wifiOn else { return }
        relayOn = on
        UserDefaults.standard.set(on, forKey: "transport.relay")
        Task {
            if !on { await session?.cancelPairing() } // pairing goes through the relay: closed there first
            restartRelay()
            await session?.setSwitches(wifi: wifiOn, relay: relayOn)
        }
    }

    /// Moves to another relay (nil: Seam's). The group lives on one relay, so the phone is
    /// unpaired (told so first) and pairs again; this Mac leaves its old group.
    func useRelay(_ link: RelayLink?) {
        guard let store, let session else { return }
        Task {
            await session.cancelPairing()
            if phone != nil, !(await session.unpairPhone()) { return } // forgetting it must be saved first (SR-7-04)
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
            restartRelay()
        }
    }

    /// (Re)starts the relay connection for the current store, if the switch allows: the old
    /// connection finishes first, so its events never land after the new one's.
    private func restartRelay() {
        let oldTask = relayTask
        var next: RelayClient?
        if relayOn, let store {
            next = RelayClient(
                device: store.device, groupID: store.appData.groupId, relayURL: store.relayURL,
                ownerKey: store.appData.ownerKey, handler: self
            )
        }
        relay = next
        let session = session
        relayTask = Task {
            await session?.setRelay(next)
            oldTask?.cancel()
            await oldTask?.value
            guard !Task.isCancelled, let next else { return }
            await next.run()
        }
    }

    /// The same‑Wi‑Fi listener runs while the switch is on and a phone is paired.
    private func updateLan() {
        let wanted = wifiOn && phone != nil && store != nil
        if wanted, lan == nil, let store, let session {
            let server = LanServer(device: store.device)
            lan = server
            lanTask = Task { [weak self] in
                do {
                    _ = try await server.start()
                } catch {
                    self?.log.error("same Wi‑Fi listener didn't start")
                    if self?.lan === server { self?.lan = nil }
                    return
                }
                await session.setLan(server)
                for await event in server.events { await session.lanEvent(event) }
            }
        } else if !wanted, let server = lan {
            lanTask?.cancel()
            lan = nil
            server.stop() // closes the link gracefully: what was already sent is written first
            Task { await session?.setLan(nil) }
        }
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
        Task {
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

    // MARK: RelayHandler

    func relayConnected(members: [Data]) async {
        await session?.relayConnected()
    }

    func relayDisconnected() async {
        await session?.relayDisconnected()
    }

    func relayReceived(_ envelope: Seam_V1_Envelope) async -> Bool {
        await session?.relayReceived(envelope) ?? true
    }

    func relayPeerJoined(_ deviceKey: Data) async {}
    func relayBundle(deviceKey: Data, bundle: Data) async {}

    func relayError(_ code: String) async {
        await session?.relayError(code)
    }
}
