// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import OSLog
import SeamCore
import SeamProtocol

/// Everything between this Mac and its phone, the Mac counterpart of Android's Session: delivery
/// (sealed, saved, then sent on a path the core's connection rules allow; resent if a path drops
/// before the phone acknowledged it), what arrives (to the features), forgetting the phone, and
/// pairing. Same Wi‑Fi and Tor each reach it through a `LanSender` (the same channel over either
/// carrier); `Paths` starts and stops those, and the app shows `state`.
public actor Session {
    /// What the app shows.
    public struct State: Sendable {
        public var phone: PeerInfo?
        /// Where a message would go now; nil: it waits.
        public var route: PeerPath?
        /// Paths switched on at both ends.
        public var allowed: [PeerPath]
        /// Messages not yet delivered.
        public var waiting: Int
        public var status: Seam_V1_Status?
        /// Clips both ways in the last day, newest first.
        public var clips: [Clip]
        /// The phone's notifications still up, newest first.
        public var phoneNotifications: [Seam_V1_Notification]
        public var notificationSettings: Seam_V1_NotificationSettings?
        public var appIcons: [String: Data]
        public var pairing: PairingState
        /// Something the user should see in the menu (a refused login, a save that failed).
        public var warning: String?
        /// Paused on the phone until then (`.distantFuture`: until it resumes); nil: not paused.
        public var pausedUntil: Date?
        /// The onion services this Mac should host now: the paired phone's, and a pairing's new one.
        public var torServices: [TorServiceSpec]
        /// A phone is paired or pairing, so the links should be open.
        public var wantsLinks: Bool
    }

    static let settingsTTL: UInt32 = 600
    static let saveFailedWarning = "Seam couldn't save to disk. Check there's free space; messages wait until it can."

    let device: SeamDevice
    let storage: SessionStorage
    let timers: any Timers
    let appVersion: String
    // Logs codes and counts only, never contents or keys (threat model, rule 7)
    let log = Logger(subsystem: "com.avetero.seam", category: "session")

    var wifi: Bool
    var torOn: Bool
    /// The running carriers: Same Wi‑Fi's listener and the Tor service's streams.
    var links: [PeerPath: any LanSender] = [:]
    /// Paths with the phone authenticated on them now.
    var up: Set<PeerPath> = []

    /// The switches as the core's path list: the one place they're translated (the core picks the order).
    var enabledPaths: [PeerPath] { (wifi ? [.lan] : []) + (torOn ? [.tor] : []) }
    /// The links connected now, as the core's path list.
    var upPaths: [PeerPath] { [PeerPath.lan, .tor].filter { up.contains($0) } }
    var phone: PeerInfo?
    var warning: String?
    /// Sealed envelopes not yet delivered (waiting for a path, or sent and not yet acked), saved
    /// before any is handed to a path.
    var outbox = Outbox()
    /// Hellos in the outbox: the only envelopes allowed the Hello route. Memory only: after a
    /// restart a waiting Hello takes the normal route, and a fresh one goes when a path comes up.
    var hellos: Set<Data> = []

    let clipboard: ClipboardFeature
    let notifications: NotificationsFeature
    let status = StatusFeature()
    /// Every feature, so forgetting the phone resets them all.
    var features: [any Feature] { [clipboard, notifications, status] }
    var clipTimer: TimerToken?
    var stopped = false

    /// The phone paused Seam until then (only the phone pauses). Nothing is captured or sent
    /// meanwhile; the paths stay open so the phone can resume from anywhere.
    var pausedUntil: Date?
    var pauseTimer: TimerToken?
    var paused: Bool { pausedUntil.map { $0 > timers.now() } ?? false }

    var flow = PairingFlow()
    var expiry: TimerToken?
    /// The new onion service a pairing shows in its code, until confirmed or abandoned.
    var pairingService: TorServiceSpec?
    /// The link the pairing phone is on (the first to authenticate wins).
    var pairingPath: PeerPath?
    /// The bundle this code's phone was given: asking again gets the same one, never more saved keys.
    var pairingBundle: Data?
    /// The PairAccept on its way, until the phone acks it, with the app data from before it.
    var pendingAccept: PendingAccept?

    /// Every change to `state`, newest only.
    public nonisolated let updates: AsyncStream<State>
    private let updatesIn: AsyncStream<State>.Continuation

    public init(
        device: SeamDevice, storage: SessionStorage, pasteboard: any Pasteboard, notifications: any NotificationCentre,
        wifi: Bool, tor: Bool, clipboardSync: Bool, appVersion: String, timers: any Timers = SystemTimers()
    ) {
        self.device = device
        self.storage = storage
        self.timers = timers
        self.appVersion = appVersion
        self.wifi = wifi
        torOn = tor
        clipboard = ClipboardFeature(pasteboard: pasteboard, syncOn: clipboardSync)
        self.notifications = NotificationsFeature(centre: notifications)
        phone = device.peers().first { $0.confirmed }
        pausedUntil = phone == nil ? nil : storage.pausedUntil()
        (updates, updatesIn) = AsyncStream.makeStream(bufferingPolicy: .bufferingNewest(1))
    }

    public var state: State {
        State(
            phone: phone, route: device.route(enabled: enabledPaths, up: upPaths, hello: false),
            allowed: device.allowedPaths(enabled: enabledPaths),
            waiting: outbox.waiting.count + outbox.sent.count, status: status.status, clips: clipboard.clips,
            phoneNotifications: notifications.shown,
            notificationSettings: notifications.settings, appIcons: notifications.icons, pairing: flow.state, warning: warning,
            pausedUntil: paused ? pausedUntil : nil, torServices: torServices, wantsLinks: phone != nil || pairingOpen
        )
    }

    /// The paired phone's service (from app data), then a pairing's: only while Tor is on.
    var torServices: [TorServiceSpec] {
        guard torOn else { return [] }
        var out: [TorServiceSpec] = []
        if phone != nil, let data = device.appData(), data.peerDiscovery.count == 32 {
            out.append(TorServiceSpec(secret: data.torSecret, clients: [data.peerDiscovery]))
        }
        if let pairingService, !out.contains(pairingService) { out.append(pairingService) }
        return out
    }

    /// Loads what waited from before a restart and starts watching the pasteboard.
    public func start() async {
        if phone != nil {
            outbox = storage.outbox.load(now: timers.now())
            outbox.requeueSent() // sent directly before a restart and never acked: goes again
        } else {
            storage.outbox.delete() // a reset, or the phone went while Seam was closed
        }
        await clipboard.start()
        pollClipboardLater()
        if let until = pausedUntil { setPause(until: until) } // its end, timed from now
        publish()
    }

    /// Timers off, for a session being replaced.
    public func stop() {
        stopped = true
        clipTimer?.cancel()
        expiry?.cancel()
        updatesIn.finish()
    }

    // MARK: Paths

    /// The connection switches changed (at least one stays on): the phone is told, and what
    /// waited goes if it now can.
    public func setSwitches(wifi: Bool, tor: Bool) async {
        self.wifi = wifi
        torOn = tor
        await sendHello()
        await flush()
        publish()
    }

    /// A carrier started (or nil: stopped).
    public func setLink(_ link: (any LanSender)?, for path: PeerPath) async {
        links[path] = link
        if link == nil {
            if pairingPath == path { pairingPath = nil }
            await pathClosed(path)
        }
        publish()
    }

    public func linkEvent(_ event: LanEvent, on path: PeerPath) async {
        defer { publish() }
        switch event {
        case .authenticated(let peerId) where peerId == LanServer.pairingPeer:
            pairingLinked(on: path)
        case .authenticated(let peerId):
            guard peerId == phone?.id else { return links[path]?.close(peerId: peerId) ?? () } // no longer the phone
            up.insert(path)
            // A new link can replace the old one without a close event: what the old one never
            // got acked goes again
            if !outbox.sent.isEmpty {
                outbox.requeueSent()
                saveOutbox()
            }
            await pathCameUp()
        case .closed(let peerId) where peerId == LanServer.pairingPeer:
            await pairingUnlinked(on: path)
        case .closed(let peerId):
            if peerId == phone?.id { await pathClosed(path) }
        case .envelope(let peerId, let envelope) where peerId == LanServer.pairingPeer:
            pairingEnvelope(envelope, on: path)
        case .envelope(let peerId, let envelope):
            guard peerId == phone?.id else { return }
            // Acked on the same link once handled and saved (duplicates too); after a failed save
            // it isn't, so the phone sends it again after this Mac restarts
            if await received(envelope) {
                _ = links[path]?.send(.with { $0.delivered = .with { $0.msgID = envelope.msgID } }, to: peerId)
            }
        case .delivered(let peerId, let msgID) where peerId == LanServer.pairingPeer:
            await pairingAcked(msgID, on: path)
        case .delivered(let peerId, let msgID):
            guard peerId == phone?.id else { return }
            outbox.remove(msgID)
            hellos.remove(msgID)
            saveOutbox()
        case .bundleRequest(let peerId):
            if peerId == LanServer.pairingPeer { sendBundle(on: path) }
        }
    }

    // MARK: Features

    public func setClipboardSync(_ on: Bool) async {
        await clipboard.setSync(on)
        publish()
    }

    public func copyAgain(_ text: String) async {
        await clipboard.copyAgain(text)
    }

    /// The phone owns its notification settings: this asks it to change them.
    public func changeNotificationSettings(_ change: Seam_V1_NotificationSettingsChange) async {
        let out = notifications.change(change)
        publish()
        await send([out])
    }

    public func clearClips() {
        clipboard.clearClips()
        publish()
    }

    /// What the user did to a phone notification's banner here.
    public func notificationAction(_ action: Seam_V1_NotificationAction) async {
        let out = notifications.action(action)
        publish()
        await send(out.map { [$0] } ?? [])
    }

    /// The main window's dismiss of one of the phone's notifications.
    public func dismissNotification(key: String) async {
        let out = await notifications.dismiss(key)
        publish()
        await send(out.map { [$0] } ?? [])
    }

    // MARK: Private

    private func pollClipboardLater() {
        guard !stopped else { return }
        clipTimer = timers.after(ClipboardFeature.pollInterval) { [weak self] in await self?.pollClipboard() }
    }

    private func pollClipboard() async {
        let connected = device.route(enabled: enabledPaths, up: upPaths, hello: false) != nil
        // Paused: a copy is seen and let go, so it never goes later either
        if let out = await clipboard.poll(paired: phone != nil && !paused, connected: connected, now: timers.now()) {
            await send([out])
        }
        pollClipboardLater()
    }

    func saveFailed() {
        warning = Self.saveFailedWarning
        log.error("saving state failed")
    }

    /// The phone paused (a time, or `.distantFuture`) or resumed (nil). A timed pause ends here at
    /// the same time as on the phone, with no message.
    func setPause(until: Date?) {
        pauseTimer?.cancel()
        pausedUntil = until
        storage.setPausedUntil(until)
        if let until, until != .distantFuture {
            pauseTimer = timers.after(max(0, until.timeIntervalSince(timers.now()))) { [weak self] in await self?.publish() }
        }
        publish()
    }

    func publish() {
        updatesIn.yield(state)
    }
}
