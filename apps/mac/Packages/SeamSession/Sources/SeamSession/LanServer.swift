// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import Network
import SeamCore
import SeamProtocol

/// What a direct link reports, in order, on `LanServer.events`. `peerId` is the authenticated
/// paired device the link belongs to, or `LanServer.pairingPeer` for a phone that proved it
/// scanned this Mac's code (a pairing channel, which carries only the pairing exchange).
public enum LanEvent: Sendable, Equatable {
    /// A paired (or pairing) phone proved who it is; it may now be sent to.
    case authenticated(peerId: String)
    /// That phone's link closed (not reported when a newer link from it replaced this one).
    case closed(peerId: String)
    case envelope(peerId: String, Seam_V1_Envelope)
    case delivered(peerId: String, msgID: Data)
    /// A pairing phone asks for this Mac's prekey bundle (pairing channels only).
    case bundleRequest(peerId: String)
}

/// The Mac's side of a direct link (protocol/README.md, "The direct connection"), over either
/// carrier: Same Wi‑Fi (a TCP listener announced over Bonjour, on Wi‑Fi and wired Ethernet only) or
/// Tor (streams from the Mac's onion service, handed in with `adopt`). All the cryptography is the
/// core's `LanChannel`; this only moves bytes, enforces the limits, and turns frames into events.
public final class LanServer: @unchecked Sendable {
    /// The peer id of a pairing channel (the core's PAIRING_PEER).
    public static let pairingPeer = "pairing"
    public static let serviceType = "_seam._tcp"
    /// Fixed, so the Bonjour name doesn't announce whose Mac it is.
    public static let serviceName = "Seam"
    /// Connections still authenticating. When full, one goes (a silent one first, chosen at
    /// random), so idle sockets can't lock the phone out.
    public static let maxPending = 16
    /// Per bucket (`addressKey`): an IPv4 address, a link-local IPv6 address, or another IPv6 /64.
    public static let maxPendingPerAddress = 4
    public static let authDeadline: TimeInterval = 10
    /// The phone writes its handshake as soon as it connects: a silent socket goes after this.
    public static let firstBytesDeadline: TimeInterval = 2
    /// A link closed on purpose waits this long at most for what was already sent to be written.
    public static let closeGrace: TimeInterval = 2
    /// Over Tor a handshake crosses about six relays each way: more time before a stream goes.
    public static let torFirstBytesDeadline: TimeInterval = 15
    public static let torAuthDeadline: TimeInterval = 45

    public let events: AsyncStream<LanEvent>
    private let continuation: AsyncStream<LanEvent>.Continuation
    private let device: SeamDevice
    /// False: no TCP listener; streams come in through `adopt` (the Tor carrier).
    private let listens: Bool
    private let advertise: Bool
    private let deadline: TimeInterval
    private let firstBytesDeadline: TimeInterval
    /// Tests only: loopback is otherwise refused, both as an interface and as a peer address.
    let allowLoopback: Bool
    // Everything below is only touched on this queue
    private let queue = DispatchQueue(label: "com.avetero.seam.lan")
    private var listener: NWListener?
    private var stopped = false
    private var attempt = 0
    /// Oldest first.
    private var pending: [Link] = []
    private var authed: [String: Link] = [:]

    /// Only touched on `queue`.
    private final class Link: @unchecked Sendable {
        let pipe: any Pipe
        let channel: LanChannel
        let address: String
        var peer: String?
        var heard = false
        var closed = false
        /// Frames handed to the pipe and not yet written.
        var unsent = 0

        init(pipe: any Pipe, channel: LanChannel, address: String) {
            self.pipe = pipe
            self.channel = channel
            self.address = address
        }
    }

    /// `advertise` false: no Bonjour (tests connect by port).
    public convenience init(device: SeamDevice, advertise: Bool = true, authDeadline: TimeInterval = LanServer.authDeadline) {
        self.init(device: device, advertise: advertise, authDeadline: authDeadline, firstBytesDeadline: Self.firstBytesDeadline, allowLoopback: false)
    }

    /// The Tor carrier: no listener; each stream from the onion service comes in through `adopt`.
    public static func tor(device: SeamDevice) -> LanServer {
        LanServer(device: device, listens: false, advertise: false, authDeadline: torAuthDeadline, firstBytesDeadline: torFirstBytesDeadline, allowLoopback: false)
    }

