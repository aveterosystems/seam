// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import SeamProtocol

/// One feature (clipboard, notifications, status), the Mac counterpart of Android's `Feature`.
/// It owns its state and its actions; the session hosting it owns delivery, the pause, the paths
/// and pairing. Features live on the main actor so the screens watch their state directly
/// (`@Observable`); the session hands each what the phone sends, tells it when the phone is
/// reachable, and resets it when the phone is forgotten. Adding one means no change to `Session`.
@MainActor
public protocol Feature: AnyObject, Sendable {
    /// Before anything else: the session it sends through (held weakly).
    func start(host: any FeatureHost) async
    /// Each feature picks out the payloads it handles and ignores the rest.
    func receive(_ body: Seam_V1_Payload.OneOf_Body) async
    /// A path to the phone came up.
    func connected() async
    /// The phone was forgotten (or replaced): nothing from it may stay.
    func reset() async
    /// The session is being replaced: timers off.
    func stop()
}

extension Feature {
    public func connected() async {}
    public func stop() {}
}

/// What a feature can ask of the session hosting it.
public protocol FeatureHost: Actor {
    /// Sealed, saved, then sent; dropped with no phone, or while it has Seam paused (except control).
    func send(_ payload: Seam_V1_Payload) async
    func reach() -> Reach
}

/// Whether what happens here now can go to the phone.
public enum Reach: Sendable {
    /// No paired phone, or it has Seam paused: nothing is captured.
    case off
    /// Paired, but no path to it now.
    case away
    case connected
}
