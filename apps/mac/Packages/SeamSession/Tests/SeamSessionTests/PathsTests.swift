// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import SeamCore
import SeamProtocol
import Testing

@testable import SeamSession

/// What the fakes did, in order.
final class EventLog: @unchecked Sendable {
    private let lock = NSLock()
    private var events: [String] = []

    func add(_ event: String) { lock.withLock { events.append(event) } }
    var all: [String] { lock.withLock { events } }
}

final class FakeLanRunner: LanRunner, @unchecked Sendable {
    let name: String
    let log: EventLog
    let fails: Bool
    /** Holds `start()` until released, like a listener still binding its port. */
    var gate: CheckedContinuation<Void, Never>?
    var slow = false
    let events: AsyncStream<LanEvent>
    private let continuation: AsyncStream<LanEvent>.Continuation

    init(_ log: EventLog, name: String = "lan", fails: Bool = false) {
        self.name = name
        self.log = log
        self.fails = fails
        (events, continuation) = AsyncStream.makeStream()
    }

    func start() async throws -> UInt16 {
        log.add("\(name) starts")
        if slow { await withCheckedContinuation { gate = $0 } }
        if fails { throw CancellationError() }
        return 1
    }

    func stop() {
        log.add("\(name) stops")
        continuation.finish()
    }

    func adopt(_ stream: TorStream) {}
    func send(_ frame: Seam_V1_ServerFrame, to peerId: String) -> Bool { false }
    func close(peerId: String) {}
}

/// Tor that's ready when the test says, and remembers which services are up.
final class FakeTor: TorRunner, @unchecked Sendable {
    private let lock = NSLock()
    private var report: (@Sendable (TorState) -> Void)?
    private var _live: Set<TorServiceSpec> = []
    private var _starts = 0
    private var _hosts = 0

    var live: Set<TorServiceSpec> { lock.withLock { _live } }
    var starts: Int { lock.withLock { _starts } }
    /// Every publish, a republish included.
    var hosts: Int { lock.withLock { _hosts } }

    func start(onState: @escaping @Sendable (TorState) -> Void) {
        lock.withLock {
            _starts += 1
            report = onState
        }
        onState(.starting(percent: 0))
    }

    func become(_ state: TorState) {
        lock.withLock { report }?(state)
    }

    func host(_ spec: TorServiceSpec, onStream: @escaping @Sendable (TorStream) -> Void) -> (any TorHosted)? {
        lock.withLock {
            _hosts += 1
            _live.insert(spec)
        }
        return Hosted { [weak self] in self?.stopped(spec) }
    }

    private func stopped(_ spec: TorServiceSpec) {
        lock.withLock { _ = _live.remove(spec) }
    }

    private final class Hosted: TorHosted, @unchecked Sendable {
        let onStop: @Sendable () -> Void
        init(_ onStop: @escaping @Sendable () -> Void) { self.onStop = onStop }
        func stop() { onStop() }
    }
}

@MainActor
@Suite struct PathsTests {
    let log = EventLog()
    let tor = FakeTor()
    private let a = TorServiceSpec(secret: Data(repeating: 0xA, count: 32), clients: [Data(repeating: 1, count: 32)])
    private let b = TorServiceSpec(secret: Data(repeating: 0xB, count: 32), clients: [Data(repeating: 2, count: 32)])

    private func paths(_ rig: Rig, wifi: Bool = true, tor torOn: Bool = true, lanFails: Bool = false) -> Paths {
        Paths(
            session: rig.session, wifi: wifi, tor: torOn, torRunner: tor,
            makeLan: { [log] in FakeLanRunner(log, fails: lanFails) },
            makeTorLan: { [log] in FakeLanRunner(log, name: "tor") },
            lanFailed: { [log] in log.add("lan failed") },
            onTorState: { [log] in log.add("tor \($0)") }
        )
    }

    /// Lets the tasks Paths started run to their next wait.
    private func settle(_ p: Paths) async {
        await p.settled()
        for _ in 0..<20 { await Task.yield() }
    }

