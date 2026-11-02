// SPDX-License-Identifier: AGPL-3.0-only
import Darwin
import Foundation
import Network
import Testing

@testable import SeamSession

/// The addresses of this Mac's Wi‑Fi and wired interfaces, if any (CI machines often have none).
private func lanAddresses() -> [String] {
    let lan = MacSameWifi.lanInterfaces()
    var list: UnsafeMutablePointer<ifaddrs>?
    guard getifaddrs(&list) == 0, let first = list else { return [] }
    defer { freeifaddrs(list) }
    var out: [String] = []
    for p in sequence(first: first, next: { $0.pointee.ifa_next }) {
        guard let sa = p.pointee.ifa_addr, sa.pointee.sa_family == UInt8(AF_INET), lan.contains(String(cString: p.pointee.ifa_name)) else { continue }
        var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
        if getnameinfo(sa, socklen_t(sa.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 {
            out.append(String(cString: host))
        }
    }
    return out
}

final class Found: @unchecked Sendable {
    private let lock = NSLock()
    private var names: [String] = []
    func set(_ n: [String]) { lock.withLock { names = n } }
    func get() -> [String] { lock.withLock { names } }
}

@Suite(.serialized)
struct SameWifiTests {
    /// Rule 7: a connection that arrives on loopback, a VPN tunnel or any other interface that
    /// isn't Wi‑Fi or wired Ethernet is refused.
    @Test func onlyWifiAndWiredAddressesCount() {
        let wifi = MacSameWifi()
        #expect(!wifi.isLanAddress(ip: "127.0.0.1"))
        #expect(!wifi.isLanAddress(ip: "::1"))
        #expect(!wifi.isLanAddress(ip: "192.0.2.1"), "an address this Mac doesn't have")
        #expect(!MacSameWifi.lanInterfaces().contains("lo0"))
        #expect(!MacSameWifi.lanInterfaces().contains { $0.hasPrefix("utun") }, "VPN tunnels never")
        for address in lanAddresses() { #expect(wifi.isLanAddress(ip: address), "\(address)") }
    }

    /// The announcement a phone browses for: `_seam._tcp`, named "Seam" whoever's Mac it is.
    @Test(.enabled(if: !lanAddresses().isEmpty, "needs Wi‑Fi or Ethernet"))
    func thePhoneFindsSeamUnderAFixedName() async throws {
        let wifi = MacSameWifi()
        wifi.announce(port: 54321)
        defer { wifi.stopAnnouncing() }
        let browser = NWBrowser(for: .bonjour(type: MacSameWifi.serviceType, domain: nil), using: .tcp)
        let found = Found()
        browser.browseResultsChangedHandler = { results, _ in
            found.set(results.compactMap { if case .service(let name, _, _, _) = $0.endpoint { name } else { nil } })
        }
        browser.start(queue: .global())
        defer { browser.cancel() }
        for _ in 0..<30 where !found.get().contains(MacSameWifi.serviceName) {
            try await Task.sleep(for: .milliseconds(100))
        }
        #expect(found.get().contains(MacSameWifi.serviceName))
    }
}
