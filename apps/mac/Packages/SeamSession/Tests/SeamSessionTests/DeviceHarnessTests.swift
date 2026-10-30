// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import Network
import SeamProtocol
import Testing

@testable import SeamSession

/// Manual checks against a real phone on the same network. Skipped unless their variable is set:
///
///     SEAM_FAKE_MAC_SECONDS=90 swift test --filter DeviceHarnessTests/aFakeMac
///         A Mac with its own identity, announced as Seam: a paired phone must refuse it.
///
///     SEAM_CAPTURE_VIA=<phone ip>:<port> swift test --filter DeviceHarnessTests/throughAnotherDevice
///         The real listener and client talk through a relay on another device (e.g. the phone's
///         `nc -L -p <port> nc -O <file> <this Mac> $(cat /tmp/seam-capture-port)`), so every byte
///         crosses the network and can be recorded there. Waits for /tmp/seam-capture-ready.
@Suite(.serialized) struct DeviceHarnessTests {
    static let marker = "SEAM-WIRE-MARKER-7f3a"

    @Test(.enabled(if: ProcessInfo.processInfo.environment["SEAM_FAKE_MAC_SECONDS"] != nil))
    func aFakeMac() async throws {
        let (mac, _) = try pairedDevices()
        let server = LanServer(device: mac)
        defer { server.stop() }
        let port = try await server.start()
        print("fake Mac listening on \(port)")
        let seconds = Double(ProcessInfo.processInfo.environment["SEAM_FAKE_MAC_SECONDS"] ?? "") ?? 60
        try await Task.sleep(for: .seconds(seconds))
        #expect(await collect(server, 1, within: 0.1).allSatisfy { if case .authenticated = $0 { false } else { true } })
    }

    @Test(.enabled(if: ProcessInfo.processInfo.environment["SEAM_CAPTURE_VIA"] != nil))
    func throughAnotherDevice() async throws {
        let via = try #require(ProcessInfo.processInfo.environment["SEAM_CAPTURE_VIA"]?.split(separator: ":"))
        let (mac, phone) = try pairedDevices()
        let server = LanServer(device: mac)
        defer { server.stop() }
        let port = try await server.start()
        try? FileManager.default.removeItem(atPath: "/tmp/seam-capture-ready")
        try "\(port)".write(toFile: "/tmp/seam-capture-port", atomically: true, encoding: .utf8)
        for _ in 0..<120 where !FileManager.default.fileExists(atPath: "/tmp/seam-capture-ready") {
            try await Task.sleep(for: .seconds(1))
        }

        let params = NWParameters.tcp
        params.prohibitedInterfaceTypes = [.loopback]
        let socket = try await TestSocket(
            endpoint: .hostPort(host: NWEndpoint.Host(String(via[0])), port: NWEndpoint.Port(String(via[1]))!), using: params)
        let p = try await TestPhone.connect(phone, macID: mac.id(), socket: socket)
        #expect(await collect(server, 1) == [.authenticated(peerId: phone.id())])

        // The marker each way, inside the envelope: on the wire it must only ever appear encrypted
        let env = Seam_V1_Envelope.with { $0.msgID = Data([7]); $0.ciphertext = Data((Self.marker + " phone to Mac").utf8) }
        try p.send(.with { $0.send = env })
        #expect(await collect(server, 1) == [.envelope(peerId: phone.id(), env)])
        let back = Seam_V1_Envelope.with { $0.msgID = Data([8]); $0.ciphertext = Data((Self.marker + " Mac to phone").utf8) }
        #expect(server.send(.with { $0.deliver = back }, to: phone.id()))
        #expect(try await p.receiveFrame()?.deliver == back)
    }
}
