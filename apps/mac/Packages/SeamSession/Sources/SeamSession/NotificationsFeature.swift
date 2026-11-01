// SPDX-License-Identifier: AGPL-3.0-only
import AppKit
import Observation
import SeamProtocol

/// The Mac's notification centre, as the notifications feature sees it.
public protocol NotificationCentre: Sendable {
    /// `icon`: the phone app's icon PNG.
    func show(_ notification: Seam_V1_Notification, icon: Data?) async
    func dismiss(key: String) async
    /// Every banner from the phone.
    func removeAll() async
}

/// The phone's notifications as banners here, what's done to them sent back, and the phone's
/// notification settings (the phone owns them; the Mac asks it to change them).
@MainActor @Observable
public final class NotificationsFeature: Feature {
    /// Matches the phone's limit per message (NotificationsFeature.MAX_ICONS).
    static let iconBatch = 40
    /// From the phone, but still checked: a small, real image or nothing.
    static let maxIcon = 256 * 1024

    @ObservationIgnored private let centre: any NotificationCentre
    @ObservationIgnored private weak var host: (any FeatureHost)?
    /// As the phone last sent them, without icons. Memory only.
    public internal(set) var settings: Seam_V1_NotificationSettings?
    /// Phone apps' icon PNGs, from the phone when asked for. Memory only.
    public private(set) var icons: [String: Data] = [:]
    /// The same icons, ready to draw.
    public private(set) var images: [String: NSImage] = [:]
    /// The phone's notifications that are still up, newest first, for the main window (memory only).
    public internal(set) var shown: [Seam_V1_Notification] = []
    static let maxShown = 200
    /// Counts notifications arriving from the phone, so the bell rings once for each.
    public private(set) var arrivals = 0
    /// Something new from the phone, for the app to announce (the menu bar ping, VoiceOver).
    @ObservationIgnored public var onArrival: ((String) -> Void)?
    /// Asked once per launch each, so an app whose icon the phone can't draw isn't asked for forever.
    @ObservationIgnored private var asked: Set<String> = []

    public nonisolated init(centre: any NotificationCentre) {
        self.centre = centre
    }

    public func start(host: any FeatureHost) async { self.host = host }

    public func receive(_ body: Seam_V1_Payload.OneOf_Body) async {
        switch body {
        case .notification(let n):
            await centre.show(n, icon: icons[n.appID])
            // An update to one that's up (a new message in a chat) replaces it
            let old = shown
            shown = Array(([n] + shown.filter { $0.key != n.key }).prefix(Self.maxShown))
            if Arrival.notification(old: old, new: shown) != nil {
                arrivals += 1
                onArrival?("Notification from \(n.appName)")
            }
        case .notificationAction(let a):
            if a.dismissed {
                await centre.dismiss(key: a.key)
                shown.removeAll { $0.key == a.key }
            }
        case .notificationSettings(let s):
            await received(s)
        default:
            break
        }
    }

    /// Asks the phone to change its settings, showing the change here at once; the phone's
    /// answer confirms it.
    public func change(_ change: Seam_V1_NotificationSettingsChange) async {
        if var s = settings {
            if change.hasEnabled { s.enabled = change.enabled }
            if change.hasDismissBoth { s.dismissBoth = change.dismissBoth }
            if change.hasQuietWhileUsingPhone { s.quietWhileUsingPhone = change.quietWhileUsingPhone }
            if change.hasPausedUntil { s.pausedUntil = change.pausedUntil }
            let modes = Dictionary(change.apps.map { ($0.appID, $0.mode) }, uniquingKeysWith: { $1 })
            for i in s.apps.indices { if let mode = modes[s.apps[i].appID] { s.apps[i].mode = mode } }
            settings = s
        }
        await host?.send(.with { $0.notificationSettingsChange = change })
    }

    /// What the user did to a banner or the main window here, for the phone. A typed reply waits
    /// for the phone like any message; a stale dismiss or button press could hit a later
    /// notification that reuses the key, so it expires sooner (the core's catalogue, SR-19-03).
    public func act(_ a: Seam_V1_NotificationAction) async {
        if a.dismissed {
            shown.removeAll { $0.key == a.key }
            if settings?.dismissBoth == false { return }
        }
        await host?.send(.with { $0.notificationAction = a })
    }

    /// The main window's dismiss: the banner goes too.
    public func dismiss(key: String) async {
        await centre.dismiss(key: key)
        await act(.with { $0.key = key; $0.dismissed = true })
    }

    public func reset() async {
        await centre.removeAll()
        shown = []
        settings = nil
        icons = [:]
        images = [:]
        asked = []
    }

    private func received(_ received: Seam_V1_NotificationSettings) async {
        var s = received
        for i in s.apps.indices where !s.apps[i].iconPng.isEmpty {
            let png = s.apps[i].iconPng
            if png.count <= Self.maxIcon, icons[s.apps[i].appID] != png, let image = NSImage(data: png) {
                icons[s.apps[i].appID] = png
                images[s.apps[i].appID] = image
            }
            s.apps[i].iconPng = Data()
        }
        settings = s
        // In batches the phone answers in one message; each answer brings the next request
        let missing = Array(s.apps.map(\.appID).filter { icons[$0] == nil && !asked.contains($0) }.prefix(Self.iconBatch))
        guard !missing.isEmpty else { return }
        asked.formUnion(missing)
        await host?.send(.with { $0.notificationSettingsChange = .with { $0.needIcons = missing } })
    }
}
