// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import SeamCore
import SeamProtocol
import SeamSession

/// Where Seam keeps things on this Mac: the sealed core state (keys, peers, and the group id and
/// relay URL in its appData) in Application Support, sealed with a key from the Keychain.
struct Store: Sendable {
    /// Saved data exists but can't be opened (damaged, or its Keychain key is gone). Only a reset,
    /// which the user chooses, gets past this; Seam never overwrites it by itself.
    struct Unreadable: Error {}

    #if DEBUG
    static let allowInsecureRelay = true
    #else
    static let allowInsecureRelay = false
    #endif

    let device: SeamDevice
    /// The relay part of the core's app data as of opening (it only changes with `movingRelay`);
    /// the Hello state in it is the core's to keep.
    let appData: AppData
    private let key: Data
    private let file: StateFile

    private static var defaults: UserDefaults { .standard }

    /// The relay this Mac's group lives on. A Debug build also honours
    /// `defaults write com.avetero.seam.debug relayURL ws://<this Mac's LAN IP>:8787` (Debug builds only).
    var relayURL: URL {
        RelayPolicy.resolve(
            Self.allowInsecureRelay ? [Self.defaults.string(forKey: "relayURL"), appData.relayUrl] : [appData.relayUrl],
            allowInsecure: Self.allowInsecureRelay
        )
    }

    /// A relay the user chose (Settings › Connection), rather than the build's own.
    var usesOwnRelay: Bool {
        appData.relayUrl != RelayPolicy.forNewGroup(override: Self.defaults.string(forKey: "relayURL"), allowInsecure: Self.allowInsecureRelay).absoluteString
    }

    /// Moves this Mac to another relay (nil: the build's own) with a new group there.
    /// A custom relay comes only through this explicit choice (decision 0007). Any phone pairs again.
    func movingRelay(to link: RelayLink?) throws -> Store {
        let url = link?.url ?? RelayPolicy.forNewGroup(override: Self.defaults.string(forKey: "relayURL"), allowInsecure: Self.allowInsecureRelay)
        let appData = Self.appData(relayURL: url.absoluteString, ownerKey: link?.ownerKey)
        let previous = device.appData()
        device.setAppData(data: appData)
        let store = Store(device: device, appData: appData, key: key, file: file)
        do {
            try store.save()
        } catch {
            if let previous { device.setAppData(data: previous) } // stay on the old relay rather than half-move
            throw error
        }
        store.needsBundles = true
        return store
    }

    /// True when the relay should get fresh prekey bundles (new group, or one used by pairing).
    var needsBundles: Bool {
        get { Self.defaults.bool(forKey: "needsBundles") }
        nonmutating set { Self.defaults.set(newValue, forKey: "needsBundles") }
    }

    /// Restores the saved device, or creates one on first launch. Throws `Unreadable` when saved
    /// data exists but can't be opened, and other errors for anything that may be temporary.
    static func open() throws -> Store {
        let file = try stateFile()
        guard let blob = try file.read() else { return try create(at: file) }
        guard let key = try Keychain.storageKey(create: false) else { throw Unreadable() }
        let device: SeamDevice
        do {
            device = try SeamDevice.restore(state: blob, storageKey: key)
        } catch SeamError.BadState {
            throw Unreadable()
        }
        if var appData = device.appData() {
            try migrateHelloState(&appData, device: device, key: key, file: file)
            return Store(device: device, appData: appData, key: key, file: file)
        }
        // State from before appData: move the group id and relay out of UserDefaults, once. App
        // data the core can't read (a newer Seam's, or damaged) looks the same, so without that
        // group id it's unreadable: never replaced by a new group on Seam's relay, which would
        // silently move a private-relay user to ours
        guard let old = defaults.data(forKey: "groupID"), old.count == 16 else { throw Unreadable() }
        var appData = Self.appData(
            relayURL: RelayPolicy.forNewGroup(override: defaults.string(forKey: "relayURL"), allowInsecure: allowInsecureRelay).absoluteString
        )
        appData.groupId = old
        device.setAppData(data: appData)
        let store = Store(device: device, appData: appData, key: key, file: file)
        try store.save()
        defaults.removeObject(forKey: "groupID")
        return store
    }

