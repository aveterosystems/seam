// Exports what the README mockups draw with: the real SF Symbols, and a QR code made like QRCodeImage.swift.
import AppKit
import CoreImage.CIFilterBuiltins

let names = ["wifi", "globe", "iphone", "doc.on.clipboard", "bell", "bell.badge", "slider.horizontal.3",
             "chevron.right", "checkmark.shield", "xmark", "lock"]
func png(_ size: NSSize, to path: String, draw: () -> Void) {
    let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: Int(size.width * 2), pixelsHigh: Int(size.height * 2),
                               bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
                               colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
    rep.size = size
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
    draw()
    NSGraphicsContext.restoreGraphicsState()
    try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: path))
}
// Drawn at 64 pt: CSS sizes in the pages are the symbol's size here × font size / 64
for n in names {
    let img = NSImage(systemSymbolName: n, accessibilityDescription: nil)!
        .withSymbolConfiguration(.init(pointSize: 64, weight: .regular))!
    png(img.size, to: "sym/\(n).png") { img.draw(in: NSRect(origin: .zero, size: img.size)) }
}
// A stand-in offer about as long as a real one; nobody can pair with it
let filter = CIFilter.qrCodeGenerator()
filter.message = Data("seam1:Hk3vQm9TzP2wLx8RbN4cYs7JfD6gA1eUoK5iVt0WqZ3nMh8XrC2yBj9pE4lG7aF".utf8)
filter.correctionLevel = "M"
let cg = CIContext().createCGImage(filter.outputImage!, from: filter.outputImage!.extent)!
try! NSBitmapImageRep(cgImage: cg).representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: "qr.png"))
