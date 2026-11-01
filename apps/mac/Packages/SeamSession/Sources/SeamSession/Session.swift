// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import OSLog
import SeamCore
import SeamProtocol

/// Everything between this Mac and its phone, the Mac counterpart of Android's Session: delivery
/// (sealed, saved, then sent on a path the core's connection rules allow; resent if a path drops
/// before the phone acknowledged it), what arrives (to the features), forgetting the phone, and
/// pairing. The features (`Feature`) own their state and actions; the session only hosts them.
/// Same Wi‑Fi and Tor each reach it through a `LanSender` (the same channel over either
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
    /// Each envelope sent and not yet acked, with the link it went on and when that link is given up.
    var ackWaits: [Data: (path: PeerPath, timer: TimerToken)] = [:]
    /// A link that leaves an envelope unacked this long, while another link is up, is taken for dead
    /// (a network that went away without closing it): it's closed and the envelope goes on the other.
    static let ackTimeout: TimeInterval = 8

    /// Every feature, in the order each hears of a new path.
    let features: [any Feature]

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
        device: SeamDevice, storage: SessionStorage, features: [any Feature],
        wifi: Bool, tor: Bool, appVersion: String, timers: any Timers = SystemTimers()
    ) {
        self.device = device
        self.storage = storage
        self.timers = timers
        self.appVersion = appVersion
        self.wifi = wifi
        torOn = tor
        self.features = features
        phone = device.peers().first { $0.confirmed }
        pausedUntil = phone == nil ? nil : storage.pausedUntil()
        (updates, updatesIn) = AsyncStream.makeStream(bufferingPolicy: .bufferingNewest(1))
    }

    public var state: State {
        State(
            phone: phone, route: device.route(enabled: enabledPaths, up: upPaths, hello: false),
            allowed: device.allowedPaths(enabled: enabledPaths),
            waiting: outbox.waiting.count + outbox.sent.count, pairing: flow.state, warning: warning,
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

    /// Loads what waited from before a restart and starts the features.
    public func start() async {
        if phone != nil {
            outbox = storage.outbox.load(now: timers.now())
            outbox.requeueSent() // sent directly before a restart and never acked: goes again
        } else {
            storage.outbox.delete() // a reset, or the phone went while Seam was closed
        }
        for feature in features { await feature.start(host: self) }
        if let until = pausedUntil { setPause(until: until) } // its end, timed from now
        publish()
    }

    /// Timers off, for a session being replaced.
    public func stop() async {
        for feature in features { await feature.stop() }
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
            ackWaits.removeValue(forKey: msgID)?.timer.cancel()
            saveOutbox()
        case .bundleRequest(let peerId):
            if peerId == LanServer.pairingPeer { sendBundle(on: path) }
        }
    }

    // MARK: Private

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
