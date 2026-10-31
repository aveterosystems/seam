// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
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

final class FakeRelayRunner: RelayRunner, @unchecked Sendable {
    let name: String
    let log: EventLog

    init(_ name: String, _ log: EventLog) {
        self.name = name
        self.log = log
    }

    func send(_ frame: Seam_V1_ClientFrame) async throws {}

    func run() async {
        log.add("\(name) runs")
        try? await Task.sleep(for: .seconds(3600)) // until cancelled
        log.add("\(name) ends")
    }
}

final class FakeLanRunner: LanRunner, @unchecked Sendable {
    let log: EventLog
    let fails: Bool
    /** Holds `start()` until released, like a listener still binding its port. */
    var gate: CheckedContinuation<Void, Never>?
    var slow = false
    let events: AsyncStream<LanEvent>
    private let continuation: AsyncStream<LanEvent>.Continuation

    init(_ log: EventLog, fails: Bool = false) {
        self.log = log
        self.fails = fails
        (events, continuation) = AsyncStream.makeStream()
    }

    func start() async throws -> UInt16 {
        log.add("lan starts")
        if slow { await withCheckedContinuation { gate = $0 } }
        if fails { throw CancellationError() }
        return 1
    }

    func stop() {
        log.add("lan stops")
        continuation.finish()
    }

    func send(_ frame: Seam_V1_ServerFrame, to peerId: String) -> Bool { false }
    func close(peerId: String) {}
}

@MainActor
@Suite struct PathsTests {
    let log = EventLog()

    private func paths(_ rig: Rig, wifi: Bool = true, relay: Bool = true, lanFails: Bool = false) -> Paths {
        var made = 0
        return Paths(
            session: rig.session, wifi: wifi, relay: relay,
            makeRelay: { [log] _ in
                made += 1
                return FakeRelayRunner("relay \(made)", log)
            },
            makeLan: { [log] in FakeLanRunner(log, fails: lanFails) },
            lanFailed: { [log] in log.add("lan failed") }
        )
    }

    /// Lets the tasks Paths started run to their next wait.
    private func settle(_ p: Paths) async {
        await p.settled()
        for _ in 0..<20 { await Task.yield() }
    }

    @Test func theLastSwitchCantGoOff() async throws {
        let p = paths(try await Rig(), wifi: true, relay: false)
        #expect(!p.setWifi(false))
        #expect(p.wifiOn)
        #expect(p.setRelay(true))
        #expect(p.setWifi(false))
        #expect(!p.setRelay(false))
        #expect(p.relayOn)
    }

    @Test func anOldRelayEndsBeforeTheNewOneRuns() async throws {
        let p = paths(try await Rig())
        p.start(phonePaired: true)
        await settle(p)
        p.restartRelay() // moved to another relay
        await settle(p)
        let relays = log.all.filter { $0.hasPrefix("relay") }
        #expect(relays == ["relay 1 runs", "relay 1 ends", "relay 2 runs"])
    }

    @Test func switchingTheRelayOffTellsTheSessionThenStopsIt() async throws {
        let rig = try await Rig()
        let p = paths(rig)
        p.start(phonePaired: true)
        await settle(p)
        p.setRelay(false)
        await settle(p)
        #expect(log.all.last { $0.hasPrefix("relay") } == "relay 1 ends")
        // SR-29-07: the session already knew, so nothing more is routed to the relay
        #expect(await !rig.session.state.allowed.contains(.relay))
        #expect(await rig.session.state.relayUp == false)
    }

    @Test func pairingCanWaitForASwitchStillBeingApplied() async throws {
        let rig = try await Rig()
        let p = paths(rig, wifi: true, relay: false)
        p.start(phonePaired: true)
        p.setRelay(true) // "Turn On Relay", then a pairing code straight away
        await p.settled()
        #expect(await rig.session.state.allowed.contains(.relay), "the session heard before pairing goes on")
    }

    @Test func switchesReachTheSessionInTheOrderTheyWereMade() async throws {
        let rig = try await Rig()
        let p = paths(rig)
        p.start(phonePaired: true)
        p.setRelay(false)
        p.setRelay(true)
        p.setWifi(false)
        await p.settled()
        #expect(await rig.session.state.allowed == [.relay])
    }

    @Test func theListenerRunsOnlyWhileWifiIsOnAndAPhoneIsPaired() async throws {
        let p = paths(try await Rig())
        p.start(phonePaired: false)
        await settle(p)
        #expect(!log.all.contains("lan starts"), "no phone: nothing to listen for")
        p.setPhonePaired(true)
        await settle(p)
        #expect(log.all.filter { $0.hasPrefix("lan") } == ["lan starts"])
        p.setWifi(false)
        await settle(p)
        #expect(log.all.filter { $0.hasPrefix("lan") } == ["lan starts", "lan stops"])
    }

    @Test func aListenerThatCantStartIsReportedAndTriedAgainLater() async throws {
        let p = paths(try await Rig(), lanFails: true)
        p.start(phonePaired: true)
        await settle(p)
        #expect(log.all.contains("lan failed"))
        p.setPhonePaired(true) // the next state update
        await settle(p)
        #expect(log.all.filter { $0 == "lan starts" }.count == 2)
    }

    @Test func stopClosesEverythingAndStartsNothingMore() async throws {
        let p = paths(try await Rig())
        p.start(phonePaired: true)
        await settle(p)
        p.stop()
        p.restartRelay()
        p.setPhonePaired(true)
        await settle(p)
        #expect(log.all.contains("relay 1 ends"))
        #expect(log.all.contains("lan stops"))
        #expect(!log.all.contains("relay 2 runs"))
        #expect(log.all.filter { $0 == "lan starts" }.count == 1)
    }

    @Test func sr_31_03_aListenerClosedWhileStartingIsNeverHandedOver() async throws {
        let rig = try await Rig()
        var made: [FakeLanRunner] = []
        let p = Paths(
            session: rig.session, wifi: true, relay: false,
            makeRelay: { _ in nil },
            makeLan: { [log] in
                let lan = FakeLanRunner(log)
                lan.slow = made.isEmpty // only the first one is slow to start
                made.append(lan)
                return lan
            }
        )
        p.start(phonePaired: true)
        await settle(p)
        p.setRelay(true) // so Wi-Fi may go off
        p.setWifi(false) // closed while the first is still starting
        p.setWifi(true) // a new one starts and is handed over
        await settle(p)
        made[0].gate?.resume() // the first finally finishes starting
        await settle(p)
        #expect(log.all.filter { $0 == "lan stops" }.count == 2, "the stale one is stopped, not handed over")
        #expect(await rig.session.lan as AnyObject? === made[1], "the session keeps the newer listener")
    }
}
