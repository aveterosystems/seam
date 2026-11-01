// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import SeamCore
import SeamProtocol

/// Sealed envelopes for the phone that haven't been delivered yet, oldest first: `waiting` for an
/// allowed path, and `sent` directly but not yet acked by the phone's Delivered. Saved to disk
/// (`OutboxFile`) before an envelope is handed to any path, so a quit or crash loses nothing.
public struct Outbox: Equatable, Sendable {
    /// Per list; the oldest goes first.
    public static let cap = 100
    /// When an envelope sets no TTL.
    static let defaultTTL: TimeInterval = 86_400

    public private(set) var waiting: [Seam_V1_Envelope] = []
    public private(set) var sent: [Seam_V1_Envelope] = []

    public init() {}

    public var isEmpty: Bool { waiting.isEmpty && sent.isEmpty }

    /// Queues it unless it's already here (a retry).
    public mutating func add(_ envelope: Seam_V1_Envelope) {
        guard !contains(envelope.msgID) else { return }
        waiting.append(envelope)
        if waiting.count > Self.cap { waiting.removeFirst(waiting.count - Self.cap) }
    }

    /// Handed to the direct link: kept until the phone's Delivered.
    public mutating func markSent(_ msgID: Data) {
        guard let i = waiting.firstIndex(where: { $0.msgID == msgID }) else { return }
        sent.append(waiting.remove(at: i))
        if sent.count > Self.cap { sent.removeFirst(sent.count - Self.cap) }
    }

    /// Delivered (the phone acked it).
    public mutating func remove(_ msgID: Data) {
        waiting.removeAll { $0.msgID == msgID }
        sent.removeAll { $0.msgID == msgID }
    }

    /// The direct link dropped: what it never acked waits again, ahead of the rest.
    public mutating func requeueSent() {
        waiting = Array((sent + waiting).prefix(Self.cap))
        sent = []
    }

    /// True if anything went.
    public mutating func dropExpired(now: Date) -> Bool {
        let before = waiting.count + sent.count
        waiting.removeAll { Self.expired($0, now: now) }
        sent.removeAll { Self.expired($0, now: now) }
        return waiting.count + sent.count != before
    }

    public mutating func clear() {
        waiting = []
        sent = []
    }

    func contains(_ msgID: Data) -> Bool {
        waiting.contains { $0.msgID == msgID } || sent.contains { $0.msgID == msgID }
    }

    static func expired(_ e: Seam_V1_Envelope, now: Date) -> Bool {
        let ttl = e.ttlS == 0 ? defaultTTL : TimeInterval(e.ttlS)
        return now.timeIntervalSince1970 * 1000 > Double(e.sentAt) + ttl * 1000
    }

    // Encoding: "SOB1" | for waiting, then sent: count (u32 BE) | each: length (u32 BE) | Envelope bytes

    static let magic = Data("SOB1".utf8)
    static let maxEnvelope = 1_200_000

    public func encoded() throws -> Data {
        var out = Self.magic
        for list in [waiting, sent] {
            out += Self.u32(list.count)
            for e in list {
                let b = try e.serializedData()
                out += Self.u32(b.count) + b
            }
        }
        return out
    }

    /// Nil for anything malformed: the caller starts empty rather than trusting part of it.
    public init?(decoding data: Data) {
        let b = [UInt8](data)
        guard b.count >= 4, Data(b[0..<4]) == Self.magic else { return nil }
        var at = 4
        func u32() -> Int? {
            guard b.count - at >= 4 else { return nil }
            defer { at += 4 }
            return b[at..<(at + 4)].reduce(0) { $0 << 8 | Int($1) }
        }
        var lists: [[Seam_V1_Envelope]] = []
        for _ in 0..<2 {
            guard let n = u32(), n <= Self.cap else { return nil }
            var list: [Seam_V1_Envelope] = []
            for _ in 0..<n {
                guard let len = u32(), len <= Self.maxEnvelope, b.count - at >= len,
                      let e = try? Seam_V1_Envelope(serializedBytes: Data(b[at..<(at + len)])) else { return nil }
                at += len
                list.append(e)
            }
            lists.append(list)
        }
        guard at == b.count else { return nil }
        waiting = lists[0]
        sent = lists[1]
    }

    private static func u32(_ n: Int) -> Data {
        withUnsafeBytes(of: UInt32(n).bigEndian) { Data($0) }
    }
}

/// Where the outbox is kept: written durably and atomically, mode 0600 (StateFile's rules).
public struct OutboxFile: Sendable {
    public let file: StateFile

    public init(url: URL) {
        file = StateFile(url: url)
    }

    /// What was saved, minus anything expired. Empty for a missing, unreadable or damaged file:
    /// never a crash, and never part of one.
    public func load(now: Date) -> Outbox {
        guard let data = try? file.read(), var box = Outbox(decoding: data) else { return Outbox() }
        _ = box.dropExpired(now: now)
        return box
    }

