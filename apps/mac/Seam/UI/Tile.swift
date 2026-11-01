// SPDX-License-Identifier: AGPL-3.0-only
import SeamSession
import SwiftUI

// Board M01's tile and battery pill: shared, animated, and drawn to the popover prototype.

/// A wide tile (board M01): a round symbol, a title and its state in words. `ring` and `bounce`
/// replay the symbol's emphasis (catalogue 2 and 5); only one is used per event.
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
            HStack(spacing: 9) {
                Image(systemName: symbol)
                    .font(.system(size: 13))
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
                    .frame(width: 30, height: 30)
                    .background(Circle().fill(fill))
                    .foregroundStyle(ink)
                    .animation(Motion.fade, value: tint)
                VStack(alignment: .leading, spacing: 1) {
                    Text(title).font(.system(size: 12, weight: .semibold)).foregroundStyle(SeamStyle.Pop.fg)
                    SwapText(subtitle).font(.system(size: 10.5))
                        .foregroundStyle(tint == .paused ? SeamStyle.warn : SeamStyle.Pop.fg2)
                        .frame(height: 13, alignment: .leading)
                }
                .lineLimit(1)
                Spacer(minLength: 0)
            }
            .padding(9)
            .padding(.trailing, Accessory.self == EmptyView.self ? 0 : 24) // the ▾ (15 pt) and the gap before it
            .background(RoundedRectangle(cornerRadius: 12).fill(hovering ? SeamStyle.Pop.fill : SeamStyle.Pop.fill2))
            .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(SeamStyle.Pop.line, lineWidth: 0.5))
        }
        .buttonStyle(PressableStyle())
        .overlay(alignment: .trailing) { accessory.padding(.trailing, 9) }
        .onHover { h in withAnimation(Motion.ease(0.15)) { hovering = h } }
        .accessibilityLabel(title)
        .accessibilityValue(subtitle)
    }

    private var fill: Color {
        switch tint {
        case .on: SeamStyle.teal
        case .paused: SeamStyle.warn.opacity(0.22)
        case .off: SeamStyle.Pop.fill
        }
    }

    private var ink: Color {
        switch tint {
        case .on: SeamStyle.onTeal
        case .paused: SeamStyle.warn
        case .off: SeamStyle.Pop.fg
        }
    }
}

extension Tile where Accessory == EmptyView {
    init(title: String, subtitle: String, symbol: String, tint: Tint, ring: Int = 0, bounce: Int = 0, action: @escaping () -> Void) {
        self.init(title: title, subtitle: subtitle, symbol: symbol, tint: tint, ring: ring, bounce: bounce, action: action) { EmptyView() }
    }
}

/// Catalogue 11: the phone's battery. The number counts, the bolt springs in and out, and at 15% or
/// below while not charging the pill fades to `warn`.
struct BatteryPill: View {
    let look: PhoneStatusLook

    var body: some View {
        HStack(spacing: 3) {
            if look.charging { Image(systemName: "bolt.fill").foregroundStyle(SeamStyle.glow).transition(.scale.combined(with: .opacity)) }
            Text("\(look.percent)").monospacedDigit().contentTransition(.numericText(value: Double(look.percent)))
            Text("%")
        }
        .font(.system(size: 11, weight: .semibold))
        .padding(.horizontal, 8)
        .frame(height: 20)
        .background(Capsule().fill(look.low ? SeamStyle.warn.opacity(0.18) : SeamStyle.Pop.fill))
        .foregroundStyle(look.low ? SeamStyle.warn : SeamStyle.Pop.fg2)
        .animation(Motion.css(0.35), value: look.charging)
        .animation(Motion.count, value: look.percent)
        .animation(Motion.ease(0.4), value: look.low)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Phone " + look.spoken)
    }
}
