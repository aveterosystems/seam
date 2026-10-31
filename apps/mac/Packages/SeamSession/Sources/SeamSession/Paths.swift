// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import SeamCore
import SeamProtocol

/// A direct-link carrier as `Paths` runs it: `LanServer` in the app (Same Wi‑Fi's listener, or
/// the Tor service's streams), a fake in tests.
public protocol LanRunner: LanSender, AnyObject {
    func start() async throws -> UInt16
    /// Closes gracefully: what was already sent is written first.
    func stop()
    var events: AsyncStream<LanEvent> { get }
    /// Takes a stream from the Mac's onion service, as if it had connected (the Tor carrier).
    func adopt(_ stream: TorStream)
}

extension LanServer: LanRunner {}

/// One onion service as the session wants it: its secret seed and the discovery keys of the only
/// clients that may find it. Never printed: the secret is the service's identity.
public struct TorServiceSpec: Equatable, Hashable, Sendable, CustomStringConvertible {
    public let secret: Data
    public let clients: [Data]

    public init(secret: Data, clients: [Data]) {
        self.secret = secret
        self.clients = clients
    }

    public var description: String { "TorServiceSpec(..)" }
}

/// Tor as `Paths` runs it: `SeamTor` in the app, a fake in tests.
public protocol TorRunner: AnyObject, Sendable {
    /// Starts Tor once; `onState` hears every change (on any thread).
    func start(onState: @escaping @Sendable (TorState) -> Void)
    /// Publishes a service; its streams go to `onStream`. Nil while Tor isn't ready.
    func host(_ spec: TorServiceSpec, onStream: @escaping @Sendable (TorStream) -> Void) -> (any TorHosted)?
}

/// A published onion service.
public protocol TorHosted: AnyObject, Sendable {
    func stop()
}

/// Runs the paths the connection switches allow (decision 0006) and keeps the session told:
/// - a carrier switched off closes at once, so nothing more can go on it (SR-29-07); a closed
///   carrier refuses every send;
/// - switch changes reach the session one at a time, in order (SR-29-11);
/// - the Mac's onion services follow what the session asks for (the paired phone's, a pairing's),
///   republished only when that changes or after wake, never on every network change. Tor is
///   started once and never made dormant here: the Mac is the one the phone reaches.
@MainActor
public final class Paths {
    public private(set) var wifiOn: Bool
    public private(set) var torOn: Bool
    /// Tor's own state, for the link's words (blocked, clock); `onTorState` hears changes.
    public private(set) var torState: TorState = .off

    private let session: Session
    private let makeLan: @MainActor () -> any LanRunner
    private let makeTorLan: @MainActor () -> any LanRunner
    private let tor: any TorRunner
    private let lanFailed: @MainActor () -> Void
    private let onTorState: @MainActor (TorState) -> Void
    private var linksWanted = false
    private var stopped = false
    private var torStarted = false
    private var wanted: [TorServiceSpec] = []
    private var hosted: [TorServiceSpec: any TorHosted] = [:]
    private var carriers: [PeerPath: (runner: any LanRunner, task: Task<Void, Never>)] = [:]
    private var switching: Task<Void, Never>?

    /// `makeLan`: Same Wi‑Fi's listener. `makeTorLan`: the carrier Tor streams are adopted into.
    /// `lanFailed` is told when the listener can't start (the app logs it).
    public init(
        session: Session, wifi: Bool, tor: Bool, torRunner: any TorRunner,
        makeLan: @escaping @MainActor () -> any LanRunner,
        makeTorLan: @escaping @MainActor () -> any LanRunner,
        lanFailed: @escaping @MainActor () -> Void = {},
        onTorState: @escaping @MainActor (TorState) -> Void = { _ in }
    ) {
        self.session = session
        wifiOn = wifi
        torOn = tor
        self.tor = torRunner
        self.makeLan = makeLan
        self.makeTorLan = makeTorLan
        self.lanFailed = lanFailed
        self.onTorState = onTorState
    }

