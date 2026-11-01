// SPDX-License-Identifier: AGPL-3.0-only
import AppKit
import SeamSession
import SwiftUI

// Settings' and the main window's grouped rows, drawn to the Settings prototype, and the pieces
// rows share.

/// A group of rows on a faint fill with a hairline edge (the prototype's `.grp`), 16 pt above the
/// next. Each row draws a hairline above itself; the group clips the first one off.
struct SettingsGroup<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        VStack(spacing: 0) { content }
            .padding(.top, -0.5)
            .background(SeamStyle.Win.group)
            .clipShape(RoundedRectangle(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(SeamStyle.Win.line, lineWidth: 0.5))
            .padding(.bottom, 16)
    }
}

/// A group's title (the prototype's `.gt`): 13 pt semibold, with optional controls on its line.
struct GroupTitle<Extra: View>: View {
    let title: String
    @ViewBuilder var extra: Extra

    var body: some View {
        HStack(alignment: .center, spacing: 8) {
            Text(title).font(.system(size: 13, weight: .semibold)).foregroundStyle(SeamStyle.Win.fg)
            extra
        }
        .padding(EdgeInsets(top: 4, leading: 2, bottom: 7, trailing: 2))
        .accessibilityAddTraits(.isHeader)
    }
}

extension GroupTitle where Extra == EmptyView {
    init(_ title: String) { self.init(title: title) { EmptyView() } }
}

/// The line under a group (the prototype's `.foot`): 8 pt below it, 16 pt above what follows.
struct GroupFootnote<Words: View>: View {
    @ViewBuilder var words: Words

    var body: some View {
        words.font(.system(size: 11.5)).foregroundStyle(SeamStyle.Win.fg2).lineSpacing(1)
            .fixedSize(horizontal: false, vertical: true)
            .padding(EdgeInsets(top: -8, leading: 4, bottom: 16, trailing: 4))
    }
}

/// The prototypes' capsule button (`.cap`): 12 pt on a faint fill, teal when it's the main action,
/// red for a destructive one.
struct CapButtonStyle: ButtonStyle {
    enum Kind { case normal, primary }
    var kind = Kind.normal
    @Environment(\.isEnabled) private var enabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 12, weight: .medium))
            .padding(.horizontal, 12).padding(.vertical, 4)
            .background(Capsule().fill(kind == .primary ? SeamStyle.teal : SeamStyle.Win.fill2))
            .foregroundStyle(kind == .primary ? SeamStyle.onTeal : SeamStyle.Win.fg)
            .opacity(enabled ? 1 : 0.45)
            .scaleEffect(configuration.isPressed ? 0.94 : 1)
            .animation(Motion.css(0.2), value: configuration.isPressed)
    }
}

/// One row (the prototype's `.row`): optional leading content (an icon, a checkbox), a title with
/// an optional badge or ⓘ, an optional line of explanation, and what goes on the right.
struct SettingsRow<Leading: View, Trailing: View>: View {
    let title: String
    let detail: String?
    var badge: String?
    /// ⓘ beside the title, unfolding `InfoMore` below the row.
    var info: Binding<Bool>?
    @ViewBuilder let leading: Leading
    @ViewBuilder let trailing: Trailing

    init(_ title: String, _ detail: String?, badge: String? = nil, info: Binding<Bool>? = nil,
         @ViewBuilder leading: () -> Leading, @ViewBuilder trailing: () -> Trailing) {
        self.title = title
        self.detail = detail
        self.badge = badge
        self.info = info
        self.leading = leading()
        self.trailing = trailing()
    }

    var body: some View {
        HStack(spacing: 12) {
            leading
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(title).foregroundStyle(SeamStyle.Win.fg).lineLimit(1)
                    if let info { InfoButton(open: info) }
                    if let badge { Badge(text: badge).transition(.scale(scale: 0.6).combined(with: .opacity)) }
                }
                if let detail { RowDetail(detail) }
            }
            Spacer(minLength: 12)
            trailing
        }
        .font(.system(size: 13))
        .padding(.horizontal, 14).padding(.vertical, 9)
        .frame(minHeight: 42)
        .overlay(alignment: .top) { Rectangle().fill(SeamStyle.Win.line).frame(height: 0.5) }
        .accessibilityElement(children: .contain)
    }
}

/// A row's line of explanation (the prototype's `.d`): 11.5 pt, secondary.
struct RowDetail: View {
    let text: String
    init(_ text: String) { self.text = text }

    var body: some View {
        Text(text).font(.system(size: 11.5)).foregroundStyle(SeamStyle.Win.fg2).lineSpacing(1)
            .fixedSize(horizontal: false, vertical: true)
    }
}

/// What a row's ⓘ unfolds, under a hairline (the prototype's `.cx .more`).
struct InfoMore: View {
    let text: String
    init(_ text: String) { self.text = text }

