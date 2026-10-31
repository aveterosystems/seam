// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import SeamProtocol
import Testing

@testable import SeamSession

struct OutboxTests {
    private func env(_ id: UInt8, sentAt: Int64 = 999_000, ttl: UInt32 = 600) -> Seam_V1_Envelope {
        .with { $0.msgID = Data([id]); $0.sentAt = sentAt; $0.ttlS = ttl; $0.ciphertext = Data(repeating: id, count: 32) }
    }
    private let now = Date(timeIntervalSince1970: 1000)

    @Test func tracksWaitingAndSentAndRequeuesWhatWasntAcked() {
        var box = Outbox()
        box.add(env(1))
        box.add(env(2))
        box.add(env(1)) // a retry: not twice
        #expect(box.waiting.map(\.msgID) == [Data([1]), Data([2])])
        box.markSent(Data([1]))
        #expect(box.sent.map(\.msgID) == [Data([1])] && box.waiting.map(\.msgID) == [Data([2])])
        box.add(env(1)) // already sent, waiting for its ack: not queued again
        #expect(box.waiting.count == 1)
        box.requeueSent()
        #expect(box.waiting.map(\.msgID) == [Data([1]), Data([2])] && box.sent.isEmpty, "unacked goes first")
        box.remove(Data([1]))
        box.remove(Data([2]))
        #expect(box.isEmpty)
    }

    @Test func capsAndDropsWhatExpired() {
        var box = Outbox()
        box.add(env(1, sentAt: 990_000, ttl: 5)) // expired at 995 s
        box.add(env(2, sentAt: 990_000, ttl: 600))
        box.add(env(3, sentAt: 990_000, ttl: 0)) // the default 24 h
        let dropped = box.dropExpired(now: now)
        #expect(dropped)
        #expect(box.waiting.map(\.msgID) == [Data([2]), Data([3])])
        let nothing = box.dropExpired(now: now)
        #expect(!nothing)
        for i in 0..<(Outbox.cap + 5) { box.add(env(UInt8(10 + i % 200))) }
        #expect(box.waiting.count == Outbox.cap)
    }
}

struct OutboxFileTests {
    private func file() -> OutboxFile {
        OutboxFile(url: FileManager.default.temporaryDirectory.appendingPathComponent("outbox-\(UUID()).bin"))
    }
    private func env(_ id: UInt8, sentAt: Int64 = 999_000, ttl: UInt32 = 600) -> Seam_V1_Envelope {
        .with { $0.msgID = Data([id]); $0.sentAt = sentAt; $0.ttlS = ttl; $0.ciphertext = Data(repeating: id, count: 1000) }
    }
    private let now = Date(timeIntervalSince1970: 1000)

    @Test func roundTripsPrivately() throws {
        let f = file()
        defer { f.delete() }
        var box = Outbox()
        box.add(env(1))
        box.add(env(2))
        box.markSent(Data([2]))
        try f.save(box)
        #expect(f.load(now: now) == box)
        let mode = try FileManager.default.attributesOfItem(atPath: f.file.url.path)[.posixPermissions] as? Int
        #expect(mode == 0o600)
    }

    @Test func dropsExpiredOnLoad() throws {
        let f = file()
        defer { f.delete() }
        var box = Outbox()
        box.add(env(1, sentAt: 990_000, ttl: 5))
        box.add(env(2))
        box.markSent(Data([1]))
        try f.save(box)
        let loaded = f.load(now: now)
        #expect(loaded.waiting.map(\.msgID) == [Data([2])] && loaded.sent.isEmpty)
    }

    @Test func aMissingOrDamagedFileIsEmptyNeverACrash() throws {
        let f = file()
        defer { f.delete() }
        #expect(f.load(now: now).isEmpty, "missing")
        var box = Outbox()
        box.add(env(1))
        let good = try box.encoded()
        let magic = Data("SOB1".utf8)
        let noise = Data((0..<5000).map { _ in UInt8.random(in: 0...255) })
        let bads: [Data] = [
            Data(), Data("SOB2".utf8) + good.dropFirst(4), good.dropLast(), good + Data([0]),
            magic + Data([0, 0, 0, 200]), magic + Data([0, 0, 0, 1, 0xFF, 0xFF, 0xFF, 0xFF]), noise,
        ]
        for bad in bads {
            try f.file.write(bad)
            #expect(f.load(now: now).isEmpty)
        }
    }

    @Test func deletingClearsIt() throws {
        let f = file()
        var box = Outbox()
        box.add(env(1))
        try f.save(box)
        f.delete()
        #expect(f.load(now: now).isEmpty)
        #expect(!FileManager.default.fileExists(atPath: f.file.url.path))
    }
}

