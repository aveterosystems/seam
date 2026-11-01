// SPDX-License-Identifier: AGPL-3.0-only
import AppKit
import SeamProtocol
import SeamSession
import SwiftUI

/// A small uppercase heading with its link on the right ("All 3 ›").
private struct SectionHeading: View {
    let title: String
    var link: String?
    var action: () -> Void = {}

    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            Text(title.uppercased()).font(.system(size: 10.5, weight: .bold)).tracking(0.63).foregroundStyle(SeamStyle.Pop.fg3)
                .frame(height: 13)
                .accessibilityAddTraits(.isHeader)
            Spacer()
            if let link {
                Button(link, action: action).buttonStyle(.plain)
                    .font(.system(size: 11, weight: .semibold)).foregroundStyle(SeamStyle.tealInk)
            }
        }
        .padding(EdgeInsets(top: 10, leading: 14, bottom: 4, trailing: 14))
    }
}

/// A row's hover fill (catalogue 12), shared by both lists.
private struct RowFill: View {
    let on: Bool
    var body: some View { RoundedRectangle(cornerRadius: 8).fill(on ? SeamStyle.Pop.fill : .clear) }
}

/// The phone's latest notifications. New ones push in at the top, dismissed ones leave to the right.
struct PopoverNotifications: View {
    let controller: SeamController
    let openMain: (MainWindow.Pane?) -> Void
    let appeared: Bool
    @Environment(\.accessibilityReduceMotion) private var reduce
    /// Keys already on screen when the popover opened: only rows after that pop their icon in.
    @State private var seen: Set<String>?
    /// Replied to here: the row leaves the popover after "✓ Sent" (catalogue 8). The phone's own
    /// list, and the main window, keep it until the phone clears or updates it.
    @State private var replied: Set<String> = []

    var body: some View {
        let showing = controller.phoneNotifications.filter { !replied.contains($0.key) }
        let latest = Array(showing.prefix(3))
        VStack(alignment: .leading, spacing: 0) {
            SectionHeading(title: "Notifications", link: "All \(showing.count) ›") { openMain(.notifications) }
                .rise(3, appeared)
            VStack(alignment: .leading, spacing: 0) {
                if latest.isEmpty {
                    Text("Nothing on your phone right now").font(.system(size: 11)).foregroundStyle(SeamStyle.Pop.fg3)
                        .padding(EdgeInsets(top: 6, leading: 14, bottom: 4, trailing: 14))
                        .transition(.opacity)
                }
                ForEach(latest, id: \.key) { n in
                    NotificationRow(n: n, controller: controller, fresh: seen.map { !$0.contains(n.key) } ?? false,
                                    sent: { replied.insert(n.key) }) { openMain(.notifications) }
                        .transition(.arrive(reduce))
                }
            }
            .rise(4, appeared)
        }
        .animation(Motion.spatial(reduce), value: latest.map(\.key))
        .onAppear { seen = Set(latest.map(\.key)) }
        .onChange(of: latest.map(\.key)) { _, keys in seen?.formUnion(keys) }
    }
}

/// One notification: who and what, and on hover or focus, Reply (in place) and dismiss.
private struct NotificationRow: View {
    let n: Seam_V1_Notification
    let controller: SeamController
    let fresh: Bool
    /// "✓ Sent" has shown: the row can go.
    let sent: () -> Void
    let open: () -> Void
    @State private var hovering = false
    @State private var replying = false
    @State private var reply = ""
    @State private var done = false
    @State private var icon: CGFloat = 1
    @FocusState private var focus: Part?
    @Environment(\.accessibilityReduceMotion) private var reduce

    enum Part { case row, action, dismiss, field }

