// SPDX-License-Identifier: AGPL-3.0-only
import AppKit
import SeamSession
import SwiftUI

// Seam's shared animated pieces (docs/design/motion.md, "Where motion lives in code"). Screens
// compose these (Tile.swift has the tile and the battery pill); a new kind of motion is added
// here and to the catalogue first, then used.

/// Catalogue 2: a press scales to 0.96 and springs back. Used by every Seam-drawn control.
struct PressableStyle: ButtonStyle {
    var scale: CGFloat = 0.96

    func makeBody(configuration: Configuration) -> some View {
        Pressed(label: configuration.label, pressed: configuration.isPressed, scale: scale)
    }

    private struct Pressed<Label: View>: View {
        let label: Label
        let pressed: Bool
        let scale: CGFloat
        @Environment(\.accessibilityReduceMotion) private var reduce

        var body: some View {
            label
                .scaleEffect(pressed && !reduce ? scale : 1)
                .opacity(pressed && reduce ? 0.7 : 1)
                .animation(Motion.springFast, value: pressed)
                .contentShape(Rectangle())
        }
    }
}

/// Catalogue 3: the old words rise out and the new rise into the same slot, which keeps its height.
struct SwapText: View {
    let text: String
    var lines: Int? = 1
    /// Settings' swap moves 7 pt and leaves in 160 ms; the popover's 8 pt and 180 ms.
    var pane = false
    @Environment(\.accessibilityReduceMotion) private var reduce

    init(_ text: String, lines: Int? = 1, pane: Bool = false) {
        self.text = text
        self.lines = lines
        self.pane = pane
    }

    var body: some View {
        ZStack(alignment: .leading) {
            Text(text).lineLimit(lines).fixedSize(horizontal: false, vertical: lines != 1).id(text).transition(reduce ? .opacity : swap)
        }
        .clipped()
        .animation(Motion.spatial(reduce), value: text)
    }

    /// The old words leave first; the new ones start once they've gone (the prototypes' swap()).
    private var swap: AnyTransition {
        let d: CGFloat = pane ? 7 : 8
        let out = pane ? 0.16 : 0.18
        return .asymmetric(
            insertion: .offset(y: d).combined(with: .opacity).animation(Motion.swapIn.delay(out - 0.01)),
            removal: .offset(y: -d).combined(with: .opacity).animation(.easeIn(duration: out))
        )
    }
}

extension AnyTransition {
    /// Catalogue 5: a new row's height grows from zero as it fades in and slides down 10 pt.
    static func arrive(_ reduce: Bool) -> AnyTransition {
        reduce ? .opacity : .asymmetric(
            insertion: grow(Motion.reveal).combined(with: .offset(y: -10).animation(Motion.css(0.4))),
            removal: leave)
    }

    /// Catalogue 6: a row's height collapses as it fades and slides 30 pt toward the trailing edge.
    static var leave: AnyTransition {
        grow(Motion.reveal).combined(with: .offset(x: 30).animation(Motion.css(0.4)))
    }

    /// Catalogue 7: an explanation, field or banner unfolds in place: its height grows from zero
    /// on a spring, revealing it where it sits, while it fades in over 300 ms.
    static func unfold(_ reduce: Bool, height: Animation = Motion.reveal, fade: Double = 0.3) -> AnyTransition {
        reduce ? .opacity : grow(height, fade: fade)
    }

    /// Height from zero, clipped, plus a fade: the prototypes' `grid-template-rows: 0fr → 1fr`.
    static func grow(_ height: Animation, fade: Double = 0.3) -> AnyTransition {
        AnyTransition.modifier(active: Reveal(progress: 0), identity: Reveal(progress: 1)).animation(height)
            .combined(with: .opacity.animation(Motion.ease(fade)))
    }
}

/// Reports `progress` of its content's height and clips the rest, so the views below move as it grows.
private struct Reveal: ViewModifier, Animatable {
    var progress: CGFloat
    nonisolated var animatableData: CGFloat {
        get { progress }
        set { progress = newValue }
    }

