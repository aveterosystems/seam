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

/// What the app does with relay events: the `Session` takes them, on its own actor, and
/// publishes what the UI shows. `relayReceived` returns true once the envelope is dealt with
/// (handled, a duplicate, or dropped for good); only then is it acked so the relay deletes it.
public protocol RelayHandler: AnyObject, Sendable {
    func relayConnected(members: [Data]) async
    func relayDisconnected() async
    func relayReceived(_ envelope: Seam_V1_Envelope) async -> Bool
    func relayPeerJoined(_ deviceKey: Data) async
    func relayBundle(deviceKey: Data, bundle: Data) async
    func relayError(_ code: String) async
}

public enum RelayClientError: Error, Equatable {
    case notConnected, unexpectedFrame, loginRefused, textFrame, timeout, badChallenge
}

/// Logs in by signed challenge, then passes frames to the handler; reconnects with backoff.
public actor RelayClient {
    /// How long the relay gets to send the challenge, and then the login result.
    public static let loginTimeout: Duration = .seconds(10)

    private let device: SeamDevice
    private let groupID: Data
    private let relayURL: URL
    private let ownerKey: Data?
    private let connect: @Sendable (URL) -> any RelaySocket
    private let loginTimeout: Duration
    private weak var handler: (any RelayHandler)?
    private let backoff = Backoff()
    private var socket: (any RelaySocket)?
    private var attempt = 0
    private var loggedInAt: Date?
    public private(set) var isLoggedIn = false

    public init(
        device: SeamDevice, groupID: Data, relayURL: URL, ownerKey: Data? = nil, handler: any RelayHandler,
        loginTimeout: Duration = RelayClient.loginTimeout,
        connect: @escaping @Sendable (URL) -> any RelaySocket = { URLSessionSocket(url: $0) }
    ) {
        self.device = device
        self.groupID = groupID
        self.relayURL = relayURL
        self.ownerKey = ownerKey
        self.handler = handler
        self.loginTimeout = loginTimeout
        self.connect = connect
    }

    /// Runs until the task is cancelled.
    public func run() async {
        while !Task.isCancelled {
            loggedInAt = nil
            try? await connectOnce()
            let connectedFor = loggedInAt.map { Date().timeIntervalSince($0) }
            isLoggedIn = false
            socket = nil
            await handler?.relayDisconnected()
            // Only a connection that stayed up resets the backoff, so a relay that accepts and
            // then drops every login can't make the Mac reconnect in a tight loop.
            attempt = Backoff.nextAttempt(after: attempt, connectedFor: connectedFor)
            try? await Task.sleep(for: .seconds(backoff.delay(attempt: attempt, jitter: .random(in: 0..<1))))
        }
    }

    /// Sends a frame on the logged-in connection.
    public func send(_ frame: Seam_V1_ClientFrame) async throws {
        guard isLoggedIn, let socket else { throw RelayClientError.notConnected }
        try await socket.send(try frame.serializedData())
    }

    /// One connection, from challenge to close. Returns or throws when the socket ends.
    /// Order is enforced: a 32-byte challenge, then a login result (or an error), then the rest.
    func connectOnce() async throws {
        let ws = connect(Frames.connectURL(relay: relayURL, groupID: groupID))
        socket = ws
        defer { ws.close() }

        guard case .challenge(let c)? = try await frame(from: ws, within: loginTimeout).frame else {
            throw RelayClientError.unexpectedFrame
        }
        guard c.nonce.count == 32 else { throw RelayClientError.badChallenge }
        // createGroup: the Mac is always the group's first device; the relay ignores it afterwards
        try await ws.send(try Frames.login(device: device, nonce: c.nonce, groupID: groupID, createGroup: true, ownerKey: ownerKey).serializedData())

        switch try await frame(from: ws, within: loginTimeout).frame {
        case .loginResult(let r) where r.ok:
            isLoggedIn = true
            loggedInAt = Date()
            await handler?.relayConnected(members: r.members)
        case .error(let e):
            await handler?.relayError(e.code)
            throw RelayClientError.loginRefused
        case .loginResult:
            throw RelayClientError.loginRefused
        default:
            throw RelayClientError.unexpectedFrame
        }

        while true {
            switch try await frame(from: ws, within: nil).frame {
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
            case .challenge, .loginResult, .delivered, nil: // delivered is only ever sent on the direct link
                throw RelayClientError.unexpectedFrame
            }
        }
    }

    /// The next frame; with a deadline, a silent relay gets its socket closed instead of a hang.
    private func frame(from ws: any RelaySocket, within deadline: Duration?) async throws -> Seam_V1_ServerFrame {
        guard let deadline else { return try Seam_V1_ServerFrame(serializedBytes: try await ws.receive()) }
        let data = try await withThrowingTaskGroup(of: Data.self) { group in
            group.addTask { try await ws.receive() }
            group.addTask {
                try await Task.sleep(for: deadline)
                ws.close() // unblocks a receive that ignores cancellation
                throw RelayClientError.timeout
            }
            defer { group.cancelAll() }
            return try await group.next()!
        }
        return try Seam_V1_ServerFrame(serializedBytes: data)
    }
}

