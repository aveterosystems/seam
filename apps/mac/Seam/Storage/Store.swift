// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import SeamCore

/// Where Seam keeps things on this Mac: the sealed core state in Application Support, and the
/// group id, relay URL and a bundle flag in UserDefaults (none of which are secret).
struct Store {
    static let defaultRelayURL = "ws://localhost:8787"

    private let defaults = UserDefaults.standard
    private let key: Data
    private let stateURL: URL

    init() throws {
        key = try Keychain.storageKey()
        let dir = try FileManager.default
            .url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
            .appendingPathComponent("Seam", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        stateURL = dir.appendingPathComponent("state.bin")
    }

    /// 16 random bytes, made once: this Mac's pairing group on the relay.
    var groupID: Data {
        if let g = defaults.data(forKey: "groupID"), g.count == 16 { return g }
        let g = Data((0..<16).map { _ in UInt8.random(in: .min ... .max) })
        defaults.set(g, forKey: "groupID")
        return g
    }

    /// Override with `defaults write com.avetero.seam relayURL ws://<this Mac's LAN IP>:8787`.
    var relayURL: URL {
        URL(string: defaults.string(forKey: "relayURL") ?? "") ?? URL(string: Self.defaultRelayURL)!
    }

    /// True when the relay should get fresh prekey bundles (new group, or one used by pairing).
    var needsBundles: Bool {
        get { defaults.bool(forKey: "needsBundles") }
        nonmutating set { defaults.set(newValue, forKey: "needsBundles") }
    }

    /// Restores the saved device, or creates one on first launch.
    func loadOrCreateDevice() throws -> SeamDevice {
        if let blob = try? Data(contentsOf: stateURL) {
            do {
                return try SeamDevice.restore(state: blob, storageKey: key)
            } catch SeamError.BadState {
                // Wrong key or damaged: keep the file for inspection instead of overwriting it
                let aside = stateURL.appendingPathExtension("unreadable-\(Int(Date().timeIntervalSince1970))")
                try FileManager.default.moveItem(at: stateURL, to: aside)
            }
        }
        let device = try SeamDevice.generate()
        try save(device)
        needsBundles = true
        return device
    }

    func save(_ device: SeamDevice) throws {
        try device.exportState(storageKey: key).write(to: stateURL, options: .atomic)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: stateURL.path)
    }
}
