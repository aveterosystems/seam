// SPDX-License-Identifier: AGPL-3.0-only
import SwiftUI

/// Catalogue 7 and 8: a reply field that unfolds in place, takes focus once it has opened, sends on
/// Return, closes on Escape, then says "✓ Sent to your phone" for 1.1 s (the phone sends it, or it waits for the phone). The popover's and the
/// main window's notifications share it.
struct ReplyField: View {
    let prompt: String
    let send: (String) -> Void
    let close: () -> Void
    /// "✓ Sent" has shown for its 1.1 s.
    var sent: () -> Void = {}
    @State private var text = ""
    @State private var done = false
    @FocusState private var focused: Bool

    var body: some View {
        HStack(spacing: 6) {
            if done {
                Text("✓ Sent to your phone").font(.system(size: 11, weight: .semibold)).foregroundStyle(SeamStyle.live)
                    .transition(.opacity)
                Spacer(minLength: 0)
            } else {
                TextField(prompt, text: $text)
                    .textFieldStyle(.plain)
                    .font(.system(size: 12))
                    .padding(.horizontal, 9).padding(.vertical, 5)
                    .background(RoundedRectangle(cornerRadius: 8).fill(SeamStyle.Pop.fill))
                    .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(SeamStyle.teal, lineWidth: 1.5))
                    .focused($focused)
                    .onSubmit(submit)
                Button("Send", action: submit)
                    .buttonStyle(PillButtonStyle())
                    .disabled(trimmed.isEmpty)
            }
        }
        .onExitCommand(perform: close) // from the field or from Send
        .task {
            // Focus moves in once the field has opened
            try? await Task.sleep(for: .seconds(0.25))
            focused = true
        }
        .task(id: done) {
            // Tied to this field: closed sooner, it's cancelled and never touches a later one
            guard done else { return }
            try? await Task.sleep(for: .seconds(1.1))
            if !Task.isCancelled { sent() }
        }
    }

    private var trimmed: String { text.trimmingCharacters(in: .whitespacesAndNewlines) }

    private func submit() {
        guard !trimmed.isEmpty else { return }
        send(trimmed)
        text = ""
        withAnimation(Motion.fade) { done = true }
    }
}
