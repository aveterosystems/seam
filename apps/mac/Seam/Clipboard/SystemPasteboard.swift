// SPDX-License-Identifier: AGPL-3.0-only
import AppKit
import SeamCore

/// The clipboard feature's pasteboard: the Mac's own, read and written on the main thread.
final class SystemPasteboard: SeamCore.Pasteboard, @unchecked Sendable { // AppKit has a Pasteboard too
    func changeCount() async -> Int64 {
        await MainActor.run { Int64(NSPasteboard.general.changeCount) }
    }

    func firstItem(ifStill change: Int64) async -> PasteboardItem? {
        await MainActor.run {
            let pb = NSPasteboard.general
            guard Int64(pb.changeCount) == change, let item = pb.pasteboardItems?.first else { return nil }
            // Types and text from the same item, then make sure nothing replaced it meanwhile (SR-4-05)
            let found = PasteboardItem(types: item.types.map(\.rawValue), text: item.string(forType: .string))
            return Int64(pb.changeCount) == change ? found : nil
        }
    }

    func write(text: String) async -> Int64 {
        await MainActor.run {
            let pb = NSPasteboard.general
            pb.clearContents()
            pb.setString(text, forType: .string)
            return Int64(pb.changeCount)
        }
    }
}
