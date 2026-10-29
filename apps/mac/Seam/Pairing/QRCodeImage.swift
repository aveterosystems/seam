// SPDX-License-Identifier: AGPL-3.0-only
import AppKit
import CoreImage.CIFilterBuiltins

enum QRCodeImage {
    /// A QR code rendered at whole-pixel scale, so it stays sharp (shown with interpolation off).
    static func make(_ text: String, scale: CGFloat = 10) -> NSImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: scale, y: scale)),
              let cg = CIContext().createCGImage(output, from: output.extent)
        else { return nil }
        return NSImage(cgImage: cg, size: NSSize(width: output.extent.width, height: output.extent.height))
    }
}
