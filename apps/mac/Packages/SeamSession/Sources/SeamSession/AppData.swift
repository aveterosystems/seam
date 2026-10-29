// SPDX-License-Identifier: AGPL-3.0-only
import Foundation

/// The Mac's own settings, sealed inside the core's state (`setAppData`) next to the keys, so the
/// group id can never drift apart from the identity that belongs to it (SR-2-06).
///
/// Encoding: version (1) | group id length (u8) | group id | relay URL length (u16 BE) | URL UTF-8.
public struct AppData: Equatable, Sendable {
    public var groupID: Data
    public var relayURL: String

    public init(groupID: Data, relayURL: String) {
        self.groupID = groupID
        self.relayURL = relayURL
    }

    /// 16 random bytes: a new pairing group on the relay.
    public static func newGroupID() -> Data {
        var rng = SystemRandomNumberGenerator()
        return Data((0..<16).map { _ in UInt8.random(in: .min ... .max, using: &rng) })
    }

    public func encoded() -> Data {
        let url = Data(relayURL.utf8.prefix(Int(UInt16.max)))
        var out = Data([1, UInt8(clamping: groupID.count)])
        out += groupID.prefix(255)
        out += [UInt8(url.count >> 8), UInt8(url.count & 0xFF)]
        out += url
        return out
    }

    /// Nil for anything that isn't a complete v1 encoding with a 16-byte group id.
    public init?(decoding data: Data) {
        let b = [UInt8](data)
        guard b.count >= 4, b[0] == 1 else { return nil }
        let g = Int(b[1])
        guard g == 16, b.count >= 2 + g + 2 else { return nil }
        let u = Int(b[2 + g]) << 8 | Int(b[3 + g])
        guard b.count == 4 + g + u, let url = String(bytes: b[(4 + g)...], encoding: .utf8) else { return nil }
        self.init(groupID: Data(b[2..<(2 + g)]), relayURL: url)
    }
}
