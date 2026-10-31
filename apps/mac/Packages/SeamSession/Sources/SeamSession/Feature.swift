// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import SeamProtocol

/// A payload a feature wants sealed and sent to the phone, with its time limit (0: the outbox's 24 h).
public struct Outgoing: Sendable {
    public let payload: Seam_V1_Payload
    public let ttl: UInt32
}

/// One feature (clipboard, notifications, status), the Mac counterpart of Android's `Feature`.
/// The session registers each once, hands it what the phone sends, tells it when the phone is
/// reachable, and resets it when the phone is forgotten. It sends what the feature returns.
protocol Feature: AnyObject {
    /// Each feature picks out the payloads it handles and ignores the rest.
    nonisolated(nonsending) func receive(_ body: Seam_V1_Payload.OneOf_Body) async -> [Outgoing]
    /// A path to the phone came up.
    nonisolated(nonsending) func onConnected(now: Date) async -> [Outgoing]
    /// The phone was forgotten (or replaced): nothing from it may stay.
    nonisolated(nonsending) func reset() async
}

extension Feature {
    nonisolated(nonsending) func onConnected(now: Date) async -> [Outgoing] { [] }
}

/// The Mac's pasteboard, as the clipboard feature sees it.
public protocol Pasteboard: Sendable {
    func changeCount() async -> Int
    /// The first item's types and text, read together, if the change count is still `change`.
    /// Nil when there's nothing, or it changed meanwhile (a password copied a moment later can't
    /// be read under the previous item's types, SR-4-05).
    func firstItem(ifStill change: Int) async -> PasteboardItem?
    /// Replaces the contents with `text`; returns the new change count.
    func write(_ text: String) async -> Int
}

public struct PasteboardItem: Sendable {
    public let types: [String]
    public let text: String?

    public init(types: [String], text: String?) {
        self.types = types
        self.text = text
    }
}

/// The Mac's notification centre, as the notifications feature sees it.
public protocol NotificationCentre: Sendable {
    /// `icon`: the phone app's icon PNG.
    func show(_ notification: Seam_V1_Notification, icon: Data?) async
    func dismiss(key: String) async
    /// Every banner from the phone.
    func removeAll() async
}

/// The phone's battery and the like, for the menu bar; and this Mac's battery, for the phone.
final class StatusFeature: Feature {
    private(set) var status: Seam_V1_Status?
    /// When the phone last sent it, so a stale level can say how old it is.
    private(set) var statusAt: Date?
    private let now: @Sendable () -> Date
    /// This Mac's battery; nil on a Mac without one, which sends nothing.
    let own: @Sendable () -> Seam_V1_Status?

    /// Short: an old battery level is worse than none.
    static let ttl: UInt32 = 120

    init(now: @escaping @Sendable () -> Date, own: @escaping @Sendable () -> Seam_V1_Status?) {
        self.now = now
        self.own = own
    }

    nonisolated(nonsending) func receive(_ body: Seam_V1_Payload.OneOf_Body) async -> [Outgoing] {
        if case .status(var s) = body {
            s.batteryPct = min(s.batteryPct, 100) // never nonsense in the menu
            status = s
            statusAt = now()
        }
        return []
    }

    /// This Mac's status as something to send, if it has one.
    func ownStatus() -> [Outgoing] {
        guard var s = own() else { return [] }
        s.batteryPct = min(s.batteryPct, 100)
        return [Outgoing(payload: .with { $0.status = s }, ttl: Self.ttl)]
    }

    nonisolated(nonsending) func reset() async {
        status = nil
        statusAt = nil
    }
}