    /// Opens what the switches allow. Call once the session has started, so what waited from
    /// before goes ahead of anything new.
    public func start(linksWanted: Bool, services: [TorServiceSpec] = []) {
        update(linksWanted: linksWanted, services: services)
    }

    /// Closes everything, for a session being replaced.
    public func stop() {
        stopped = true
        switching?.cancel()
        for path in Array(carriers.keys) { close(path) }
        for service in hosted.values { service.stop() }
        hosted = [:]
    }

    /// False, and nothing changes, if it would turn off the last switch: one always stays on.
    @discardableResult
    public func setWifi(_ on: Bool) -> Bool {
        guard on || torOn else { return false }
        wifiOn = on
        updateCarriers()
        applySwitches()
        return true
    }

    /// False, and nothing changes, if it would turn off the last switch.
    @discardableResult
    public func setTor(_ on: Bool) -> Bool {
        guard on || wifiOn else { return false }
        torOn = on
        updateCarriers()
        reconcile()
        applySwitches()
        return true
    }

    /// Returns once every switch change so far has reached the session.
    public func settled() async {
        await switching?.value
    }

    /// What the session wants now: links while a phone is paired or pairing, and these services.
    public func update(linksWanted: Bool, services: [TorServiceSpec]) {
        self.linksWanted = linksWanted
        wanted = services
        updateCarriers()
        reconcile()
    }

    /// After wake: the services are published again (the descriptors may have lapsed meanwhile).
    public func republish() {
        for service in hosted.values { service.stop() }
        hosted = [:]
        reconcile()
    }

    // MARK: Private

    /// Each change waits for the one before, carrying the switches as they were when it was made.
    private func applySwitches() {
        let previous = switching
        let (wifi, tor) = (wifiOn, torOn)
        switching = Task { [weak self] in
            await previous?.value
            guard let self, !self.stopped else { return }
            await self.session.setSwitches(wifi: wifi, tor: tor)
        }
    }

    /// Hosts what's wanted and not yet up, stops what's no longer wanted. Tor starts on first need.
    private func reconcile() {
        let want = stopped || !torOn ? [] : wanted
        for (spec, service) in hosted where !want.contains(spec) {
            service.stop()
            hosted[spec] = nil
        }
        guard !want.isEmpty else { return }
        if !torStarted {
            torStarted = true
            tor.start { [weak self] state in Task { @MainActor in self?.torChanged(state) } }
        }
        guard torState == .ready, let carrier = carriers[.tor]?.runner else { return }
        for spec in want where hosted[spec] == nil {
            hosted[spec] = tor.host(spec) { [weak carrier] stream in
                guard let carrier else { return stream.close() }
                carrier.adopt(stream)
            }
        }
    }

    private func torChanged(_ state: TorState) {
        torState = state
        onTorState(state)
        if state == .ready { reconcile() }
    }

    private func updateCarriers() {
        for (path, on) in [(PeerPath.lan, wifiOn), (.tor, torOn)] {
            let want = !stopped && on && linksWanted
            if want, carriers[path] == nil {
                open(path)
            } else if !want {
                close(path)
            }
        }
    }

    private func open(_ path: PeerPath) {
        let runner = path == .lan ? makeLan() : makeTorLan()
        let session = session
        let task = Task { [weak self] in
            do {
                _ = try await runner.start()
            } catch {
                self?.lanFailed()
                if self?.carriers[path]?.runner === runner { self?.carriers[path] = nil }
                return
            }
            // Closed or replaced while starting: never hand a stale carrier to the session (SR-31-03)
            guard !Task.isCancelled, self?.carriers[path]?.runner === runner else { return runner.stop() }
            await session.setLink(runner, for: path)
            for await event in runner.events { await session.linkEvent(event, on: path) }
        }
        carriers[path] = (runner, task)
    }

    private func close(_ path: PeerPath) {
        guard let carrier = carriers.removeValue(forKey: path) else { return }
        carrier.task.cancel()
        carrier.runner.stop()
        if path == .tor {
            for service in hosted.values { service.stop() } // no carrier to take their streams
            hosted = [:]
        }
        let session = session
        Task { await session.setLink(nil, for: path) }
    }
}
