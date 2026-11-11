// SPDX-License-Identifier: AGPL-3.0-only
import SeamCore
import SeamSession
import SwiftUI

// Board M01's tile and battery pill: shared, animated, and drawn to the popover prototype.

/// A row of the popover's control module, as Control Centre draws its connectivity block: a round
/// button that fills with the accent when on, the feature's name, and its state in words.
/// `ring` and `bounce` replay the symbol's emphasis (catalogue 2 and 5); only one is used per event.
struct Tile<Accessory: View>: View {
    enum Tint { case on, paused, off }

    let title: String
    let subtitle: String
    let symbol: String
    let tint: Tint
    var ring = 0
    var bounce = 0
    let action: () -> Void
    @ViewBuilder var accessory: Accessory
    @State private var hovering = false
    @Environment(\.accessibilityReduceMotion) private var reduce

    var body: some View {
        Button(action: action) {
            HStack(spacing: 10) {
                Image(systemName: symbol)
                    .font(.system(size: 13, weight: .medium))
                    .keyframeAnimator(initialValue: 1.0, trigger: reduce ? 0 : bounce) { icon, scale in
                        icon.scaleEffect(scale)
                    } keyframes: { _ in
                        // Catalogue 2: the symbol dips to 0.7 at 30% and springs back over 500 ms
                        KeyframeTrack {
                            CubicKeyframe(0.7, duration: 0.15)
                            SpringKeyframe(1, duration: 0.35, spring: Spring(response: 0.388 * 0.5, dampingRatio: 0.632))
                        }
                    }
                    .keyframeAnimator(initialValue: 0.0, trigger: reduce ? 0 : ring) { icon, angle in
                        icon.rotationEffect(.degrees(angle), anchor: UnitPoint(x: 0.5, y: 0.15))
                    } keyframes: { _ in
                        // Catalogue 5: a ±18° wiggle that dies away over 600 ms
                        KeyframeTrack {
                            CubicKeyframe(18, duration: 0.12)
                            CubicKeyframe(-14, duration: 0.12)
                            CubicKeyframe(8, duration: 0.12)
                            CubicKeyframe(-4, duration: 0.12)
                            CubicKeyframe(0, duration: 0.12)
                        }
                    }
                    .frame(width: 28, height: 28)
                    .background(Circle().fill(fill))
                    .foregroundStyle(ink)
                    .animation(Motion.fade, value: tint)
                VStack(alignment: .leading, spacing: 0) {
                    Text(title).font(.system(size: 13, weight: .medium)).foregroundStyle(SeamStyle.Pop.fg)
                    SwapText(subtitle).font(.system(size: 11))
                        .foregroundStyle(SeamStyle.Pop.fg2)
                        .frame(height: 14, alignment: .leading)
                }
                .lineLimit(1)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 8).padding(.vertical, 6)
            .padding(.trailing, Accessory.self == EmptyView.self ? 0 : 40) // the accessory's whole 44 pt target, so a near miss can't toggle the row
            .background(RoundedRectangle(cornerRadius: 9, style: .continuous).fill(hovering ? SeamStyle.Pop.fill : .clear))
            .contentShape(Rectangle())
        }
        .buttonStyle(PressableStyle(scale: 0.98))
        .overlay(alignment: .trailing) { accessory.padding(.trailing, 8) }
        .onHover { h in withAnimation(Motion.ease(0.15)) { hovering = h } }
        .accessibilityLabel(title)
        .accessibilityValue(subtitle)
    }

    private var fill: Color {
        switch tint {
        case .on: SeamStyle.teal
        case .paused: SeamStyle.paused
        case .off: SeamStyle.Pop.fill
        }
    }

    private var ink: Color {
        switch tint {
        case .on: SeamStyle.onTeal
        case .paused: Color(light: 0xFFFFFF, dark: 0xFFFFFF)
        case .off: SeamStyle.Pop.fg
        }
    }
}

