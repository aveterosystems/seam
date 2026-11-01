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
    static let ok = Color(light: 0x1C7C3A, dark: 0x4ADE80)
    static let danger = Color(light: 0xB4232F, dark: 0xFF7B86)
    /// The live dot and done ticks ("✓ Copied"): the same green in both themes, as in the prototypes.
    static let live = Color(light: 0x34C759, dark: 0x34C759)
    /// The bolt and the moon: the yellow of the prototypes' ⚡ and 🌙.
    static let glow = Color(light: 0xF2B300, dark: 0xFFCC33)
    static let onTeal = Color(light: 0xFFFFFF, dark: 0x04241F)
    /// Glyphs on the coloured sidebar and privacy squares: white in both themes, as drawn.
    static let onTint = Color(light: 0xFFFFFF, dark: 0xFFFFFF)

    /// The Settings and main windows' palette (docs/design/prototypes/mac-settings.html, `.win`).
    enum Win {
        static let fg = Color(light: 0x1D1D1F, dark: 0xF5F5F7)
        static let fg2 = Color(light: 0x000000, dark: 0xFFFFFF, lightAlpha: 0.56, darkAlpha: 0.62)
        static let fg3 = Color(light: 0x000000, dark: 0xFFFFFF, lightAlpha: 0.4, darkAlpha: 0.4)
        static let fill = Color(light: 0x000000, dark: 0xFFFFFF, lightAlpha: 0.05, darkAlpha: 0.07)
        static let fill2 = Color(light: 0x000000, dark: 0xFFFFFF, lightAlpha: 0.08, darkAlpha: 0.11)
        static let line = Color(light: 0x000000, dark: 0xFFFFFF, lightAlpha: 0.08, darkAlpha: 0.08)
        static let group = Color(light: 0x000000, dark: 0xFFFFFF, lightAlpha: 0.035, darkAlpha: 0.045)
        static let background = Color(light: 0xF6F6F8, dark: 0x1E1F22)
        static let side = Color(light: 0xE8E9ED, dark: 0x3A3C42, lightAlpha: 0.8, darkAlpha: 0.55)
    }

    /// The popover's own palette (docs/design/prototypes/mac-popover.html, `.pop`): greys, fills
    /// and hairlines as alpha over its gradient, so both themes match the approved prototype.
    enum Pop {
        static let fg = Color(light: 0x1D1D1F, dark: 0xF2F2F2)
        static let fg2 = Color(light: 0x000000, dark: 0xFFFFFF, lightAlpha: 0.55, darkAlpha: 0.6)
        static let fg3 = Color(light: 0x000000, dark: 0xFFFFFF, lightAlpha: 0.4, darkAlpha: 0.38)
        static let fill = Color(light: 0x000000, dark: 0xFFFFFF, lightAlpha: 0.06, darkAlpha: 0.08)
        static let fill2 = Color(light: 0x000000, dark: 0xFFFFFF, lightAlpha: 0.035, darkAlpha: 0.05)
        static let line = Color(light: 0x000000, dark: 0xFFFFFF, lightAlpha: 0.08, darkAlpha: 0.09)
        static let background = LinearGradient(colors: [
            Color(light: 0xFAFAFB, dark: 0x303236, lightAlpha: 0.98, darkAlpha: 0.97),
            Color(light: 0xF0F1F3, dark: 0x222427, lightAlpha: 0.99, darkAlpha: 0.98),
        ], startPoint: .top, endPoint: .bottom)
    }
}

/// The timing tokens from docs/design/motion.md. Screens use these names, never a bare duration.
/// Under Reduce Motion, movement becomes a crossfade: pass the environment's flag to `spatial`.
enum Motion {
    /// The prototypes' `--spring` over a CSS duration: a stiff spring that peaks at 25% of it with
    /// 7.7% overshoot and settles by about 45% (its `linear()` points, fitted: damping 0.632,
    /// response 0.388 × duration). Every spring in Seam is this one at some duration.
    static func css(_ duration: Double) -> Animation {
        .spring(response: 0.388 * duration, dampingFraction: 0.632)
    }

    /// Default spatial: open, expand, insert, press release.
    static let spring = css(0.42)
    /// Presses, chevrons, small icons.
    static let springFast = css(0.3)
    /// CSS's default `ease` (0.25, 0.1, 0.25, 1): what every un-timed transition in the
    /// prototypes uses for colour and opacity.
    static func ease(_ duration: Double) -> Animation { .timingCurve(0.25, 0.1, 0.25, 1, duration: duration) }

    /// Colour and opacity.
    static let fade = ease(0.35)
    /// Content leaving its slot.
    static let exit = Animation.easeIn(duration: 0.18)
    /// Line drawing, counting numbers.
    static let settle = Animation.timingCurve(0.2, 0.8, 0.2, 1, duration: 0.7)
    /// The connection line's styles crossfade (catalogue 10).
    static let lineFade = ease(0.4)

