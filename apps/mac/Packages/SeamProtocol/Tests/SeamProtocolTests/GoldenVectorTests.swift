// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import SeamProtocol
import Testing

/// The same golden vector the Kotlin and server tests decode (protocol/testdata/README.md).
struct GoldenVectorTests {
    /// Walks up from this file to the repo root (the folder holding protocol/testdata).
    private func goldenVector() throws -> Data {
        var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        while dir.path != "/" {
            let file = dir.appendingPathComponent("protocol/testdata/inner_clipboard.bin")
            if FileManager.default.fileExists(atPath: file.path) { return try Data(contentsOf: file) }
            dir.deleteLastPathComponent()
        }
        throw CocoaError(.fileNoSuchFile)
    }

    @Test func decodesTheGoldenVector() throws {
        let msg = try Seam_V1_Inner(serializedBytes: goldenVector())
        #expect(msg.msgID == Data(0..<16))
        #expect(msg.sentAt == 1_793_266_000_000)
        guard case .clipboard(let clip)? = msg.payload.body else {
            Issue.record("expected a clipboard payload, got \(String(describing: msg.payload.body))")
            return
        }
        #expect(clip.text == "ZK4 9QP · café")
        #expect(clip.copiedAt == 1_793_265_999_000)
    }

    @Test func reEncodesToTheSameBytes() throws {
        let vector = try goldenVector()
        let msg = try Seam_V1_Inner(serializedBytes: vector)
        #expect(try msg.serializedData() == vector)
    }

    @Test func helloCarriesTheConnectionPolicy() throws {
        var hello = Seam_V1_Hello()
        hello.allowedTransports = [.lan]
        let back = try Seam_V1_Hello(serializedBytes: try hello.serializedData())
        #expect(back.allowedTransports == [.lan])
    }
}