    /// Tells Paths what the session wants now, as the app does on every state update.
    private func follow(_ p: Paths, _ rig: Rig) async {
        let s = await rig.session.state
        p.update(linksWanted: s.wantsLinks, services: s.torServices)
        await settle(p)
    }

    @Test func theLastSwitchCantGoOff() async throws {
        let p = paths(try await Rig(), wifi: true, tor: false)
        #expect(!p.setWifi(false))
        #expect(p.wifiOn)
        #expect(p.setTor(true))
        #expect(p.setWifi(false))
        #expect(!p.setTor(false))
        #expect(p.torOn)
    }

    @Test func servicesArePublishedOnceTorIsReady() async throws {
        let p = paths(try await Rig())
        p.start(linksWanted: true, services: [a])
        await settle(p)
        #expect(tor.starts == 1 && tor.live.isEmpty, "not before Tor is ready")
        tor.become(.ready)
        await settle(p)
        #expect(tor.live == [a])
        #expect(log.all.contains("tor ready"), "the app hears Tor's state for the link's words")
    }

    @Test func onlyAChangeRepublishes() async throws {
        let p = paths(try await Rig())
        p.start(linksWanted: true, services: [a])
        tor.become(.ready)
        await settle(p)
        p.update(linksWanted: true, services: [a]) // any state update: nothing changes
        p.update(linksWanted: true, services: [a, b])
        await settle(p)
        #expect(tor.live == [a, b] && tor.hosts == 2, "a stays up; only b is new")
        p.update(linksWanted: true, services: [b])
        await settle(p)
        #expect(tor.live == [b])
        p.republish() // after wake
        #expect(tor.live == [b] && tor.hosts == 3)
    }

    @Test func switchingTorOffTellsTheSessionThenStopsItsServicesButNeverTor() async throws {
        let rig = try await Rig()
        let p = paths(rig)
        p.start(linksWanted: true, services: [a])
        tor.become(.ready)
        await settle(p)
        p.setTor(false)
        await settle(p)
        // SR-29-07: the session already knew, so nothing more is routed through Tor
        #expect(await !rig.session.state.allowed.contains(.tor))
        #expect(tor.live.isEmpty)
        #expect(log.all.contains("tor stops"), "the carrier closes")
        p.setTor(true)
        await settle(p)
        #expect(tor.live == [a], "back on: published again")
        #expect(tor.starts == 1, "Tor itself was started once and kept running")
    }

    @Test func followsTheSessionThroughPairCancelAndUnpair() async throws {
        let rig = try await Rig()
        let p = paths(rig)
        await follow(p, rig)
        tor.become(.ready)
        await settle(p)
        let paired = TorServiceSpec(secret: Rig.macData.torSecret, clients: [Rig.macData.peerDiscovery])
        #expect(tor.live == [paired])
        await rig.session.startPairing(name: "Mac")
        await follow(p, rig)
        #expect(tor.live.count == 2, "the code's new service, beside the paired one")
        await rig.session.cancelPairing()
        await follow(p, rig)
        #expect(tor.live == [paired], "cancelled: the new one stops")
        await rig.session.unpairPhone()
        await follow(p, rig)
        #expect(tor.live.isEmpty, "unpaired: nothing hosted")
        #expect(tor.starts == 1, "and Tor is never put to sleep on the Mac")
    }

    @Test func switchesReachTheSessionInTheOrderTheyWereMade() async throws {
        let rig = try await Rig()
        let p = paths(rig)
        p.start(linksWanted: true)
        p.setTor(false)
        p.setTor(true)
        p.setWifi(false)
        await p.settled()
        #expect(await rig.session.state.allowed == [.tor])
    }

