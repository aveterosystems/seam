// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import SeamCore
import Testing

@testable import SeamSession

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