    func body(content: Content) -> some View {
        RevealLayout(progress: progress) { content }.clipped()
    }
}

private struct RevealLayout: Layout {
    var progress: CGFloat

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let size = subviews.first?.sizeThatFits(proposal) ?? .zero
        return CGSize(width: size.width, height: size.height * max(0, progress))
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        subviews.first?.place(at: bounds.origin, proposal: ProposedViewSize(width: bounds.width, height: nil))
    }
}

extension View {
    /// Catalogue 1: a section rises and fades in, `index` places it in the stagger. The popover's
    /// sections rise 6 pt on `rise` from 60 ms; a Settings pane's 8 pt on `spring`, 35 ms apart
    /// from the start (the two prototypes).
    func rise(_ index: Int, _ shown: Bool, pane: Bool = false) -> some View {
        modifier(Rise(index: index, shown: shown, pane: pane))
    }
}

private struct Rise: ViewModifier {
    let index: Int
    let shown: Bool
    let pane: Bool
    @Environment(\.accessibilityReduceMotion) private var reduce

    func body(content: Content) -> some View {
        let motion = reduce ? Motion.fade : pane ? Motion.spring : Motion.rise
        content
            .opacity(shown ? 1 : 0)
            .offset(y: shown || reduce ? 0 : pane ? 8 : 6)
            .animation(motion.delay(pane ? 0.035 * Double(index) : Motion.stagger(index)), value: shown)
    }
}

/// The stitch (design-system.md, catalogue 10): solid teal drawn left to right on the same Wi‑Fi,
/// teal dashes flowing at 11 pt a second through Tor, still grey dots while Tor connects and a
/// broken grey line offline. The styles crossfade; the words beside it always say the same thing.
struct Stitch: View {
    let line: LinkLook.Line
    /// Change it to draw the solid line again (a surface opened).
    var replay = 0
    @Environment(\.accessibilityReduceMotion) private var reduce
    @State private var drawn: CGFloat = 1

    var body: some View {
        ZStack {
            Rule().trim(from: 0, to: drawn).stroke(SeamStyle.teal, lineWidth: 2)
                .opacity(line == .solid ? 1 : 0)
            TimelineView(.animation(paused: reduce || line != .dashed)) { tl in
                let phase = tl.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 1) * 11
                Rule().stroke(SeamStyle.teal, style: StrokeStyle(lineWidth: 2, dash: [7, 4], dashPhase: reduce ? 0 : -phase))
            }
            .opacity(line == .dashed ? 1 : 0)
            Rule().stroke(SeamStyle.Pop.fg3, style: StrokeStyle(lineWidth: 2, dash: line == .dotted ? [2, 5] : [3, 5]))
                .opacity(line == .broken || line == .dotted ? 1 : 0)
        }
        .frame(height: 2)
        .animation(Motion.lineFade, value: line)
        .onAppear(perform: draw)
        .onChange(of: line) { if line == .solid { draw() } }
        .onChange(of: replay) { draw() }
        .accessibilityHidden(true)
    }

    private func draw() {
        guard line == .solid, !reduce else { drawn = 1; return }
        var still = Transaction()
        still.disablesAnimations = true
        withTransaction(still) { drawn = 0 }
        DispatchQueue.main.async { withAnimation(Motion.settle) { drawn = 1 } }
    }

    private struct Rule: Shape {
        func path(in r: CGRect) -> Path {
            Path { $0.move(to: CGPoint(x: 0, y: r.midY)); $0.addLine(to: CGPoint(x: r.maxX, y: r.midY)) }
        }
    }
}

/// The status dot: green with a soft ring every 2.4 s while connected, still grey when not.
struct StatusDot: View {
    let on: Bool
    @Environment(\.accessibilityReduceMotion) private var reduce

