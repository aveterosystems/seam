// SPDX-License-Identifier: AGPL-3.0-only
import Foundation

/// Retry delays: doubling from 1 s up to 60 s, scaled by 75–100% so many clients don't retry in step.
public struct Backoff: Sendable {
    public var base: Double = 1
    public var cap: Double = 60

    public init() {}

    /// `jitter` in 0..<1.
    public func delay(attempt: Int, jitter: Double) -> Double {
        min(cap, base * pow(2, Double(min(attempt, 30)))) * (0.75 + 0.25 * jitter)
    }
}