    init(device: SeamDevice, listens: Bool = true, advertise: Bool, authDeadline: TimeInterval, firstBytesDeadline: TimeInterval, allowLoopback: Bool) {
        self.device = device
        self.listens = listens
        self.advertise = advertise
        self.deadline = authDeadline
        self.firstBytesDeadline = firstBytesDeadline
        self.allowLoopback = allowLoopback
        (events, continuation) = AsyncStream.makeStream()
    }

    /// Listens on any free port and returns it once ready. If the listener fails later (a network
    /// change), it starts again by itself with backoff. Single use: after `stop()`, make a new one.
    public func start() async throws -> UInt16 {
        guard listens else { return 0 } // the Tor carrier: streams arrive through `adopt`
        return try await withCheckedThrowingContinuation { (done: CheckedContinuation<UInt16, Error>) in
            queue.async { self.listen(first: done) }
        }
    }

    /// Closes the listener and every link. Reports `.closed` for an authenticated phone, then ends `events`.
    public func stop() {
        queue.sync {
            stopped = true
            listener?.cancel()
            listener = nil
            for link in pending + Array(authed.values) { drop(link, graceful: true) }
        }
        continuation.finish()
    }

    /// Sends to an authenticated phone. False when it isn't connected (send it another way).
    public func send(_ frame: Seam_V1_ServerFrame, to peerId: String) -> Bool {
        guard let bytes = try? frame.serializedData() else { return false }
        return queue.sync {
            guard let link = authed[peerId] else { return false }
            guard let wire = try? link.channel.send(frame: bytes) else {
                drop(link)
                return false
            }
            link.unsent += 1
            WireDump.record(wire, out: true)
            link.pipe.write(wire) { [weak self] ok in
                link.unsent -= 1 // on `queue`: the pipe calls back there
                if !ok {
                    self?.drop(link)
                    link.pipe.cancel() // closed gracefully already: it's not coming
                } else if link.closed, link.unsent == 0 {
                    link.pipe.cancel() // the last frame of a graceful close is out
                }
            }
            return true
        }
    }

    /// Closes a peer's link (one that isn't, or is no longer, the paired phone).
    public func close(peerId: String) {
        queue.sync { if let link = authed[peerId] { drop(link, graceful: true) } }
    }

    // MARK: Private (on `queue`)

    private func parameters() -> NWParameters {
        let tcp = NWProtocolTCP.Options()
        tcp.noDelay = true
        // A phone that vanished (Wi‑Fi off, out of range) is noticed in about two minutes
        tcp.enableKeepalive = true
        tcp.keepaliveIdle = 60
        tcp.keepaliveInterval = 15
        tcp.keepaliveCount = 4
        let params = NWParameters(tls: nil, tcp: tcp)
        params.includePeerToPeer = false
        // Wi‑Fi and wired only: not mobile data, VPN tunnels (.other) or loopback
        params.prohibitedInterfaceTypes = allowLoopback ? [.cellular, .other] : [.cellular, .other, .loopback]
        return params
    }

    /// `first`: the start() call to answer. Later (re)starts after a failure answer nobody.
    private func listen(first: CheckedContinuation<UInt16, Error>? = nil) {
        guard !stopped else {
            first?.resume(throwing: CancellationError())
            return
        }
        let l: NWListener
        do {
            l = try NWListener(using: parameters())
        } catch {
            if let first { return first.resume(throwing: error) }
            return retry()
        }
        if advertise { l.service = NWListener.Service(name: Self.serviceName, type: Self.serviceType) }
        let once = Once()
        l.stateUpdateHandler = { [weak self, weak l] state in
            guard let self, let l else { return }
            switch state {
            case .ready:
                self.attempt = 0
                if let first { once.run { first.resume(returning: l.port?.rawValue ?? 0) } }
            case .failed(let e):
                l.cancel()
                guard self.listener === l else { return }
                self.listener = nil
                // Before it was ever up, start() reports it; afterwards, try again
                if let first, once.run({ first.resume(throwing: e) }) { return }
                self.retry()
            case .cancelled:
                if let first { once.run { first.resume(throwing: CancellationError()) } }
            default: break
            }
        }
        l.newConnectionHandler = { [weak self] c in self?.accept(c) }
        listener = l
        l.start(queue: queue)
    }

    private func retry() {
        attempt += 1
        let delay = Backoff().delay(attempt: attempt, jitter: .random(in: 0..<1))
        queue.asyncAfter(deadline: .now() + delay) { [weak self] in
            guard let self, self.listener == nil else { return }
            self.listen()
        }
    }

