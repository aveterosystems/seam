// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import Network
import SeamCore
import SeamProtocol
import Testing

@testable import SeamSession

/// A Mac and phone paired through the real flow (as in SeamCoreTests).
func pairedDevices() throws -> (mac: SeamDevice, phone: SeamDevice) {
    let mac = try SeamDevice.generate()
    let phone = try SeamDevice.generate()
    let offer = mac.startPairing()
    let start = try phone.pairWith(
        identityKey: mac.identityKey(), relayKey: mac.relayKey(), name: "Mac",
        bundle: try mac.prekeyBundles(count: 1)[0], token: offer.token, myName: "Phone", myModel: "Test"
    )
    let request = try mac.acceptPairing(envelope: start.envelope)
    try mac.confirmPairing(peerId: request.peerId)
    let accept = Data([0x0A, 0x02, 0x32, 0x00]) // Payload{control{pair_accept{}}}
    _ = try phone.open(envelope: try mac.seal(peerId: request.peerId, payload: accept, ttlS: 0))
    return (mac, phone)
}

/// A raw TCP client on localhost, with async reads.
final class TestSocket: @unchecked Sendable {
    let connection: NWConnection

    convenience init(port: UInt16) async throws {
        try await self.init(endpoint: .hostPort(host: "127.0.0.1", port: NWEndpoint.Port(rawValue: port)!), using: .tcp)
    }

    init(endpoint: NWEndpoint, using parameters: NWParameters) async throws {
        connection = NWConnection(to: endpoint, using: parameters)
        try await withCheckedThrowingContinuation { (done: CheckedContinuation<Void, Error>) in
            let once = Once()
            connection.stateUpdateHandler = { state in
                switch state {
                case .ready: once.run { done.resume() }
                // Refused: Network.framework waits to retry instead of failing
                case .failed(let e), .waiting(let e): once.run { done.resume(throwing: e) }
                default: break
                }
            }
            connection.start(queue: .global())
            DispatchQueue.global().asyncAfter(deadline: .now() + 5) { once.run { done.resume(throwing: URLError(.timedOut)) } }
        }
    }

    func write(_ data: Data) {
        connection.send(content: data, completion: .idempotent)
    }

    /// Nil when the other end closed (or nothing came within `seconds`).
    func read(within seconds: Double = 5) async -> Data? {
        await withCheckedContinuation { (done: CheckedContinuation<Data?, Never>) in
            let once = Once()
            connection.receive(minimumIncompleteLength: 1, maximumLength: 1 << 21) { data, _, complete, error in
                once.run { done.resume(returning: (data?.isEmpty == false) ? data : nil) }
                _ = (complete, error)
            }
            DispatchQueue.global().asyncAfter(deadline: .now() + seconds) { once.run { done.resume(returning: nil) } }
        }
    }

    /// True if the other end closed within `seconds` (false: still open, or it sent bytes).
    func closed(within seconds: Double) async -> Bool {
        await withCheckedContinuation { (done: CheckedContinuation<Bool, Never>) in
            let once = Once()
            connection.receive(minimumIncompleteLength: 1, maximumLength: 1 << 16) { data, _, complete, error in
                once.run { done.resume(returning: (data?.isEmpty ?? true) && (complete || error != nil)) }
            }
            DispatchQueue.global().asyncAfter(deadline: .now() + seconds) { once.run { done.resume(returning: false) } }
        }
    }

    func close() { connection.cancel() }
}

/// The phone's side, driven by the core like the Android app does.
struct TestPhone {
    let socket: TestSocket
    let channel: LanChannel

    static func connect(_ phone: SeamDevice, macID: String, port: UInt16) async throws -> TestPhone {
        try await connect(phone, macID: macID, socket: try await TestSocket(port: port))
    }

    static func connect(_ phone: SeamDevice, macID: String, socket s: TestSocket) async throws -> TestPhone {
        let ch = try LanChannel.initiator(device: phone, peerId: macID)
        s.write(ch.start())
        let p = TestPhone(socket: s, channel: ch)
        // The Mac's handshake reply and proof, then ours
        while ch.peerId() == nil {
            guard let data = await s.read() else { throw URLError(.networkConnectionLost) }
            let out = try ch.receive(bytes: data)
            if !out.send.isEmpty { s.write(out.send) }
        }
        return p
    }

    func send(_ frame: Seam_V1_ClientFrame) throws {
        socket.write(try channel.send(frame: try frame.serializedData()))
    }

    func receiveFrame() async throws -> Seam_V1_ServerFrame? {
        while let data = await socket.read() {
            if let f = try channel.receive(bytes: data).frames.first { return try Seam_V1_ServerFrame(serializedBytes: f) }
        }
        return nil
    }
}

