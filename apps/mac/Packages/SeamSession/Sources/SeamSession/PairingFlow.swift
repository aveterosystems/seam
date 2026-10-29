// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import SeamCore

/// How long a pairing QR code is valid, matching the core's own 5-minute slot (SR-2-22).
public struct PairingDeadline: Equatable, Sendable {
    public static let lifetime: TimeInterval = 300
    public let expiresAt: Date

    public init(startedAt: Date) {
        expiresAt = startedAt.addingTimeInterval(Self.lifetime)
    }

    public func remaining(at now: Date) -> TimeInterval {
        max(0, expiresAt.timeIntervalSince(now))
    }

    /// For OpenPairing after a reconnect: never longer than what's left of the code's life.
    public func ttlSeconds(at now: Date) -> UInt32 {
        UInt32(remaining(at: now).rounded(.down))
    }
}

public enum PairingState: Equatable, Sendable {
    case idle
    case waiting(qr: String, deadline: PairingDeadline)
    /// A verified request: the user compares the safety code.
    case confirm(PairingRequest)
    /// Pair was clicked and PairAccept is on its way; nothing can cancel it now.
    case confirming(PairingRequest)
    case paired(name: String)
    case expired
    case failed(String)
}

/// The pairing window's state machine (SR-2-21, 22, 23). Every transition that doesn't apply in
/// the current state is refused, so double clicks and late events can't skip a step.
public struct PairingFlow: Sendable {
    public private(set) var state: PairingState = .idle

    public init() {}

    public var isConfirming: Bool {
        if case .confirming = state { return true }
        return false
    }

    /// A fresh QR code. Refused while a confirmation is in flight.
    @discardableResult
    public mutating func start(qr: String, now: Date) -> Bool {
        guard !isConfirming else { return false }
        state = .waiting(qr: qr, deadline: PairingDeadline(startedAt: now))
        return true
    }

    /// A request the core verified. Only accepted while a QR code is showing.
    @discardableResult
    public mutating func received(_ request: PairingRequest) -> Bool {
        guard case .waiting = state else { return false }
        state = .confirm(request)
        return true
    }

    /// The user clicked Pair. Returns the request once; a second click gets nil.
    public mutating func beginConfirming() -> PairingRequest? {
        guard case .confirm(let r) = state else { return nil }
        state = .confirming(r)
        return r
    }

    /// Cancel, or the window closed. Returns the state it left (to undo it), or nil while
    /// confirming, when there's nothing safe to undo.
    public mutating func cancel() -> PairingState? {
        guard !isConfirming else { return nil }
        let previous = state
        state = .idle
        return previous
    }

    /// The code's 5 minutes are up. Only a showing QR code expires.
    @discardableResult
    public mutating func expire() -> Bool {
        guard case .waiting = state else { return false }
        state = .expired
        return true
    }

    public mutating func finish(name: String) {
        guard isConfirming else { return }
        state = .paired(name: name)
    }

    public mutating func fail(_ reason: String) {
        state = .failed(reason)
    }
}
