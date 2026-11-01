// SPDX-License-Identifier: AGPL-3.0-only
import AppKit
import SwiftUI

/// A window laid out like board M03 (docs/design/prototypes/mac-settings.html): a floating sidebar
/// panel inset 8 pt with the traffic lights and search at its top, a 52 pt title bar with the
/// title on the left, and the pane scrolling below it, never under it. Settings and the main
/// window both use it.
struct SidebarWindow<Side: View, Toolbar: View, Content: View>: View {
    let title: String
    /// Search at the top of the sidebar; nil for none.
    var search: Binding<String>?
    /// The whole window's size when it's fixed (Settings); nil for a resizable one.
    var size: CGSize?
    @ViewBuilder let sidebar: Side
    /// Controls on the title's line, on the right (board M05's Clear all).
    @ViewBuilder let toolbar: Toolbar
    @ViewBuilder let content: Content

    var body: some View {
        HStack(spacing: 0) {
            VStack(alignment: .leading, spacing: 1) {
                Spacer().frame(height: 26) // the traffic lights' row (TrafficLights puts them here)
                if let search { SidebarSearch(text: search).padding(.bottom, 9) }
                sidebar
            }
            .padding(EdgeInsets(top: 10, leading: 8, bottom: 10, trailing: 8))
            .frame(width: 196)
            .frame(maxHeight: .infinity, alignment: .top)
            .background(RoundedRectangle(cornerRadius: 14).fill(SeamStyle.Win.side))
            .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(SeamStyle.Win.line, lineWidth: 0.5))
            .padding(EdgeInsets(top: 8, leading: 8, bottom: 8, trailing: 0))
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 10) {
                    SwapText(title, pane: true).font(.system(size: 15, weight: .bold))
                    Spacer(minLength: 12)
                    toolbar
                }
                .frame(height: 52)
                .padding(.horizontal, 22)
                content
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                    .clipped() // the pane scrolls below the title bar, never under it
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        }
        .background(SeamStyle.Win.background)
        .background(TrafficLights(size: size))
        .tint(SeamStyle.teal)
        .toggleStyle(SeamSwitchStyle())
        .ignoresSafeArea()
    }
}

extension SidebarWindow where Toolbar == EmptyView {
    init(title: String, search: Binding<String>? = nil, size: CGSize? = nil, @ViewBuilder sidebar: () -> Side, @ViewBuilder content: () -> Content) {
        self.init(title: title, search: search, size: size, sidebar: sidebar, toolbar: { EmptyView() }, content: content)
    }
}

/// The sidebar's search field: a magnifier on a faint fill.
private struct SidebarSearch: View {
    @Binding var text: String

    var body: some View {
        HStack(spacing: 6) {
            Image(systemName: "magnifyingglass").font(.system(size: 11, weight: .medium)).foregroundStyle(SeamStyle.Win.fg3)
            TextField("Search", text: $text, prompt: Text("Search").foregroundStyle(SeamStyle.Win.fg3))
                .textFieldStyle(.plain).font(.system(size: 12.5)).labelsHidden()
        }
        .padding(.horizontal, 8).padding(.vertical, 5)
        .background(RoundedRectangle(cornerRadius: 8).fill(SeamStyle.Win.fill))
    }
}

/// A sidebar entry: an icon, a title, and a count or a status dot. Selected, it fills teal.
struct SidebarItem<Icon: View>: View {
    let title: String
    let selected: Bool
    var count: Int = 0
    /// A status dot instead of a count: green when on.
    var dot: Bool?
    /// Search is on and this entry doesn't match: it fades.
    var miss = false
    @ViewBuilder let icon: Icon
    let action: () -> Void
    @State private var hovering = false

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                icon
                Text(title).lineLimit(1)
                Spacer(minLength: 0)
                if let dot {
                    Circle().fill(selected ? SeamStyle.onTeal : dot ? SeamStyle.ok : SeamStyle.Win.fg3).frame(width: 6, height: 6)
                        .animation(Motion.ease(0.4), value: dot)
                        .accessibilityLabel(dot ? "Connected" : "Not connected")
                } else if count > 0 {
                    Text("\(count)").font(.system(size: 11)).monospacedDigit().opacity(0.7)
                }
            }
            .font(.system(size: 13))
            .foregroundStyle(selected ? SeamStyle.onTeal : SeamStyle.Win.fg)
            .padding(.horizontal, 7).padding(.vertical, 5)
            .background(RoundedRectangle(cornerRadius: 8).fill(selected ? SeamStyle.teal : hovering ? SeamStyle.Win.fill : .clear))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .opacity(miss ? 0.32 : 1)
        .animation(Motion.ease(0.3), value: miss)
        .onHover { h in withAnimation(Motion.ease(0.15)) { hovering = h } }
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

