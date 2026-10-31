// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import OSLog
import SeamCore
import SeamProtocol

/// Everything between this Mac and its phone, the Mac counterpart of Android's Session: delivery
/// (sealed, saved, then sent on a path the core's connection rules allow; resent if a path drops
/// before the phone acknowledged it), what arrives (to the features), forgetting the phone, and
/// pairing. Same Wi‑Fi and the relay reach it through `LanSender` and `RelaySender`; the app
/// starts and stops those, and shows `state`.
public actor Session {
    /// What the app shows.
    public struct State: Sendable {
        public var phone: PeerInfo?
        /// Where a message would go now; nil: it waits.
        public var route: PeerPath?
        /// Paths switched on at both ends.
        public var allowed: [PeerPath]
        public var relayUp: Bool
        /// Messages not yet delivered.
        public var waiting: Int
        public var status: Seam_V1_Status?
        public var recentFromPhone: [FromPhone]
        public var notificationSettings: Seam_V1_NotificationSettings?
        public var appIcons: [String: Data]
        public var pairing: PairingState
        /// Something the user should see in the menu (a refused login, a save that failed).
        public var warning: String?
    }

    static let settingsTTL: UInt32 = 600
    static let phoneLeft = "Your phone may have unpaired from this Mac. Unpair here and pair again."
    static let loginRefused = "The relay didn't accept this Mac's login. Seam keeps retrying."
    static let saveFailedWarning = "Seam couldn't save to disk. Check there's free space; messages wait on the relay until it can."

    let device: SeamDevice
    let storage: SessionStorage
    let timers: any Timers
    let appVersion: String
    // Logs codes and counts only, never contents or keys (threat model, rule 7)
    let log = Logger(subsystem: "com.avetero.seam", category: "session")

    var wifi: Bool
    var relayOn: Bool
    var lan: (any LanSender)?
    var relay: (any RelaySender)?
    var lanUp = false
    var relayUp = false

    /// The switches as the core's path list: the one place they're translated (the core picks the order).
    var enabledPaths: [PeerPath] { (wifi ? [.lan] : []) + (relayOn ? [.relay] : []) }
    /// The links connected now, as the core's path list.
    var upPaths: [PeerPath] { (lanUp ? [.lan] : []) + (relayUp ? [.relay] : []) }
    var phone: PeerInfo?
    var warning: String?
    /// Sealed envelopes not yet delivered (waiting for a path, or sent directly and not yet acked),
    /// saved before any is handed to a path.
    var outbox = Outbox()
    /// Handed to the relay and not yet answered, so a flush meanwhile doesn't send them twice.
    var inFlight: Set<Data> = []
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

    var flow = PairingFlow()
    var openTokenHash: Data?
    var expiry: TimerToken?

    /// Every change to `state`, newest only.
    public nonisolated let updates: AsyncStream<State>
    private let updatesIn: AsyncStream<State>.Continuation

    public init(
        device: SeamDevice, storage: SessionStorage, pasteboard: any Pasteboard, notifications: any NotificationCentre,
        wifi: Bool, relay: Bool, clipboardSync: Bool, appVersion: String, timers: any Timers = SystemTimers()
    ) {
        self.device = device
        self.storage = storage
        self.timers = timers
        self.appVersion = appVersion
        self.wifi = wifi
        relayOn = relay
        clipboard = ClipboardFeature(pasteboard: pasteboard, syncOn: clipboardSync)
        self.notifications = NotificationsFeature(centre: notifications)
        phone = device.peers().first { $0.confirmed }
        (updates, updatesIn) = AsyncStream.makeStream(bufferingPolicy: .bufferingNewest(1))
    }

    public var state: State {
        State(
            phone: phone, route: device.route(enabled: enabledPaths, up: upPaths, hello: false),
            allowed: device.allowedPaths(enabled: enabledPaths), relayUp: relayUp,
            waiting: outbox.waiting.count + outbox.sent.count, status: status.status, recentFromPhone: clipboard.recent,
            notificationSettings: notifications.settings, appIcons: notifications.icons, pairing: flow.state, warning: warning
        )
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
    public func setSwitches(wifi: Bool, relay: Bool) async {
        self.wifi = wifi
        relayOn = relay
        if !relay { await cancelPairing() } // pairing goes through the relay for now
        await sendHello()
        await flush()
        publish()
    }

    /// The Same Wi‑Fi server started (or nil: stopped).
    public func setLan(_ lan: (any LanSender)?) async {
        self.lan = lan
        if lan == nil { await lanClosed() }
        publish()
    }

    public func lanEvent(_ event: LanEvent) async {
        switch event {
        case .authenticated(let peerId):
            guard peerId == phone?.id else { return lan?.close(peerId: peerId) ?? () } // a peer that's no longer the phone
            lanUp = true
            // A new link can replace the old one without a close event: what the old one never
            // got acked goes again on this one
            if !outbox.sent.isEmpty {
                outbox.requeueSent()
                saveOutbox()
            }
            await pathCameUp()
        case .closed(let peerId):
            if peerId == phone?.id { await lanClosed() }
        case .envelope(let peerId, let envelope):
            guard peerId == phone?.id else { return }
            // Acked on the same link once handled and saved (duplicates too); after a failed save
            // it isn't, so the phone sends it again after this Mac restarts
            if await received(envelope) {
                _ = lan?.send(.with { $0.delivered = .with { $0.msgID = envelope.msgID } }, to: peerId)
            }
        case .delivered(let peerId, let msgID):
            guard peerId == phone?.id else { return }
            outbox.remove(msgID)
            hellos.remove(msgID)
            saveOutbox()
        }
        publish()
    }

    /// A new relay connection (or nil: none); it's up once `relayConnected`.
    public func setRelay(_ relay: (any RelaySender)?) {
        self.relay = relay
        relayUp = false
        publish()
    }

    public func relayConnected() async {
        relayUp = true
        if warning == Self.loginRefused { warning = nil }
        await announcePairing()
        await uploadBundlesIfNeeded()
        await pathCameUp()
        publish()
    }

    public func relayDisconnected() {
        relayUp = false
        publish()
    }

    /// True once it's dealt with (handled, a duplicate, or dropped for good), so it may be acked.
    public func relayReceived(_ envelope: Seam_V1_Envelope) async -> Bool {
        defer { publish() }
        return await received(envelope)
    }

    public func relayError(_ code: String) {
        log.error("relay error \(code, privacy: .public)")
        if code == "bad_login" { warning = Self.loginRefused }
        // The phone left the group (it forgot this Mac and the farewell got lost). Not forgotten
        // here on the relay's word alone: a malicious relay could send this. Only while paired, so
        // this Mac's own removeMember of a phone already gone doesn't trigger it.
        if code == "not_member", phone != nil { warning = Self.phoneLeft }
        publish()
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

    /// What the user did to a phone notification's banner here.
    public func notificationAction(_ action: Seam_V1_NotificationAction) async {
        await send(notifications.action(action).map { [$0] } ?? [])
    }

    // MARK: Forgetting the phone

    /// "Unpair <phone>": tells the phone (sealed), takes it out of the relay group, and forgets it
    /// here. A phone that's offline finds out when the relay refuses it, and is told to pair
    /// again. False if forgetting it couldn't be saved (then nothing is sent).
    @discardableResult
    public func unpairPhone() async -> Bool {
        guard let phone else { return true } // nothing to forget (SR-29-06: the caller's copy may lag)
        let unpair = Seam_V1_Payload.with { $0.control = .with { $0.unpair = .init() } }
        // Not saved before sending, unlike other messages (SR-2-11): the phone's keys are deleted
        // right after, so a crash can't reuse them
        let goodbye = (try? device.seal(peerId: phone.id, payload: unpair.serializedData(), ttlS: 0))
            .flatMap { try? Seam_V1_Envelope(serializedBytes: $0) }
        let key = removableRelayKey(phone.id)
        // Every up path this Mac's own switches allow, both if both are up (the relay keeps it for
        // a phone that's away), whatever the phone's last list said; never a path switched off
        let (viaLan, viaRelay) = (wifi && lanUp, relayOn && relayUp)
        // Only once forgetting it is saved: otherwise a restart would reload the phone with the
        // ratchet from before the goodbye was sealed, and could reuse its key (SR-7-04)
        guard await forget(phone.id) else { return false }
        if let goodbye, viaLan { _ = lan?.send(.with { $0.deliver = goodbye }, to: phone.id) }
        if let goodbye, viaRelay { try? await relay?.send(Frames.send(goodbye)) } // before it's removed
        if let key { try? await relay?.send(Frames.removeMember(key)) }
        publish()
        return true
    }

    /// This Mac leaves its relay group (it's moving to another relay).
    public func leaveRelayGroup() async {
        try? await relay?.send(Frames.removeMember(device.relayKey()))
    }

    /// The phone is gone (unpaired here, or it unpaired us): its keys, Hello state, every
    /// feature's data and anything held for it. False if that couldn't be saved.
    @discardableResult
    func forget(_ peerId: String) async -> Bool {
        device.forgetHello()
        device.removePeer(peerId: peerId)
        let saved: Bool
        do {
            try storage.saveState()
            saved = true
        } catch {
            saveFailed()
            saved = false
        }
        if warning == Self.phoneLeft { warning = nil } // done what it asked
        if phone?.id == peerId { phone = nil }
        outbox.clear()
        inFlight = []
        hellos = []
        storage.outbox.delete()
        for feature in features { await feature.reset() }
        return saved
    }

    // MARK: Private

    private func pollClipboardLater() {
        guard !stopped else { return }
        clipTimer = timers.after(ClipboardFeature.pollInterval) { [weak self] in await self?.pollClipboard() }
    }

    private func pollClipboard() async {
        let connected = device.route(enabled: enabledPaths, up: upPaths, hello: false) != nil
        if let out = await clipboard.poll(paired: phone != nil, connected: connected, now: timers.now()) {
            await send([out])
        }
        pollClipboardLater()
    }

    func saveFailed() {
        warning = Self.saveFailedWarning
        log.error("saving state failed")
    }

    func publish() {
        updatesIn.yield(state)
    }
}
