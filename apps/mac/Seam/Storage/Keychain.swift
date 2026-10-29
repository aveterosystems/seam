// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import Security

/// The 32-byte key the Rust core seals Seam's state with. Only this Mac's Keychain holds it.
///
/// shortcut: uses the legacy file-based keychain, which works for unsigned dev builds; signed
/// builds should move to the data-protection keychain with the key wrapped by a Secure Enclave key.
enum Keychain {
    private static let service = "com.avetero.seam"
    private static let account = "state-key"

    struct Failure: Error, CustomStringConvertible {
        let status: OSStatus
        var description: String { "Keychain error \(status)" }
    }

    /// Loads the key, or creates it on first launch. Never replaces a key it can't read: that
    /// would orphan the saved state.
    static func storageKey() throws -> Data {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var out: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &out)
        if status == errSecSuccess, let key = out as? Data, key.count == 32 { return key }
        guard status == errSecItemNotFound else { throw Failure(status: status) }

        var key = Data(count: 32)
        let rng = key.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, 32, $0.baseAddress!) }
        guard rng == errSecSuccess else { throw Failure(status: rng) }

        var add: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecAttrLabel as String: "Seam storage key",
            kSecValueData as String: key,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        var added = SecItemAdd(add as CFDictionary, nil)
        if added == errSecParam {
            // The file-based keychain may refuse accessibility classes; the item stays local either way
            add.removeValue(forKey: kSecAttrAccessible as String)
            added = SecItemAdd(add as CFDictionary, nil)
        }
        guard added == errSecSuccess else { throw Failure(status: added) }
        return key
    }
}
