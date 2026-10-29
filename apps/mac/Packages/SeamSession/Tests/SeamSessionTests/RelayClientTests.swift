// SPDX-License-Identifier: AGPL-3.0-only
import CryptoKit
import Foundation
import SeamCore
import SeamProtocol
import Testing

@testable import SeamSession

/// Plays back server frames, records what the client sends, then reports the socket closed.
actor FakeSocket: RelaySocket {
    private var incoming: [Data]
    private(set) var sent: [Seam_V1_ClientFrame] = []

    init(_ frames: [Seam_V1_ServerFrame]) {
        incoming = frames.map { try! $0.serializedData() }
    }

    func send(_ data: Data) async throws {
        sent.append(try Seam_V1_ClientFrame(serializedBytes: data))
    }

    func receive() async throws -> Data {
        guard !incoming.isEmpty else { throw URLError(.networkConnectionLost) }
        return incoming.removeFirst()
    }

    nonisolated func close() {}
}

@MainActor
final class FakeHandler: RelayHandler {
    let handles: Bool
    private(set) var members: [Data] = []
    private(set) var received: [Data] = []

    nonisolated init(handles: Bool) { self.handles = handles }

    func relayConnected(members: [Data]) async { self.members = members }
    func relayDisconnected() async {}
    func relayReceived(_ envelope: Seam_V1_Envelope) async -> Bool {
        received.append(envelope.msgID)
        return handles
    }
    func relayPeerJoined(_ deviceKey: Data) async {}
    func relayBundle(deviceKey: Data, bundle: Data) async {}
    func relayError(_ code: String) async {}
}

struct RelayClientTests {
    private let nonce = Data(repeating: 4, count: 32)
    private let group = Data(repeating: 6, count: 16)

    private func server(msgID: Data, members: [Data]) -> [Seam_V1_ServerFrame] {
        [
            .with { $0.challenge = .with { $0.nonce = nonce } },
            .with { $0.loginResult = .with { $0.ok = true; $0.members = members } },
            .with { $0.deliver = .with { $0.msgID = msgID } },
        ]
    }

    private func run(handles: Bool) async throws -> (sent: [Seam_V1_ClientFrame], handler: FakeHandler, device: SeamDevice) {
        let device = try SeamDevice.generate()
        let socket = FakeSocket(server(msgID: Data([7, 7]), members: [device.relayKey()]))
        let handler = FakeHandler(handles: handles)
        let client = RelayClient(device: device, groupID: group, relayURL: URL(string: "ws://localhost:8787")!, handler: handler) { _ in socket }
        await #expect(throws: URLError.self) { try await client.connectOnce() }
        return (await socket.sent, handler, device)
    }

    @Test func logsInThenAcksOnlyAfterTheEnvelopeIsHandled() async throws {
        let (sent, handler, device) = try await run(handles: true)
        #expect(sent.count == 2)
        let login = sent[0].login
        let key = try Curve25519.Signing.PublicKey(rawRepresentation: login.deviceKey)
        #expect(key.isValidSignature(login.signature, for: Frames.loginMessage(nonce: nonce, groupID: group)))
        #expect(sent[1].delivered.msgID == Data([7, 7]))
        #expect(await handler.members == [device.relayKey()])
        #expect(await handler.received == [Data([7, 7])])
    }

    @Test func unhandledEnvelopeIsNotAcked() async throws {
        let (sent, _, _) = try await run(handles: false)
        #expect(sent.count == 1) // the login only: the relay will redeliver
    }

    @Test func sendingBeforeLoginFails() async throws {
        let client = RelayClient(device: try SeamDevice.generate(), groupID: group, relayURL: URL(string: "ws://x")!, handler: FakeHandler(handles: true)) { _ in FakeSocket([]) }
        await #expect(throws: RelayClientError.notConnected) { try await client.send(Frames.delivered(Data())) }
    }

    @Test func refusedLoginEndsTheConnection() async throws {
        let socket = FakeSocket([
            .with { $0.challenge = .with { $0.nonce = nonce } },
            .with { $0.loginResult = .with { $0.ok = false } },
        ])
        let client = RelayClient(device: try SeamDevice.generate(), groupID: group, relayURL: URL(string: "ws://x")!, handler: FakeHandler(handles: true)) { _ in socket }
        await #expect(throws: RelayClientError.loginRefused) { try await client.connectOnce() }
    }
}
