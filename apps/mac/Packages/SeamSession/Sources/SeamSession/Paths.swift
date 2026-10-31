// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import SeamProtocol

/// The relay connection as `Paths` runs it: `RelayClient` in the app, a fake in tests.
public protocol RelayRunner: RelaySender {
    /// Runs until its task is cancelled.
    func run() async
}

/// The Same Wi‑Fi listener as `Paths` runs it: `LanServer` in the app, a fake in tests.
public protocol LanRunner: LanSender, AnyObject {
    func start() async throws -> UInt16
    /// Closes gracefully: what was already sent is written first.
    func stop()
    var events: AsyncStream<LanEvent> { get }
}

extension RelayClient: RelayRunner {}
extension LanServer: LanRunner {}

/// Runs the paths the connection switches allow (decision 0006) and keeps the session told, in
/// the order that matters:
/// - a switch reaches the session before its relay goes, so nothing more is sent on a relay
///   switched off (SR-29-07);
/// - switch changes apply one at a time, in order, and pairing can wait for them (SR-29-11);
/// - an old relay connection finishes before a new one runs, so its events never land after
///   the new one's.
@MainActor
public final class Paths {
    public private(set) var wifiOn: Bool
    public private(set) var relayOn: Bool

    private let session: Session
    private let makeRelay: @MainActor (any RelayHandler) -> (any RelayRunner)?
    private let makeLan: @MainActor () -> any LanRunner
    private let lanFailed: @MainActor () -> Void
    private var phonePaired = false
    private var stopped = false
    private var relayTask: Task<Void, Never>?
    private var lan: (any LanRunner)?
    private var lanTask: Task<Void, Never>?
    private var switching: Task<Void, Never>?

    /// `makeRelay` returns nil when there's no relay to use; `lanFailed` is told when the
    /// listener can't start (the app logs it).
    public init(
        session: Session, wifi: Bool, relay: Bool,
        makeRelay: @escaping @MainActor (any RelayHandler) -> (any RelayRunner)?,
        makeLan: @escaping @MainActor () -> any LanRunner,
        lanFailed: @escaping @MainActor () -> Void = {}
    ) {
        self.session = session
        wifiOn = wifi
        relayOn = relay
        self.makeRelay = makeRelay
        self.makeLan = makeLan
        self.lanFailed = lanFailed
    }

    /// Opens what the switches allow. Call once the session has started, so what waited from
    /// before goes ahead of anything new.
    public func start(phonePaired: Bool) {
        self.phonePaired = phonePaired
        restartRelay()
        updateLan()
    }

    /// Closes everything, for a session being replaced.
    public func stop() {
        stopped = true
        switching?.cancel()
        relayTask?.cancel()
        closeLan()
    }

    /// False, and nothing changes, if it would turn off the last switch: one always stays on.
    @discardableResult
    public func setWifi(_ on: Bool) -> Bool {
        guard on || relayOn else { return false }
        wifiOn = on
        updateLan()
        applySwitches(restartingRelay: false)
        return true
    }

    /// False, and nothing changes, if it would turn off the last switch.
    @discardableResult
    public func setRelay(_ on: Bool) -> Bool {
        guard on || wifiOn else { return false }
        relayOn = on
        applySwitches(restartingRelay: true)
        return true
    }

    /// Returns once every switch change so far has reached the session (pairing starts after
    /// "Turn On Relay" only once the session knows, SR-29-11).
    public func settled() async {
        await switching?.value
    }

    /// The phone was paired or forgotten: the listener runs only while one is.
    public func setPhonePaired(_ paired: Bool) {
        phonePaired = paired
        updateLan()
    }

    /// (Re)starts the relay connection, e.g. after moving to another relay. The old connection
    /// finishes first.
    public func restartRelay() {
        guard !stopped else { return }
        let oldTask = relayTask
        let next = relayOn ? makeRelay(session) : nil
        let session = session
        relayTask = Task {
            await session.setRelay(next)
            oldTask?.cancel()
            await oldTask?.value
            guard !Task.isCancelled, let next else { return }
            await next.run()
        }
    }

    /// Each change waits for the one before; the session hears it before the relay changes.
    private func applySwitches(restartingRelay: Bool) {
        let previous = switching
        switching = Task { [weak self] in
            await previous?.value
            guard let self, !self.stopped else { return }
            await self.session.setSwitches(wifi: self.wifiOn, relay: self.relayOn)
            if restartingRelay { self.restartRelay() }
        }
    }

    private func updateLan() {
        let wanted = !stopped && wifiOn && phonePaired
        if wanted, lan == nil {
            let server = makeLan()
            lan = server
            let session = session
            lanTask = Task { [weak self] in
                do {
                    _ = try await server.start()
                } catch {
                    self?.lanFailed()
                    if self?.lan === server { self?.lan = nil }
                    return
                }
                await session.setLan(server)
                for await event in server.events { await session.lanEvent(event) }
            }
        } else if !wanted {
            closeLan()
        }
    }

    private func closeLan() {
        guard let server = lan else { return }
        lanTask?.cancel()
        lan = nil
        server.stop()
        let session = session
        Task { await session.setLan(nil) }
    }
}
