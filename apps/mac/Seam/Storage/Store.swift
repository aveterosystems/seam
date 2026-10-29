// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import SeamCore
import SeamSession

/// Where Seam keeps things on this Mac: the sealed core state (keys, peers, and the group id and
/// relay URL in its appData) in Application Support, sealed with a key from the Keychain.
struct Store {
    /// Saved data exists but can't be opened (damaged, or its Keychain key is gone). Only a reset,
    /// which the user chooses, gets past this; Seam never overwrites it by itself.
    struct Unreadable: Error {}

    #if DEBUG
    static let allowInsecureRelay = true
    #else
    static let allowInsecureRelay = false
    #endif

    let device: SeamDevice
    let appData: AppData
    private let key: Data
    private let file: StateFile

    private static var defaults: UserDefaults { .standard }

    /// The relay this Mac's group lives on. A Debug build also honours
    /// `defaults write com.avetero.seam relayURL ws://<this Mac's LAN IP>:8787`.
    var relayURL: URL {
        RelayPolicy.resolve(
            Self.allowInsecureRelay ? [Self.defaults.string(forKey: "relayURL"), appData.relayURL] : [appData.relayURL],
            allowInsecure: Self.allowInsecureRelay
        )
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
        if let appData = AppData(decoding: device.appData()) {
            return Store(device: device, appData: appData, key: key, file: file)
        }
        // State from before appData: move the group id and relay out of UserDefaults, once
        let old = defaults.data(forKey: "groupID")
        let appData = AppData(
            groupID: old?.count == 16 ? old! : AppData.newGroupID(),
            relayURL: RelayPolicy.resolve([defaults.string(forKey: "relayURL")], allowInsecure: allowInsecureRelay).absoluteString
        )
        device.setAppData(data: appData.encoded())
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
        defaults.removeObject(forKey: "groupID")
        return try create(at: file)
    }

    /// Durable before it returns. Callers must not send anything a failed save would forget (SR-2-11).
    func save() throws {
        try file.write(try device.exportState(storageKey: key))
    }

    // MARK: Private

    private static func create(at file: StateFile) throws -> Store {
        guard let key = try Keychain.storageKey(create: true) else { throw Unreadable() }
        let device = try SeamDevice.generate()
        let appData = AppData(
            groupID: AppData.newGroupID(),
            relayURL: RelayPolicy.resolve([defaults.string(forKey: "relayURL")], allowInsecure: allowInsecureRelay).absoluteString
        )
        device.setAppData(data: appData.encoded())
        let store = Store(device: device, appData: appData, key: key, file: file)
        try store.save()
        store.needsBundles = true
        return store
    }

    private static func stateFile() throws -> StateFile {
        let dir = try FileManager.default
            .url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
            .appendingPathComponent("Seam", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        return StateFile(url: dir.appendingPathComponent("state.bin"))
    }
}
