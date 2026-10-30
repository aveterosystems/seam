// SPDX-License-Identifier: AGPL-3.0-only
import SeamProtocol
import SeamSession
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
}
