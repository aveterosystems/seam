// SPDX-License-Identifier: AGPL-3.0-only
import AppKit
import Observation
import OSLog
import SeamCore
import SeamProtocol
import SeamSession

/// The app's one live object: owns the device, the connections to the phone and what the UI shows.
@MainActor @Observable
final class SeamController: RelayHandler {
    /// Logged in to the relay (pairing and the relay path need it).
    private(set) var relayUp = false
    /// The phone is authenticated on the same‑Wi‑Fi link.
    private(set) var lanUp = false
    /// The connection switches (Settings › Connection; decision 0006). At least one stays on.
    private(set) var wifiOn = UserDefaults.standard.object(forKey: "transport.wifi") as? Bool ?? true
    private(set) var relayOn = UserDefaults.standard.object(forKey: "transport.relay") as? Bool ?? true
    /// The phone's switches from its last Hello; nil until it sends one (older phones never do).
    /// Kept sealed in the app data; this is its copy for the UI.
    private(set) var peerAllowed: Set<Seam_V1_Transport>?
    private(set) var phone: PeerInfo?
    private(set) var status: Seam_V1_Status?
    private(set) var startupError: String?
    /// Saved data exists but can't be opened: the menu offers a reset instead of pairing.
    private(set) var needsReset = false
    /// Something the user should see in the menu (a refused login, a save that failed).
    private(set) var warning: String?
    private(set) var flow = PairingFlow()
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

    @ObservationIgnored private var store: Store?
    @ObservationIgnored private var relay: RelayClient?
    @ObservationIgnored private var relayTask: Task<Void, Never>?
    @ObservationIgnored private var lan: LanServer?
    @ObservationIgnored private var lanTask: Task<Void, Never>?
    /// Sealed envelopes not yet delivered (waiting for a path, or sent directly and not yet acked),
    /// saved before any is handed to a path.
    @ObservationIgnored private var outbox = Outbox()
    /// Handed to the relay and not yet answered, so a flush meanwhile doesn't send them twice.
    @ObservationIgnored private var inFlight: Set<Data> = []
    /// Hellos in the outbox: the only envelopes allowed `Paths.helloRoute`. Memory only: after a
    /// restart a waiting Hello takes the normal route, and a fresh one goes when a path comes up.
    @ObservationIgnored private var hellos: Set<Data> = []
    @ObservationIgnored private var openTokenHash: Data?
    @ObservationIgnored private var expiryTask: Task<Void, Never>?
    @ObservationIgnored private var clipRules = ClipRules()
    @ObservationIgnored private var lastChange = NSPasteboard.general.changeCount
    @ObservationIgnored private var clipTask: Task<Void, Never>?
    /// The latest copy made while the relay was unreachable (SR-4-03).
    @ObservationIgnored private var pendingClip = ClipQueue<Seam_V1_Payload>()
    @ObservationIgnored private let notifications = PhoneNotifications()
    @ObservationIgnored private var iconPNGs: [String: Data] = [:]
    /// Asked once per launch each, so an app whose icon the phone can't draw isn't asked for forever.
    @ObservationIgnored private var iconsAsked: Set<String> = []
    // Logs codes and counts only, never contents or keys (threat model, rule 7)
    private let log = Logger(subsystem: "com.avetero.seam", category: "session")

    /// For the pairing screens: the host the phone will be told to trust with its traffic.
    var relayHost: String { store?.relayURL.host() ?? "" }

    var usesOwnRelay: Bool { store?.usesOwnRelay ?? false }

    /// Paths allowed at both ends.
    private var allowed: Set<Seam_V1_Transport> {
        Paths.allowed(mine: Paths.mine(wifi: wifiOn, relay: relayOn), peer: peerAllowed)
    }

