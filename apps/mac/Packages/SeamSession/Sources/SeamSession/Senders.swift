// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import SeamProtocol

/// A direct link (Same Wi‑Fi or Tor) as the session sees it. Its events (`LanEvent`) go to
/// `Session.linkEvent` with the path they came on.
public protocol LanSender: Sendable {
    /// False when the phone isn't connected (send it another way).
    func send(_ frame: Seam_V1_ServerFrame, to peerId: String) -> Bool
    func close(peerId: String)
}

extension LanServer: LanSender {}

/// The clock and timers the session uses, so tests can move time on instead of waiting.
public protocol Timers: Sendable {
    func now() -> Date
    /// Runs `work` once after `seconds`, unless cancelled first.
    func after(_ seconds: TimeInterval, _ work: @escaping @Sendable () async -> Void) -> TimerToken
}

public struct TimerToken: Sendable {
    public let cancel: @Sendable () -> Void

    public init(cancel: @escaping @Sendable () -> Void) {
        self.cancel = cancel
    }
}

public struct SystemTimers: Timers {
    public init() {}

    public func now() -> Date { Date() }

    public func after(_ seconds: TimeInterval, _ work: @escaping @Sendable () async -> Void) -> TimerToken {
        let task = Task {
            try? await Task.sleep(for: .seconds(seconds))
            guard !Task.isCancelled else { return }
            await work()
        }
        return TimerToken { task.cancel() }
    }
}

/// Where the session keeps what must survive a restart.
public struct SessionStorage: Sendable {
    /// Seals the core's state to disk, durable before it returns. Nothing a failed save would
    /// forget is ever sent (SR-2-11).
    public var saveState: @Sendable () throws -> Void
    public var outbox: OutboxFile
    /// The phone's pause, kept across a restart (a preference, not a secret): nil when not paused.
    public var pausedUntil: @Sendable () -> Date?
    public var setPausedUntil: @Sendable (Date?) -> Void

    public init(
        saveState: @escaping @Sendable () throws -> Void, outbox: OutboxFile,
        pausedUntil: @escaping @Sendable () -> Date? = { nil }, setPausedUntil: @escaping @Sendable (Date?) -> Void = { _ in }
    ) {
        self.saveState = saveState
        self.outbox = outbox
        self.pausedUntil = pausedUntil
        self.setPausedUntil = setPausedUntil
    }
}