    var body: some View {
        let actions = hovering || focus != nil || replying
        VStack(spacing: 0) {
            HStack(spacing: 10) {
                Group {
                    AppIcon(image: controller.appIcons[n.appID], size: 24, name: n.appName).clipShape(RoundedRectangle(cornerRadius: 6)).scaleEffect(icon)
                    VStack(alignment: .leading, spacing: 1) {
                        (Text(n.title.isEmpty ? n.appName : n.title).fontWeight(.semibold) + Text(n.text.isEmpty ? "" : " — " + n.text))
                            .font(.system(size: 12)).lineLimit(1).frame(height: 15)
                        TimelineView(.periodic(from: .now, by: 30)) { tl in
                            Text(n.postedAt > 0 ? "\(n.appName) · \(LastSeen.text(Date(timeIntervalSince1970: TimeInterval(n.postedAt) / 1000), now: tl.date, justNow: "now"))" : n.appName)
                        }
                        .font(.system(size: 10.5)).foregroundStyle(SeamStyle.Pop.fg3).frame(height: 12)
                    }
                }
                .allowsHitTesting(false) // a click there opens the row, behind
                .accessibilityHidden(true)
                Spacer(minLength: 0)
                HStack(spacing: 2) {
                    Button(n.canReply ? "Reply" : "Open") { n.canReply ? toggleReply() : open() }
                        .buttonStyle(ActionStyle())
                        .focused($focus, equals: .action)
                    Button("✕") { controller.dismiss(n) }
                        .buttonStyle(ActionStyle(quiet: true))
                        .focused($focus, equals: .dismiss)
                        .help("Dismiss")
                        .accessibilityLabel("Dismiss")
                }
                .opacity(actions ? 1 : 0)
                .offset(x: actions || reduce ? 0 : 6)
                .animation(.timingCurve(0.2, 0.8, 0.2, 1, duration: 0.2), value: actions)
            }
            .padding(.horizontal, 8).padding(.vertical, 7)
            .background {
                Button(action: open) { RowFill(on: hovering) }
                    .buttonStyle(.plain)
                    .focused($focus, equals: .row)
                    .accessibilityLabel("\(n.appName): \(n.title). \(n.text)")
                    .accessibilityHint("Opens Seam's notifications")
            }
            .padding(.horizontal, 6)
            .onHover { h in withAnimation(Motion.ease(0.15)) { hovering = h } }
            if replying { replyField.transition(.unfold(reduce)) }
        }
        .clipped()
        .animation(Motion.spatial(reduce), value: replying)
        .onAppear {
            guard fresh, !reduce else { return }
            icon = 0.3 // catalogue 5: the icon pops in 100 ms behind the row
            withAnimation(Motion.css(0.5).delay(0.1)) { icon = 1 }
        }
    }

    private var replyField: some View {
        HStack(spacing: 6) {
            if done {
                Text("✓ Sent from your phone").font(.system(size: 11, weight: .semibold)).foregroundStyle(SeamStyle.live)
                    .transition(.opacity)
                Spacer(minLength: 0)
            } else {
                TextField("Reply to \(n.appName)", text: $reply)
                    .textFieldStyle(.plain)
                    .font(.system(size: 12))
                    .padding(.horizontal, 9).padding(.vertical, 5)
                    .background(RoundedRectangle(cornerRadius: 8).fill(SeamStyle.Pop.fill))
                    .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(SeamStyle.teal, lineWidth: 1.5))
                    .focused($focus, equals: .field)
                    .onSubmit(send)
                    .onExitCommand { replying = false }
                Button("Send", action: send)
                    .buttonStyle(PillButtonStyle())
                    .disabled(reply.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }
        .padding(EdgeInsets(top: 2, leading: 48, bottom: 8, trailing: 14))
    }

    private func toggleReply() {
        replying.toggle()
        guard replying else { return }
        // Catalogue 7: focus moves in once the field has opened
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.25) { focus = .field }
    }

    private func send() {
        let text = reply.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        controller.act(.with { $0.key = n.key; $0.replyText = text })
        reply = ""
        withAnimation(Motion.fade) { done = true }
        // Catalogue 8: "✓ Sent" for 1.1 s, then the row leaves
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.1, execute: self.sent)
    }
}

/// A row's text action ("Reply", "Copy"): teal words with a tinted fill on hover.
private struct ActionStyle: ButtonStyle {
    var quiet = false

    func makeBody(configuration: Configuration) -> some View {
        Hovered(label: configuration.label, quiet: quiet, pressed: configuration.isPressed)
    }

    private struct Hovered<Label: View>: View {
        let label: Label
        let quiet: Bool
        let pressed: Bool
        @State private var hovering = false

