// SPDX-License-Identifier: AGPL-3.0-only
import AppKit
import SeamCore
import SeamSession
import SwiftUI

// Small pieces rows share across Settings, the main window and the popover.

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
                Text(name.prefix(1).uppercased()).font(.system(size: size * 0.48, weight: .semibold, design: .rounded))
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(RoundedRectangle(cornerRadius: size / 4.5, style: .continuous).fill(monogram.gradient))
                    .foregroundStyle(.white)
            }
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }

    /// Until the icon arrives: a colour of its own per app, the same every time.
    private var monogram: Color {
        let colours: [Color] = [.blue, .green, .orange, .pink, .purple, .red, .teal, .indigo, .mint, .brown]
        let sum = name.unicodeScalars.reduce(0) { $0 &+ Int($1.value) }
        return colours[sum % colours.count]
    }
}

/// While connected, the path as an icon beside "Connected": hover names it, a click explains it.
struct PathIcon: View {
    let look: LinkLook
    @State private var showing = false

    var body: some View {
        if look.connected, let symbol = look.icon?.symbol {
            Button { showing = true } label: { Image(systemName: symbol).contentTransition(.symbolEffect(.replace)) }
                .buttonStyle(.plain)
                .help(look.sentence)
                .accessibilityLabel(look.label)
                .popover(isPresented: $showing, arrowEdge: .bottom) {
                    Text(look.detail ?? look.sentence).font(.system(size: 12)).fixedSize(horizontal: false, vertical: true)
                        .frame(width: 240, alignment: .leading).padding(12)
                }
        }
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
                Text(look.short)
                if let symbol = look.icon?.symbol { Image(systemName: symbol) }
                if look.detail != nil && !look.connected { Image(systemName: "info.circle") }
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


extension Clip {
    /// One line to recognise a copy by, the same everywhere: newlines flattened, links without
    /// their scheme, and only the start of a long one.
    var preview: String {
        let flat = text.prefix(300).split(whereSeparator: \.isNewline).joined(separator: " ")
        let short = openableURL == nil ? flat : flat.replacingOccurrences(of: "https://", with: "", options: .anchored)
        return text.count > 300 ? short + "…" : short
    }
}

/// The phone as Seam draws it everywhere, like a connected device in the Bluetooth menu: the
/// phone symbol on a disc, filled with the accent while connected, quiet grey when not.
struct PhoneDisc: View {
    let connected: Bool
    var size: CGFloat = 36

    var body: some View {
        Image(systemName: "iphone")
            .font(.system(size: size * 0.47, weight: .regular))
            .frame(width: size, height: size)
            .background(Circle().fill(connected ? SeamStyle.teal : SeamStyle.Pop.fill))
            .foregroundStyle(connected ? SeamStyle.onTeal : SeamStyle.Pop.fg2)
            .animation(Motion.ease(0.5), value: connected)
            .accessibilityHidden(true)
    }
}
