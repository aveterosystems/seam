// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import Testing

@testable import SeamSession

struct PairingQRTests {
    private let qr = PairingQR(
        relayURL: "ws://192.168.1.20:8787",
        groupID: Data((0..<16).map { UInt8($0) }),
        token: Data((0..<32).map { UInt8(255 - $0) }),
        identityKey: Data([5] + (0..<32).map { UInt8($0) }),
        relayKey: Data(repeating: 0xFB, count: 32),
        name: "Matt's MacBook Pro + Café"
    )

    @Test func roundTrips() throws {
        #expect(try PairingQR.parse(qr.url) == qr)
    }

    @Test func usesOnlyUnreservedCharactersInValues() {
        let query = qr.url.dropFirst("seam://pair?".count)
        for pair in query.split(separator: "&") {
            let value = pair.split(separator: "=", maxSplits: 1)[1]
            #expect(!value.contains("+") && !value.contains(" ") && !value.contains("/") && !value.contains("'"))
        }
        #expect(qr.url.hasPrefix("seam://pair?v=1&r=ws%3A%2F%2F192.168.1.20%3A8787&g=AAECAwQFBgcICQoLDA0ODw&"))
    }

    @Test func rejectsWrongSchemeVersionAndLengths() {
        #expect(throws: PairingQR.ParseError.notSeamPairing) { try PairingQR.parse("https://pair?v=1") }
        #expect(throws: PairingQR.ParseError.unsupportedVersion) {
            try PairingQR.parse(qr.url.replacingOccurrences(of: "v=1", with: "v=2"))
        }
        var short = qr
        short.token = Data(count: 31)
        #expect(throws: PairingQR.ParseError.badLength("t")) { try PairingQR.parse(short.url) }
        #expect(throws: PairingQR.ParseError.missing("n")) {
            try PairingQR.parse(qr.url.components(separatedBy: "&n=")[0])
        }
    }

    @Test func base64URLHasNoPadding() {
        #expect(Data([0xFF, 0xFE]).base64URL == "__4")
        #expect(Data(base64URL: "__4") == Data([0xFF, 0xFE]))
    }
}