extension SidebarItem where Icon == SidebarSymbol {
    /// The main window's entries: a teal symbol.
    init(title: String, symbol: String, selected: Bool, count: Int = 0, action: @escaping () -> Void) {
        self.init(title: title, selected: selected, count: count, icon: { SidebarSymbol(symbol: symbol) }, action: action)
    }
}

struct SidebarSymbol: View {
    let symbol: String
    var body: some View { Image(systemName: symbol).foregroundStyle(SeamStyle.teal).frame(width: 20) }
}

/// A small heading between sidebar entries.
struct SidebarSection: View {
    let title: String
    init(_ title: String) { self.title = title }

    var body: some View {
        Text(title).font(.system(size: 11, weight: .semibold)).foregroundStyle(SeamStyle.Win.fg3)
            .padding(EdgeInsets(top: 12, leading: 8, bottom: 4, trailing: 8))
    }
}

/// Puts the window's close, minimise and zoom buttons where the prototype draws them: inside the
/// sidebar panel, centred 26 pt from the top, 20 pt apart, the first centred 26 pt in.
/// shortcut: AppKit lays the title bar out again on some changes (key, resize, full screen), so this
/// re-applies on each; if a macOS release moves them anyway, they fall back to the system's place.
private struct TrafficLights: NSViewRepresentable {
    let size: CGSize?

    func makeNSView(context: Context) -> NSView { Placer(size: size) }
    func updateNSView(_ view: NSView, context: Context) { (view as? Placer)?.place() }

    final class Placer: NSView {
        private var observers: [NSObjectProtocol] = []
        let size: CGSize?

        init(size: CGSize?) {
            self.size = size
            super.init(frame: .zero)
        }

        required init?(coder: NSCoder) { nil }

        override func viewDidMoveToWindow() {
            super.viewDidMoveToWindow()
            observers.forEach(NotificationCenter.default.removeObserver)
            observers = []
            guard let window else { return }
            for name in [NSWindow.didBecomeKeyNotification, NSWindow.didResignKeyNotification, NSWindow.didResizeNotification, NSWindow.didExitFullScreenNotification] {
                observers.append(NotificationCenter.default.addObserver(forName: name, object: window, queue: .main) { [weak self] _ in
                    MainActor.assumeIsolated { self?.place() }
                })
            }
            DispatchQueue.main.async { self.place() }
        }

        func place() {
            guard let window, !window.styleMask.contains(.fullScreen) else { return }
            // An empty unified toolbar makes the title bar 52 pt, so the buttons sit (and click) at 26 pt
            if window.toolbar == nil {
                let frame = window.frame
                window.toolbar = NSToolbar()
                window.toolbarStyle = .unified
                window.titleVisibility = .hidden
                window.titlebarAppearsTransparent = true
                window.setFrame(frame, display: true) // the toolbar overlays the content; the window keeps its size
            }
            // Settings is a fixed window, exactly the prototype's size overall (title bar included)
            if let size, window.frame.size != size {
                var f = window.frame
                f.origin.y += f.height - size.height
                f.size = size
                window.setFrame(f, display: true)
                window.styleMask.remove(.resizable)
            }
            let kinds: [NSWindow.ButtonType] = [.closeButton, .miniaturizeButton, .zoomButton]
            for (i, kind) in kinds.enumerated() {
                guard let button = window.standardWindowButton(kind), let bar = button.superview else { continue }
                let size = button.frame.size
                let centre = CGPoint(x: 26 + 20 * CGFloat(i), y: 26)
                // The title bar view isn't flipped: measure down from its top, which is the window's
                let fromTop = bar.isFlipped ? centre.y - size.height / 2 : bar.bounds.height - centre.y - size.height / 2
                button.setFrameOrigin(CGPoint(x: centre.x - size.width / 2, y: fromTop))
            }
        }
    }
}
