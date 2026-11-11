// SPDX-License-Identifier: AGPL-3.0-only
import AppKit
import SeamCore
import SeamProtocol
import SeamSession
import SwiftUI

/// Board M06's clipboard column: what crossed either way in the last day.
struct ClipboardPane: View {
    let controller: SeamController
    @Environment(\.accessibilityReduceMotion) private var reduce

    var body: some View {
        if controller.phone == nil {
            EmptyPane(symbol: "iphone", title: "No phone paired", detail: "Pair your phone to share the clipboard.",
                      action: ("Show pairing code", { PairingWindow.show(controller) }))
        } else {
            List {
                Section {
                    if !controller.clipboardSync {
                        LabeledContent {
                            Button("Turn on") { controller.setClipboardSync(true) }
                        } label: {
                            Label("Clipboard sync is off", systemImage: "doc.on.clipboard")
                        }
                        .listRowSeparator(.hidden)
                    }
                    if controller.waiting > 0, !controller.connected {
                        Label {
                            Text("\(Text(waitingWords(count: UInt32(controller.waiting))).fontWeight(.semibold)) · sends when \(phoneName) is back")
                        } icon: {
                            Image(systemName: "clock")
                        }
                        .foregroundStyle(.secondary)
                        .listRowSeparator(.hidden)
                        .transition(.opacity)
                    }
                }
                Section {
                    if controller.clipboard.clips.isEmpty {
                        VStack(spacing: 6) {
                            Image(systemName: "doc.on.clipboard").font(.title2).foregroundStyle(.tertiary)
                            Text("Nothing copied yet").font(.headline)
                            Text(controller.clipboardSync ? "Copies to and from \(phoneName) show here for a day." : "Turn on sync to share copies with \(phoneName).")
                                .foregroundStyle(.secondary).multilineTextAlignment(.center)
                        }
                        .frame(maxWidth: .infinity).padding(.vertical, 24)
                    }
                    ForEach(controller.clipboard.clips) { clip in
                        ClipRow(clip: clip, controller: controller).transition(.arrive(reduce))
                    }
                    // A row, not the section's footer: a list footer on the Mac is one line and cut the sentence
                    Text("Kept in memory for a day, and gone when Seam quits. Copies that password managers and other apps mark as private are never synced.")
                        .font(.callout).foregroundStyle(.secondary)
                        .frame(maxWidth: 480, alignment: .leading) // a readable measure
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, 6)
                        .listRowSeparator(.hidden)
                } header: {
                    Text("Recent copies")
                }
            }
            .listStyle(.inset(alternatesRowBackgrounds: false))
            .animation(Motion.spatial(reduce), value: controller.clipboard.clips.map(\.id))
        }
    }

    private var phoneName: String {
        let name = controller.phone?.name ?? ""
        return name.isEmpty ? "your phone" : name
    }
}

/// One copy, drawn like the popover's: a link or text icon, the copy, where it came from and when,
/// and Open or Copy. Copy says "✓ Copied" in place for 1.3 s (catalogue 8).
private struct ClipRow: View {
    let clip: Clip
    let controller: SeamController
    @State private var copied = false
    @State private var hovering = false
    /// Which action has keyboard focus: either shows them both.
    @FocusState private var focus: String?

    var body: some View {
        let url = clip.openableURL
        HStack(spacing: 12) {
            Image(systemName: url == nil ? "text.alignleft" : "link")
                .font(.system(size: 13, weight: .medium)).foregroundStyle(.secondary)
                .frame(width: 32, height: 32)
                .background(RoundedRectangle(cornerRadius: 7, style: .continuous).fill(.quaternary))
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(clip.preview).lineLimit(2)
                    .alignmentGuide(.listRowSeparatorLeading) { $0[.leading] }
                TimelineView(.periodic(from: .now, by: 30)) { tl in
                    Text("\(clip.fromPhone ? "From \(phoneName)" : "From this Mac") · \(Looks.ago(clip.at, now: tl.date))")
                }
                .font(.callout).foregroundStyle(.secondary)
            }
            Spacer(minLength: 12)
            // The same actions as a notification's: borderless, shown on hover, and in the context menu
            HStack(spacing: 10) {
                if let url { Button("Open") { NSWorkspace.shared.open(url) }.focused($focus, equals: "open") }
                Button(action: copy) {
                    SwapText(copied ? "✓ Copied" : "Copy", pane: true).foregroundStyle(copied ? SeamStyle.ok : SeamStyle.tealInk)
                }
                .accessibilityValue(copied ? "Copied" : "")
                .focused($focus, equals: "copy")
            }
            .rowActions(shown: hovering || copied || focus != nil)
        }
        .padding(.vertical, 6)
        .contentShape(Rectangle())
        .onHover { hovering = $0 }
        .contextMenu {
            Button("Copy", action: copy)
            if let url { Button("Open") { NSWorkspace.shared.open(url) } }
        }
    }

    private func copy() {
        Task { await controller.clipboard.copyAgain(clip.text) }
        copied = true
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.3) { copied = false }
    }

    private var phoneName: String {
        let name = controller.phone?.name ?? ""
        return name.isEmpty ? "your phone" : name
    }
}
