// PROTOTYPE, throwaway. The Mac side of the async Pasteboard interface, on the real NSPasteboard.
import AppKit

final class MacPasteboard: Pasteboard, @unchecked Sendable {
    func changeCount() async -> Int64 {
        await MainActor.run { Int64(NSPasteboard.general.changeCount) }
    }
    func firstItem(ifStill: Int64) async -> PasteboardItem? {
        await MainActor.run {
            let pb = NSPasteboard.general
            guard Int64(pb.changeCount) == ifStill, let item = pb.pasteboardItems?.first else { return nil }
            return PasteboardItem(types: item.types.map(\.rawValue), text: item.string(forType: .string))
        }
    }
    func write(text: String) async throws -> Int64 {
        if text == "FAIL" { throw PasteboardError.Unavailable(reason: "Swift threw on purpose") }
        return await MainActor.run {
            let pb = NSPasteboard.general
            pb.clearContents()
            pb.setString(text, forType: .string)
            return Int64(pb.changeCount)
        }
    }
    func slow(ms: UInt64) async -> String {
        do {
            try await Task.sleep(for: .milliseconds(ms))
            return "slept \(ms) ms"
        } catch {
            print("   swift: slow(\(ms)) saw cancellation")
            return "cancelled"
        }
    }
}

final class Printer: Report, @unchecked Sendable {
    func line(text: String) {
        print(text, "| on main thread:", Thread.isMainThread)
        if text == "done" { DispatchQueue.main.asyncAfter(deadline: .now() + 2.5) { restore(); exit(0) } }
    }
}

// Put the clipboard back as it was before the spike
let old = NSPasteboard.general.string(forType: .string)
func restore() {
    NSPasteboard.general.clearContents()
    if let old { NSPasteboard.general.setString(old, forType: .string) }
}
let spike = Spike(pb: MacPasteboard(), report: Printer())
spike.run()
DispatchQueue.main.asyncAfter(deadline: .now() + 30) { print("TIMEOUT"); restore(); exit(1) }
RunLoop.main.run()
