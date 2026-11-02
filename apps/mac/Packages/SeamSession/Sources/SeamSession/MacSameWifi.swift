// SPDX-License-Identifier: AGPL-3.0-only
import Darwin
import dnssd
import Foundation
import SeamCore
import SystemConfiguration

/// Same Wi‑Fi as macOS provides it: the Bonjour announcement, and which local addresses are on
/// Wi‑Fi or wired Ethernet. A connection on anything else (a VPN tunnel, mobile data, a bridge,
/// loopback) is refused, as Network.framework's listener did.
public final class MacSameWifi: SameWifi, @unchecked Sendable {
    /// Fixed, so the Bonjour name doesn't say whose Mac it is.
    public static let serviceName = "Seam"
    public static let serviceType = "_seam._tcp"

    private let lock = NSLock()
    private var service: DNSServiceRef?
    /// mDNSResponder expects a reference to be freed on the queue it's scheduled on.
    private let queue = DispatchQueue(label: "com.avetero.seam.same-wifi")

    public init() {}

    deinit {
        stopAnnouncing()
    }

    public func announce(port: UInt16) {
        stopAnnouncing()
        var ref: DNSServiceRef?
        let status = DNSServiceRegister(&ref, 0, 0, Self.serviceName, Self.serviceType, nil, nil, port.bigEndian, 0, nil, nil, nil)
        guard status == kDNSServiceErr_NoError, let ref else { return }
        DNSServiceSetDispatchQueue(ref, queue)
        lock.withLock { service = ref }
    }

    public func stopAnnouncing() {
        if let ref = lock.withLock({ () -> DNSServiceRef? in defer { service = nil }; return service }) {
            queue.sync { DNSServiceRefDeallocate(ref) } // no callback was given, so this can't wait on itself
        }
    }

    /// Only when every interface holding the address is Wi‑Fi or wired Ethernet.
    public func isLanAddress(ip: String) -> Bool {
        let holders = Self.interfaces(holding: ip)
        let lan = Self.lanInterfaces()
        return !holders.isEmpty && holders.allSatisfy(lan.contains)
    }

    /// Wi‑Fi and Ethernet interfaces by BSD name (en0, en5…), as System Settings lists them.
    /// Asked each time, so it's right from launch and after a network change.
    static func lanInterfaces() -> Set<String> {
        let all = SCNetworkInterfaceCopyAll() as? [SCNetworkInterface] ?? []
        let types: Set<String> = [kSCNetworkInterfaceTypeIEEE80211 as String, kSCNetworkInterfaceTypeEthernet as String]
        return Set(all.compactMap { i in
            guard let type = SCNetworkInterfaceGetInterfaceType(i) as String?, types.contains(type) else { return nil }
            return SCNetworkInterfaceGetBSDName(i) as String?
        })
    }

    /// Every interface this address is on (usually one).
    static func interfaces(holding ip: String) -> Set<String> {
        var list: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&list) == 0, let first = list else { return [] }
        defer { freeifaddrs(list) }
        var out: Set<String> = []
        for p in sequence(first: first, next: { $0.pointee.ifa_next }) {
            guard let sa = p.pointee.ifa_addr, [AF_INET, AF_INET6].contains(Int32(sa.pointee.sa_family)) else { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            guard getnameinfo(sa, socklen_t(sa.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 else { continue }
            // Link-local IPv6 comes back with its zone ("fe80::1%en0")
            let address = String(cString: host).split(separator: "%").first.map(String.init) ?? ""
            if address == ip { out.insert(String(cString: p.pointee.ifa_name)) }
        }
        return out
    }
}
