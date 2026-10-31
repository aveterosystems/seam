// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import SeamCore
import SeamProtocol
import Testing

@testable import SeamSession

/// A clock that only moves when the test says, running what was due on the way, in order.
final class FakeTimers: Timers, @unchecked Sendable {
    private struct Job {
        let id: Int
        let at: Date
        let work: @Sendable () async -> Void
    }

    private let lock = NSLock()
    private var clock: Date
    private var jobs: [Job] = []
    private var lastID = 0

    /// Starts at the real time: the core stamps envelopes with it.
    init(now: Date = Date()) {
        clock = now
    }

    func now() -> Date { lock.withLock { clock } }

    func after(_ seconds: TimeInterval, _ work: @escaping @Sendable () async -> Void) -> TimerToken {
        let id = lock.withLock {
            lastID += 1
            jobs.append(Job(id: lastID, at: clock.addingTimeInterval(seconds), work: work))
            return lastID
        }
        return TimerToken { [weak self] in self?.lock.withLock { self?.jobs.removeAll { $0.id == id } } }
    }

    func advance(_ seconds: TimeInterval) async {
        let target = now().addingTimeInterval(seconds)
        while let job = lock.withLock({ () -> Job? in
            guard let next = jobs.filter({ $0.at <= target }).min(by: { $0.at < $1.at }) else { return nil }
            jobs.removeAll { $0.id == next.id }
            clock = max(clock, next.at)
            return next
        }) {
            await job.work()
        }
        lock.withLock { clock = target }
    }
}

final class FakeRelay: RelaySender, @unchecked Sendable {
    private let lock = NSLock()
    private var _frames: [Seam_V1_ClientFrame] = []
    private var _failing = false
    private var _attempts = 0

    var frames: [Seam_V1_ClientFrame] { lock.withLock { _frames } }
    /// Sends tried, taken or not.
    var attempts: Int { lock.withLock { _attempts } }
    var failing: Bool {
        get { lock.withLock { _failing } }
        set { lock.withLock { _failing = newValue } }
    }

    func send(_ frame: Seam_V1_ClientFrame) async throws {
        try lock.withLock {
            _attempts += 1
            if _failing { throw RelayClientError.notConnected }
            _frames.append(frame)
        }
    }
}

final class FakeLan: LanSender, @unchecked Sendable {
    private let lock = NSLock()
    private var _frames: [Seam_V1_ServerFrame] = []
    private var _closed: [String] = []

    var frames: [Seam_V1_ServerFrame] { lock.withLock { _frames } }
    var closed: [String] { lock.withLock { _closed } }

    func send(_ frame: Seam_V1_ServerFrame, to peerId: String) -> Bool {
        lock.withLock { _frames.append(frame) }
        return true
    }

    func close(peerId: String) {
        lock.withLock { _closed.append(peerId) }
    }
}

final class MemoryPasteboard: Pasteboard, @unchecked Sendable {
    private let lock = NSLock()
    private var count = 0
    private var item: PasteboardItem?
    private var _whileReading: (@Sendable () async -> Void)?

    var text: String? { lock.withLock { item?.text } }
    /// Runs while the next read is in progress, once: something the user does mid-read.
    var whileReading: (@Sendable () async -> Void)? {
        get { lock.withLock { _whileReading } }
        set { lock.withLock { _whileReading = newValue } }
    }

    /// Someone copied something on this Mac.
    func copy(_ text: String, types: [String] = ["public.utf8-plain-text"]) {
        lock.withLock {
            count += 1
            item = PasteboardItem(types: types, text: text)
        }
    }

    func changeCount() async -> Int { lock.withLock { count } }

    func firstItem(ifStill change: Int) async -> PasteboardItem? {
        if let during = lock.withLock({ () -> (@Sendable () async -> Void)? in defer { _whileReading = nil }; return _whileReading }) {
            await during()
        }
        return lock.withLock { count == change ? item : nil }
    }

    func write(_ text: String) async -> Int {
        lock.withLock {
            count += 1
            item = PasteboardItem(types: ["public.utf8-plain-text"], text: text)
            return count
        }
    }
}

final class MemoryNotifications: NotificationCentre, @unchecked Sendable {
    private let lock = NSLock()
    private var _shown: [(Seam_V1_Notification, Data?)] = []
    private var _dismissed: [String] = []

    var shown: [(notification: Seam_V1_Notification, icon: Data?)] { lock.withLock { _shown } }
    var dismissed: [String] { lock.withLock { _dismissed } }

    func show(_ notification: Seam_V1_Notification, icon: Data?) async {
        lock.withLock { _shown.append((notification, icon)) }
    }

    func dismiss(key: String) async {
        lock.withLock { _dismissed.append(key) }
    }

    func removeAll() async {
        lock.withLock { _shown = [] }
    }
}

/// The core state as last saved, in memory; a save can be made to fail.
final class SavedState: @unchecked Sendable {
    static let key = Data(repeating: 7, count: 32)
    private let lock = NSLock()
    private var _blob: Data?
    private var _failing = false
    private var _needsBundles = false
    private var _pausedUntil: Date?

    var blob: Data? { lock.withLock { _blob } }
    var failing: Bool {
        get { lock.withLock { _failing } }
        set { lock.withLock { _failing = newValue } }
    }
    var pausedUntil: Date? {
        get { lock.withLock { _pausedUntil } }
        set { lock.withLock { _pausedUntil = newValue } }
    }
    var needsBundles: Bool {
        get { lock.withLock { _needsBundles } }
        set { lock.withLock { _needsBundles = newValue } }
    }

    func save(_ device: SeamDevice) throws {
        if failing { throw CocoaError(.fileWriteOutOfSpace) }
        let blob = try device.exportState(storageKey: Self.key)
        lock.withLock { _blob = blob }
    }
}

