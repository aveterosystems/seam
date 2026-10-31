// SPDX-License-Identifier: AGPL-3.0-only
import SwiftUI

/// A window laid out like board M03: a floating sidebar, a bold title on the left, and the pane
/// below it. Settings and the main window both use it.
struct SidebarWindow<Side: View, Toolbar: View, Content: View>: View {
    let title: String
    @ViewBuilder let sidebar: Side
    /// Controls on the title's line, on the right (board M05's Clear all).
    @ViewBuilder let toolbar: Toolbar
    @ViewBuilder let content: Content

    var body: some View {
        HStack(spacing: 0) {
            VStack(alignment: .leading, spacing: 1) {
                Spacer().frame(height: 30) // room for the traffic lights
                sidebar
            }
            .padding(8)
            .frame(width: 220)
            .frame(maxHeight: .infinity, alignment: .top)
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14))
            .overlay(RoundedRectangle(cornerRadius: 14).stroke(Color.primary.opacity(0.09), lineWidth: 0.5))
            .padding(EdgeInsets(top: 8, leading: 8, bottom: 8, trailing: 0))
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 8) {
                    Text(title).font(.system(size: 15, weight: .bold))
                    Spacer(minLength: 12)
                    toolbar
                }
                .frame(height: 52)
                .padding(.horizontal, 24)
                content
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        }
        .tint(SeamStyle.teal)
        .toggleStyle(.switch)
        .ignoresSafeArea()
    }
}

extension SidebarWindow where Toolbar == EmptyView {
    init(title: String, @ViewBuilder sidebar: () -> Side, @ViewBuilder content: () -> Content) {
        self.init(title: title, sidebar: sidebar, toolbar: { EmptyView() }, content: content)
    }
}

/// A sidebar entry: a teal symbol and a title, highlighted when selected.
struct SidebarItem: View {
    let title: String
    let symbol: String
    let selected: Bool
    var count: Int = 0
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                Image(systemName: symbol).foregroundStyle(SeamStyle.teal).frame(width: 18)
                Text(title).lineLimit(1)
                Spacer(minLength: 0)
                if count > 0 { Text("\(count)").font(.system(size: 11)).monospacedDigit().foregroundStyle(.secondary) }
            }
            .padding(.horizontal, 8)
            .frame(height: 28)
            .background(RoundedRectangle(cornerRadius: 8).fill(selected ? Color.primary.opacity(0.08) : .clear))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

/// A small heading between sidebar entries.
struct SidebarSection: View {
    let title: String
    init(_ title: String) { self.title = title }

    var body: some View {
        Text(title).font(.system(size: 11, weight: .semibold)).foregroundStyle(.tertiary)
            .padding(EdgeInsets(top: 12, leading: 8, bottom: 4, trailing: 8))
    }
}
