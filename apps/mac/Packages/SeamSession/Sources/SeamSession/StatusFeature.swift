// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import Observation
import SeamProtocol

/// The phone's battery and the like, for the menu bar; and this Mac's battery, for the phone.
@MainActor @Observable
public final class StatusFeature: Feature {
    public internal(set) var status: Seam_V1_Status?
    /// When the phone last sent it, so a stale level can say how old it is.
    public internal(set) var statusAt: Date?
    @ObservationIgnored private let timers: any Timers
    /// This Mac's battery; nil on a Mac without one, which sends nothing.
    @ObservationIgnored private let own: @Sendable () -> Seam_V1_Status?
    @ObservationIgnored private weak var host: (any FeatureHost)?

    public nonisolated init(timers: any Timers, own: @escaping @Sendable () -> Seam_V1_Status?) {
        self.timers = timers
        self.own = own
    }

    public func start(host: any FeatureHost) async { self.host = host }

    public func receive(_ body: Seam_V1_Payload.OneOf_Body) async {
        if case .status(var s) = body {
            s.batteryPct = min(s.batteryPct, 100) // never nonsense in the menu
            status = s
            statusAt = timers.now()
        }
    }

    /// This Mac's battery changed: the phone hears it if it's connected (and not paused).
    /// Otherwise it waits for the next connection, which sends it fresh.
    public func ownChanged() async {
        guard let host, await host.reach() == .connected, var s = own() else { return }
        s.batteryPct = min(s.batteryPct, 100)
        await host.send(.with { $0.status = s })
    }

    public func connected() async { await ownChanged() }

    public func reset() async {
        status = nil
        statusAt = nil
    }
}
