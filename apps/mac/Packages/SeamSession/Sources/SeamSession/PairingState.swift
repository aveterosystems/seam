// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import SeamCore

/// When the pairing QR code stops working (the core's 5-minute slot, SR-2-22).
public struct PairingDeadline: Equatable, Sendable {
    public let expiresAt: Date

    public init(expiresAt: Date) {
        self.expiresAt = expiresAt
    }

    public func remaining(at now: Date) -> TimeInterval {
        max(0, expiresAt.timeIntervalSince(now))
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
