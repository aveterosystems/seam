import Foundation
import SeamCore
import Testing

/// Calls the Rust core through the generated Swift bindings, the way the Mac app will.
struct SeamCoreTests {
    private func paired() throws -> (mac: SeamDevice, phone: SeamDevice) {
        let mac = try SeamDevice(deviceId: "mac")
        let phone = try SeamDevice(deviceId: "phone")
        try mac.pinPeer(peer: "phone", identityKey: phone.identityKey())
        try phone.pinPeer(peer: "mac", identityKey: mac.identityKey())
        return (mac, phone)
    }

    @Test func messagesFlowBothWays() throws {
        let (mac, phone) = try paired()
        try mac.startSession(peer: "phone", bundle: phone.prekeyBundle())

        let sealed = try mac.encrypt(peer: "phone", plaintext: Data("from Swift".utf8))
        #expect(sealed.kind == 3) // PreKeySignalMessage: carries the session setup
        #expect(try phone.decrypt(peer: "mac", sealed: sealed) == Data("from Swift".utf8))

        let reply = try phone.encrypt(peer: "mac", plaintext: Data("back".utf8))
        #expect(try mac.decrypt(peer: "phone", sealed: reply) == Data("back".utf8))
    }

    @Test func impostorIsRefusedWithTheRightError() throws {
        let (mac, _) = try paired()
        let impostor = try SeamDevice(deviceId: "phone")
        #expect(throws: SeamError.UntrustedIdentity) {
            try mac.startSession(peer: "phone", bundle: impostor.prekeyBundle())
        }
    }

    @Test func fileChunksRoundTripAndRejectReordering() throws {
        let key = newFileKey()
        let id = newFileId()
        let sealed = try sealChunk(key: key, fileId: id, index: 0, last: false, plain: Data("chunk".utf8))
        #expect(try openChunk(key: key, fileId: id, index: 0, last: false, sealed: sealed) == Data("chunk".utf8))
        #expect(throws: SeamError.self) {
            try openChunk(key: key, fileId: id, index: 1, last: false, sealed: sealed)
        }
    }
}
