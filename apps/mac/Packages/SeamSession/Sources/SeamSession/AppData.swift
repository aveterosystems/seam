// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import SeamProtocol

/// The Mac's own settings, sealed inside the core's state (`setAppData`) next to the keys, so the
/// group id can never drift apart from the identity that belongs to it (SR-2-06).
///
/// Encoding: version | group id length (u8) | group id | relay URL length (u16 BE) | URL UTF-8, then
/// - v2: owner key length (u16 BE, > 0) | owner key
/// - v3: owner key length (u16 BE, may be 0) | owner key | Hello seq sent (u64 BE) | Hello seq
///   applied (u64 BE) | the phone's allowed list: 0xFF (never heard) or a count (u8) | values (u8 each)
///
/// The lowest version that holds the values is written, so an older Seam can still open the usual case.
public struct AppData: Equatable, Sendable {
    public var groupID: Data
    public var relayURL: String
    /// A private relay's owner key (decision 0007); nil on Seam's relay.
    public var ownerKey: Data?
    /// Hello ordering (protocol/README.md, "Which paths may be used"), for the current pairing.
    public var helloSeqSent: UInt64 = 0
    public var helloSeqApplied: UInt64 = 0
    /// The phone's switches from its last applied Hello; nil until it sends one.
    public var peerAllowed: Set<Seam_V1_Transport>?

    public init(groupID: Data, relayURL: String, ownerKey: Data? = nil) {
        self.groupID = groupID
        self.relayURL = relayURL
        self.ownerKey = ownerKey
    }

    /// 16 random bytes: a new pairing group on the relay.
    public static func newGroupID() -> Data {
        var rng = SystemRandomNumberGenerator()
        return Data((0..<16).map { _ in UInt8.random(in: .min ... .max, using: &rng) })
    }

    // MARK: Hello

    /// The seq for the next Hello: save this app data before the Hello leaves.
    public mutating func nextHelloSeq() -> UInt64 {
        helloSeqSent &+= 1
        return helloSeqSent
    }

    /// Applies a Hello only if its seq is higher than any before (0 never counts).
    public mutating func applyHello(seq: UInt64, allowed: [Seam_V1_Transport]) -> Bool {
        guard seq > helloSeqApplied else { return false }
        helloSeqApplied = seq
        peerAllowed = Set(allowed)
        return true
    }

    /// The phone was forgotten, or a new one paired: its Hello state starts again.
    public mutating func forgetPeer() {
        helloSeqSent = 0
        helloSeqApplied = 0
        peerAllowed = nil
    }

    /// UserDefaults keys earlier builds kept Hello state in; remove them once the app data is saved.
    public static let legacyKeys = ["peerTransports", "helloSeqSent", "helloSeqApplied", "peerTransportsAt"]

    /// Takes Hello state from those keys if this app data has none yet. True if anything changed.
    public mutating func migrateHelloState(from d: UserDefaults) -> Bool {
        guard peerAllowed == nil, helloSeqApplied == 0, let raw = d.array(forKey: "peerTransports") as? [Int] else { return false }
        peerAllowed = Set(raw.map { Seam_V1_Transport(rawValue: $0) ?? .unspecified })
        helloSeqSent = max(helloSeqSent, (d.object(forKey: "helloSeqSent") as? NSNumber)?.uint64Value ?? 0)
        helloSeqApplied = (d.object(forKey: "helloSeqApplied") as? NSNumber)?.uint64Value ?? 0
        return true
    }

    // MARK: Encoding

    public func encoded() -> Data {
        let url = Data(relayURL.utf8.prefix(Int(UInt16.max)))
        let key = ownerKey.map { $0.prefix(Int(UInt16.max)) } ?? Data()
        let v3 = helloSeqSent != 0 || helloSeqApplied != 0 || peerAllowed != nil
        var out = Data([v3 ? 3 : key.isEmpty ? 1 : 2, UInt8(clamping: groupID.count)])
        out += groupID.prefix(255)
        out += [UInt8(url.count >> 8), UInt8(url.count & 0xFF)]
        out += url
        if v3 || !key.isEmpty {
            out += [UInt8(key.count >> 8), UInt8(key.count & 0xFF)]
            out += key
        }
        if v3 {
            for n in [helloSeqSent, helloSeqApplied] { out += withUnsafeBytes(of: n.bigEndian) { Data($0) } }
            if let list = peerAllowed {
                let values = list.map { UInt8(clamping: $0.rawValue) }.sorted().prefix(254)
                out += [UInt8(values.count)] + values
            } else {
                out += [0xFF]
            }
        }
        return out
    }

    /// Nil for anything that isn't a complete v1, v2 or v3 encoding with a 16-byte group id.
    public init?(decoding data: Data) {
        var r = Reader(bytes: [UInt8](data))
        guard let v = r.u8(), (1...3).contains(v), let g = r.u8(), g == 16, let group = r.take(16),
              let u = r.u16(), let urlBytes = r.take(u), let url = String(bytes: urlBytes, encoding: .utf8)
        else { return nil }
        self.init(groupID: Data(group), relayURL: url)
        if v >= 2 {
            guard let k = r.u16(), v == 3 || k > 0, let key = r.take(k) else { return nil }
            ownerKey = key.isEmpty ? nil : Data(key)
        }
        if v == 3 {
            guard let sent = r.u64(), let applied = r.u64(), let count = r.u8() else { return nil }
            helloSeqSent = sent
            helloSeqApplied = applied
            if count != 0xFF {
                guard let values = r.take(Int(count)) else { return nil }
                peerAllowed = Set(values.map { Seam_V1_Transport(rawValue: Int($0)) ?? .unspecified })
            }
        }
        guard r.atEnd else { return nil }
    }

    private struct Reader {
        let bytes: [UInt8]
        var at = 0
        var atEnd: Bool { at == bytes.count }

        mutating func take(_ n: Int) -> ArraySlice<UInt8>? {
            guard n >= 0, bytes.count - at >= n else { return nil }
            defer { at += n }
            return bytes[at..<(at + n)]
        }
        mutating func u8() -> UInt8? { take(1)?.first }
        mutating func u16() -> Int? { take(2).map { Int($0.first!) << 8 | Int($0.last!) } }
        mutating func u64() -> UInt64? { take(8)?.reduce(0) { $0 << 8 | UInt64($1) } }
    }
}
