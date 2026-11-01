// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import SeamSession

/// Pairing a phone: the session runs the flow, the pairing window shows it.
extension SeamController {
    func startPairing() {
        guard let session else { return }
        let name = Host.current().localizedName ?? "Mac"
        let paths = paths
        Task {
            await paths?.settled() // a switch changed just before: the session hears of it first
            await session.startPairing(name: name)
            #if DEBUG
            // Device tests: hand the code to an emulator, which has no camera to scan with
            if let path = ProcessInfo.processInfo.environment["SEAM_PAIRING_CODE_FILE"], case .waiting(let qr, _) = await session.state.pairing {
                FileManager.default.createFile(atPath: path, contents: Data(qr.utf8), attributes: [.posixPermissions: 0o600])
            }
            #endif
        }
    }

    /// Pair was clicked. Safe to call twice: only the first call does anything.
    func confirmPairing() {
        Task { await session?.confirmPairing() }
    }

    /// Cancel, or the window closed. Does nothing while PairAccept is already on its way.
    func cancelPairing() {
        Task { await session?.cancelPairing() }
    }
}