extension Tile where Accessory == EmptyView {
    init(title: String, subtitle: String, symbol: String, tint: Tint, ring: Int = 0, bounce: Int = 0, action: @escaping () -> Void) {
        self.init(title: title, subtitle: subtitle, symbol: symbol, tint: tint, ring: ring, bounce: bounce, action: action) { EmptyView() }
    }
}

/// Catalogue 11: the phone's battery, the way the Mac's own Battery menu writes it: the battery
/// symbol (a bolt while charging) and the number, which counts. At 15% or below while not charging
/// it turns `warn`. Selected (its details open), it sits on a light accent fill.
struct BatteryPill: View {
    let look: DeviceStatusLook
    var selected = false

    var body: some View {
        HStack(spacing: 4) {
            Text("\(look.percent)%").monospacedDigit().contentTransition(.numericText(value: Double(look.percent)))
            // Low shows in the glyph and in words under it; the number stays legible on glass
            Image(systemName: look.batteryIcon?.symbol ?? "battery.100percent")
                .symbolRenderingMode(look.charging ? .multicolor : .monochrome)
                .foregroundStyle(look.low ? AnyShapeStyle(SeamStyle.warn) : AnyShapeStyle(SeamStyle.Pop.fg2))
                .font(.system(size: 13))
                .contentTransition(.symbolEffect(.replace))
        }
        .font(.system(size: 12, weight: look.low ? .semibold : .medium))
        .padding(.leading, 8).padding(.trailing, 6)
        .frame(height: 22)
        .background(Capsule().fill(selected ? SeamStyle.teal.opacity(0.15) : .clear))
        .foregroundStyle(look.low ? SeamStyle.warn : selected ? SeamStyle.tealInk : SeamStyle.Pop.fg)
        .animation(Motion.ease(0.2), value: selected)
        .animation(Motion.count, value: look.percent)
        .animation(Motion.ease(0.4), value: look.low)
        .opacity(look.stale && !look.low ? 0.6 : 1) // the last reading, not a live one; "as of" says when. Low stays loud
        .help(look.stale ? (look.meta ?? "") : "")
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Phone " + look.spoken)
    }
}

/// The battery's details under the header: the time and the rate as figures (the tile's value over
/// label), and the level as a bar in the pill's colours. An older phone that sends neither says
/// how to get them.
struct BatteryDetails: View {
    let look: DeviceStatusLook

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if look.powerTime == nil && look.powerRate == nil {
                Text("Update Seam on your phone to see its charge rate")
                    .font(.system(size: 11)).foregroundStyle(SeamStyle.Pop.fg2)
            } else {
                HStack(alignment: .top) {
                    // The time leads; the rate is the detail behind it
                    figure(look.powerTime, .leading, hero: true)
                    Spacer(minLength: 8)
                    figure(look.powerRate, .trailing, hero: false)
                }
            }
            GeometryReader { g in
                Capsule().fill(SeamStyle.Pop.fill)
                    .overlay(alignment: .leading) {
                        Capsule().fill(fill).frame(width: g.size.width * CGFloat(look.percent) / 100)
                    }
            }
            .frame(height: 4)
            .animation(Motion.count, value: look.percent)
            .animation(Motion.ease(0.4), value: fill)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(look.power ?? "Update Seam on your phone to see its charge rate")
    }

    private var fill: Color { look.low ? SeamStyle.warn : look.charging ? SeamStyle.teal : SeamStyle.Pop.fg2 }

    @ViewBuilder
    private func figure(_ r: Readout?, _ edge: HorizontalAlignment, hero: Bool) -> some View {
        if let r {
            VStack(alignment: edge, spacing: 1) {
                SwapText(r.value).font(.system(size: hero ? 15 : 13, weight: hero ? .semibold : .medium)).monospacedDigit()
                    .foregroundStyle(hero ? SeamStyle.Pop.fg : SeamStyle.Pop.fg2)
                    .frame(height: 16)
                Text(r.label).font(.system(size: 10.5)).foregroundStyle(SeamStyle.Pop.fg2)
            }
        }
    }
}
