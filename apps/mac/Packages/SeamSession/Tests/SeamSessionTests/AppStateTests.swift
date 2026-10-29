// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import SeamCore
import Testing

@testable import SeamSession

struct AppDataTests {
    @Test func roundTrips() {
        let data = AppData(groupID: AppData.newGroupID(), relayURL: "wss://relay.example.com")
        #expect(data.groupID.count == 16)
        #expect(AppData(decoding: data.encoded()) == data)
    }

    @Test func refusesAnythingElse() {
        let good = AppData(groupID: Data(repeating: 1, count: 16), relayURL: "wss://r.example").encoded()
        #expect(AppData(decoding: Data()) == nil) // a state from before appData existed
        #expect(AppData(decoding: good.dropLast()) == nil)
        #expect(AppData(decoding: good + [0]) == nil)
        var v2 = good
        v2[0] = 2
        #expect(AppData(decoding: v2) == nil)
        #expect(AppData(decoding: AppData(groupID: Data([1, 2]), relayURL: "wss://r").encoded()) == nil)
    }

    @Test func survivesTheCoresSealedState() throws {
        let device = try SeamDevice.generate()
        let data = AppData(groupID: AppData.newGroupID(), relayURL: "wss://relay.example.com")
        device.setAppData(data: data.encoded())
        let key = Data(repeating: 3, count: 32)
        let restored = try SeamDevice.restore(state: try device.exportState(storageKey: key), storageKey: key)
        #expect(AppData(decoding: restored.appData()) == data)
    }
}

struct RelayPolicyTests {
    @Test func releaseOnlyTalksTLS() {
        #expect(RelayPolicy.acceptable("wss://relay.example.com", allowInsecure: false) != nil)
        #expect(RelayPolicy.acceptable("ws://localhost:8787", allowInsecure: false) == nil)
        #expect(RelayPolicy.resolve(["ws://localhost:8787"], allowInsecure: false) == RelayPolicy.productionURL)
    }

    @Test func debugAllowsALocalRelay() {
        #expect(RelayPolicy.resolve([nil], allowInsecure: true) == RelayPolicy.developmentURL)
        #expect(RelayPolicy.resolve(["ws://192.168.1.4:8787"], allowInsecure: true).host == "192.168.1.4")
    }

    @Test func oddValuesFallBackInsteadOfCrashing() {
        for bad in ["", "not a url", "https://relay.example.com", "wss://", "wss://u:p@r.example", "wss://r.example?x=1", "wss://r.example#f", String(repeating: "a", count: 5000)] {
            #expect(RelayPolicy.acceptable(bad, allowInsecure: true) == nil, "\(bad.prefix(40))")
        }
        #expect(RelayPolicy.resolve(["garbage", "wss://good.example"], allowInsecure: false).host == "good.example")
    }
}

struct PairingFlowTests {
    private let start = Date(timeIntervalSince1970: 1_000_000)
    private let request = PairingRequest(peerId: "p", deviceName: "Pixel", model: "Pixel 10", safetyCode: "12345")

    @Test func codeExpiresAfterFiveMinutes() {
        let d = PairingDeadline(startedAt: start)
        #expect(d.remaining(at: start) == 300)
        #expect(d.ttlSeconds(at: start.addingTimeInterval(120.4)) == 179) // a reconnect re-announces only what's left
        #expect(d.ttlSeconds(at: start.addingTimeInterval(301)) == 0)
    }

    @Test func pairCanOnlyBeClickedOnce() {
        var flow = PairingFlow()
        flow.start(qr: "seam://pair", now: start)
        #expect(flow.received(request))
        #expect(flow.beginConfirming() == request)
        #expect(flow.beginConfirming() == nil) // a double click sends nothing twice
        #expect(flow.cancel() == nil) // and Cancel can't race the PairAccept
        flow.finish(name: "Pixel")
        #expect(flow.state == .paired(name: "Pixel"))
    }

    @Test func lateEventsCantSkipAStep() {
        var flow = PairingFlow()
        #expect(!flow.received(request)) // no QR showing
        #expect(flow.beginConfirming() == nil)
        flow.start(qr: "seam://pair", now: start)
        #expect(flow.expire())
        #expect(flow.state == .expired)
        #expect(!flow.received(request)) // an expired code can't be answered
    }

    @Test func cancelReturnsWhatToUndo() {
        var flow = PairingFlow()
        flow.start(qr: "seam://pair", now: start)
        flow.received(request)
        #expect(flow.cancel() == .confirm(request))
        #expect(flow.state == .idle)
        #expect(!flow.expire()) // a cancelled code doesn't later show "expired"
    }
}
