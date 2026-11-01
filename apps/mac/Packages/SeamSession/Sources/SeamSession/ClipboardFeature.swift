// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import Observation
import SeamCore
import SeamProtocol

/// One clip that crossed between the Mac and the phone.
public struct Clip: Identifiable, Sendable {
    public let id = UUID()
    public let text: String
    public let at: Date
    public let fromPhone: Bool

    public init(text: String, at: Date, fromPhone: Bool) {
        self.text = text
        self.at = at
        self.fromPhone = fromPhone
    }

    public var openableURL: URL? { ClipRules.openableURL(text) }
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

/// Clipboard both ways: copies here go to the phone while sync is on, the phone's land here, and
/// neither bounces back (the core's ClipRules).
@MainActor @Observable
public final class ClipboardFeature: Feature {
    /// macOS has no change notification for the pasteboard: looking at its counter twice a second
    /// is the standard way, and cheap.
    static let pollInterval: TimeInterval = 0.5

    @ObservationIgnored private let pasteboard: any Pasteboard
    @ObservationIgnored private let timers: any Timers
    @ObservationIgnored private let rules = ClipRules()
    @ObservationIgnored private var lastChange: Int?
    /// The latest copy made while the phone was unreachable (SR-4-03).
    @ObservationIgnored private var held = ClipQueue<Seam_V1_Payload>()
    @ObservationIgnored private weak var host: (any FeatureHost)?
    @ObservationIgnored private var pollTimer: TimerToken?
    @ObservationIgnored private var stopped = false
    public private(set) var syncOn: Bool
    /// Clips that crossed either way in the last day, newest first, for the main window (memory
    /// only: gone when Seam quits).
    public internal(set) var clips: [Clip] = []
    static let maxClips = 100
    /// Something new from the phone, for the app to announce (the menu bar ping, VoiceOver).
    @ObservationIgnored public var onArrival: ((String) -> Void)?

    public nonisolated init(pasteboard: any Pasteboard, syncOn: Bool, timers: any Timers = SystemTimers()) {
        self.pasteboard = pasteboard
        _syncOn = syncOn // the stored value: @Observable's setter is main-actor only
        self.timers = timers
    }

    /// What's on the pasteboard at launch was copied before, not for the phone.
    public func start(host: any FeatureHost) async {
        self.host = host
        lastChange = await pasteboard.changeCount()
        pollLater()
    }

    public func stop() {
        stopped = true
        pollTimer?.cancel()
    }

    public func setSync(_ on: Bool) async {
        syncOn = on
        lastChange = await pasteboard.changeCount() // turning it on never sends what was copied while off
        if !on {
            held.clear() // nor does turning it off let a held copy go later
            clips = [] // and it takes the clips off the screen (SR-10-04)
        }
    }

    private func pollLater() {
        guard !stopped else { return }
        pollTimer = timers.after(Self.pollInterval) { [weak self] in await self?.poll() }
    }

    private func poll() async {
        guard let host else { return }
        if let payload = await capture(reach: await host.reach(), now: timers.now()) { await host.send(payload) }
        pollLater()
    }

    /// A new copy here: what to send now. Held instead while the phone can't be reached; with no
    /// phone, or while paused, seen and let go, so it never goes later either.
    private func capture(reach: Reach, now: Date) async -> Seam_V1_Payload? {
        let change = await pasteboard.changeCount()
        guard change != lastChange else { return nil }
        guard syncOn, reach != .off else {
            lastChange = change
            return nil
        }
        guard let item = await pasteboard.firstItem(ifStill: change) else { return nil } // look again next time
        lastChange = change
        guard syncOn else { return nil } // turned off while the read was in progress (SR-29-05)
        guard ClipRules.sendable(types: item.types),
              let text = rules.outgoing(text: item.text, nowMs: Int64(now.timeIntervalSince1970 * 1000)) else { return nil }
        let payload = Seam_V1_Payload.with {
            $0.clipboard = .with {
                $0.text = text
                $0.copiedAt = Int64(now.timeIntervalSince1970 * 1000)
            }
        }
        remember(Clip(text: text, at: now, fromPhone: false))
        guard reach == .connected else {
            held.hold(payload, at: now)
            return nil
        }
        return payload
    }

    /// Puts an earlier clip from the phone back on this Mac's clipboard (popover "Copy").
    public func copyAgain(_ text: String) async {
        lastChange = await pasteboard.write(text) // and doesn't send it back
    }

    public func receive(_ body: Seam_V1_Payload.OneOf_Body) async {
        guard case .clipboard(let clip) = body, syncOn, let text = rules.incoming(text: clip.text) else { return }
        lastChange = await pasteboard.write(text) // our own write isn't a new copy
        remember(Clip(text: text, at: Date(), fromPhone: true))
        onArrival?("Copied from your phone")
    }

    public func clearClips() { clips = [] }

    private func remember(_ clip: Clip) {
        let dayAgo = clip.at.addingTimeInterval(-86400)
        clips = Array(([clip] + clips.filter { $0.at > dayAgo }).prefix(Self.maxClips))
    }

    /// A copy held while the phone was away goes now, unless it's too old.
    public func connected() async {
        if let payload = held.take(now: timers.now(), syncOn: syncOn) { await host?.send(payload) }
    }

    public func reset() async {
        held.clear()
        rules.reset() // nothing the old phone sent counts as an echo
        clips = []
    }
}
