// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import SeamProtocol

/// The Same Wi‑Fi link as the session sees it. Its events (`LanEvent`) go to `Session.lanEvent`.
public protocol LanSender: Sendable {
    /// False when the phone isn't connected (send it another way).
    func send(_ frame: Seam_V1_ServerFrame, to peerId: String) -> Bool
    func close(peerId: String)
}

/// The relay as the session sees it. Its callbacks go to `Session.relayConnected` and the rest.
public protocol RelaySender: Sendable {
    /// Returns once the relay took the frame; throws when it didn't (not logged in, a dropped socket).
    func send(_ frame: Seam_V1_ClientFrame) async throws
}

extension LanServer: LanSender {}
extension RelayClient: RelaySender {}

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
    /// Whether the relay should get fresh prekey bundles (a new group, or one used by pairing).
    public var needsBundles: @Sendable () -> Bool
    public var setNeedsBundles: @Sendable (Bool) -> Void

    public init(
        saveState: @escaping @Sendable () throws -> Void, outbox: OutboxFile,
        needsBundles: @escaping @Sendable () -> Bool, setNeedsBundles: @escaping @Sendable (Bool) -> Void
    ) {
        self.saveState = saveState
        self.outbox = outbox
        self.needsBundles = needsBundles
        self.setNeedsBundles = setNeedsBundles
    }
}
