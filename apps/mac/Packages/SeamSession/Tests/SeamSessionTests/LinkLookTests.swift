// SPDX-License-Identifier: AGPL-3.0-only
import SeamCore
import SeamProtocol
import SeamSession
import Foundation
import Testing

@Suite struct LinkLookTests {
    @Test func eachPathHasItsOwnLineIconAndWords() {
        #expect(LinkLook.of(route: .lan, allowed: [.lan, .tor]) == .sameWifi)
        #expect(LinkLook.sameWifi.line == .solid && LinkLook.sameWifi.symbol == "wifi")
        #expect(LinkLook.of(route: .tor, allowed: [.tor]) == .throughTor)
        #expect(LinkLook.throughTor.line == .dashed && LinkLook.throughTor.symbol == "globe")
        #expect(LinkLook.throughTor.sentence == "Connected · Through Tor")
    }

    @Test func notConnectedIsBrokenAndSaysWhy() {
        #expect(LinkLook.of(route: nil, allowed: []) == .noSharedPath)
        #expect(LinkLook.of(route: nil, allowed: [.lan]) == .waitingOnWifi)
        #expect(LinkLook.of(route: nil, allowed: [.lan, .tor]) == .away)
        for look in [LinkLook.noSharedPath, .waitingOnWifi, .away, .torBlocked, .torClock] {
            #expect(look.line == .broken && look.symbol == nil && !look.connected)
            #expect(look.sentence == look.label)
        }
        #expect(LinkLook.sameWifi.sentence == "Connected · Same Wi‑Fi")
    }

    // Every reason the phone isn't there can be clicked for why and what happens next
    @Test func notConnectedExplainsItselfConnectedDoesNot() {
        for look in [LinkLook.noSharedPath, .waitingOnWifi, .away, .torBlocked, .torClock, .paused(until: .distantFuture, now: Date())] {
            #expect(look.detail?.isEmpty == false, "\(look.label)")
        }
        #expect(LinkLook.sameWifi.detail == nil && LinkLook.throughTor.detail == nil)
    }

    // Never a silent wait. Tor's own trouble is said, not shown as "offline"
    @Test func torsStateSaysWhyThePhoneCantComeThroughTor() {
        let tor: [PeerPath] = [.lan, .tor]
        #expect(LinkLook.of(route: nil, allowed: tor, tor: .starting(percent: 40)) == .connectingTor)
        #expect(LinkLook.of(route: nil, allowed: tor, tor: .off) == .connectingTor)
        #expect(LinkLook.of(route: nil, allowed: tor, tor: .blocked(reason: .filtered)).label == "Tor is blocked on this network")
        #expect(LinkLook.of(route: nil, allowed: tor, tor: .blocked(reason: .clockSkewed)).label
            == "Tor needs the correct time. Check your clock")
        #expect(LinkLook.of(route: nil, allowed: tor, tor: .ready) == .away)
        #expect(LinkLook.of(route: nil, allowed: [.lan], tor: .blocked(reason: .filtered)) == .waitingOnWifi, "Tor is off: not its problem")
        #expect(LinkLook.of(route: .lan, allowed: tor, tor: .blocked(reason: .filtered)) == .sameWifi, "Same Wi‑Fi still works")
    }

    @Test func aPauseOnThePhoneComesFirstAndSaysUntilWhen() {
        let now = Date(timeIntervalSince1970: 1_793_455_000)
        let paused = LinkLook.of(route: .lan, allowed: [.lan], pausedUntil: now.addingTimeInterval(3600), now: now)
        #expect(paused.line == .broken && !paused.connected && paused.symbol == "pause.circle")
        #expect(paused.label.hasPrefix("Paused on your phone until "))
        #expect(LinkLook.of(route: nil, allowed: [.lan], pausedUntil: .distantFuture, now: now).label
            == "Paused on your phone until you resume it there")
        #expect(LinkLook.of(route: .lan, allowed: [.lan], pausedUntil: now, now: now) == .sameWifi, "over: back to normal")
    }
}