    private func accept(_ c: NWConnection) {
        guard case .hostPort(let host, _) = c.endpoint, Self.isLocal(host) || (allowLoopback && isLoopback(host)) else {
            return c.cancel()
        }
        // No paired phone: the core refuses to make a responder, so nobody gets in
        guard let channel = try? LanChannel.responder(device: device) else { return c.cancel() }
        take(ConnectionPipe(c, queue: queue), channel: channel, address: Self.addressKey(host))
    }

    /// A stream from the Mac's onion service, handled exactly like a Same Wi‑Fi connection. Only the
    /// paired phone (or, while pairing, the phone that scanned the code) can find the service, and
    /// the channel still has to authenticate it.
    public func adopt(_ stream: TorStream) {
        queue.async {
            guard !self.stopped, let channel = try? LanChannel.responder(device: self.device) else { return stream.close() }
            // Tor streams carry no address to bucket by: each is its own bucket, so only the overall limit applies
            self.take(TorPipe(stream, queue: self.queue), channel: channel, address: UUID().uuidString)
        }
    }

    /// A new link, which has to authenticate within the deadlines.
    private func take(_ pipe: any Pipe, channel: LanChannel, address: String) {
        // Room for the newcomer: first within its own address, then overall
        let same = pending.filter { $0.address == address }
        if same.count >= Self.maxPendingPerAddress, let v = Self.victim(same, heard: \.heard) { drop(v) }
        if pending.count >= Self.maxPending, let v = Self.victim(pending, heard: \.heard) { drop(v) }
        let link = Link(pipe: pipe, channel: channel, address: address)
        pending.append(link)
        queue.asyncAfter(deadline: .now() + firstBytesDeadline) { [weak self, weak link] in
            if let link, !link.heard { self?.drop(link) }
        }
        queue.asyncAfter(deadline: .now() + deadline) { [weak self, weak link] in
            if let link, link.peer == nil { self?.drop(link) }
        }
        pipe.start { [weak self, weak link] data, done in
            guard let self, let link, !link.closed else { return }
            if let data, !data.isEmpty {
                link.heard = true
                if !self.handle(link, data) { return self.drop(link) }
            }
            if done { self.drop(link) }
        }
    }

    private func isLoopback(_ host: NWEndpoint.Host) -> Bool {
        switch host {
        case .ipv4(let a): return a == .loopback
        case .ipv6(let a): return a == .loopback
        default: return false
        }
    }

    /// False closes the link: the channel refused the bytes (a stranger, tampering, garbage).
    private func handle(_ link: Link, _ data: Data) -> Bool {
        WireDump.record(data, out: false)
        guard let out = try? link.channel.receive(bytes: data) else { return false }
        if !out.send.isEmpty {
            WireDump.record(out.send, out: true)
            link.pipe.write(out.send) { _ in }
        }
        if let peer = out.authenticated {
            pending.removeAll { $0 === link }
            link.peer = peer
            let old = authed[peer]
            authed[peer] = link // first, so the old link's close isn't reported
            if let old { drop(old, graceful: true) }
            continuation.yield(.authenticated(peerId: peer))
        }
        guard let peer = link.peer else { return true }
        for bytes in out.frames {
            // From the authenticated phone, but still only the frames this link carries
            let pairing = link.channel.isPairing()
            guard let frame = try? Seam_V1_ClientFrame(serializedBytes: bytes) else {
                if pairing { return false }
                continue
            }
            switch frame.frame {
            case .send(let envelope): continuation.yield(.envelope(peerId: peer, envelope))
            case .delivered(let d): continuation.yield(.delivered(peerId: peer, msgID: d.msgID))
            // Only a pairing phone needs the bundle, and only this Mac's
            case .getBundle(let g) where pairing && g.deviceKey == device.relayKey():
                continuation.yield(.bundleRequest(peerId: peer))
            // A pairing channel carries the pairing exchange and nothing else: anything more closes it
            default: if pairing { return false }
            }
        }
        return true
    }

    /// `graceful`: closed on purpose, so frames already passed to `send` are written first (up to
    /// `closeGrace`); it's gone for routing at once. Errors and strangers go abruptly.
    private func drop(_ link: Link, graceful: Bool = false) {
        guard !link.closed else { return }
        link.closed = true
        if graceful && link.unsent > 0 {
            queue.asyncAfter(deadline: .now() + Self.closeGrace) { link.pipe.cancel() }
        } else {
            link.pipe.cancel()
        }
        pending.removeAll { $0 === link }
        if let peer = link.peer, authed[peer] === link {
            authed[peer] = nil
            continuation.yield(.closed(peerId: peer))
        }
    }
}
