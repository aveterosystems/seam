// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import SeamCore
import Testing

/// Calls the Rust core through the generated Swift bindings, the way the Mac app will.
struct SeamCoreTests {
    private let key = Data(repeating: 7, count: 32)

    /// A Mac and phone paired through the real flow: QR offer, bundle, PairRequest, accept.
    private func paired() throws -> (mac: SeamDevice, phone: SeamDevice) {
        let mac = try SeamDevice.generate()
        let phone = try SeamDevice.generate()
        let offer = mac.startPairing()
        let start = try phone.pairWith(
            identityKey: mac.identityKey(), relayKey: mac.relayKey(), name: "Mac",
            bundle: try mac.prekeyBundles(count: 1)[0], token: offer.token,
            myName: "Galaxy S25 Ultra", myModel: "SM-S938B"
        )
        let request = try mac.acceptPairing(envelope: start.envelope)
        #expect(request.peerId == phone.id())
        #expect(try request.safetyCode == phone.safetyCode(peerId: mac.id()))
        return (mac, phone)
    }

    @Test func pairsThenTalksAfterARestart() throws {
        let (mac0, phone0) = try paired()
        let mac = try SeamDevice.restore(state: try mac0.exportState(storageKey: key), storageKey: key)
        let phone = try SeamDevice.restore(state: try phone0.exportState(storageKey: key), storageKey: key)

        let status = Data([0x92, 0x03, 0x02, 0x08, 82]) // Payload{status{battery_pct: 82}}
        let opened = try mac.open(envelope: try phone.seal(peerId: mac.id(), payload: status, ttlS: 0))
        #expect(opened.payload == status)
        #expect(opened.peerId == phone.id())
        #expect(mac.peers().first?.name == "Galaxy S25 Ultra")
    }

    @Test func wrongPairingCodeIsRefused() throws {
        let mac = try SeamDevice.generate()
        let phone = try SeamDevice.generate()
        _ = mac.startPairing()
        let start = try phone.pairWith(
            identityKey: mac.identityKey(), relayKey: mac.relayKey(), name: "Mac",
            bundle: try mac.prekeyBundles(count: 1)[0], token: Data(count: 32),
            myName: "Phone", myModel: "Model"
        )
        #expect(throws: SeamError.self) { try mac.acceptPairing(envelope: start.envelope) }
    }

    @Test func badStateIsRefused() throws {
        #expect(throws: SeamError.BadState) { try SeamDevice.restore(state: Data([1, 2, 3]), storageKey: key) }
    }

    @Test func fileChunksRoundTripAndRejectReordering() throws {
        let k = newFileKey()
        let id = newFileId()
        let sealed = try sealChunk(key: k, fileId: id, index: 0, last: false, plain: Data("chunk".utf8))
        #expect(try openChunk(key: k, fileId: id, index: 0, last: false, sealed: sealed) == Data("chunk".utf8))
        #expect(throws: SeamError.self) { try openChunk(key: k, fileId: id, index: 1, last: false, sealed: sealed) }
    }
}
