// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import SeamCore
import Testing

/// Calls the Rust core through the generated Swift bindings, the way the Mac app will.
struct SeamCoreTests {
    private let key = Data(repeating: 7, count: 32)

    /// A Mac and phone paired through the real flow: QR offer, bundle, PairRequest, the user's
    /// confirmation, then PairAccept.
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
        #expect(mac.peers().first?.confirmed == false) // nothing is trusted until the user says so
        try mac.confirmPairing(peerId: request.peerId)
        let accept = Data([0x0A, 0x02, 0x32, 0x00]) // Payload{control{pair_accept{}}}
        _ = try phone.open(envelope: try mac.seal(peerId: request.peerId, payload: accept, ttlS: 0))
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

    @Test func anUnconfirmedPhoneIsNotSaved() throws {
        let mac = try SeamDevice.generate()
        let phone = try SeamDevice.generate()
        let offer = mac.startPairing()
        let start = try phone.pairWith(
            identityKey: mac.identityKey(), relayKey: mac.relayKey(), name: "Mac",
            bundle: try mac.prekeyBundles(count: 1)[0], token: offer.token, myName: "Phone", myModel: "Model"
        )
        let request = try mac.acceptPairing(envelope: start.envelope)
        let restored = try SeamDevice.restore(state: try mac.exportState(storageKey: key), storageKey: key)
        #expect(restored.peers().isEmpty)
        mac.rejectPairing(peerId: request.peerId)
        #expect(mac.peers().isEmpty)
    }

    @Test func appDataIsSealedWithTheState() throws {
        let mac = try SeamDevice.generate()
        let secret = torNewSecret()
        let data = AppData(
            torSecret: secret, onionAddress: "", peerDiscovery: torDiscoveryPublic(discoverySecret: torNewSecret()),
            helloSeqSent: 2, helloSeqApplied: 3, peerAllowed: [.lan, .tor]
        )
        try mac.setAppData(data: data)
        let restored = try SeamDevice.restore(state: try mac.exportState(storageKey: key), storageKey: key)
        #expect(restored.appData() == data)
        #expect(torOnionAddress(serviceSecret: secret).hasSuffix(".onion"))
    }

    @Test func fileChunksRoundTripInOrderOnly() throws {
        let sealer = FileSealer()
        let size = Int(chunkDataSize())
        let first = try sealer.sealNext(data: Data(repeating: 1, count: size), last: false)
        let last = try sealer.sealNext(data: Data("end".utf8), last: true)

        let receiver = try FileReceiver(key: sealer.key(), fileId: sealer.fileId())
        #expect(throws: SeamError.self) { try receiver.openNext(sealed: last, last: true) } // out of order
        let inOrder = try FileReceiver(key: sealer.key(), fileId: sealer.fileId())
        #expect(try inOrder.openNext(sealed: first, last: false) == Data(repeating: 1, count: size))
        #expect(throws: SeamError.self) { try inOrder.finish() } // cut short
        #expect(try inOrder.openNext(sealed: last, last: true) == Data("end".utf8))
        try inOrder.finish()
    }
}