    var body: some View {
        Circle().fill(on ? SeamStyle.live : SeamStyle.Pop.fg3)
            .frame(width: 7, height: 7)
            .background {
                if on && !reduce {
                    TimelineView(.animation) { tl in
                        let k = min(1, tl.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 2.4) / 2.4 / 0.7)
                        let eased = 1 - (1 - k) * (1 - k)
                        Circle().fill(SeamStyle.live).scaleEffect(1 + 1.8 * eased).opacity(0.6 * (1 - eased))
                    }
                }
            }
            .animation(Motion.ease(0.4), value: on)
            .accessibilityHidden(true)
    }
}

/// ⓘ: unfolds an explanation in place, and turns while it's open.
struct InfoButton: View {
    @Binding var open: Bool
    var label = "More about this"
    /// Settings' ⓘ turns while open; the popover's only changes colour (the prototypes differ).
    var turns = true
    @State private var hovering = false

    var body: some View {
        Button { open.toggle() } label: {
            Image(systemName: "info.circle")
                .rotationEffect(.degrees(open && turns ? 180 : 0))
                .foregroundStyle(open || hovering ? SeamStyle.tealInk : SeamStyle.Pop.fg3)
                .onHover { hovering = $0 }
        }
        .buttonStyle(PressableStyle(scale: 0.9))
        .animation(Motion.springFast, value: open)
        .help(label)
        .accessibilityLabel(label)
        .accessibilityValue(open ? "Shown" : "Hidden")
    }
}

/// Seam's one floating surface, for anything that hovers over a screen: the popover's toast and
/// the Settings bulk bar. The theme's own colours (dark in dark mode, never inverted) and one lift,
/// so they always match. A new floating bar uses this rather than drawing its own.
struct FloatingSurface: ViewModifier {
    func body(content: Content) -> some View {
        content
            .foregroundStyle(SeamStyle.Pop.fg)
            .background(.regularMaterial, in: Capsule())
            .background(SeamStyle.Win.side, in: Capsule())
            .overlay(Capsule().strokeBorder(SeamStyle.Win.line, lineWidth: 0.5))
            .shadow(color: .black.opacity(0.2), radius: 15, y: 8)
    }
}

extension View {
    func floatingSurface() -> some View { modifier(FloatingSurface()) }
}

/// Catalogue 8: when the result happens somewhere else, a small pill rises above the footer.
struct ToastPill: View {
    let text: String

    var body: some View {
        Text(text).font(.system(size: 11, weight: .semibold))
            .padding(.horizontal, 11).padding(.vertical, 5)
            .floatingSurface()
            .accessibilityAddTraits(.isStaticText)
    }
}

/// A keyboard shortcut shown beside its action ("⌘O").
struct KeyCap: View {
    let keys: String
    init(_ keys: String) { self.keys = keys }

    var body: some View {
        Text(keys).font(.system(size: 10)).foregroundStyle(SeamStyle.Pop.fg3)
            .padding(.horizontal, 4)
            .background(RoundedRectangle(cornerRadius: 4).fill(SeamStyle.Pop.fill))
            .padding(.leading, 3)
            .accessibilityHidden(true)
    }
}

/// The prototypes' small pill button (`.btn`): teal, 11 pt semibold, for Update and Send.
struct PillButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var enabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 11, weight: .semibold))
            .padding(.horizontal, 10).padding(.vertical, 3)
            .background(Capsule().fill(SeamStyle.teal))
            .foregroundStyle(SeamStyle.onTeal)
            .opacity(enabled ? 1 : 0.5)
            .scaleEffect(configuration.isPressed ? 0.92 : 1)
            .animation(Motion.css(0.2), value: configuration.isPressed)
    }
}

/// The prototypes' phone: a rounded outline with its speaker slot, stroked at the glyph's size.
struct PhoneOutline: View {
    var width: CGFloat = 13
    var height: CGFloat = 20

    var body: some View {
        RoundedRectangle(cornerRadius: width * 0.23).strokeBorder(lineWidth: 1.6)
            .frame(width: width, height: height)
            .overlay(alignment: .top) { Capsule().frame(width: width * 0.3, height: 1.2).padding(.top, 2) }
            .accessibilityHidden(true)
    }
}
