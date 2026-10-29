// SPDX-License-Identifier: AGPL-3.0-only
import CryptoKit
import Foundation
import SeamCore
import SeamProtocol
import Testing

@testable import SeamSession

struct FramesTests {
    @Test func loginIsSignedOverTheProtocolsExactBytes() throws {
        let device = try SeamDevice.generate()
        let nonce = Data(repeating: 1, count: 32)
        let group = Data(repeating: 2, count: 16)
        let login = Frames.login(device: device, nonce: nonce, groupID: group).login
        #expect(login.deviceKey == device.relayKey())
        #expect(login.groupID == group)
        #expect(login.joinToken.isEmpty)
        #expect(!login.createGroup)
        #expect(Frames.login(device: device, nonce: nonce, groupID: group, createGroup: true).login.createGroup)

        // What the relay verifies: Ed25519 over "seam-relay-login-v1" || nonce || group_id
        let key = try Curve25519.Signing.PublicKey(rawRepresentation: login.deviceKey)
        #expect(key.isValidSignature(login.signature, for: Frames.loginMessage(nonce: nonce, groupID: group)))
        #expect(!key.isValidSignature(login.signature, for: Frames.loginMessage(nonce: Data(count: 32), groupID: group)))
    }

    @Test func connectURLCarriesTheGroup() {
        let url = Frames.connectURL(relay: URL(string: "ws://localhost:8787")!, groupID: Data([0xFF, 0xFE]))
        #expect(url.absoluteString == "ws://localhost:8787/v1/connect?group=__4")
    }

    @Test func framesRoundTripThroughProtobuf() throws {
        let hash = Data(repeating: 9, count: 32)
        let open = try Seam_V1_ClientFrame(serializedBytes: try Frames.openPairing(tokenHash: hash).serializedData())
        #expect(open.openPairing.tokenHash == hash)
        #expect(open.openPairing.ttlS == 300)
        #expect(Frames.delivered(Data([1, 2])).delivered.msgID == Data([1, 2]))
        #expect(Frames.putBundles([Data([1]), Data([2])]).putBundles.bundles.count == 2)
        #expect(Frames.getBundle(Data([3])).getBundle.deviceKey == Data([3]))
        let close = try Seam_V1_ClientFrame(serializedBytes: try Frames.closePairing().serializedData())
        guard case .closePairing? = close.frame else { Issue.record("not a ClosePairing frame"); return }
        #expect(Frames.removeMember(Data([4])).removeMember.deviceKey == Data([4]))
    }
}

struct BackoffTests {
    @Test func doublesFromOneSecondAndCapsAtSixty() {
        let b = Backoff()
        #expect(b.delay(attempt: 0, jitter: 0) == 0.75)
        #expect(b.delay(attempt: 1, jitter: 0) == 1.5)
        #expect(b.delay(attempt: 0, jitter: 0.999) < 1)
        #expect(b.delay(attempt: 6, jitter: 0.999) <= 60)
        #expect(b.delay(attempt: 100, jitter: 0) == 45) // capped, still jittered
    }

    @Test func onlyAStableConnectionResetsTheBackoff() {
        #expect(Backoff.nextAttempt(after: 4, connectedFor: nil) == 5) // login never succeeded
        #expect(Backoff.nextAttempt(after: 4, connectedFor: 2) == 5) // accepted, then dropped
        #expect(Backoff.nextAttempt(after: 4, connectedFor: 30) == 0)
    }
}