/// The first `count` events, or what came before `seconds` ran out.
func collect(_ server: LanServer, _ count: Int, within seconds: Double = 5) async -> [LanEvent] {
    let t = Task {
        var out: [LanEvent] = []
        for await e in server.events {
            out.append(e)
            if out.count == count { break }
        }
        return out
    }
    let timer = Task {
        try? await Task.sleep(for: .seconds(seconds))
        t.cancel()
    }
    defer { timer.cancel() }
    return await t.value
}

/// A listener tests can reach on 127.0.0.1 (the app's refuses loopback).
func testServer(_ device: SeamDevice, authDeadline: TimeInterval = 10, firstBytes: TimeInterval = 2) -> LanServer {
    LanServer(device: device, advertise: false, authDeadline: authDeadline, firstBytesDeadline: firstBytes, allowLoopback: true)
}

@Suite(.serialized)
struct LanServerTests {
    @Test func pairedPhoneAuthenticatesAndFramesFlowBothWays() async throws {
        let (mac, phone) = try pairedDevices()
        let server = testServer(mac)
        defer { server.stop() }
        let port = try await server.start()
        let p = try await TestPhone.connect(phone, macID: mac.id(), port: port)

        let env = Seam_V1_Envelope.with { $0.msgID = Data([1, 2]) }
        try p.send(.with { $0.send = env })
        try p.send(.with { $0.delivered = .with { $0.msgID = Data([9]) } })
        try p.send(.with { $0.getBundle = .init() }) // pairing channels only: ignored
        let events = await collect(server, 3)
        #expect(events == [
            .authenticated(peerId: phone.id()), .envelope(peerId: phone.id(), env), .delivered(peerId: phone.id(), msgID: Data([9])),
        ])

        #expect(server.send(.with { $0.deliver = env }, to: phone.id()))
        #expect(try await p.receiveFrame()?.deliver == env)
        // The Mac's ack for something the phone sent directly
        #expect(server.send(.with { $0.delivered = .with { $0.msgID = Data([1, 2]) } }, to: phone.id()))
        #expect(try await p.receiveFrame()?.delivered.msgID == Data([1, 2]))
        #expect(!server.send(.with { $0.deliver = env }, to: "someone-else"))
        server.close(peerId: phone.id())
        #expect(await p.socket.read() == nil)
        #expect(await collect(server, 1, within: 1) == [.closed(peerId: phone.id())])
    }

    @Test func aStrangerNeverAuthenticatesAndIsClosed() async throws {
        let (mac, _) = try pairedDevices()
        let (otherMac, otherPhone) = try pairedDevices()
        let server = testServer(mac)
        defer { server.stop() }
        let port = try await server.start()
        // A phone paired with another Mac: our Mac's proof doesn't match, so it never gets in
        await #expect(throws: (any Error).self) { _ = try await TestPhone.connect(otherPhone, macID: otherMac.id(), port: port) }
        let s = try await TestSocket(port: port)
        s.write(Data([0, 3, 1, 2, 3])) // garbage handshake
        #expect(await s.read() == nil, "closed by the Mac")
        #expect(await collect(server, 1, within: 0.5).isEmpty)
    }

    @Test func closesWhoeverDoesntAuthenticateInTime() async throws {
        let (mac, _) = try pairedDevices()
        let server = testServer(mac, authDeadline: 0.3, firstBytes: 10)
        defer { server.stop() }
        let port = try await server.start()
        let s = try await TestSocket(port: port)
        s.write(Data([0, 40])) // a length, then nothing: heard from, but never authenticates
        let started = Date()
        #expect(await s.read(within: 5) == nil)
        #expect(Date().timeIntervalSince(started) < 3)
    }

    @Test func closesASilentSocketSooner() async throws {
        let (mac, _) = try pairedDevices()
        let server = testServer(mac, authDeadline: 30, firstBytes: 0.3)
        defer { server.stop() }
        let port = try await server.start()
        let s = try await TestSocket(port: port)
        let started = Date()
        #expect(await s.read(within: 5) == nil)
        #expect(Date().timeIntervalSince(started) < 3)
    }

    @Test func idleSocketsCantLockThePhoneOut() async throws {
        let (mac, phone) = try pairedDevices()
        let server = testServer(mac, authDeadline: 30, firstBytes: 30)
        defer { server.stop() }
        let port = try await server.start()
        var idle: [TestSocket] = []
        for _ in 0..<(LanServer.maxPendingPerAddress + 2) { idle.append(try await TestSocket(port: port)) }
        try await Task.sleep(for: .milliseconds(200))
        // At most four wait per address: two went at once, not at the deadline
        var closed = 0
        for s in idle where await s.closed(within: 0.5) { closed += 1 }
        #expect(closed == 2)
        _ = try await TestPhone.connect(phone, macID: mac.id(), port: port)
        #expect(await collect(server, 1) == [.authenticated(peerId: phone.id())])
        idle.forEach { $0.close() }
    }

    @Test func whatWasSentJustBeforeAClosingIsStillDelivered() async throws {
        let (mac, phone) = try pairedDevices()
        let server = testServer(mac)
        let port = try await server.start()
        let p = try await TestPhone.connect(phone, macID: mac.id(), port: port)
        _ = await collect(server, 1)
        // A big farewell, then closed at once: it must arrive whole before the end of the stream
        let farewell = Seam_V1_Envelope.with { $0.msgID = Data([7]); $0.ciphertext = Data(repeating: 1, count: 1_000_000) }
        #expect(server.send(.with { $0.deliver = farewell }, to: phone.id()))
        server.close(peerId: phone.id())
        #expect(try await p.receiveFrame()?.deliver == farewell)
        #expect(!server.send(.with { $0.deliver = farewell }, to: phone.id()), "gone for routing at once")

        // And when the whole listener stops (Wi‑Fi switched off, phone forgotten)
        let q = try await TestPhone.connect(phone, macID: mac.id(), port: port)
        #expect(await collect(server, 2) == [.closed(peerId: phone.id()), .authenticated(peerId: phone.id())])
        #expect(server.send(.with { $0.deliver = farewell }, to: phone.id()))
        server.stop()
        #expect(try await q.receiveFrame()?.deliver == farewell)
        #expect(await q.socket.closed(within: 3), "then closed")
    }

    @Test func refusesEveryoneWhileNoPhoneIsPaired() async throws {
        let server = testServer(try SeamDevice.generate())
        defer { server.stop() }
        let port = try await server.start()
        let s = try await TestSocket(port: port)
        #expect(await s.read() == nil)
    }

    @Test func aNewLinkFromThePhoneReplacesTheOld() async throws {
        let (mac, phone) = try pairedDevices()
        let server = testServer(mac)
        defer { server.stop() }
        let port = try await server.start()
        let first = try await TestPhone.connect(phone, macID: mac.id(), port: port)
        let second = try await TestPhone.connect(phone, macID: mac.id(), port: port)
        // Two authentications and no close: the phone stayed reachable throughout
        #expect(await collect(server, 3, within: 1).count == 2)
        #expect(await first.socket.read() == nil, "the old link is closed")
        #expect(server.send(.with { $0.deliver = .with { $0.msgID = Data([3]) } }, to: phone.id()))
        #expect(try await second.receiveFrame()?.deliver.msgID == Data([3]))
    }

    @Test func theAppsListenerRefusesLoopback() async throws {
        let (mac, _) = try pairedDevices()
        let server = LanServer(device: mac, advertise: false)
        defer { server.stop() }
        let port = try await server.start()
        // Either the connection is refused outright or closed at once: never accepted
        if let s = try? await TestSocket(port: port) {
            #expect(await s.read(within: 3) == nil)
            s.close()
        }
    }
}

