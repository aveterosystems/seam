// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import Network
import SeamCore
import SeamProtocol

/// What the direct link reports, in order, on `LanServer.events`. `peerId` is the authenticated
/// paired device the link belongs to.
public enum LanEvent: Sendable, Equatable {
    /// A paired phone proved who it is; it may now be sent to.
    case authenticated(peerId: String)
    /// That phone's link closed (not reported when a newer link from it replaced this one).
    case closed(peerId: String)
    case envelope(peerId: String, Seam_V1_Envelope)
    case delivered(peerId: String, msgID: Data)
}

/// The Mac's side of the same‑Wi‑Fi link (protocol/README.md, "The direct connection"): a TCP
/// listener announced over Bonjour, on Wi‑Fi and wired Ethernet only. All the cryptography is the
/// core's `LanChannel`; this only moves bytes, enforces the limits, and turns frames into events.
public final class LanServer: @unchecked Sendable {
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

    public let events: AsyncStream<LanEvent>
    private let continuation: AsyncStream<LanEvent>.Continuation
    private let device: SeamDevice
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
        let connection: NWConnection
        let channel: LanChannel
        let address: String
        var peer: String?
        var heard = false
        var closed = false
        /// Frames handed to the connection and not yet written.
        var unsent = 0

        init(connection: NWConnection, channel: LanChannel, address: String) {
            self.connection = connection
            self.channel = channel
            self.address = address
        }
    }

    /// `advertise` false: no Bonjour (tests connect by port).
    public convenience init(device: SeamDevice, advertise: Bool = true, authDeadline: TimeInterval = LanServer.authDeadline) {
        self.init(device: device, advertise: advertise, authDeadline: authDeadline, firstBytesDeadline: Self.firstBytesDeadline, allowLoopback: false)
    }

    init(device: SeamDevice, advertise: Bool, authDeadline: TimeInterval, firstBytesDeadline: TimeInterval, allowLoopback: Bool) {
        self.device = device
        self.advertise = advertise
        self.deadline = authDeadline
        self.firstBytesDeadline = firstBytesDeadline
        self.allowLoopback = allowLoopback
        (events, continuation) = AsyncStream.makeStream()
    }

    /// Listens on any free port and returns it once ready. If the listener fails later (a network
    /// change), it starts again by itself with backoff. Single use: after `stop()`, make a new one.
    public func start() async throws -> UInt16 {
        try await withCheckedThrowingContinuation { (done: CheckedContinuation<UInt16, Error>) in
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
            link.connection.send(content: wire, completion: .contentProcessed { [weak self] error in
                link.unsent -= 1 // on `queue`: the connection runs there
                if error != nil {
                    self?.drop(link)
                    link.connection.cancel() // closed gracefully already: it's not coming
                } else if link.closed, link.unsent == 0 {
                    link.connection.cancel() // the last frame of a graceful close is out
                }
            })
            return true
        }
    }

    /// Closes a peer's link (one that isn't, or is no longer, the paired phone).
    public func close(peerId: String) {
        queue.sync { if let link = authed[peerId] { drop(link, graceful: true) } }
    }

    /// Whether a connection may come from `host`: private, link-local or unique-local only, never
    /// the internet (a port forward, a VPN) or this Mac itself.
    static func isLocal(_ host: NWEndpoint.Host) -> Bool {
        switch host {
        case .ipv4(let a): return isLocal(v4: [UInt8](a.rawValue))
        case .ipv6(let a):
            let b = [UInt8](a.rawValue)
            guard b.count == 16 else { return false }
            if b[0..<10].allSatisfy({ $0 == 0 }) && b[10] == 0xFF && b[11] == 0xFF { return isLocal(v4: Array(b[12...])) } // ::ffff:a.b.c.d
            return (b[0] == 0xFE && b[1] & 0xC0 == 0x80) || b[0] & 0xFE == 0xFC // fe80::/10, fc00::/7
        default: return false
        }
    }

    /// Which waiting connection makes room: one that has sent nothing yet, at random, else any at
    /// random. Random, so an attacker can't predict which of its own (or the phone's) goes.
    static func victim<T>(_ candidates: [T], heard: (T) -> Bool) -> T? {
        candidates.filter { !heard($0) }.randomElement() ?? candidates.randomElement()
    }

    /// The per-address limit's bucket: the IPv4 address (IPv4-mapped too), the exact address for
    /// link-local IPv6 (the whole LAN shares fe80::/64, so the phone would share an attacker's
    /// bucket), and the /64 for other IPv6 (one device can hold a whole /64).
    static func addressKey(_ host: NWEndpoint.Host) -> String {
        guard case .ipv6(let a) = host else { return "\(host)" }
        let b = [UInt8](a.rawValue)
        guard b.count == 16 else { return "\(host)" }
        if b[0..<10].allSatisfy({ $0 == 0 }) && b[10] == 0xFF && b[11] == 0xFF { return b[12...].map(String.init).joined(separator: ".") }
        if b[0] == 0xFE && b[1] & 0xC0 == 0x80 { return b.map { String(format: "%02x", $0) }.joined() } // fe80::/10, without the zone
        return b[..<8].map { String(format: "%02x", $0) }.joined() + "::/64"
    }

    private static func isLocal(v4 b: [UInt8]) -> Bool {
        guard b.count == 4 else { return false }
        return b[0] == 10 || (b[0] == 172 && b[1] & 0xF0 == 16) || (b[0] == 192 && b[1] == 168) || (b[0] == 169 && b[1] == 254)
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
        let address = Self.addressKey(host)
        // Room for the newcomer: first within its own address, then overall
        let same = pending.filter { $0.address == address }
        if same.count >= Self.maxPendingPerAddress, let v = Self.victim(same, heard: \.heard) { drop(v) }
        if pending.count >= Self.maxPending, let v = Self.victim(pending, heard: \.heard) { drop(v) }
        let link = Link(connection: c, channel: channel, address: address)
        pending.append(link)
        c.stateUpdateHandler = { [weak self, weak link] state in
            switch state {
            case .failed, .cancelled: if let link { self?.drop(link) }
            default: break
            }
        }
        c.start(queue: queue)
        queue.asyncAfter(deadline: .now() + firstBytesDeadline) { [weak self, weak link] in
            if let link, !link.heard { self?.drop(link) }
        }
        queue.asyncAfter(deadline: .now() + deadline) { [weak self, weak link] in
            if let link, link.peer == nil { self?.drop(link) }
        }
        read(link)
    }

    private func isLoopback(_ host: NWEndpoint.Host) -> Bool {
        switch host {
        case .ipv4(let a): return a == .loopback
        case .ipv6(let a): return a == .loopback
        default: return false
        }
    }

    private func read(_ link: Link) {
        link.connection.receive(minimumIncompleteLength: 1, maximumLength: 65536) { [weak self] data, _, complete, error in
            guard let self, !link.closed else { return }
            if let data, !data.isEmpty {
                link.heard = true
                if !self.handle(link, data) { return self.drop(link) }
            }
            if complete || error != nil { return self.drop(link) }
            self.read(link)
        }
    }

    /// False closes the link: the channel refused the bytes (a stranger, tampering, garbage).
    private func handle(_ link: Link, _ data: Data) -> Bool {
        WireDump.record(data, out: false)
        guard let out = try? link.channel.receive(bytes: data) else { return false }
        if !out.send.isEmpty {
            WireDump.record(out.send, out: true)
            link.connection.send(content: out.send, completion: .idempotent)
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
            guard let frame = try? Seam_V1_ClientFrame(serializedBytes: bytes) else { continue }
            switch frame.frame {
            case .send(let envelope): continuation.yield(.envelope(peerId: peer, envelope))
            case .delivered(let d): continuation.yield(.delivered(peerId: peer, msgID: d.msgID))
            default: break
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
            queue.asyncAfter(deadline: .now() + Self.closeGrace) { link.connection.cancel() }
        } else {
            link.connection.cancel()
        }
        pending.removeAll { $0 === link }
        if let peer = link.peer, authed[peer] === link {
            authed[peer] = nil
            continuation.yield(.closed(peerId: peer))
        }
    }
}

/// Runs its body at most once, from any thread.
final class Once: @unchecked Sendable {
    private let lock = NSLock()
    private var done = false

    /// True if the body ran now.
    @discardableResult
    func run(_ body: () -> Void) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard !done else { return false }
        done = true
        body()
        return true
    }
}

/// Debug builds only, and only when started with SEAM_WIRE_DUMP=<file>: appends every byte the
/// direct link sends or receives on the socket (before any decryption), so a test can check that
/// nothing readable crosses the network. Compiled out of Release.
enum WireDump {
    #if DEBUG
    private static let file: FileHandle? = ProcessInfo.processInfo.environment["SEAM_WIRE_DUMP"].flatMap { path in
        FileManager.default.createFile(atPath: path, contents: nil, attributes: [.posixPermissions: 0o600])
        return FileHandle(forWritingAtPath: path)
    }
    private static let lock = NSLock()
    #endif

    static func record(_ bytes: Data, out: Bool) {
        #if DEBUG
        guard let file else { return }
        lock.lock()
        defer { lock.unlock() }
        file.write(Data((out ? ">" : "<").utf8) + withUnsafeBytes(of: UInt32(bytes.count).bigEndian) { Data($0) } + bytes)
        #endif
    }
}
