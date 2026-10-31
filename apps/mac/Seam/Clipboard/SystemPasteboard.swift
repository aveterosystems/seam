// SPDX-License-Identifier: AGPL-3.0-only
import AppKit
import SeamSession

/// The clipboard feature's pasteboard: the Mac's own, read and written on the main thread.
struct SystemPasteboard: SeamSession.Pasteboard { // AppKit has a Pasteboard too
    @MainActor func changeCount() -> Int {
        NSPasteboard.general.changeCount
    }

    @MainActor func firstItem(ifStill change: Int) -> PasteboardItem? {
        let pb = NSPasteboard.general
        guard pb.changeCount == change, let item = pb.pasteboardItems?.first else { return nil }
        // Types and text from the same item, then make sure nothing replaced it meanwhile (SR-4-05)
        let found = PasteboardItem(types: item.types.map(\.rawValue), text: item.string(forType: .string))
        return pb.changeCount == change ? found : nil
    }

    @MainActor func write(_ text: String) -> Int {
        let pb = NSPasteboard.general
        pb.clearContents()
        pb.setString(text, forType: .string)
        return pb.changeCount
    }
}
