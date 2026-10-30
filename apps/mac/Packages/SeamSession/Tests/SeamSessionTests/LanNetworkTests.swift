// SPDX-License-Identifier: AGPL-3.0-only
import Darwin
import Foundation
import Network
import SeamCore
import SeamProtocol
import Testing

@testable import SeamSession

/// A Wi‑Fi or Ethernet interface (en*) that's up with a private IPv4 address: what a real phone
/// would reach the Mac on. CI machines usually have none, and the test is skipped there.
func privateLANAddress() -> String? {
    var list: UnsafeMutablePointer<ifaddrs>?
    guard getifaddrs(&list) == 0, let first = list else { return nil }
    defer { freeifaddrs(list) }
    for p in sequence(first: first, next: { $0.pointee.ifa_next }) {
        let i = p.pointee
        guard let sa = i.ifa_addr, sa.pointee.sa_family == UInt8(AF_INET), String(cString: i.ifa_name).hasPrefix("en"),
              i.ifa_flags & UInt32(IFF_UP | IFF_RUNNING) == UInt32(IFF_UP | IFF_RUNNING) else { continue }
        let addr = sa.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { $0.pointee.sin_addr }
        let host = NWEndpoint.Host(String(cString: inet_ntoa(addr)))
        if LanServer.isLocal(host) { return "\(host)" }
    }
    return nil
}

/// Every `_seam._tcp` service Bonjour finds within `seconds` (another Seam on the network shows up too).
func browseSeam(within seconds: Double) async -> [NWEndpoint] {
    let browser = NWBrowser(for: .bonjour(type: LanServer.serviceType, domain: nil), using: .tcp)
    let found = Found()
    browser.browseResultsChangedHandler = { results, _ in found.set(results.map(\.endpoint)) }
    browser.start(queue: .global())
    try? await Task.sleep(for: .seconds(seconds))
    browser.cancel()
    return found.get()
}

final class Found: @unchecked Sendable {
    private let lock = NSLock()
    private var endpoints: [NWEndpoint] = []
    func set(_ e: [NWEndpoint]) { lock.lock(); endpoints = e; lock.unlock() }
    func get() -> [NWEndpoint] { lock.lock(); defer { lock.unlock() }; return endpoints }
}

/// The production listener, found and reached like the phone does: Bonjour, then a non-loopback
/// address. No test-only switches.
@Suite(.serialized, .enabled(if: privateLANAddress() != nil, "needs Wi‑Fi or Ethernet with a private IPv4 address"))
struct LanNetworkTests {
    @Test func aPhoneFindsAndUsesTheRealListener() async throws {
        let (mac, phone) = try pairedDevices()
        let server = LanServer(device: mac) // production: Bonjour on, loopback refused
        defer { server.stop() }
        let port = try await server.start()

        let services = await browseSeam(within: 3)
        #expect(!services.isEmpty, "Bonjour found no _seam._tcp service")

        // Not loopback, so the path is the one a phone takes
        let params = NWParameters.tcp
        params.prohibitedInterfaceTypes = [.loopback]
        var connected: TestPhone?
        var ours: NWEndpoint?
        var errors: [String] = []
        for service in services {
            do {
                let s = try await TestSocket(endpoint: service, using: params)
                let path = s.connection.currentPath
                connected = try await TestPhone.connect(phone, macID: mac.id(), socket: s)
                ours = service
                if case .hostPort(let host, _)? = path?.remoteEndpoint {
                    print("LAN test: \(service) resolved to \(host) over \(path?.availableInterfaces.map(\.name) ?? [])")
                    #expect(LanServer.isLocal(host), "resolved to \(host)")
                }
                break
            } catch {
                errors.append("\(service): \(error)") // another Seam on the network, or refused
            }
        }
        let p = try #require(connected, "couldn't reach our listener: \(errors)")
        #expect(await collect(server, 1) == [.authenticated(peerId: phone.id())])

        // A frame each way, and the Mac's Delivered
        let env = Seam_V1_Envelope.with { $0.msgID = Data([4, 2]) }
        try p.send(.with { $0.send = env })
        #expect(await collect(server, 1) == [.envelope(peerId: phone.id(), env)])
        #expect(server.send(.with { $0.delivered = .with { $0.msgID = env.msgID } }, to: phone.id()))
        #expect(try await p.receiveFrame()?.delivered.msgID == env.msgID)
        #expect(server.send(.with { $0.deliver = env }, to: phone.id()))
        #expect(try await p.receiveFrame()?.deliver == env)

        // A phone paired with some other Mac, on the same address, never gets in
        let (otherMac, otherPhone) = try pairedDevices()
        let stranger = try await TestSocket(endpoint: try #require(ours), using: params)
        await #expect(throws: (any Error).self) { _ = try await TestPhone.connect(otherPhone, macID: otherMac.id(), socket: stranger) }
        #expect(await collect(server, 1, within: 1).isEmpty)
    }
}