    /// How the link looks everywhere it's shown: the stitch, the icon and the words.
    var link: LinkLook {
        LinkLook.of(route: Paths.route(allowed: allowed, lanUp: lanUp, relayUp: relayUp), allowed: allowed, ownRelay: usesOwnRelay)
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
            needsReset = false
            startupError = nil
        } catch {
            startupError = "Seam couldn't reset: \(error.localizedDescription)"
            log.error("reset failed")
        }
    }

    private func begin(_ store: Store) {
        self.store = store
        phone = store.device.peers().first { $0.confirmed }
        peerAllowed = store.appData.peerAllowed
        if phone != nil {
            outbox = store.outboxFile.load(now: Date())
            outbox.requeueSent() // sent directly before a restart and never acked: goes again
        } else {
            outbox = Outbox()
            store.outboxFile.delete() // a reset, or the phone went while Seam was closed
        }
        restartRelay()
        updateLan()
        watchClipboard()
        notifications.start { [weak self] action in
            guard let self else { return }
            if action.dismissed, notificationSettings?.dismissBoth == false { return }
            // A typed reply waits for the phone like any message; a stale dismiss or button press
            // could hit a later notification that reuses the key (SR-19-03)
            sendToPhone(.with { $0.notificationAction = action }, ttl: action.replyText.isEmpty ? Self.settingsTTL : 0)
        }
    }

    // MARK: Unpairing

    /// "Unpair <phone>": tells the phone (sealed), takes it out of the relay group, and forgets it
    /// here. A phone that's offline finds out when the relay refuses it, and is told to pair again.
    func unpairPhone() {
        guard let store, let phone else { return }
        let unpair = Seam_V1_Payload.with { $0.control = .with { $0.unpair = .init() } }
        // Not saved before sending, unlike other messages (SR-2-11): the phone's keys are deleted
        // right after, so a crash can't reuse them
        let farewell = (try? store.device.seal(peerId: phone.id, payload: unpair.serializedData(), ttlS: 0))
            .flatMap { try? Seam_V1_Envelope(serializedBytes: $0) }
        let key = removableRelayKey(phone.id)
        let (viaLan, viaRelay) = farewellPaths
        // Only once forgetting it is saved: otherwise a restart would reload the phone with the
        // ratchet from before the farewell was sealed, and could reuse its key (SR-7-04)
        guard forgetPhone(phone.id) else { return }
        if let farewell, viaLan { _ = lan?.send(.with { $0.deliver = farewell }, to: phone.id) }
        updateLan() // closes the link gracefully: the farewell is written first
        Task {
            if let farewell, viaRelay { try? await relay?.send(Frames.send(farewell)) } // first, so a connected phone gets it before it's removed
            if let key { try? await relay?.send(Frames.removeMember(key)) }
        }
    }

    /// Where the Unpair goes: every up path this Mac's own switches allow, both if both are up (the
    /// relay keeps it for a phone that's away), whatever the phone's last list said; never a path
    /// this Mac switched off.
    private var farewellPaths: (lan: Bool, relay: Bool) {
        (wifiOn && lanUp, relayOn && relayUp)
    }

    /// The phone is gone (unpaired here, or it unpaired us): its keys, status and anything held for it.
    /// False if that couldn't be saved.
    @discardableResult
    private func forgetPhone(_ peerId: String) -> Bool {
        self.store?.update { $0.forgetPeer() } // saved with the removal below
        guard let store else { return false }
        store.device.removePeer(peerId: peerId)
        let saved: Bool
        do {
            try store.save()
            saved = true
        } catch {
            saveFailed()
            saved = false
        }
        if warning == Self.phoneLeft { warning = nil } // done what it asked
        if phone?.id == peerId {
            phone = nil
            status = nil
            recentFromPhone = []
        }
        pendingClip.clear()
        outbox.clear()
        inFlight = []
        hellos = []
        store.outboxFile.delete()
        peerAllowed = nil
        notifications.removeAll()
        notificationSettings = nil
        appIcons = [:]
        iconPNGs = [:]
        iconsAsked = []
        return saved
    }

    // MARK: Clipboard

    func setClipboardSync(_ on: Bool) {
        clipboardSync = on
        UserDefaults.standard.set(on, forKey: "clipboardSync")
        lastChange = NSPasteboard.general.changeCount // turning it on never sends what was copied while off
        if !on {
            pendingClip.clear() // nor does turning it off let a held copy go later
            recentFromPhone = [] // and it takes the phone's clips off the screen (SR-10-04)
        }
    }

    /// macOS has no change notification for the pasteboard: checking its counter twice a second is the standard way, and cheap.
    private func watchClipboard() {
        clipTask?.cancel()
        clipTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(500))
                self?.checkPasteboard()
            }
        }
    }

    private func checkPasteboard() {
        let pb = NSPasteboard.general
        let change = pb.changeCount
        guard change != lastChange else { return }
        guard clipboardSync, phone != nil, let item = pb.pasteboardItems?.first else {
            lastChange = change
            return
        }
        // Types and text from the same item, then make sure nothing replaced it meanwhile: a password
        // copied a moment later can't be read under the previous item's types (SR-4-05)
        let types = item.types.map(\.rawValue)
        let string = item.string(forType: .string)
        guard pb.changeCount == change else { return } // look again on the next check
        lastChange = change
        guard ClipRules.sendable(types: types), let text = clipRules.outgoing(string) else { return }
        let payload = Seam_V1_Payload.with {
            $0.clipboard = .with {
                $0.text = text
                $0.copiedAt = Int64(Date().timeIntervalSince1970 * 1000)
            }
        }
        guard connected else {
            pendingClip.hold(payload)
            return
        }
        sendToPhone(payload, ttl: Self.clipboardTTL)
    }

    private func receivedClip(_ clip: Seam_V1_Clipboard) {
        guard clipboardSync, let text = clipRules.incoming(clip.text) else { return }
        let pb = NSPasteboard.general
        pb.clearContents()
        pb.setString(text, forType: .string)
        lastChange = pb.changeCount // our own write isn't a new copy
        recentFromPhone = Array(([FromPhone(text: text, at: Date())] + recentFromPhone).prefix(2))
    }

    /// Puts an earlier clip from the phone back on this Mac's clipboard (popover "Copy").
    func copyAgain(_ text: String) {
        let pb = NSPasteboard.general
        pb.clearContents()
        pb.setString(text, forType: .string)
        lastChange = pb.changeCount // and don't send it back
    }

    /// The code both devices showed at pairing, for re-checking in Settings.
    func phoneCode() -> String? {
        guard let store, let phone else { return nil }
        return try? store.device.safetyCode(peerId: phone.id)
    }

    /// A stale clipboard is worse than none: the relay drops it after 10 minutes.
    private static let clipboardTTL: UInt32 = 600

    /// Sealed, saved, then sent: never sends what a failed save would forget (SR-2-11).
    private func sendToPhone(_ payload: Seam_V1_Payload, ttl: UInt32) {
        guard let store, let phone else { return }
        let envelope: Seam_V1_Envelope
        do {
            let sealed = try store.device.seal(peerId: phone.id, payload: try payload.serializedData(), ttlS: ttl)
            do { try store.save() } catch { return saveFailed() }
            envelope = try Seam_V1_Envelope(serializedBytes: sealed)
        } catch {
            log.error("couldn't seal a message for the phone")
            return
        }
        deliver(envelope)
    }

    /// Saved to the outbox first, then onto the best path both ends allow; otherwise it waits
    /// there. Never a disallowed path, and never handed to a path before it's on disk.
    private func deliver(_ envelope: Seam_V1_Envelope) {
        guard phone != nil else { return }
        outbox.add(envelope)
        guard saveOutbox() else { return } // stays in memory; the next flush tries again
        attempt(envelope)
    }

    private func attempt(_ envelope: Seam_V1_Envelope) {
        guard let phone, !inFlight.contains(envelope.msgID) else { return }
        let route = hellos.contains(envelope.msgID)
            ? Paths.helloRoute(mine: Paths.mine(wifi: wifiOn, relay: relayOn), lanUp: lanUp, relayUp: relayUp)
            : Paths.route(allowed: allowed, lanUp: lanUp, relayUp: relayUp)
        switch route {
        case .lan?:
            guard lan?.send(.with { $0.deliver = envelope }, to: phone.id) == true else { return }
            outbox.markSent(envelope.msgID) // until the phone's Delivered; resent if the link drops first
            saveOutbox()
        case .relay?:
            inFlight.insert(envelope.msgID)
            Task { [weak self, relay] in
                let ok = (try? await relay?.send(Frames.send(envelope))) != nil
                guard let self else { return }
                self.inFlight.remove(envelope.msgID)
                if ok { // the relay holds it now
                    self.outbox.remove(envelope.msgID)
                    self.hellos.remove(envelope.msgID)
                    self.saveOutbox()
                }
            }
        default:
            break
        }
    }

    /// Everything waiting, now that a path may have opened.
    private func flush() {
        _ = outbox.dropExpired(now: Date())
        guard saveOutbox() else { return } // nothing goes out that isn't on disk (an earlier save may have failed)
        outbox.waiting.forEach(attempt)
    }

    @discardableResult
    private func saveOutbox() -> Bool {
        guard let store else { return false }
        do {
            try store.outboxFile.save(outbox)
            return true
        } catch {
            saveFailed()
            return false
        }
    }

    /// A path to the phone came up: what waited, then this Mac's switches, then a held copy.
    private func pathCameUp() {
        guard phone != nil else { return }
        flush()
        sendHello()
        if let clip = pendingClip.take(syncOn: clipboardSync) { sendToPhone(clip, ttl: Self.clipboardTTL) }
    }

    // MARK: Connection

    func setWifi(_ on: Bool) {
        guard on || relayOn else { return } // one always stays on
        wifiOn = on
        UserDefaults.standard.set(on, forKey: "transport.wifi")
        updateLan()
        switchesChanged()
    }

    func setRelay(_ on: Bool) {
        guard on || wifiOn else { return }
        relayOn = on
        UserDefaults.standard.set(on, forKey: "transport.relay")
        if !on { cancelPairing() } // pairing goes through the relay for now
        restartRelay()
        switchesChanged()
    }

    /// Moves to another relay (nil: Seam's). The group lives on one relay, so the phone is
    /// unpaired (told so first) and pairs again; this Mac leaves its old group.
    func useRelay(_ link: RelayLink?) {
        guard let store else { return }
        cancelPairing()
        var farewell: [Seam_V1_ClientFrame] = []
        if let phone {
            let unpair = Seam_V1_Payload.with { $0.control = .with { $0.unpair = .init() } }
            let env = (try? store.device.seal(peerId: phone.id, payload: unpair.serializedData(), ttlS: 0))
                .flatMap { try? Seam_V1_Envelope(serializedBytes: $0) }
            let (viaLan, viaRelay) = farewellPaths
            if let env, viaRelay { farewell.append(Frames.send(env)) } // sent on the old relay before it closes
            if let key = removableRelayKey(phone.id) { farewell.append(Frames.removeMember(key)) }
            guard forgetPhone(phone.id) else { return } // saved before the farewell leaves (SR-7-04)
            if let env, viaLan { _ = lan?.send(.with { $0.deliver = env }, to: phone.id) }
            updateLan() // closes the link gracefully: the farewell is written first
        }
        farewell.append(Frames.removeMember(store.device.relayKey())) // and this Mac leaves the old group
        do {
            self.store = try store.movingRelay(to: link)
        } catch {
            return saveFailed()
        }
        restartRelay(farewell: relayOn ? farewell : [])
    }

    private func switchesChanged() {
        sendHello()
        flush()
    }

    /// Sealed, then its seq is recorded in the app data, then one save, then sent: a Hello never
    /// leaves with a seq a restart could hand out again.
    private func sendHello() {
        guard var store, let phone else { return }
        var data = store.appData
        let hello = Seam_V1_Hello.with {
            $0.appVersion = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? ""
            $0.platform = "macos"
            $0.seq = data.nextHelloSeq()
            $0.allowedTransports = Paths.mine(wifi: wifiOn, relay: relayOn).sorted { $0.rawValue < $1.rawValue }
        }
        let envelope: Seam_V1_Envelope
        do {
            let payload = Seam_V1_Payload.with { $0.control = .with { $0.hello = hello } }
            let sealed = try store.device.seal(peerId: phone.id, payload: try payload.serializedData(), ttlS: Self.settingsTTL)
            store.update { $0 = data }
            self.store = store
            do { try store.save() } catch { return saveFailed() }
            envelope = try Seam_V1_Envelope(serializedBytes: sealed)
        } catch {
            log.error("couldn't seal a Hello for the phone")
            return
        }
        hellos.insert(envelope.msgID) // may take any path this Mac allows (protocol/README.md)
        deliver(envelope)
    }

    /// Only a Hello with a higher seq counts: the relay can hold an old one and release it later,
    /// and it would switch a path back on. Applied before the save that follows opening it.
    private func applyHello(_ hello: Seam_V1_Hello) {
        var applied = false
        store?.update { applied = $0.applyHello(seq: hello.seq, allowed: hello.allowedTransports) }
        if applied { peerAllowed = store?.appData.peerAllowed }
    }

    /// (Re)starts the relay connection for the current store, if the switch allows: the old
    /// connection sends `farewell` and finishes first, so its events never land after the new one's.
    private func restartRelay(farewell: [Seam_V1_ClientFrame] = []) {
        let old = relay
        let oldTask = relayTask
        relay = nil
        relayUp = false
        var next: RelayClient?
        if relayOn, let store {
            next = RelayClient(
                device: store.device, groupID: store.appData.groupID, relayURL: store.relayURL,
                ownerKey: store.appData.ownerKey, handler: self
            )
        }
        relay = next
        relayTask = Task {
            for frame in farewell { try? await old?.send(frame) }
            oldTask?.cancel()
            await oldTask?.value
            guard !Task.isCancelled, let next else { return }
            await next.run()
        }
    }

    /// The same‑Wi‑Fi listener runs while the switch is on and a phone is paired.
    private func updateLan() {
        let wanted = wifiOn && phone != nil && store != nil
        if wanted, lan == nil, let store {
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
                for await event in server.events { await self?.lanEvent(event) }
            }
        } else if !wanted, let server = lan {
            lanTask?.cancel()
            lan = nil
            server.stop()
            lanClosed()
        }
    }

    private func lanEvent(_ event: LanEvent) async {
        switch event {
        case .authenticated(let peerId):
            guard peerId == phone?.id else { return lan?.close(peerId: peerId) ?? () } // a peer that's no longer the phone
            lanUp = true
            // A new link can replace the old one without a close event: what the old one never
            // got acked goes again on this one
            if !outbox.sent.isEmpty { outbox.requeueSent(); saveOutbox() }
            pathCameUp()
        case .closed(let peerId):
            if peerId == phone?.id { lanClosed() }
        case .envelope(let peerId, let envelope):
            guard peerId == phone?.id else { return }
            // Acked on the same link once handled and saved (duplicates too); after a failed save
            // it isn't, so the phone sends it again after this Mac restarts
            if await relayReceived(envelope) {
                _ = lan?.send(.with { $0.delivered = .with { $0.msgID = envelope.msgID } }, to: peerId)
            }
        case .delivered(let peerId, let msgID):
            guard peerId == phone?.id else { return }
            outbox.remove(msgID)
            hellos.remove(msgID)
            saveOutbox()
        }
    }

    /// Anything sent directly but not acked goes again, on whatever path is allowed now.
    private func lanClosed() {
        lanUp = false
        guard !outbox.sent.isEmpty else { return }
        outbox.requeueSent()
        saveOutbox()
        flush()
    }

    // MARK: Notifications

    func setNotifications(enabled: Bool) {
        notificationSettings?.enabled = enabled
        changeNotificationSettings { $0.enabled = enabled }
    }

    func setNotifications(quietWhileUsingPhone on: Bool) {
        notificationSettings?.quietWhileUsingPhone = on
        changeNotificationSettings { $0.quietWhileUsingPhone = on }
    }

    func setNotifications(dismissBoth on: Bool) {
        notificationSettings?.dismissBoth = on
        changeNotificationSettings { $0.dismissBoth = on }
    }

    /// Until a time, or nil to resume. The phone holds it, so a paused phone sends nothing.
    func pauseNotifications(until: Date?) {
        let ms = until.map { Int64($0.timeIntervalSince1970 * 1000) } ?? 0
        notificationSettings?.pausedUntil = ms
        changeNotificationSettings { $0.pausedUntil = ms }
    }

    func setNotificationMode(_ mode: Seam_V1_NotificationMode, for appIDs: Set<String>) {
        guard !appIDs.isEmpty else { return }
        for i in notificationSettings?.apps.indices ?? 0..<0 where appIDs.contains(notificationSettings!.apps[i].appID) {
            notificationSettings?.apps[i].mode = mode
        }
        changeNotificationSettings { $0.apps = appIDs.sorted().map { id in .with { $0.appID = id; $0.mode = mode } } }
    }

    /// The phone owns the settings: this asks it to change them, and the phone's answer confirms.
    private func changeNotificationSettings(_ edit: (inout Seam_V1_NotificationSettingsChange) -> Void) {
        var change = Seam_V1_NotificationSettingsChange()
        edit(&change)
        sendToPhone(.with { $0.notificationSettingsChange = change }, ttl: Self.settingsTTL)
    }

    private func receivedNotificationSettings(_ received: Seam_V1_NotificationSettings) {
        log.info("notification settings: \(received.apps.count, privacy: .public) apps, \(received.apps.filter { !$0.iconPng.isEmpty }.count, privacy: .public) icons")
        var s = received
        for i in s.apps.indices where !s.apps[i].iconPng.isEmpty {
            let png = s.apps[i].iconPng
            // From the phone, but still checked: a small, real image or nothing
            if png.count <= 256 * 1024, let image = NSImage(data: png) {
                iconPNGs[s.apps[i].appID] = png
                appIcons[s.apps[i].appID] = image
            }
            s.apps[i].iconPng = Data()
        }
        notificationSettings = s
        // In batches the phone answers in one message; each answer brings the next request
        let missing = Array(s.apps.map(\.appID).filter { iconPNGs[$0] == nil && !iconsAsked.contains($0) }.prefix(Self.iconBatch))
        guard !missing.isEmpty else { return }
        iconsAsked.formUnion(missing)
        changeNotificationSettings { $0.needIcons = missing }
    }

    /// Matches the phone's limit per message (NotificationsFeature.MAX_ICONS).
    private static let iconBatch = 40

    /// A settings change is worth little once the phone has moved on.
    private static let settingsTTL: UInt32 = 600

    // MARK: Pairing

    func startPairing() {
        guard let store, relayOn, !flow.isConfirming else { return } // pairing goes through the relay for now
        let offer = store.device.startPairing()
        openTokenHash = offer.tokenHash
        store.needsBundles = true
        let qr = PairingQR(
            relayURL: store.relayURL.absoluteString, groupID: store.appData.groupID, token: offer.token,
            identityKey: store.device.identityKey(), relayKey: store.device.relayKey(), name: Host.current().localizedName ?? "Mac"
        )
        flow.start(qr: qr.url, now: Date())
        expiryTask?.cancel()
        expiryTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(PairingDeadline.lifetime))
            guard !Task.isCancelled, let self, self.flow.expire() else { return }
            self.closeSlot()
        }
        Task { await announcePairing() }
    }

    /// Pair was clicked. Safe to call twice: only the first call does anything.
    func confirmPairing() {
        guard let request = flow.beginConfirming() else { return }
        Task { await sendAccept(request) }
    }

    /// Cancel, or the window closed. Does nothing while PairAccept is already on its way.
    func cancelPairing() {
        guard let previous = flow.cancel() else { return }
        switch previous {
        case .confirm(let request): reject(request)
        case .waiting: closeSlot()
        default: break
        }
    }

    private func sendAccept(_ request: PairingRequest) async {
        guard let store else { return }
        let device = store.device
        let frame: Seam_V1_ClientFrame
        do {
            try device.confirmPairing(peerId: request.peerId)
            let accept = Seam_V1_Payload.with { $0.control = .with { $0.pairAccept = .init() } }
            let sealed = try device.seal(peerId: request.peerId, payload: try accept.serializedData(), ttlS: 0)
            self.store?.update { $0.forgetPeer() } // a new pairing starts the Hello counts again, on both devices
            peerAllowed = nil
            try store.save() // never tell the phone it's paired unless this Mac will remember it
            frame = Frames.send(try Seam_V1_Envelope(serializedBytes: sealed))
        } catch {
            log.error("pairing confirm failed")
            unpair(request.peerId)
            flow.fail("Seam couldn't save the pairing on this Mac. Show a new code and scan it again.")
            return
        }
        // The relay may be reconnecting: retry for about a minute before giving up
        let backoff = Backoff()
        for attempt in 0..<6 {
            if (try? await relay?.send(frame)) != nil { return finishPairing(request) }
            try? await Task.sleep(for: .seconds(backoff.delay(attempt: attempt, jitter: .random(in: 0..<1))))
        }
        log.error("pairing accept not delivered")
        unpair(request.peerId)
        flow.fail("Couldn't reach your phone. Check both are online, then show a new code and scan it again.")
    }

    private func finishPairing(_ request: PairingRequest) {
        // Only a phone the core really holds as confirmed counts as paired
        guard let peer = store?.device.peers().first(where: { $0.id == request.peerId && $0.confirmed }) else {
            flow.fail("Pairing didn't finish. Show a new code and scan it again.")
            return
        }
        expiryTask?.cancel()
        openTokenHash = nil
        pendingClip.clear() // copied for the previous phone, if any
        phone = peer
        status = nil
        flow.finish(name: request.deviceName)
        updateLan()
        sendHello()
        Task { await uploadBundlesIfNeeded() }
    }

    /// The user said no to a verified request: forget the phone and take it out of the group.
    private func reject(_ request: PairingRequest) {
        guard let device = store?.device else { return }
        let key = removableRelayKey(request.peerId)
        device.rejectPairing(peerId: request.peerId)
        closeSlot()
        if let key { Task { try? await relay?.send(Frames.removeMember(key)) } }
    }

    /// A phone confirmed here whose PairAccept never got out.
    private func unpair(_ peerId: String) {
        guard let store else { return }
        let key = removableRelayKey(peerId)
        pendingClip.clear()
        store.device.removePeer(peerId: peerId)
        do { try store.save() } catch { saveFailed() }
        closeSlot()
        if let key { Task { try? await relay?.send(Frames.removeMember(key)) } }
    }

    /// The peer's relay key, unless another (already paired) device shares it.
    private func removableRelayKey(_ peerId: String) -> Data? {
        let peers = store?.device.peers() ?? []
        guard let key = peers.first(where: { $0.id == peerId })?.relayKey else { return nil }
        return peers.contains { $0.id != peerId && $0.confirmed && $0.relayKey == key } ? nil : key
    }

    /// Closes the pairing slot in the core and on the relay, so the code can't be used later.
    private func closeSlot() {
        expiryTask?.cancel()
        store?.device.cancelPairing()
        guard openTokenHash != nil else { return }
        openTokenHash = nil
        Task { try? await relay?.send(Frames.closePairing()) }
    }

    // MARK: RelayHandler

    func relayConnected(members: [Data]) async {
        relayUp = true
        if warning == Self.loginRefused { warning = nil }
        await announcePairing()
        await uploadBundlesIfNeeded()
        pathCameUp()
    }

    func relayDisconnected() async {
        relayUp = false
    }

    func relayReceived(_ envelope: Seam_V1_Envelope) async -> Bool {
        guard let store, let bytes = try? envelope.serializedData() else { return true }
        let message: OpenedMessage
        do {
            message = try store.device.open(envelope: bytes)
        } catch SeamError.NotPaired {
            if case .waiting = flow.state { acceptPairing(bytes) }
            return true
        } catch SeamError.Duplicate {
            return true // already handled before a reconnect
        } catch SeamError.UntrustedIdentity {
            warning = "A device tried to talk to this Mac with keys that don't match your paired phone. It was ignored."
            log.error("untrusted identity")
            return true
        } catch {
            log.error("dropped an envelope that failed its checks")
            return true // dropped for good: the relay can delete it
        }
        let payload = try? Seam_V1_Payload(serializedBytes: message.payload)
        if case .control(let c)? = payload?.body, case .hello(let h)? = c.body, isConfirmedPhone(message.peerId) {
            applyHello(h) // its seq is saved by the save just below, with the ratchet
        }
        let saved = (try? store.save()) != nil
        // Handled even if the save failed: the ratchet has moved, so a redelivery in this run opens
        // as a duplicate and would be lost (SR-2-59). Not acked, so after a restart the saved state
        // opens it again.
        if let payload { handle(payload, from: message.peerId) }
        if !saved {
            saveFailed()
            return false
        }
        return true
    }

    func relayPeerJoined(_ deviceKey: Data) async {}
    func relayBundle(deviceKey: Data, bundle: Data) async {}

    func relayError(_ code: String) async {
        log.error("relay error \(code, privacy: .public)")
        if code == "bad_login" { warning = Self.loginRefused }
        // The phone left the group (it forgot this Mac and the farewell got lost). Not forgotten
        // here on the relay's word alone: a malicious relay could send this. Only while paired, so
        // this Mac's own removeMember of a phone already gone doesn't trigger it.
        if code == "not_member", phone != nil { warning = Self.phoneLeft }
    }

    private static let phoneLeft = "Your phone may have unpaired from this Mac. Unpair here and pair again."

    // MARK: Private

    private static let loginRefused = "The relay didn't accept this Mac's login. Seam keeps retrying."

    private func acceptPairing(_ envelope: Data) {
        guard let device = store?.device else { return }
        do {
            let request = try device.acceptPairing(envelope: envelope)
            guard flow.received(request) else { return device.rejectPairing(peerId: request.peerId) }
            NSApp.activate() // the safety code needs the user's eyes now
        } catch {
            // A wrong or stale code doesn't use up the slot; keep waiting for the real phone
            log.error("pairing attempt refused")
        }
    }

    private func isConfirmedPhone(_ peerId: String) -> Bool {
        peerId == phone?.id && store?.device.peers().contains(where: { $0.id == peerId && $0.confirmed }) == true
    }

    private func handle(_ payload: Seam_V1_Payload, from peerId: String) {
        // Features only from the paired, confirmed phone
        guard isConfirmedPhone(peerId) else { return }
        switch payload.body {
        case .status(var s):
            s.batteryPct = min(s.batteryPct, 100)
            status = s
        case .clipboard(let c):
            receivedClip(c)
        case .notification(let n):
            notifications.show(n, icon: iconPNGs[n.appID])
        case .notificationSettings(let s):
            receivedNotificationSettings(s)
        case .notificationAction(let a):
            if a.dismissed { notifications.dismiss(key: a.key) }
        case .control(let c):
            if case .hello = c.body { flush() } // applied before the save; a path may have opened
            if case .unpair = c.body {
                // It leaves the relay group itself, but only best-effort: make sure (SR-7-02)
                let key = removableRelayKey(peerId)
                if forgetPhone(peerId), let key { Task { try? await relay?.send(Frames.removeMember(key)) } }
                updateLan()
            }
        default: break
        }
    }

    private func announcePairing() async {
        guard let hash = openTokenHash, case .waiting(_, let deadline) = flow.state else { return }
        let ttl = deadline.ttlSeconds(at: Date()) // a reconnect never extends the code's life
        guard ttl > 0 else { return }
        try? await relay?.send(Frames.openPairing(tokenHash: hash, ttl: ttl))
    }

    private func uploadBundlesIfNeeded() async {
        guard let store, store.needsBundles else { return }
        do {
            let bundles = try store.device.prekeyBundles(count: 20)
            try store.save()
            try await relay?.send(Frames.putBundles(bundles))
            store.needsBundles = false
        } catch {
            log.error("bundle upload failed; retrying on next connect")
        }
    }

    private func saveFailed() {
        warning = "Seam couldn't save to disk. Check there's free space; messages wait on the relay until it can."
        log.error("saving state failed")
    }
}

/// One clip that arrived from the phone.
struct FromPhone: Identifiable {
    let id = UUID()
    let text: String
    let at: Date

    var openableURL: URL? { ClipRules.openableURL(text) }
}