/// A Mac session with fake paths and system adapters, and a real phone (a second core device)
/// to open what the Mac sends and seal what it receives.
final class Rig: @unchecked Sendable {
    let mac: SeamDevice
    let phone: SeamDevice
    let lan = FakeLan()
    let relay = FakeRelay()
    let pasteboard = MemoryPasteboard()
    let centre = MemoryNotifications()
    let timers: FakeTimers
    let saved: SavedState
    let outbox: OutboxFile
    private(set) var session: Session
    private var opened: [Data: Seam_V1_Payload] = [:]

    /// `paired` false: the phone is made but not paired yet (pairing tests pair it).
    init(paired: Bool = true, wifi: Bool = true, relay: Bool = true, clipboardSync: Bool = true) async throws {
        if paired {
            (mac, phone) = try pairedDevices()
        } else {
            (mac, phone) = (try SeamDevice.generate(), try SeamDevice.generate())
        }
        for device in [mac, phone] {
            try device.setAppData(data: AppData(
                groupId: Data(repeating: 1, count: 16), relayUrl: "wss://relay.example.com", ownerKey: nil,
                helloSeqSent: 0, helloSeqApplied: 0, peerAllowed: nil
            ))
        }
        timers = FakeTimers()
        saved = SavedState()
        outbox = OutboxFile(url: FileManager.default.temporaryDirectory.appendingPathComponent("outbox-\(UUID()).bin"))
        session = Self.session(mac, saved, outbox, pasteboard, centre, timers, wifi: wifi, relay: relay, clipboardSync: clipboardSync)
        try saved.save(mac)
        await session.start()
    }

    private static func session(
        _ device: SeamDevice, _ saved: SavedState, _ outbox: OutboxFile, _ pasteboard: MemoryPasteboard,
        _ centre: MemoryNotifications, _ timers: FakeTimers, wifi: Bool, relay: Bool, clipboardSync: Bool
    ) -> Session {
        let storage = SessionStorage(
            saveState: { try saved.save(device) }, outbox: outbox,
            needsBundles: { saved.needsBundles }, setNeedsBundles: { saved.needsBundles = $0 },
            pausedUntil: { saved.pausedUntil }, setPausedUntil: { saved.pausedUntil = $0 }
        )
        return Session(
            device: device, storage: storage, pasteboard: pasteboard, notifications: centre,
            wifi: wifi, relay: relay, clipboardSync: clipboardSync, appVersion: "test", timers: timers
        )
    }

    /// Quit and relaunch: the Mac comes back from what it saved, with a new session.
    func restart() async throws -> Session {
        await session.stop()
        let restored = try SeamDevice.restore(state: try #require(saved.blob), storageKey: SavedState.key)
        session = Self.session(restored, saved, outbox, pasteboard, centre, timers, wifi: true, relay: true, clipboardSync: true)
        await session.start()
        return session
    }

    var phoneID: String { phone.id() }

    func relayUp() async {
        await session.setRelay(relay)
        await session.relayConnected()
    }

    func lanUp() async {
        await session.setLan(lan)
        await session.lanEvent(.authenticated(peerId: phoneID))
    }

    /// Something for the phone that isn't a Hello, told apart by `n`.
    func sendSomething(_ n: Int64) async {
        await session.changeNotificationSettings(.with { $0.pausedUntil = n })
    }

    /// The phone seals `payload` for the Mac.
    func fromPhone(_ payload: Seam_V1_Payload) throws -> Seam_V1_Envelope {
        try Seam_V1_Envelope(serializedBytes: try phone.seal(peerId: mac.id(), payload: payload.serializedData(), ttlS: 0))
    }

    /// The phone's Hello, as its switches say.
    func phoneHello(wifi: Bool, relay: Bool, pausedUntilMs: Int64 = 0) throws -> Seam_V1_Envelope {
        let next = try #require(phone.nextHello(enabled: (wifi ? [.lan] : []) + (relay ? [.relay] : [])))
        return try fromPhone(.with {
            $0.control = .with {
                $0.hello = .with {
                    $0.pausedUntilMs = pausedUntilMs
                    $0.seq = next.seq
                    $0.allowedTransports = next.allowedTransports.map { Seam_V1_Transport(rawValue: Int($0))! }
                }
            }
        })
    }

    /// What reached the phone over the relay, opened by the phone, in order.
    func onRelay() throws -> [Seam_V1_Payload] {
        try relay.frames.compactMap { if case .send(let e)? = $0.frame { e } else { nil } }.map(open)
    }

    func onLan() throws -> [Seam_V1_Payload] {
        try lan.frames.compactMap { if case .deliver(let e)? = $0.frame { e } else { nil } }.map(open)
    }

    /// The same envelope can go twice (requeued after a drop); the phone opens it once.
    private func open(_ envelope: Seam_V1_Envelope) throws -> Seam_V1_Payload {
        if let done = opened[envelope.msgID] { return done }
        let payload = try Seam_V1_Payload(serializedBytes: try phone.open(envelope: envelope.serializedData()).payload)
        opened[envelope.msgID] = payload
        return payload
    }
}

extension Seam_V1_Payload {
    var isHello: Bool {
        if case .control(let c)? = body, case .hello? = c.body { true } else { false }
    }
}

extension Array where Element == Seam_V1_Payload {
    /// Without Hellos, which go whenever a path comes up.
    var messages: [Seam_V1_Payload] { filter { !$0.isHello } }
    /// The `sendSomething` numbers, in order.
    var numbers: [Int64] {
        compactMap { if case .notificationSettingsChange(let c)? = $0.body { c.pausedUntil } else { nil } }
    }
}
