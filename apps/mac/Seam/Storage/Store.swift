// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import SeamCore
import SeamProtocol
import SeamSession

/// Where Seam keeps things on this Mac: the sealed core state (keys, peers, and the onion service
/// secret in its app data) in Application Support, sealed with a key from the Keychain, and Tor's
/// own state next to it.
struct Store: Sendable {
    /// Saved data exists but can't be opened (damaged, or its Keychain key is gone). Only a reset,
    /// which the user chooses, gets past this; Seam never overwrites it by itself.
    struct Unreadable: Error {}

    let device: SeamDevice
    /// A pairing from the relay builds was dropped on opening: the phone has to pair again.
    let pairAgain: Bool
    private let key: Data
    private let file: StateFile

    /// The app's private folder: Tor keeps its state in `tor/` inside it.
    var folder: URL { file.url.deletingLastPathComponent() }

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
        // A phone paired through the relay has app data the core no longer reads (decision 0009):
        // there's no way to reach it over Tor, so it's forgotten and pairs again. The identity stays
        guard device.appData() == nil, !device.peers().isEmpty else {
            return Store(device: device, pairAgain: false, key: key, file: file)
        }
        for peer in device.peers() { device.removePeer(peerId: peer.id) }
        device.clearAppData()
        let store = Store(device: device, pairAgain: true, key: key, file: file)
        try store.save()
        store.outboxFile.delete() // sealed for the forgotten phone
        return store
    }

    /// The user chose to start over: the unreadable files are kept aside, never deleted, and this
    /// Mac gets a new identity. Any paired phone has to pair again.
    static func reset() throws -> Store {
        let file = try stateFile()
        try file.moveAside()
        OutboxFile(url: file.url.deletingLastPathComponent().appendingPathComponent("outbox.bin")).delete() // sealed for the old identity
        return try create(at: file)
    }

    /// Envelopes for the phone not yet delivered, next to the state file.
    var outboxFile: OutboxFile {
        OutboxFile(url: folder.appendingPathComponent("outbox.bin"))
    }

    /// Durable before it returns. Callers must not send anything a failed save would forget (SR-2-11).
    func save() throws {
        try file.write(try device.exportState(storageKey: key))
    }

    // MARK: Private

    /// A new identity, with no app data until a phone pairs (pairing makes the onion service).
    private static func create(at file: StateFile) throws -> Store {
        guard let key = try Keychain.storageKey(create: true) else { throw Unreadable() }
        let store = Store(device: try SeamDevice.generate(), pairAgain: false, key: key, file: file)
        try store.save()
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
