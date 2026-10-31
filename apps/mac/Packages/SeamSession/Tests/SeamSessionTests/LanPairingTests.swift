// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import SeamCore
import SeamProtocol
import Testing

@testable import SeamSession

extension TestPhone {
    /// A phone that scanned the code: it proves the token, and the Mac proves it holds the code's key.
    static func pair(_ phone: SeamDevice, relayKey: Data, token: Data, port: UInt16) async throws -> TestPhone {
        let s = try await TestSocket(port: port)
        let ch = try LanChannel.pairingInitiator(device: phone, relayKey: relayKey, token: token)
        s.write(ch.start())
        while ch.peerId() == nil {
            guard let data = await s.read() else { throw URLError(.networkConnectionLost) }
            let out = try ch.receive(bytes: data)
            if !out.send.isEmpty { s.write(out.send) }
        }
        return TestPhone(socket: s, channel: ch)
    }
}

/// The pairing channel over the real listener (the Tor carrier runs the same code on its streams).
@Suite(.serialized)
struct LanPairingTests {
    private let pairing = LanServer.pairingPeer

    @Test func aPairingChannelCarriesTheExchange() async throws {
        let mac = try SeamDevice.generate()
        let phone = try SeamDevice.generate()
        let offer = mac.startPairing()
        let server = testServer(mac)
        defer { server.stop() }
        let port = try await server.start()
        let p = try await TestPhone.pair(phone, relayKey: mac.relayKey(), token: offer.token, port: port)
        try p.send(.with { $0.getBundle = .with { $0.deviceKey = mac.relayKey() } })
        #expect(await collect(server, 2) == [.authenticated(peerId: pairing), .bundleRequest(peerId: pairing)])

        let bundle = try mac.prekeyBundles(count: 1)[0]
        #expect(server.send(.with { $0.bundle = .with { $0.deviceKey = mac.relayKey(); $0.bundle = bundle } }, to: pairing))
        #expect(try await p.receiveFrame()?.bundle.bundle == bundle)
        let start = try phone.pairWith(
            identityKey: mac.identityKey(), relayKey: mac.relayKey(), name: "Mac", bundle: bundle,
            token: offer.token, myName: "Pixel", myModel: "Test"
        )
        let request = try Seam_V1_Envelope(serializedBytes: start.envelope)
        try p.send(.with { $0.send = request })
        try p.send(.with { $0.delivered = .with { $0.msgID = Data([7]) } })
        #expect(await collect(server, 2) == [.envelope(peerId: pairing, request), .delivered(peerId: pairing, msgID: Data([7]))])
    }

    @Test(arguments: ["another device's bundle", "a relay login"])
    func aPairingChannelClosesOnAnythingElse(frame: String) async throws {
        let mac = try SeamDevice.generate()
        let offer = mac.startPairing()
        let server = testServer(mac)
        defer { server.stop() }
        let port = try await server.start()
        let p = try await TestPhone.pair(try SeamDevice.generate(), relayKey: mac.relayKey(), token: offer.token, port: port)
        try p.send(frame == "a relay login"
            ? .with { $0.login = .with { $0.deviceKey = Data(repeating: 1, count: 32) } }
            : .with { $0.getBundle = .with { $0.deviceKey = Data(repeating: 9, count: 32) } })
        #expect(await p.socket.closed(within: 3), "closed by the Mac")
        #expect(await collect(server, 2, within: 1) == [.authenticated(peerId: pairing), .closed(peerId: pairing)])
    }

    @Test func noPairingChannelWithTheWrongCodeOrNoneOpen() async throws {
        let mac = try SeamDevice.generate()
        let offer = mac.startPairing()
        let server = testServer(mac)
        defer { server.stop() }
        let port = try await server.start()
        // The Mac proves itself first; the phone's proof with the wrong token then fails, so it's closed
        if let wrong = try? await TestPhone.pair(try SeamDevice.generate(), relayKey: mac.relayKey(), token: Data(repeating: 1, count: 32), port: port) {
            #expect(await wrong.socket.closed(within: 3))
        }
        mac.cancelPairing() // the code expired or was cancelled
        await #expect(throws: (any Error).self) {
            _ = try await TestPhone.pair(try SeamDevice.generate(), relayKey: mac.relayKey(), token: offer.token, port: port)
        }
        #expect(await collect(server, 1, within: 0.5).isEmpty)
    }
}