struct LocalAddressTests {
    @Test func acceptsOnlyPrivateAndLinkLocalAddresses() {
        for ok in ["10.0.0.5", "172.16.0.1", "172.31.255.254", "192.168.1.20", "169.254.3.4", "fe80::1", "febf::1", "fd12:3456::1", "fc00::1", "::ffff:192.168.0.2"] {
            #expect(LanServer.isLocal(NWEndpoint.Host(ok)), "\(ok)")
        }
        for bad in ["8.8.8.8", "172.32.0.1", "172.15.0.1", "192.169.0.1", "127.0.0.1", "0.0.0.0", "100.64.0.1", "::1", "2001:db8::1", "fec0::1", "::ffff:8.8.8.8", "example.com"] {
            #expect(!LanServer.isLocal(NWEndpoint.Host(bad)), "\(bad)")
        }
    }
}

struct EvictionTests {
    @Test func makesRoomWithASilentConnectionFirst() {
        struct Waiting { let id: Int; let heard: Bool }
        let waiting = [Waiting(id: 1, heard: true), Waiting(id: 2, heard: false), Waiting(id: 3, heard: true), Waiting(id: 4, heard: false)]
        for _ in 0..<50 { #expect([2, 4].contains(LanServer.victim(waiting, heard: \.heard)?.id)) }
        let allHeard = waiting.map { Waiting(id: $0.id, heard: true) }
        let picked = Set((0..<200).compactMap { _ in LanServer.victim(allHeard, heard: \.heard)?.id })
        #expect(picked.count > 1, "random, not always the same one")
        #expect(LanServer.victim([Waiting](), heard: \.heard) == nil)
    }

    @Test func groupsIPv6ByItsSlash64AndIPv4ByAddress() {
        let key = { (s: String) in LanServer.addressKey(NWEndpoint.Host(s)) }
        #expect(key("fe80::1") != key("fe80::2"), "link-local: the whole LAN shares fe80::/64")
        #expect(key("fe80::1") == key("fe80::1"))
        #expect(key("fd00:1:2:3::1") == key("fd00:1:2:3::abcd"))
        #expect(key("fd00:1:2:3::1") != key("fd00:1:2:4::1"))
        #expect(key("192.168.1.2") != key("192.168.1.3"))
        #expect(key("::ffff:192.168.1.2") == key("192.168.1.2"))
    }
}
