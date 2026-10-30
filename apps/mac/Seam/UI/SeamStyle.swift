// SPDX-License-Identifier: AGPL-3.0-only
import AppKit
import SeamSession
import SwiftUI

/// Seam's own colours (docs/design/design-system.md). Controls follow the system; teal is for the
/// icon, the stitch and accents.
enum SeamStyle {
    static let teal = Color(light: 0x0B7F6F, dark: 0x3DD6BE)
    static let tealInk = Color(light: 0x07604F, dark: 0x6FE6D2)
    static let warn = Color(light: 0x9A5200, dark: 0xF5A524)
}

extension Color {
    init(light: UInt32, dark: UInt32) {
        self.init(nsColor: NSColor(name: nil) { appearance in
            let hex = appearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua ? dark : light
            return NSColor(srgbRed: CGFloat(hex >> 16 & 0xFF) / 255, green: CGFloat(hex >> 8 & 0xFF) / 255, blue: CGFloat(hex & 0xFF) / 255, alpha: 1)
        })
    }
}

/// The menu bar mark: the Mac and phone panels with three stitches. Filled when connected,
/// outlined when not. A template image, so macOS tints it like any other menu bar item, except teal
/// when an update is waiting.
enum SeamMark {
    static func image(connected: Bool, update: Bool = false) -> NSImage {
        // Panels fill 15 of the 18 pt (16.5 with the outline), like the system's own menu bar glyphs
        let img = NSImage(size: NSSize(width: 20, height: 18), flipped: true) { _ in
            let left = NSBezierPath(roundedRect: NSRect(x: 1.25, y: 1.5, width: 7.5, height: 15), xRadius: 2.2, yRadius: 2.2)
            let right = NSBezierPath(roundedRect: NSRect(x: 11.25, y: 1.5, width: 7.5, height: 15), xRadius: 2.2, yRadius: 2.2)
            (update ? NSColor(SeamStyle.teal) : NSColor.black).set()
            if connected {
                left.fill()
                right.fill()
            } else {
                for p in [left, right] {
                    p.lineWidth = 1.5
                    p.stroke()
                }
            }
            for y in [5.6, 9.0, 12.4] {
                let s = NSBezierPath()
                s.move(to: NSPoint(x: 6.75, y: y))
                s.line(to: NSPoint(x: 13.25, y: y))
                s.lineWidth = 1.5
                s.lineCapStyle = .round
                // On the filled panels the stitch has to be cut out to show
                if connected {
                    NSGraphicsContext.current?.compositingOperation = .destinationOut
                    s.lineWidth = 2
                }
                s.stroke()
                NSGraphicsContext.current?.compositingOperation = .sourceOver
            }
            return true
        }
        img.isTemplate = !update
        img.accessibilityDescription = (connected ? "Seam, connected" : "Seam, not connected") + (update ? ", update available" : "")
        return img
    }
}

/// The stitch under the phone's name, drawn from a `LinkLook.Line` (design-system.md, "The stitch"):
/// solid teal on the same Wi‑Fi, dashed teal through a relay, dotted grey when only nearby, broken
/// grey when offline. The text beside it always says the same thing (it's never the only signal).
struct Stitch: View {
    let line: LinkLook.Line

    var body: some View {
        // Board M01: a 3 pt bar; relay dashes of 10 with gaps of 7
        Canvas { ctx, size in
            var path = Path()
            path.move(to: CGPoint(x: 0, y: size.height / 2))
            path.addLine(to: CGPoint(x: size.width, y: size.height / 2))
            let (dash, color): ([CGFloat], Color) = switch line {
            case .solid: ([], SeamStyle.teal)
            case .dashed: ([10, 7], SeamStyle.teal)
            case .dotted: ([2, 6], .secondary.opacity(0.4))
            case .broken: ([10, 7, 2, 18], .secondary.opacity(0.4))
            }
            ctx.stroke(path, with: .color(color), style: StrokeStyle(lineWidth: 3, lineCap: .butt, dash: dash))
        }
        .frame(height: 3)
        .accessibilityHidden(true)
    }
}
