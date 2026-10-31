// SPDX-License-Identifier: AGPL-3.0-only
import SeamProtocol
import SeamSession
import Foundation
import Testing

@Suite struct LinkLookTests {
    @Test func eachPathHasItsOwnLineIconAndWords() {
        #expect(LinkLook.of(route: .lan, allowed: [.lan, .relay], ownRelay: false) == .sameWifi)
        #expect(LinkLook.sameWifi.line == .solid && LinkLook.sameWifi.symbol == "wifi")
        #expect(LinkLook.of(route: .relay, allowed: [.relay], ownRelay: false) == .seamRelay)
        #expect(LinkLook.of(route: .relay, allowed: [.relay], ownRelay: true) == .ownRelay)
        #expect(LinkLook.seamRelay.line == .dashed)
    }

    @Test func notConnectedIsBrokenAndSaysWhy() {
        #expect(LinkLook.of(route: nil, allowed: [], ownRelay: false) == .noSharedPath)
        #expect(LinkLook.of(route: nil, allowed: [.lan], ownRelay: false) == .waitingOnWifi)
        #expect(LinkLook.of(route: nil, allowed: [.lan, .relay], ownRelay: false) == .away)
        for look in [LinkLook.noSharedPath, .waitingOnWifi, .away] {
            #expect(look.line == .broken && look.symbol == nil && !look.connected)
            #expect(look.sentence == look.label)
        }
        #expect(LinkLook.sameWifi.sentence == "Connected · Same Wi‑Fi")
    }

    @Test func aPauseOnThePhoneComesFirstAndSaysUntilWhen() {
        let now = Date(timeIntervalSince1970: 1_793_455_000)
        let paused = LinkLook.of(route: .lan, allowed: [.lan], ownRelay: false, pausedUntil: now.addingTimeInterval(3600), now: now)
        #expect(paused.line == .broken && !paused.connected && paused.symbol == "pause.circle")
        #expect(paused.label.hasPrefix("Paused on your phone until "))
        #expect(LinkLook.of(route: nil, allowed: [.lan], ownRelay: false, pausedUntil: .distantFuture, now: now).label
            == "Paused on your phone until you resume it there")
        #expect(LinkLook.of(route: .lan, allowed: [.lan], ownRelay: false, pausedUntil: now, now: now) == .sameWifi, "over: back to normal")
    }
}
