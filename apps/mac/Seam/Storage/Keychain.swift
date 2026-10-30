// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import Security

/// The 32-byte key the Rust core seals Seam's state with. Only this Mac's Keychain holds it.
///
/// Accepted for now, not fixed (SR-2-25): this is the legacy file-based keychain, the only one an
/// unsigned dev build can use. Moving to the data-protection keychain, with the key wrapped by a
/// Secure Enclave key, needs code signing and a keychain-access-groups entitlement; do it with the
/// first signed release, migrating the existing item.
enum Keychain {
    // Debug builds have their own bundle id, so their key never touches the installed app's
    private static let service = Bundle.main.bundleIdentifier ?? "com.avetero.seam"
    private static let account = "state-key"

    struct Failure: Error, CustomStringConvertible {
        let status: OSStatus
        var description: String { "Keychain error \(status)" }
    }

    /// Loads the key. When there's none, creates one only if `create` (first launch, or a reset);
    /// otherwise returns nil, since saved state without its key can't be opened. Never replaces a
    /// key it can't read: that would orphan the saved state.
    static func storageKey(create: Bool) throws -> Data? {
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
        guard create else { return nil }

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
        if added == errSecParam || added == errSecNoSuchAttr {
            // The file-based keychain may refuse accessibility classes; the item stays local either way
            add.removeValue(forKey: kSecAttrAccessible as String)
            added = SecItemAdd(add as CFDictionary, nil)
        }
        guard added == errSecSuccess else { throw Failure(status: added) }
        return key
    }
}
