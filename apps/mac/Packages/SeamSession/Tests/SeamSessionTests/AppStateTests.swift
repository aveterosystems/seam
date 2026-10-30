// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import SeamCore
import Testing

@testable import SeamSession

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
        let result1 = flow.received(request) // #expect can't call a mutating method itself
        #expect(result1)
        let result2 = flow.beginConfirming() // #expect can't call a mutating method itself
        #expect(result2 == request)
        let result3 = flow.beginConfirming() // #expect can't call a mutating method itself
        #expect(result3 == nil) // a double click sends nothing twice
        let result4 = flow.cancel() // #expect can't call a mutating method itself
        #expect(result4 == nil) // and Cancel can't race the PairAccept
        flow.finish(name: "Pixel")
        #expect(flow.state == .paired(name: "Pixel"))
    }

    @Test func lateEventsCantSkipAStep() {
        var flow = PairingFlow()
        let result5 = flow.received(request) // #expect can't call a mutating method itself
        #expect(!result5) // no QR showing
        let result6 = flow.beginConfirming() // #expect can't call a mutating method itself
        #expect(result6 == nil)
        flow.start(qr: "seam://pair", now: start)
        let result7 = flow.expire() // #expect can't call a mutating method itself
        #expect(result7)
        #expect(flow.state == .expired)
        let result8 = flow.received(request) // #expect can't call a mutating method itself
        #expect(!result8) // an expired code can't be answered
    }

    @Test func cancelReturnsWhatToUndo() {
        var flow = PairingFlow()
        flow.start(qr: "seam://pair", now: start)
        flow.received(request)
        let result9 = flow.cancel() // #expect can't call a mutating method itself
        #expect(result9 == .confirm(request))
        #expect(flow.state == .idle)
        let result10 = flow.expire() // #expect can't call a mutating method itself
        #expect(!result10) // a cancelled code doesn't later show "expired"
    }
}

struct NewGroupRelayTests {
    /// SR-2-60: anything in UserDefaults can't move a Release build's group to another relay.
    @Test func releaseIgnoresTheOverride() {
        #expect(RelayPolicy.forNewGroup(override: "wss://other.example", allowInsecure: false) == RelayPolicy.productionURL)
        #expect(RelayPolicy.forNewGroup(override: nil, allowInsecure: false) == RelayPolicy.productionURL)
    }

    @Test func debugTakesIt() {
        #expect(RelayPolicy.forNewGroup(override: "ws://192.168.1.4:8787", allowInsecure: true).host == "192.168.1.4")
        #expect(RelayPolicy.forNewGroup(override: "garbage", allowInsecure: true) == RelayPolicy.developmentURL)
    }
}
