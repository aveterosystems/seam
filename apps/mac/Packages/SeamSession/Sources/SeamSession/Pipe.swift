// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import Network
import SeamCore

/// The bytes a direct link runs over: a TCP connection on Same Wi‑Fi, or a Tor stream. `LanServer`
/// treats both alike; every callback comes on its queue.
protocol Pipe: AnyObject, Sendable {
    /// Starts reading: `received` hears each chunk, then `done` once when the pipe ends.
    func start(_ received: @escaping @Sendable (Data?, _ done: Bool) -> Void)
    /// Writes in order; `completion(false)` if it couldn't.
    func write(_ data: Data, completion: @escaping @Sendable (Bool) -> Void)
    func cancel()
}

/// Same Wi‑Fi: an accepted TCP connection.
final class ConnectionPipe: Pipe, @unchecked Sendable {
    private let connection: NWConnection
    private let queue: DispatchQueue

    init(_ connection: NWConnection, queue: DispatchQueue) {
        self.connection = connection
        self.queue = queue
    }

    func start(_ received: @escaping @Sendable (Data?, Bool) -> Void) {
        connection.stateUpdateHandler = { state in
            switch state {
            case .failed, .cancelled: received(nil, true)
            default: break
            }
        }
        connection.start(queue: queue)
        read(received)
    }

    private func read(_ received: @escaping @Sendable (Data?, Bool) -> Void) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 65536) { [weak self] data, _, complete, error in
            let done = complete || error != nil
            received(data, done)
            if !done { self?.read(received) }
        }
    }

    func write(_ data: Data, completion: @escaping @Sendable (Bool) -> Void) {
        connection.send(content: data, completion: .contentProcessed { error in completion(error == nil) })
    }

    func cancel() {
        connection.cancel()
    }
}

/// Tor: a stream from the Mac's onion service. Its callbacks come on Tor's threads, so they hop
/// onto the server's queue.
final class TorPipe: Pipe, @unchecked Sendable {
    private let stream: TorStream
    private let queue: DispatchQueue

    init(_ stream: TorStream, queue: DispatchQueue) {
        self.stream = stream
        self.queue = queue
    }

    func start(_ received: @escaping @Sendable (Data?, Bool) -> Void) {
        stream.listen(listener: Listener(queue: queue, received: received))
    }

    func write(_ data: Data, completion: @escaping @Sendable (Bool) -> Void) {
        let ok = stream.send(bytes: data) // queued in order; false once the stream is closed
        queue.async { completion(ok) }
    }

    func cancel() {
        stream.close()
    }

    private final class Listener: TorStreamListener, @unchecked Sendable {
        let queue: DispatchQueue
        let received: @Sendable (Data?, Bool) -> Void

        init(queue: DispatchQueue, received: @escaping @Sendable (Data?, Bool) -> Void) {
            self.queue = queue
            self.received = received
        }

        func onBytes(bytes: Data) {
            queue.async { self.received(bytes, false) }
        }

        func onClosed() {
            queue.async { self.received(nil, true) }
        }
    }
}

/// Runs its body at most once, from any thread.
final class Once: @unchecked Sendable {
    private let lock = NSLock()
    private var done = false

    /// True if the body ran now.
    @discardableResult
    func run(_ body: () -> Void) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard !done else { return false }
        done = true
        body()
        return true
    }
}

/// Debug builds only, and only when started with SEAM_WIRE_DUMP=<file>: appends every byte the
/// direct link sends or receives on the socket (before any decryption), so a test can check that
/// nothing readable crosses the network. Compiled out of Release.
enum WireDump {
    #if DEBUG
    private static let file: FileHandle? = ProcessInfo.processInfo.environment["SEAM_WIRE_DUMP"].flatMap { path in
        FileManager.default.createFile(atPath: path, contents: nil, attributes: [.posixPermissions: 0o600])
        return FileHandle(forWritingAtPath: path)
    }
    private static let lock = NSLock()
    #endif

    static func record(_ bytes: Data, out: Bool) {
        #if DEBUG
        guard let file else { return }
        lock.lock()
        defer { lock.unlock() }
        file.write(Data((out ? ">" : "<").utf8) + withUnsafeBytes(of: UInt32(bytes.count).bigEndian) { Data($0) } + bytes)
        #endif
    }
}