/// The real socket: URLSessionWebSocketTask, binary frames only, on its own ephemeral session
/// that refuses redirects (a relay URL can't be bounced elsewhere) and pings to notice dead links.
public final class URLSessionSocket: RelaySocket, @unchecked Sendable {
    /// Decision 0005: as rare as the network allows. The relay kept a silent connection open 15+
    /// minutes (measured 29 Oct 2026); 2 minutes is set by how soon a dropped link should be noticed.
    static let pingInterval: Duration = .seconds(120)
    static let pongDeadline: Duration = .seconds(10)

    private let session: URLSession
    private let task: URLSessionWebSocketTask
    private let pinger: Task<Void, Never>

    public init(url: URL) {
        let config = URLSessionConfiguration.ephemeral
        config.tlsMinimumSupportedProtocolVersion = .TLSv12
        config.httpCookieStorage = nil
        config.urlCache = nil
        let session = URLSession(configuration: config, delegate: NoRedirects(), delegateQueue: nil)
        let task = session.webSocketTask(with: url)
        task.maximumMessageSize = 2 * 1024 * 1024 // envelopes go up to ~1.1 MB; the default is 1 MB
        task.resume()
        self.session = session
        self.task = task
        pinger = Task {
            while !Task.isCancelled {
                try? await Task.sleep(for: URLSessionSocket.pingInterval)
                if Task.isCancelled { return }
                if await !URLSessionSocket.ping(task) { return }
            }
        }
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
        pinger.cancel()
        task.cancel(with: .normalClosure, reason: nil)
        session.finishTasksAndInvalidate() // a session keeps its delegate alive until invalidated
    }

    /// True when the pong comes back in time; otherwise the task is cancelled, which also fails
    /// the pending receive so the client reconnects.
    private static func ping(_ task: URLSessionWebSocketTask) async -> Bool {
        await withTaskGroup(of: Bool.self) { group in
            group.addTask {
                await withCheckedContinuation { done in
                    // URLSession can call this more than once (again when the task is cancelled, e.g. on
                    // a network change); resuming twice is fatal, so only the first call counts
                    let once = Once()
                    task.sendPing { error in once.run { done.resume(returning: error == nil) } }
                }
            }
            group.addTask {
                try? await Task.sleep(for: pongDeadline)
                if !Task.isCancelled { task.cancel(with: .goingAway, reason: nil) } // ends the sendPing wait too
                return false
            }
            defer { group.cancelAll() }
            return await group.next() ?? false
        }
    }
}

/// Refuses every HTTP redirect on the WebSocket upgrade.
private final class NoRedirects: NSObject, URLSessionTaskDelegate, Sendable {
    func urlSession(
        _ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest
    ) async -> URLRequest? {
        nil
    }
}
