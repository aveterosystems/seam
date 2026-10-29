// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import SeamCore
import SeamProtocol

/// One WebSocket to the relay, behind a protocol so tests can use a fake.
public protocol RelaySocket: Sendable {
    func send(_ data: Data) async throws
    func receive() async throws -> Data
    func close()
}

/// What the app does with relay events, on the main actor (they drive the UI). `relayReceived`
/// returns true once the envelope is dealt with (handled, a duplicate, or dropped for good); only
/// then is it acked so the relay deletes it.
@MainActor
public protocol RelayHandler: AnyObject, Sendable {
    func relayConnected(members: [Data]) async
    func relayDisconnected() async
    func relayReceived(_ envelope: Seam_V1_Envelope) async -> Bool
    func relayPeerJoined(_ deviceKey: Data) async
    func relayBundle(deviceKey: Data, bundle: Data) async
    func relayError(_ code: String) async
}

public enum RelayClientError: Error, Equatable {
    case notConnected, unexpectedFrame, loginRefused, textFrame
}

/// Logs in by signed challenge, then passes frames to the handler; reconnects with backoff.
public actor RelayClient {
    private let device: SeamDevice
    private let groupID: Data
    private let relayURL: URL
    private let connect: @Sendable (URL) -> any RelaySocket
    private weak var handler: (any RelayHandler)?
    private let backoff = Backoff()
    private var socket: (any RelaySocket)?
    private var attempt = 0
    public private(set) var isLoggedIn = false

    public init(
        device: SeamDevice, groupID: Data, relayURL: URL, handler: any RelayHandler,
        connect: @escaping @Sendable (URL) -> any RelaySocket = { URLSessionSocket(url: $0) }
    ) {
        self.device = device
        self.groupID = groupID
        self.relayURL = relayURL
        self.handler = handler
        self.connect = connect
    }

    /// Runs until the task is cancelled.
    public func run() async {
        while !Task.isCancelled {
            try? await connectOnce()
            isLoggedIn = false
            socket = nil
            await handler?.relayDisconnected()
            let delay = backoff.delay(attempt: attempt, jitter: .random(in: 0..<1))
            attempt += 1
            try? await Task.sleep(for: .seconds(delay))
        }
    }

    /// Sends a frame on the logged-in connection.
    public func send(_ frame: Seam_V1_ClientFrame) async throws {
        guard isLoggedIn, let socket else { throw RelayClientError.notConnected }
        try await socket.send(try frame.serializedData())
    }

    /// One connection, from challenge to close. Returns or throws when the socket ends.
    func connectOnce() async throws {
        let ws = connect(Frames.connectURL(relay: relayURL, groupID: groupID))
        socket = ws
        defer { ws.close() }
        guard case .challenge(let c)? = try await frame(from: ws).frame else { throw RelayClientError.unexpectedFrame }
        try await ws.send(try Frames.login(device: device, nonce: c.nonce, groupID: groupID).serializedData())
        while true {
            switch try await frame(from: ws).frame {
            case .loginResult(let r):
                guard r.ok else { throw RelayClientError.loginRefused }
                attempt = 0
                isLoggedIn = true
                await handler?.relayConnected(members: r.members)
            case .deliver(let env):
                if await handler?.relayReceived(env) == true {
                    try await ws.send(try Frames.delivered(env.msgID).serializedData())
                }
            case .peerJoined(let p):
                await handler?.relayPeerJoined(p.deviceKey)
            case .bundle(let b):
                await handler?.relayBundle(deviceKey: b.deviceKey, bundle: b.bundle)
            case .error(let e):
                await handler?.relayError(e.code)
            case .challenge, nil:
                throw RelayClientError.unexpectedFrame
            }
        }
    }

    private func frame(from ws: any RelaySocket) async throws -> Seam_V1_ServerFrame {
        try Seam_V1_ServerFrame(serializedBytes: try await ws.receive())
    }
}

/// The real socket: URLSessionWebSocketTask, binary frames only.
public final class URLSessionSocket: RelaySocket, @unchecked Sendable {
    private let task: URLSessionWebSocketTask

    public init(url: URL) {
        task = URLSession.shared.webSocketTask(with: url)
        task.maximumMessageSize = 2 * 1024 * 1024 // envelopes go up to ~1.1 MB; the default is 1 MB
        task.resume()
    }

    public func send(_ data: Data) async throws {
        try await task.send(.data(data))
    }

    public func receive() async throws -> Data {
        switch try await task.receive() {
        case .data(let d): return d
        default: throw RelayClientError.textFrame
        }
    }

    public func close() {
        task.cancel(with: .normalClosure, reason: nil)
    }
}