    /// The user chose to start over: the unreadable files are kept aside, never deleted, and this
    /// Mac gets a new identity and a new group. Any paired phone has to pair again.
    static func reset() throws -> Store {
        let file = try stateFile()
        try file.moveAside()
        OutboxFile(url: file.url.deletingLastPathComponent().appendingPathComponent("outbox.bin")).delete() // sealed for the old identity
        defaults.removeObject(forKey: "groupID")
        return try create(at: file)
    }

    /// Envelopes for the phone not yet delivered, next to the state file.
    var outboxFile: OutboxFile {
        OutboxFile(url: file.url.deletingLastPathComponent().appendingPathComponent("outbox.bin"))
    }

    /// Durable before it returns. Callers must not send anything a failed save would forget (SR-2-11).
    func save() throws {
        try file.write(try device.exportState(storageKey: key))
    }

    // MARK: Private

    /// App data for a new group: 16 random bytes, and no Hello state yet.
    private static func appData(relayURL: String, ownerKey: Data? = nil) -> AppData {
        var rng = SystemRandomNumberGenerator()
        let group = Data((0..<16).map { _ in UInt8.random(in: .min ... .max, using: &rng) })
        return AppData(groupId: group, relayUrl: relayURL, ownerKey: ownerKey, helloSeqSent: 0, helloSeqApplied: 0, peerAllowed: nil)
    }

    /// UserDefaults keys builds before 0.3 kept Hello state in.
    private static let legacyKeys = ["peerTransports", "helloSeqSent", "helloSeqApplied", "peerTransportsAt"]

    /// Those builds' Hello state moves into the sealed app data once, saved before the old keys
    /// go, unless the app data already has some.
    private static func migrateHelloState(_ data: inout AppData, device: SeamDevice, key: Data, file: StateFile) throws {
        let d = defaults
        guard legacyKeys.contains(where: { d.object(forKey: $0) != nil }) else { return }
        if data.peerAllowed == nil, data.helloSeqApplied == 0, let raw = d.array(forKey: "peerTransports") as? [Int] {
            // An empty list was an older phone's: everything, as the core reads it
            data.peerAllowed = raw.isEmpty ? nil : Array(Set(raw.compactMap { $0 == 1 ? PeerPath.lan : $0 == 2 ? .relay : nil }))
            data.helloSeqSent = max(data.helloSeqSent, (d.object(forKey: "helloSeqSent") as? NSNumber)?.uint64Value ?? 0)
            data.helloSeqApplied = (d.object(forKey: "helloSeqApplied") as? NSNumber)?.uint64Value ?? 0
            device.setAppData(data: data)
            try Store(device: device, appData: data, key: key, file: file).save()
        }
        legacyKeys.forEach(d.removeObject(forKey:))
    }

    private static func create(at file: StateFile) throws -> Store {
        guard let key = try Keychain.storageKey(create: true) else { throw Unreadable() }
        let device = try SeamDevice.generate()
        let appData = Self.appData(
            relayURL: RelayPolicy.forNewGroup(override: defaults.string(forKey: "relayURL"), allowInsecure: allowInsecureRelay).absoluteString
        )
        device.setAppData(data: appData)
        let store = Store(device: device, appData: appData, key: key, file: file)
        try store.save()
        store.needsBundles = true
        return store
    }

    /// Debug builds keep their own pairing, so they run next to the installed app without touching it.
    #if DEBUG
    /// SEAM_DATA_FOLDER picks another folder, to run a second Debug Mac with its own identity (device tests).
    private static let dataFolder = ProcessInfo.processInfo.environment["SEAM_DATA_FOLDER"].flatMap { $0.contains("/") ? nil : $0 } ?? "Seam Debug"
    #else
    private static let dataFolder = "Seam"
    #endif

    private static func stateFile() throws -> StateFile {
        let dir = try FileManager.default
            .url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
            .appendingPathComponent(dataFolder, isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        return StateFile(url: dir.appendingPathComponent("state.bin"))
    }
}