    @Test func theCarriersRunOnlyWhileAPhoneIsPairedOrPairing() async throws {
        let p = paths(try await Rig())
        p.start(linksWanted: false)
        await settle(p)
        #expect(!log.all.contains("lan starts"), "no phone: nothing to listen for")
        p.update(linksWanted: true, services: [])
        await settle(p)
        #expect(log.all.filter { $0.hasPrefix("lan") } == ["lan starts"])
        #expect(log.all.contains("tor starts"))
        p.setWifi(false)
        await settle(p)
        #expect(log.all.filter { $0.hasPrefix("lan") } == ["lan starts", "lan stops"])
    }

    @Test func aListenerThatCantStartIsReportedAndTriedAgainLater() async throws {
        let p = paths(try await Rig(), lanFails: true)
        p.start(linksWanted: true)
        await settle(p)
        #expect(log.all.contains("lan failed"))
        p.update(linksWanted: true, services: []) // the next state update
        await settle(p)
        #expect(log.all.filter { $0 == "lan starts" }.count == 2)
    }

    @Test func stopClosesEverythingAndStartsNothingMore() async throws {
        let p = paths(try await Rig())
        p.start(linksWanted: true, services: [a])
        tor.become(.ready)
        await settle(p)
        p.stop()
        p.update(linksWanted: true, services: [a])
        p.republish()
        await settle(p)
        #expect(log.all.contains("lan stops") && log.all.contains("tor stops"))
        #expect(tor.live.isEmpty)
        #expect(log.all.filter { $0 == "lan starts" }.count == 1)
    }

    @Test func restartStartsTheListenersAgainAndRepublishes() async throws {
        let rig = try await Rig()
        let p = paths(rig)
        p.start(linksWanted: true, services: [a])
        tor.become(.ready)
        await settle(p)
        #expect(tor.hosts == 1)
        p.restart() // Reconnect with the phone out of reach
        await settle(p)
        #expect(log.all.filter { $0 == "lan starts" }.count == 2 && log.all.filter { $0 == "tor starts" }.count == 2)
        #expect(log.all.contains("lan stops") && log.all.contains("tor stops"))
        #expect(tor.live == [a] && tor.hosts == 2, "published again")
        #expect(p.wifiOn && p.torOn, "never a switch")
    }

    @Test func sr_55_06_aLateCloseNeverTakesAwayTheCarrierThatReplacedIt() async throws {
        let rig = try await Rig()
        var made: [FakeLanRunner] = []
        let p = Paths(
            session: rig.session, wifi: true, tor: false, torRunner: tor,
            makeLan: { [log] in
                let lan = FakeLanRunner(log)
                made.append(lan)
                return lan
            },
            makeTorLan: { [log] in FakeLanRunner(log, name: "tor") }
        )
        p.start(linksWanted: true)
        await settle(p)
        for _ in 0..<5 { p.restart() } // each one's "closed" races the next one's hand-over
        await settle(p)
        let link = await rig.session.links[.lan]
        #expect(made.count == 6)
        #expect((link as? FakeLanRunner) === made.last, "the newest listener is the session's, not nil")
    }

    @Test func sr_31_03_aListenerClosedWhileStartingIsNeverHandedOver() async throws {
        let rig = try await Rig()
        var made: [FakeLanRunner] = []
        let p = Paths(
            session: rig.session, wifi: true, tor: true, torRunner: tor,
            makeLan: { [log] in
                let lan = FakeLanRunner(log)
                lan.slow = made.isEmpty // only the first one is slow to start
                made.append(lan)
                return lan
            },
            makeTorLan: { [log] in FakeLanRunner(log, name: "tor") }
        )
        p.start(linksWanted: true)
        await settle(p)
        p.setWifi(false) // closed while the first is still starting
        p.setWifi(true) // a new one starts and is handed over
        await settle(p)
        made[0].gate?.resume() // the first finally finishes starting
        await settle(p)
        #expect(log.all.filter { $0 == "lan stops" }.count == 2, "the stale one is stopped, not handed over")
        #expect(await rig.session.links[.lan] as AnyObject? === made[1], "the session keeps the newer listener")
    }
}
