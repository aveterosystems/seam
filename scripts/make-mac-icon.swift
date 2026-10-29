// SPDX-License-Identifier: AGPL-3.0-only
// Draws the Seam app icon (design board 00: a teal tile, the Mac and phone panels, three stitches)
// at every size macOS needs, into the asset catalogue:  swift scripts/make-mac-icon.swift
import AppKit

let out = URL(fileURLWithPath: "apps/mac/Seam/Assets.xcassets/AppIcon.appiconset")
try FileManager.default.createDirectory(at: out, withIntermediateDirectories: true)
let teal = NSColor(srgbRed: 0x0B / 255, green: 0x7F / 255, blue: 0x6F / 255, alpha: 1)

func icon(_ px: Int) -> Data {
    let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: px, pixelsHigh: px, bitsPerSample: 8, samplesPerPixel: 4,
                               hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
    let s = CGFloat(px) / 32 // the board's 32-unit grid; macOS icons keep a 10% margin
    let m = CGFloat(px) * 0.1, k = (CGFloat(px) - 2 * m) / 32
    func r(_ x: CGFloat, _ y: CGFloat, _ w: CGFloat, _ h: CGFloat) -> NSRect { NSRect(x: m + x * k, y: m + (32 - y - h) * k, width: w * k, height: h * k) }
    _ = s
    teal.setFill()
    NSBezierPath(roundedRect: r(0, 0, 32, 32), xRadius: 8 * k, yRadius: 8 * k).fill()
    NSColor.white.setFill()
    NSBezierPath(roundedRect: r(6, 8, 9, 16), xRadius: 2.5 * k, yRadius: 2.5 * k).fill()
    NSColor.white.withAlphaComponent(0.6).setFill()
    NSBezierPath(roundedRect: r(17, 8, 9, 16), xRadius: 2.5 * k, yRadius: 2.5 * k).fill()
    teal.setStroke()
    for y in [12.0, 16.0, 20.0] {
        let p = NSBezierPath()
        p.move(to: NSPoint(x: m + 13.5 * k, y: m + (32 - y) * k))
        p.line(to: NSPoint(x: m + 18.5 * k, y: m + (32 - y) * k))
        p.lineWidth = 1.6 * k
        p.lineCapStyle = .round
        p.stroke()
    }
    NSGraphicsContext.restoreGraphicsState()
    return rep.representation(using: .png, properties: [:])!
}

var images: [[String: String]] = []
for pt in [16, 32, 128, 256, 512] {
    for scale in [1, 2] {
        let name = "icon_\(pt)x\(pt)\(scale == 2 ? "@2x" : "").png"
        try icon(pt * scale).write(to: out.appendingPathComponent(name))
        images.append(["idiom": "mac", "size": "\(pt)x\(pt)", "scale": "\(scale)x", "filename": name])
    }
}
let contents: [String: Any] = ["images": images, "info": ["author": "xcode", "version": 1]]
try JSONSerialization.data(withJSONObject: contents, options: [.prettyPrinted, .sortedKeys]).write(to: out.appendingPathComponent("Contents.json"))
let catalog = out.deletingLastPathComponent().appendingPathComponent("Contents.json")
try #"{"info":{"author":"xcode","version":1}}"#.write(to: catalog, atomically: true, encoding: .utf8)
print("wrote \(images.count) icons")