    public func save(_ box: Outbox) throws {
        try file.write(try box.encoded())
    }

    public func delete() {
        try? FileManager.default.removeItem(at: file.url)
    }
}

/// How the link to the phone is shown, everywhere it's shown (docs/design/design-system.md,
/// "The stitch"). The line, the icon and the words always come from here, together, so no view
/// decides any of them on its own.
public struct LinkLook: Equatable, Sendable {
    public enum Line: Sendable { case solid, dashed, dotted, broken }

    public let line: Line
    /// The path as an SF Symbol; nil when there's no path.
    public let symbol: String?
    /// The path's name, or, when not connected, what's happening.
    public let label: String
    public let connected: Bool
    /// Why, and what happens next: shown when the status is clicked. Nil when the label says it all.
    public var detail: String? = nil

    public static let sameWifi = LinkLook(line: .solid, symbol: "wifi", label: "Same Wi‑Fi", connected: true)
    /// A neutral globe, not the Tor onion (that needs the Tor Project's permission; docs/tor/11-wording.md).
    public static let throughTor = LinkLook(line: .dashed, symbol: "globe", label: "Through Tor", connected: true)
    public static let connectingTor = LinkLook(line: .dotted, symbol: "globe", label: "Connecting through Tor", connected: false)
    public static let torBlocked = LinkLook.offline("Tor is blocked on this network",
        detail: "This network stops Tor connections. Your phone can still connect on the same Wi‑Fi.")
    public static let torClock = LinkLook.offline("Tor needs the correct time. Check your clock",
        detail: "Tor won't connect while this Mac's clock is wrong. Turn on Set time automatically in System Settings.")
    public static let noSharedPath = LinkLook.offline("Your phone and this Mac have no connection type on in common",
        detail: "Turn on the same connection type, Same Wi‑Fi or Tor, on both devices.")
    public static let waitingOnWifi = LinkLook.offline("Waiting for your phone on the same Wi‑Fi",
        detail: "Only Same Wi‑Fi is on, so your phone connects when it's on this Mac's network. Turn on Tor to connect from anywhere.")
    public static let away = LinkLook.offline("Offline · will send when your phone is back",
        detail: "To save battery, your phone disconnects about a minute after its screen goes off. It's back when you unlock it or it has something to send. Copies from this Mac wait up to 10 minutes.")

    public static func offline(_ why: String, detail: String? = nil) -> LinkLook {
        LinkLook(line: .broken, symbol: nil, label: why, connected: false, detail: detail)
    }

    /// Paused on the phone (only the phone pauses): nothing syncs until it ends or the phone resumes.
    public static func paused(until: Date, now: Date, calendar: Calendar = .current) -> LinkLook {
        let label: String
        if until == .distantFuture {
            label = "Paused on your phone until you resume it there"
        } else {
            let time = until.formatted(date: .omitted, time: .shortened)
            let day = calendar.isDate(until, inSameDayAs: now) ? ""
                : calendar.isDate(until, inSameDayAs: calendar.date(byAdding: .day, value: 1, to: now) ?? now) ? "tomorrow "
                : until.formatted(date: .abbreviated, time: .omitted) + " "
            label = "Paused on your phone until \(day)\(time)"
        }
        return LinkLook(line: .broken, symbol: "pause.circle", label: label, connected: false,
            detail: "Nothing syncs until the pause ends. Only your phone can resume it.")
    }

    /// The one place a path becomes a look. With no route, Tor's own state says why when Tor is
    /// the way the phone would come (never a silent wait on a blocked network, decision 0004).
    public static func of(route: Seam_V1_Transport?, allowed: Set<Seam_V1_Transport>, tor: TorState = .ready) -> LinkLook {
        switch route {
        case .lan?: return .sameWifi
        case .tor?: return .throughTor
        default:
            if allowed.isEmpty { return .noSharedPath }
            guard allowed.contains(.tor) else { return .waitingOnWifi }
            switch tor {
            case .off, .starting: return .connectingTor
            case .blocked(.filtered): return .torBlocked
            case .blocked(.clockSkewed): return .torClock
            default: return .away
            }
        }
    }

    /// The same, from the core's connection rules. A pause on the phone comes first.
    public static func of(route: PeerPath?, allowed: [PeerPath], tor: TorState = .ready, pausedUntil: Date? = nil, now: Date = Date()) -> LinkLook {
        if let pausedUntil, pausedUntil > now { return .paused(until: pausedUntil, now: now) }
        return of(route: route?.transport, allowed: Set(allowed.map(\.transport)), tor: tor)
    }

    /// The whole line in words, for VoiceOver and anywhere there's no room for the icon.
    public var sentence: String { connected ? "Connected · \(label)" : label }
}

extension PeerPath {
    /// In Hello form.
    public var transport: Seam_V1_Transport {
        switch self {
        case .lan: .lan
        case .tor: .tor
        }
    }
}