    var body: some View {
        RowDetail(text)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(EdgeInsets(top: 0, leading: 14, bottom: 10, trailing: 14))
    }
}

extension SettingsRow where Leading == EmptyView {
    init(_ title: String, _ detail: String?, badge: String? = nil, info: Binding<Bool>? = nil, @ViewBuilder trailing: () -> Trailing) {
        self.init(title, detail, badge: badge, info: info, leading: { EmptyView() }, trailing: trailing)
    }
}

/// A small teal pill beside a title ("Reply on Mac").
struct Badge: View {
    let text: String

    var body: some View {
        Text(text).font(.system(size: 10.5, weight: .medium)).lineLimit(1).fixedSize()
            .padding(.horizontal, 7).padding(.vertical, 1)
            .background(SeamStyle.teal.opacity(0.16), in: Capsule())
            .foregroundStyle(SeamStyle.tealInk)
    }
}

/// A phone app's icon at row size. Until the phone has sent it, its name's first letter on a tile.
struct AppIcon: View {
    let image: NSImage?
    var size: CGFloat = 24
    var name = ""

    var body: some View {
        Group {
            if let image {
                Image(nsImage: image).resizable()
            } else {
                Text(name.prefix(1).uppercased()).font(.system(size: size * 0.5, weight: .semibold))
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(RoundedRectangle(cornerRadius: size / 4).fill(SeamStyle.Pop.fill))
                    .foregroundStyle(SeamStyle.Pop.fg2)
            }
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

/// The status line beside the stitch: a dot, then "Connected" with the path's icon, or the reason
/// when not connected. Everything comes from the `LinkLook`; the tooltip and VoiceOver get the words.
/// With a `detail`, a click shows the whole reason and what happens next.
struct LinkStatus: View {
    let look: LinkLook
    @State private var showing = false

    var body: some View {
        Button { showing = true } label: {
            HStack(spacing: 6) {
                Circle().fill(look.connected ? SeamStyle.live : SeamStyle.Win.fg3).frame(width: 8, height: 8)
                Text(look.connected ? "Connected" : look.label)
                if let symbol = look.symbol { Image(systemName: symbol) }
                if look.detail != nil { Image(systemName: "info.circle") }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(look.detail == nil)
        .foregroundStyle(SeamStyle.Win.fg2)
        .help(look.sentence)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(look.sentence)
        .accessibilityHint(look.detail ?? "")
        .accessibilityAddTraits(look.detail == nil ? [] : .isButton)
        .popover(isPresented: $showing, arrowEdge: .bottom) {
            VStack(alignment: .leading, spacing: 6) {
                Text(look.sentence).font(.headline)
                if let detail = look.detail { Text(detail).foregroundStyle(SeamStyle.Win.fg2) }
            }
            .fixedSize(horizontal: false, vertical: true)
            .frame(width: 260, alignment: .leading)
            .padding(14)
        }
    }
}

/// The prototypes' switch (`.sw`): a 30 × 17 track, teal when on, with a 14 pt knob that slides on
/// a 350 ms spring and widens while pressed. Locked (disabled), it dims. Still a Toggle for
/// VoiceOver and the keyboard.
struct SeamSwitchStyle: ToggleStyle {
    func makeBody(configuration: Configuration) -> some View {
        Track(configuration: configuration)
    }

    private struct Track: View {
        let configuration: Configuration
        @Environment(\.isEnabled) private var enabled
        @GestureState private var pressed = false

        var body: some View {
            let on = configuration.isOn
            ZStack(alignment: on ? .trailing : .leading) {
                Capsule().fill(on ? SeamStyle.teal : SeamStyle.Win.fill2)
                    .overlay(Capsule().strokeBorder(SeamStyle.Win.line, lineWidth: 0.5))
                    .animation(Motion.ease(0.25), value: on)
                Capsule().fill(Color(light: 0xFFFFFF, dark: 0xFFFFFF))
                    .shadow(color: Color(light: 0x000000, dark: 0x000000, lightAlpha: 0.3, darkAlpha: 0.3), radius: 1, y: 1)
                    .frame(width: pressed ? 17 : 14, height: 14)
                    .padding(1.5)
            }
            .frame(width: 30, height: 17)
            .animation(Motion.css(0.35), value: on)
            .animation(Motion.ease(0.2), value: pressed)
            .opacity(enabled ? 1 : 0.45)
            .contentShape(Capsule())
            .gesture(DragGesture(minimumDistance: 0).updating($pressed) { _, s, _ in s = true }.onEnded { _ in
                if enabled { configuration.isOn.toggle() }
            })
            .accessibilityRepresentation { Toggle(isOn: configuration.$isOn) { configuration.label } }
        }
    }
}
