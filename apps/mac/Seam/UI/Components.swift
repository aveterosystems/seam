// SPDX-License-Identifier: AGPL-3.0-only
import AppKit
import SeamSession
import SwiftUI

// Seam's shared building blocks (board M01 and M03). Screens compose these; a new control extends
// one of them rather than drawing its own copy.

/// A Control Centre-style round module: a symbol in a circle, filled teal when on, and a label.
struct ModuleButton: View {
    let title: String
    let symbol: String
    let on: Bool
    /// What VoiceOver reads as the value; "On" or "Off" when nil.
    var value: String?
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 6) {
                Image(systemName: symbol)
                    .font(.system(size: 17))
                    .frame(width: 44, height: 44)
                    .background(Circle().fill(on ? SeamStyle.teal : Color.primary.opacity(0.08)))
                    .foregroundStyle(on ? Color.white : Color.primary)
                Text(title).font(.system(size: 11, weight: .medium)).multilineTextAlignment(.center)
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel(title)
        .accessibilityValue(value ?? (on ? "On" : "Off"))
    }
}

/// A group of rows on a faint fill, with hairlines between them (the board's `.group`). Each row
/// draws a hairline above itself; the group clips the first one off.
struct SettingsGroup<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        VStack(spacing: 0) { content }
            .padding(.top, -1)
            .background(Color.primary.opacity(0.045))
            .clipShape(RoundedRectangle(cornerRadius: 12))
    }
}

/// One row: optional leading content (an icon, a checkbox), a title with an optional badge, an
/// optional line of explanation, and what goes on the right.
struct SettingsRow<Leading: View, Trailing: View>: View {
    let title: String
    let detail: String?
    var badge: String?
    @ViewBuilder let leading: Leading
    @ViewBuilder let trailing: Trailing

    init(_ title: String, _ detail: String?, badge: String? = nil, @ViewBuilder leading: () -> Leading, @ViewBuilder trailing: () -> Trailing) {
        self.title = title
        self.detail = detail
        self.badge = badge
        self.leading = leading()
        self.trailing = trailing()
    }

    var body: some View {
        HStack(spacing: 12) {
            leading
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 8) {
                    Text(title)
                    if let badge { Badge(text: badge) }
                }
                if let detail { Text(detail).font(.system(size: 12)).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true) }
            }
            Spacer(minLength: 12)
            trailing
        }
        .padding(.horizontal, 14).padding(.vertical, 9)
        .frame(minHeight: 42)
        .overlay(alignment: .top) { Divider().padding(.leading, 14) }
        .accessibilityElement(children: .contain)
    }
}

extension SettingsRow where Leading == EmptyView {
    init(_ title: String, _ detail: String?, badge: String? = nil, @ViewBuilder trailing: () -> Trailing) {
        self.init(title, detail, badge: badge, leading: { EmptyView() }, trailing: trailing)
    }
}

/// A small teal pill beside a title ("Reply on Mac").
struct Badge: View {
    let text: String

    var body: some View {
        Text(text).font(.system(size: 11, weight: .medium))
            .padding(.horizontal, 7).padding(.vertical, 2)
            .background(SeamStyle.teal.opacity(0.15), in: Capsule())
            .foregroundStyle(SeamStyle.teal)
    }
}

/// A phone app's icon at row size, or a placeholder until the phone has sent it.
struct AppIcon: View {
    let image: NSImage?
    var size: CGFloat = 24

    var body: some View {
        Group {
            if let image { Image(nsImage: image).resizable() } else { Image(systemName: "app.dashed").resizable().foregroundStyle(.tertiary) }
        }
        .frame(width: size, height: size)
    }
}

/// The status line beside the stitch: a dot, then "Connected" with the path's icon, or the reason
/// when not connected. Everything comes from the `LinkLook`; the tooltip and VoiceOver get the words.
struct LinkStatus: View {
    let look: LinkLook

    var body: some View {
        HStack(spacing: 6) {
            Circle().fill(look.connected ? Color.green : Color.secondary.opacity(0.5)).frame(width: 8, height: 8)
            Text(look.connected ? "Connected" : look.label)
            if let symbol = look.symbol { Image(systemName: symbol) }
        }
        .foregroundStyle(.secondary)
        .help(look.sentence)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(look.sentence)
    }
}