    /// Sections arriving when a surface opens: 60 ms, then 35 ms more per section.
    static func stagger(_ i: Int) -> Double { 0.06 + 0.035 * Double(i) }
    /// A section's rise when a surface opens (the prototype's `rise .5s var(--ease)`).
    static let rise = Animation.timingCurve(0.2, 0.8, 0.2, 1, duration: 0.5)
    /// The new text rising into a value swap's slot.
    static let swapIn = css(0.35)
    /// A number counting to its new value: 700 ms, cubic ease-out (the prototypes' counter).
    static let count = Animation.timingCurve(0.33, 1, 0.68, 1, duration: 0.7)
    /// Height growing or collapsing in place (the prototypes' `grid-template-rows .45s`).
    static let reveal = css(0.45)

    /// `spring`, or a crossfade when the user has asked for less motion.
    static func spatial(_ reduce: Bool) -> Animation { reduce ? fade : spring }
}

extension Color {
    init(light: UInt32, dark: UInt32, lightAlpha: CGFloat = 1, darkAlpha: CGFloat = 1) {
        self.init(nsColor: NSColor(name: nil) { appearance in
            let isDark = appearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua
            let hex = isDark ? dark : light
            return NSColor(srgbRed: CGFloat(hex >> 16 & 0xFF) / 255, green: CGFloat(hex >> 8 & 0xFF) / 255, blue: CGFloat(hex & 0xFF) / 255,
                           alpha: isDark ? darkAlpha : lightAlpha)
        })
    }
}

/// The menu bar mark: the Mac and phone panels with three stitches. Filled when connected,
/// outlined when not. A template image, so macOS tints it like any other menu bar item, except teal
/// when an update is waiting.
enum SeamMark {
    static func image(connected: Bool, paused: Bool = false, update: Bool = false) -> NSImage {
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
        img.accessibilityDescription = (paused ? "Seam, paused" : connected ? "Seam, connected" : "Seam, not connected") + (update ? ", update available" : "")
        return img
    }
}

/// Catalogue 5: the menu bar icon pops once (scale 1.35 at 40% of a `spring`) when something arrives.
@MainActor
enum MenuBarPing {
    static func ping() {
        guard !NSWorkspace.shared.accessibilityDisplayShouldReduceMotion else { return }
        // shortcut: MenuBarExtra gives no handle on its status item, so this finds its window by class
        // name; if a macOS release renames it, the pop quietly stops and nothing else changes
        guard let view = NSApp.windows.first(where: { $0.className.contains("StatusBarWindow") })?.contentView else { return }
        view.wantsLayer = true
        guard let layer = view.layer else { return }
        let mid = CGPoint(x: layer.bounds.midX, y: layer.bounds.midY)
        func scaled(_ k: CGFloat) -> NSValue {
            var t = CATransform3DMakeTranslation(mid.x, mid.y, 0)
            t = CATransform3DScale(t, k, k, 1)
            return NSValue(caTransform3D: CATransform3DTranslate(t, -mid.x, -mid.y, 0))
        }
        let pop = CAKeyframeAnimation(keyPath: "transform")
        pop.values = [scaled(1), scaled(1.35), scaled(1)]
        pop.keyTimes = [0, 0.4, 1]
        pop.duration = 0.6
        pop.timingFunctions = [CAMediaTimingFunction(name: .easeOut), CAMediaTimingFunction(controlPoints: 0.3, 1.5, 0.6, 1)]
        layer.add(pop, forKey: "ping")
    }
}

/// When the system appearance changes, every open Seam window crossfades from the old theme to the
/// new over 400 ms (the prototypes' `transition: background .4s, color .4s`): a snapshot of the
/// old look is laid over the window and faded out as the new one draws beneath it.
@MainActor
enum AppearanceFade {
    private static var observation: NSKeyValueObservation?

    static func start() {
        observation = NSApp.observe(\.effectiveAppearance, options: [.prior]) { _, change in
            guard change.isPrior else { return }
            MainActor.assumeIsolated { fadeWindows() }
        }
    }

    private static func fadeWindows() {
        for window in NSApp.windows where window.isVisible {
            guard let frameView = window.contentView?.superview,
                  let snapshot = frameView.bitmapImageRepForCachingDisplay(in: frameView.bounds) else { continue }
            frameView.cacheDisplay(in: frameView.bounds, to: snapshot)
            let image = NSImage(size: frameView.bounds.size)
            image.addRepresentation(snapshot)
            let cover = NSImageView(frame: frameView.bounds)
            cover.image = image
            cover.imageScaling = .scaleAxesIndependently
            cover.autoresizingMask = [.width, .height]
            frameView.addSubview(cover, positioned: .above, relativeTo: nil)
            NSAnimationContext.runAnimationGroup({ ctx in
                ctx.duration = 0.4
                ctx.timingFunction = CAMediaTimingFunction(controlPoints: 0.25, 0.1, 0.25, 1) // CSS ease
                cover.animator().alphaValue = 0
            }, completionHandler: { cover.removeFromSuperview() })
        }
    }
}
