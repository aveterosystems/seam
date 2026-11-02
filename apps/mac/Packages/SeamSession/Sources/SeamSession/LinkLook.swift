// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import SeamCore
import SeamProtocol

/// How the link to the phone is shown, everywhere it's shown (docs/design/design-system.md,
/// "The stitch"). The line, the icon and the words always come from here, together, so no view
/// decides any of them on its own.
public struct LinkLook: Equatable, Sendable {
    public enum Line: Sendable { case solid, dashed, dotted, broken }

    public let line: Line
    /// The path as an SF Symbol; nil when there's no path.
    public let symbol: String?
    /// The path's name, or, when not connected, what's happening.
    public let label: String
    public let connected: Bool
    /// Why, and what happens next: shown when the status is clicked. Nil when the label says it all.
    public var detail: String? = nil

    public static let sameWifi = LinkLook(line: .solid, symbol: "wifi", label: "Same Wi‑Fi", connected: true)
    /// A neutral globe, not the Tor onion (that needs the Tor Project's permission; docs/tor/11-wording.md).
    public static let throughTor = LinkLook(line: .dashed, symbol: "globe", label: "Through Tor", connected: true)
    public static let connectingTor = LinkLook(line: .dotted, symbol: "globe", label: "Connecting through Tor", connected: false)
    public static let torBlocked = LinkLook.offline("Tor is blocked on this network",
        detail: "This network stops Tor connections. Your phone can still connect on the same Wi‑Fi.")
    public static let torClock = LinkLook.offline("Tor needs the correct time. Check your clock",
        detail: "Tor won't connect while this Mac's clock is wrong. Turn on Set time automatically in System Settings.")
    public static let noSharedPath = LinkLook.offline("Your phone and this Mac have no connection type on in common",
        detail: "Turn on the same connection type, Same Wi‑Fi or Tor, on both devices.")
    public static let waitingOnWifi = LinkLook.offline("Waiting for your phone on the same Wi‑Fi",
        detail: "Only Same Wi‑Fi is on, so your phone connects when it's on this Mac's network. Turn on Tor to connect from anywhere.")
    public static let away = LinkLook.offline("Offline · will send when your phone is back",
        detail: "To save battery, your phone disconnects about a minute after its screen goes off. It's back when you unlock it or it has something to send. Copies from this Mac wait up to 10 minutes.")

    /// The phone was asked to dial again: dots travel along the line until it's back.
    public static let reconnecting = LinkLook(line: .dotted, symbol: nil, label: "Reconnecting", connected: false)
    /// The phone was out of reach, so only this Mac's side started again.
    public static let wakePhone = LinkLook.offline("Turn on your phone's screen to connect",
        detail: "This Mac started its side again. Your phone can't be reached from here: it connects when its screen is on, or when it has something to send.")

    public static func offline(_ why: String, detail: String? = nil) -> LinkLook {
        LinkLook(line: .broken, symbol: nil, label: why, connected: false, detail: detail)
    }

    /// Paused on the phone (only the phone pauses): nothing syncs until it ends or the phone resumes.
    public static func paused(until: Date, now: Date, calendar: Calendar = .current) -> LinkLook {
        let label: String
        if until == .distantFuture {
            label = "Paused on your phone until you resume it there"
        } else {
            let time = until.formatted(date: .omitted, time: .shortened)
            let day = calendar.isDate(until, inSameDayAs: now) ? ""
                : calendar.isDate(until, inSameDayAs: calendar.date(byAdding: .day, value: 1, to: now) ?? now) ? "tomorrow "
                : until.formatted(date: .abbreviated, time: .omitted) + " "
            label = "Paused on your phone until \(day)\(time)"
        }
        return LinkLook(line: .broken, symbol: "pause.circle", label: label, connected: false,
            detail: "Nothing syncs until the pause ends. Only your phone can resume it.")
    }

    /// The one place a path becomes a look. With no route, Tor's own state says why when Tor is
    /// the way the phone would come (never a silent wait on a blocked network).
    public static func of(route: Seam_V1_Transport?, allowed: Set<Seam_V1_Transport>, tor: TorState = .ready) -> LinkLook {
        switch route {
        case .lan?: return .sameWifi
        case .tor?: return .throughTor
        default:
            if allowed.isEmpty { return .noSharedPath }
            guard allowed.contains(.tor) else { return .waitingOnWifi }
            switch tor {
            case .off, .starting: return .connectingTor
            case .blocked(.filtered): return .torBlocked
            case .blocked(.clockSkewed): return .torClock
            default: return .away
            }
        }
    }

    /// The same, from the core's connection rules. A pause on the phone comes first; a Reconnect
    /// under way shows until a path is up, but never hides why one can't be (Tor blocked, no shared path).
    public static func of(route: PeerPath?, allowed: [PeerPath], tor: TorState = .ready, pausedUntil: Date? = nil, now: Date = Date(),
                          reconnect: Reconnect? = nil) -> LinkLook {
        if let pausedUntil, pausedUntil > now { return .paused(until: pausedUntil, now: now) }
        let look = of(route: route?.transport, allowed: Set(allowed.map(\.transport)), tor: tor)
        guard let reconnect, !look.connected, ![LinkLook.torBlocked, .torClock, .noSharedPath].contains(look) else { return look }
        return reconnect == .asked ? .reconnecting : .wakePhone
    }

    /// The whole line in words, for VoiceOver and anywhere there's no room for the icon.
    public var sentence: String { connected ? "Connected · \(label)" : label }
}

extension PeerPath {
    /// In Hello form.
    public var transport: Seam_V1_Transport {
        switch self {
        case .lan: .lan
        case .tor: .tor
        }
    }
}
