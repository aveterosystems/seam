// SPDX-License-Identifier: AGPL-3.0-only
import AppKit
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
/// outlined when not. A template image, so macOS tints it like any other menu bar item.
enum SeamMark {
    static func image(connected: Bool) -> NSImage {
        let img = NSImage(size: NSSize(width: 18, height: 18), flipped: true) { _ in
            let left = NSBezierPath(roundedRect: NSRect(x: 2.5, y: 3.5, width: 5.5, height: 11), xRadius: 1.6, yRadius: 1.6)
            let right = NSBezierPath(roundedRect: NSRect(x: 10, y: 3.5, width: 5.5, height: 11), xRadius: 1.6, yRadius: 1.6)
            NSColor.black.set()
            if connected {
                left.fill()
                right.fill()
            } else {
                for p in [left, right] {
                    p.lineWidth = 1.3
                    p.stroke()
                }
            }
            for y in [6.5, 9.0, 11.5] {
                let s = NSBezierPath()
                s.move(to: NSPoint(x: 6.5, y: y))
                s.line(to: NSPoint(x: 11.5, y: y))
                s.lineWidth = 1.3
                s.lineCapStyle = .round
                // On the filled panels the stitch has to be cut out to show
                if connected {
                    NSGraphicsContext.current?.compositingOperation = .destinationOut
                    s.lineWidth = 1.6
                }
                s.stroke()
                NSGraphicsContext.current?.compositingOperation = .sourceOver
            }
            return true
        }
        img.isTemplate = true
        img.accessibilityDescription = connected ? "Seam, connected" : "Seam, not connected"
        return img
    }
}

/// The stitch under the phone's name: dashed teal through the relay, broken grey when offline.
/// The text beside it always says the same thing (it's never the only signal).
struct Stitch: View {
    let connected: Bool

    var body: some View {
        Canvas { ctx, size in
            var path = Path()
            path.move(to: CGPoint(x: 0, y: size.height / 2))
            path.addLine(to: CGPoint(x: size.width, y: size.height / 2))
            let style = connected
                ? StrokeStyle(lineWidth: 2, lineCap: .round, dash: [6, 4])
                : StrokeStyle(lineWidth: 2, lineCap: .round, dash: [6, 4, 1, 12])
            ctx.stroke(path, with: .color(connected ? SeamStyle.teal : .secondary.opacity(0.5)), style: style)
        }
        .frame(height: 4)
        .accessibilityHidden(true)
    }
}
