// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import SeamProtocol

/// One clip that arrived from the phone.
public struct FromPhone: Identifiable, Sendable {
    public let id = UUID()
    public let text: String
    public let at: Date

    public var openableURL: URL? { ClipRules.openableURL(text) }
}

/// Clipboard both ways: copies here go to the phone while sync is on, the phone's land here, and
/// neither bounces back (ClipRules).
final class ClipboardFeature: Feature {
    /// A stale clipboard is worse than none: the relay drops it after 10 minutes.
    static let ttl: UInt32 = 600
    /// macOS has no change notification for the pasteboard: looking at its counter twice a second
    /// is the standard way, and cheap.
    static let pollInterval: TimeInterval = 0.5

    private let pasteboard: any Pasteboard
    private var rules = ClipRules()
    private var lastChange: Int?
    /// The latest copy made while the phone was unreachable (SR-4-03).
    private var held = ClipQueue<Seam_V1_Payload>()
    private(set) var syncOn: Bool
    /// The last two clips from the phone, for the popover (memory only).
    private(set) var recent: [FromPhone] = []

    init(pasteboard: any Pasteboard, syncOn: Bool) {
        self.pasteboard = pasteboard
        self.syncOn = syncOn
    }

    /// What's on the pasteboard at launch was copied before, not for the phone.
    nonisolated(nonsending) func start() async {
        lastChange = await pasteboard.changeCount()
    }

    nonisolated(nonsending) func setSync(_ on: Bool) async {
        syncOn = on
        lastChange = await pasteboard.changeCount() // turning it on never sends what was copied while off
        if !on {
            held.clear() // nor does turning it off let a held copy go later
            recent = [] // and it takes the phone's clips off the screen (SR-10-04)
        }
    }

    /// A new copy here: what to send now. Held instead while the phone can't be reached.
    nonisolated(nonsending) func poll(paired: Bool, connected: Bool, now: Date) async -> Outgoing? {
        let change = await pasteboard.changeCount()
        guard change != lastChange else { return nil }
        guard syncOn, paired else {
            lastChange = change
            return nil
        }
        guard let item = await pasteboard.firstItem(ifStill: change) else { return nil } // look again next time
        lastChange = change
        guard ClipRules.sendable(types: item.types), let text = rules.outgoing(item.text, now: now) else { return nil }
        let payload = Seam_V1_Payload.with {
            $0.clipboard = .with {
                $0.text = text
                $0.copiedAt = Int64(now.timeIntervalSince1970 * 1000)
            }
        }
        guard connected else {
            held.hold(payload, at: now)
            return nil
        }
        return Outgoing(payload: payload, ttl: Self.ttl)
    }

    /// Puts an earlier clip from the phone back on this Mac's clipboard (popover "Copy").
    nonisolated(nonsending) func copyAgain(_ text: String) async {
        lastChange = await pasteboard.write(text) // and doesn't send it back
    }

    nonisolated(nonsending) func receive(_ body: Seam_V1_Payload.OneOf_Body) async -> [Outgoing] {
        guard case .clipboard(let clip) = body, syncOn, let text = rules.incoming(clip.text) else { return [] }
        lastChange = await pasteboard.write(text) // our own write isn't a new copy
        recent = Array(([FromPhone(text: text, at: Date())] + recent).prefix(2))
        return []
    }

    /// `now` decides whether a held copy is too old to send.
    nonisolated(nonsending) func onConnected(now: Date) async -> [Outgoing] {
        held.take(now: now, syncOn: syncOn).map { [Outgoing(payload: $0, ttl: Self.ttl)] } ?? []
    }

    nonisolated(nonsending) func reset() async {
        held.clear()
        recent = []
    }
}
