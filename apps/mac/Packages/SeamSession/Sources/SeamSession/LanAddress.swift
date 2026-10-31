// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import Network

/// Who may connect on Same Wi‑Fi, and how waiting connections are bucketed and evicted.
extension LanServer {
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
}
