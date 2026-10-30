// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import SeamProtocol
import Testing

@testable import SeamSession

struct PathsTests {
    @Test func onlyPathsAllowedAtBothEndsAreUsed() {
        let mine = Paths.mine(wifi: true, relay: true)
        #expect(Paths.allowed(mine: mine, peer: [.lan]) == [.lan])
        #expect(Paths.allowed(mine: Paths.mine(wifi: false, relay: true), peer: [.lan]).isEmpty)
        #expect(Paths.allowed(mine: mine, peer: nil) == Paths.all, "never heard from: everything")
        #expect(Paths.allowed(mine: mine, peer: []) == Paths.all, "an older phone's empty Hello")
        #expect(Paths.allowed(mine: Paths.mine(wifi: true, relay: false), peer: nil) == [.lan], "but never more than mine")
    }

    @Test func aHelloTakesAnyPathThisMacAllowsSoChangesAlwaysReachThePhone() {
        // The phone last said "Wi‑Fi only", but it's away and only the relay is up
        let mine = Paths.mine(wifi: true, relay: true)
        let shared = Paths.allowed(mine: mine, peer: [.lan])
        #expect(Paths.route(allowed: shared, lanUp: false, relayUp: true) == nil, "a payload waits")
        #expect(Paths.helloRoute(mine: mine, lanUp: false, relayUp: true) == .relay, "a Hello still goes")
        // The deadlock from real devices: each remembers the other's old single-path choice
        let macRelayOnly = Paths.mine(wifi: false, relay: true)
        #expect(Paths.allowed(mine: macRelayOnly, peer: [.lan]).isEmpty)
        #expect(Paths.helloRoute(mine: macRelayOnly, lanUp: false, relayUp: true) == .relay)
        // Never a path this Mac switched off, and the direct link first
        #expect(Paths.helloRoute(mine: macRelayOnly, lanUp: true, relayUp: false) == nil)
        #expect(Paths.helloRoute(mine: mine, lanUp: true, relayUp: true) == .lan)
        #expect(Paths.helloRoute(mine: mine, lanUp: false, relayUp: false) == nil, "waits in the outbox")
    }

    @Test func prefersTheDirectLinkAndNeverFallsBackToADisallowedPath() {
        #expect(Paths.route(allowed: Paths.all, lanUp: true, relayUp: true) == .lan)
        #expect(Paths.route(allowed: Paths.all, lanUp: false, relayUp: true) == .relay)
        #expect(Paths.route(allowed: [.lan], lanUp: false, relayUp: true) == nil, "waits instead of using the relay")
        #expect(Paths.route(allowed: [.relay], lanUp: true, relayUp: false) == nil)
    }
}

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
        box.add(env(3, sentAt: 990_000, ttl: 0)) // the relay's 24 h default
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

struct RelayLinkTests {
    private let key = String(repeating: "A", count: 43) // 32 bytes, base64url

    @Test func readsALinkWithItsOwnerKey() throws {
        let link = try #require(RelayLink.parse("seam-relay://relay.example.com#\(key)", allowInsecure: false))
        #expect(link.url == URL(string: "wss://relay.example.com"))
        #expect(link.ownerKey == Data(repeating: 0, count: 32))
        let port = try #require(RelayLink.parse("  seam-relay://relay.example.com:8443/seam#\(key)\n", allowInsecure: false))
        #expect(port.url.absoluteString == "wss://relay.example.com:8443/seam")
    }

    @Test func takesAPlainRelayAddress() {
        #expect(RelayLink.parse("wss://relay.example.com", allowInsecure: false) == RelayLink(url: URL(string: "wss://relay.example.com")!, ownerKey: nil))
        #expect(RelayLink.parse("seam-relay://relay.example.com", allowInsecure: false)?.ownerKey == nil)
        #expect(RelayLink.parse("ws://192.168.1.2:8787", allowInsecure: true) != nil, "Debug builds only")
    }

    @Test func refusesAnythingElse() {
        for bad in [
            "ws://relay.example.com", // plain ws in Release
            "https://relay.example.com",
            "seam-relay://#\(key)", // no host
            "seam-relay://user:pw@relay.example.com#\(key)",
            "seam-relay://relay.example.com?x=1#\(key)",
            "seam-relay://relay.example.com#short", // a key too short to be secret
            "seam-relay://relay.example.com#\(key)!", // not base64url
            "seam-relay://relay.example.com#",
            "seam-relay://relay.example.com#\(String(repeating: "A", count: 700))", // too long a key
            "wss://relay.example.com#\(key)", // a key only in a seam-relay link
            "",
            "seam-relay://" + String(repeating: "a", count: 3000),
        ] {
            #expect(RelayLink.parse(bad, allowInsecure: false) == nil, "\(bad.prefix(60))")
        }
    }
}

struct AppDataOwnerKeyTests {
    @Test func roundTripsAndStaysV1WithoutAKey() throws {
        let g = Data(repeating: 1, count: 16)
        let plain = AppData(groupID: g, relayURL: "wss://relay.avetero.com")
        #expect(plain.encoded().first == 1, "older versions can still open the usual case")
        #expect(AppData(decoding: plain.encoded()) == plain)
        let owned = AppData(groupID: g, relayURL: "wss://relay.example.com", ownerKey: Data(repeating: 9, count: 32))
        #expect(owned.encoded().first == 2)
        #expect(AppData(decoding: owned.encoded()) == owned)
        #expect(AppData(decoding: owned.encoded().dropLast()) == nil, "truncated")
        #expect(AppData(decoding: owned.encoded() + Data([0])) == nil, "trailing bytes")
    }
}
