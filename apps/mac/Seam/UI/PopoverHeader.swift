// SPDX-License-Identifier: AGPL-3.0-only
import SeamCore
import SeamSession
import SwiftUI

/// The popover's device header: the phone, its link in words, its battery and network. Offline,
/// ⓘ unfolds why underneath (catalogue 7). VoiceOver reads it as one line: "Pixel 9, connected,
/// same Wi‑Fi, battery 82 percent".
struct PopoverHeader: View {
    let controller: SeamController
    let phone: PeerInfo
    /// The header's slot in the opening stagger; the tip under it isn't part of it.
    let rise: (Int, Bool)
    @State private var why = false
    @Environment(\.accessibilityReduceMotion) private var reduce

    var body: some View {
        let look = controller.link
        let battery = controller.status.status.map { PhoneStatusLook($0, connected: look.connected, at: controller.status.statusAt) }
        VStack(alignment: .leading, spacing: 0) {
            TimelineView(.periodic(from: .now, by: 30)) { tl in
                let line = look.statusLine(lastSeen: controller.lastSeen, now: tl.date)
                HStack(spacing: 11) {
                    PhoneOutline()
                        .frame(width: 38, height: 38)
                        .background(RoundedRectangle(cornerRadius: 10).fill(look.connected ? SeamStyle.teal.opacity(0.15) : SeamStyle.Pop.fill))
                        .foregroundStyle(look.connected ? SeamStyle.tealInk : SeamStyle.Pop.fg2)
                        .animation(Motion.ease(0.5), value: look.connected)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(name).font(.system(size: 14, weight: .semibold))
                        HStack(spacing: 5) {
                            StatusDot(on: look.connected)
                            SwapText(line)
                            if look.detail != nil { InfoButton(open: $why, label: "Why", turns: false) }
                        }
                        .font(.system(size: 11.5))
                        .foregroundStyle(SeamStyle.Pop.fg2)
                        .frame(height: 15)
                    }
                    Spacer(minLength: 0)
                    if let battery { batteryColumn(battery) }
                }
                .padding(EdgeInsets(top: 14, leading: 14, bottom: 10, trailing: 14))
                .accessibilityElement(children: .ignore)
                .accessibilityLabel([name, line.replacingOccurrences(of: " · ", with: ", "), battery?.spoken].compactMap { $0 }.joined(separator: ", "))
                .accessibilityAction(named: "Why") { why.toggle() }
            }
            .rise(rise.0, rise.1)
            if why, let detail = look.detail {
                Text(detail)
                    .font(.system(size: 11)).foregroundStyle(SeamStyle.Pop.fg2)
                    .lineSpacing(11 * 0.45 - 2) // line-height 1.45
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 10).padding(.vertical, 8)
                    .background(RoundedRectangle(cornerRadius: 9).fill(SeamStyle.Pop.fill2))
                    .overlay(RoundedRectangle(cornerRadius: 9).strokeBorder(SeamStyle.Pop.line, lineWidth: 0.5))
                    .padding(EdgeInsets(top: 0, leading: 14, bottom: 2, trailing: 14))
                    .transition(.unfold(reduce))
            }
        }
        .clipped()
        .animation(Motion.spatial(reduce), value: why)
        .onChange(of: look.connected) { if look.connected { why = false } }
    }

    private var name: String { phone.name.isEmpty ? "Your phone" : phone.name }

    /// The pill, and under it the network and a moon for Do Not Disturb, or "as of 14:02" offline.
    private func batteryColumn(_ b: PhoneStatusLook) -> some View {
        VStack(alignment: .trailing, spacing: 4) {
            BatteryPill(look: b)
            // "Wi‑Fi · ☾", or "as of 14:02" offline
            HStack(spacing: 0) {
                SwapText((b.meta ?? "") + (b.dnd && b.meta != nil ? " · " : ""))
                if b.dnd { Image(systemName: "moon.fill").foregroundStyle(SeamStyle.glow).transition(.scale.combined(with: .opacity)) }
            }
            .font(.system(size: 10.5))
            .foregroundStyle(SeamStyle.Pop.fg3)
            .frame(height: b.dnd ? 17 : 13) // the prototype's moon makes its line taller
            .animation(Motion.spring, value: b.dnd)
        }
    }
}

/// Offline with copies waiting: how many, and that they go when the phone is back. The clock turns
/// once every 6 s (catalogue 10), and stops under Reduce Motion.
struct QueueBar: View {
    let count: Int
    @Environment(\.accessibilityReduceMotion) private var reduce

    var body: some View {
        HStack(spacing: 8) {
            TimelineView(.animation(paused: reduce)) { tl in
                Image(systemName: "clock").font(.system(size: 12))
                    .rotationEffect(.degrees(reduce ? 0 : tl.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 6) / 6 * 360))
            }
            Text("\(Text(Words.waiting(count)).foregroundStyle(SeamStyle.Pop.fg).fontWeight(.semibold)) · sends when your phone is back")
                .monospacedDigit()
                .contentTransition(.numericText(value: Double(count)))
            Spacer(minLength: 0)
        }
        .font(.system(size: 11.5))
        .foregroundStyle(SeamStyle.Pop.fg2)
        .padding(.horizontal, 10).padding(.vertical, 8)
        .background(RoundedRectangle(cornerRadius: 10).fill(SeamStyle.Pop.fill2))
        .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(SeamStyle.Pop.line, lineWidth: 0.5))
        .padding(EdgeInsets(top: 10, leading: 12, bottom: 0, trailing: 12))
        .animation(Motion.settle, value: count)
        .accessibilityElement(children: .combine)
    }
}
