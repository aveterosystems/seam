// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import SeamCore

/// The Keychain, as the core's key store.
final class KeychainStore: KeyStore, @unchecked Sendable {
    func storageKey(create: Bool) throws -> Data? {
        do {
            return try Keychain.storageKey(create: create)
        } catch {
            throw OsError.Failed(detail: "\(error)")
        }
    }
}
