// SPDX-License-Identifier: AGPL-3.0-only
import AppKit
import SeamProtocol

/// The phone's notifications as banners here, what's done to them sent back, and the phone's
/// notification settings (the phone owns them; the Mac asks it to change them).
final class NotificationsFeature: Feature {
    /// A settings change, reply or dismiss is worth little once the phone has moved on.
    static let ttl: UInt32 = 600
    /// Matches the phone's limit per message (NotificationsFeature.MAX_ICONS).
    static let iconBatch = 40
    /// From the phone, but still checked: a small, real image or nothing.
    static let maxIcon = 256 * 1024

    private let centre: any NotificationCentre
    /// As the phone last sent them, without icons. Memory only.
    private(set) var settings: Seam_V1_NotificationSettings?
    /// Phone apps' icon PNGs, from the phone when asked for. Memory only.
    private(set) var icons: [String: Data] = [:]
    /// Asked once per launch each, so an app whose icon the phone can't draw isn't asked for forever.
    private var asked: Set<String> = []

    init(centre: any NotificationCentre) {
        self.centre = centre
    }

    nonisolated(nonsending) func receive(_ body: Seam_V1_Payload.OneOf_Body) async -> [Outgoing] {
        switch body {
        case .notification(let n):
            await centre.show(n, icon: icons[n.appID])
        case .notificationAction(let a):
            if a.dismissed { await centre.dismiss(key: a.key) }
        case .notificationSettings(let s):
            return received(s)
        default:
            break
        }
        return []
    }

    /// Asks the phone to change its settings, showing the change here at once; the phone's
    /// answer confirms it.
    func change(_ change: Seam_V1_NotificationSettingsChange) -> Outgoing {
        if var s = settings {
            if change.hasEnabled { s.enabled = change.enabled }
            if change.hasDismissBoth { s.dismissBoth = change.dismissBoth }
            if change.hasQuietWhileUsingPhone { s.quietWhileUsingPhone = change.quietWhileUsingPhone }
            if change.hasPausedUntil { s.pausedUntil = change.pausedUntil }
            let modes = Dictionary(change.apps.map { ($0.appID, $0.mode) }, uniquingKeysWith: { $1 })
            for i in s.apps.indices { if let mode = modes[s.apps[i].appID] { s.apps[i].mode = mode } }
            settings = s
        }
        return Outgoing(payload: .with { $0.notificationSettingsChange = change }, ttl: Self.ttl)
    }

    /// What the user did to a banner here, for the phone.
    func action(_ a: Seam_V1_NotificationAction) -> Outgoing? {
        if a.dismissed, settings?.dismissBoth == false { return nil }
        // A typed reply waits for the phone like any message; a stale dismiss or button press
        // could hit a later notification that reuses the key (SR-19-03)
        return Outgoing(payload: .with { $0.notificationAction = a }, ttl: a.replyText.isEmpty ? Self.ttl : 0)
    }

    nonisolated(nonsending) func reset() async {
        await centre.removeAll()
        settings = nil
        icons = [:]
        asked = []
    }

    private func received(_ received: Seam_V1_NotificationSettings) -> [Outgoing] {
        var s = received
        for i in s.apps.indices where !s.apps[i].iconPng.isEmpty {
            let png = s.apps[i].iconPng
            if png.count <= Self.maxIcon, NSImage(data: png) != nil { icons[s.apps[i].appID] = png }
            s.apps[i].iconPng = Data()
        }
        settings = s
        // In batches the phone answers in one message; each answer brings the next request
        let missing = Array(s.apps.map(\.appID).filter { icons[$0] == nil && !asked.contains($0) }.prefix(Self.iconBatch))
        guard !missing.isEmpty else { return [] }
        asked.formUnion(missing)
        return [Outgoing(payload: .with { $0.notificationSettingsChange = .with { $0.needIcons = missing } }, ttl: Self.ttl)]
    }
}