        var body: some View {
            label
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(quiet ? SeamStyle.Pop.fg3 : SeamStyle.tealInk)
                .padding(.horizontal, 6).padding(.vertical, 2)
                .frame(minHeight: 18)
                .background(RoundedRectangle(cornerRadius: 6).fill(SeamStyle.teal.opacity(hovering ? 0.16 : 0)))
                .scaleEffect(pressed ? 0.94 : 1)
                .animation(Motion.springFast, value: pressed)
                .onHover { hovering = $0 }
        }
    }
}

/// The last copies from the phone. A click copies one again (or opens a link), and the row says so.
struct PopoverClips: View {
    let controller: SeamController
    let openMain: (MainWindow.Pane?) -> Void
    let toast: (String) -> Void
    let appeared: Bool
    @Environment(\.accessibilityReduceMotion) private var reduce

    var body: some View {
        let recent = controller.recentFromPhone
        VStack(alignment: .leading, spacing: 0) {
            SectionHeading(title: "Clipboard from phone", link: "History ›") { openMain(.clipboard) }
                .rise(5, appeared)
            VStack(alignment: .leading, spacing: 0) {
                if recent.isEmpty {
                    // Not in the prototype, which always has clips: the empty state in its voice
                    Text("What you copy on your phone shows here").font(.system(size: 11)).foregroundStyle(SeamStyle.Pop.fg3)
                        .padding(EdgeInsets(top: 6, leading: 14, bottom: 4, trailing: 14))
                        .transition(.opacity)
                }
                ForEach(recent) { clip in
                    ClipRow(clip: clip, controller: controller, toast: toast).transition(.arrive(reduce))
                }
            }
            .rise(6, appeared)
        }
        .animation(Motion.spatial(reduce), value: recent.map(\.id))
    }
}

private struct ClipRow: View {
    let clip: Clip
    let controller: SeamController
    let toast: (String) -> Void
    @State private var hovering = false
    @State private var copied = false

    var body: some View {
        let url = clip.openableURL
        Button {
            if let url {
                NSWorkspace.shared.open(url)
                toast("Opened in your browser")
            } else {
                controller.copyAgain(clip.text)
                copied = true
                DispatchQueue.main.asyncAfter(deadline: .now() + 1.3) { copied = false }
            }
        } label: {
            HStack(spacing: 10) {
                Group {
                    if url == nil { Text("¶").font(.system(size: 12)) } else { Image(systemName: "link").font(.system(size: 11, weight: .semibold)) }
                }
                .frame(width: 24, height: 24)
                .background(RoundedRectangle(cornerRadius: 6).fill(SeamStyle.Pop.fill))
                .foregroundStyle(SeamStyle.Pop.fg2)
                VStack(alignment: .leading, spacing: 1) {
                    // Text in quotes, links as they are (the prototype's format)
                    let flat = clip.text.prefix(200).split(whereSeparator: \.isNewline).joined(separator: " ")
                    Text(url == nil ? "\"\(flat)\"" : flat.replacingOccurrences(of: "https://", with: "", options: .anchored))
                        .font(.system(size: 12)).lineLimit(1).frame(height: 15)
                    TimelineView(.periodic(from: .now, by: 30)) { tl in Text(LastSeen.text(clip.at, now: tl.date)) }
                        .font(.system(size: 10.5)).foregroundStyle(SeamStyle.Pop.fg3).frame(height: 12)
                }
                Spacer(minLength: 0)
                SwapText(copied ? "✓ Copied" : url == nil ? "Copy" : "Open")
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(copied ? SeamStyle.live : SeamStyle.tealInk)
                    .frame(minWidth: 44, alignment: .trailing)
                    .animation(Motion.ease(0.2), value: copied)
            }
            .padding(.horizontal, 8).padding(.vertical, 7)
            .background(RowFill(on: hovering))
        }
        .buttonStyle(PressableStyle(scale: 0.98))
        .padding(.horizontal, 6)
        .onHover { h in withAnimation(Motion.ease(0.15)) { hovering = h } }
        .accessibilityLabel(url == nil ? "Copied text: \(clip.text.prefix(200))" : "Link: \(clip.text.prefix(200))")
        .accessibilityValue(copied ? "Copied" : "")
        .accessibilityHint(url == nil ? "Copies it to this Mac's clipboard" : "Opens it in your browser")
    }
}
