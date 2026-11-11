// SPDX-License-Identifier: AGPL-3.0-only
import SeamCore
import SeamSession
import SwiftUI

/// The popover's device header: the phone, its link in words, its battery and network. Offline,
/// ⓘ unfolds why underneath (catalogue 7). Connected, clicking the battery unfolds its power and
/// time ("Using 1.2 W · about 7 hr left"), asked for afresh every 10 s while it's on screen. VoiceOver reads it as one line: "Pixel 9, connected,
/// same Wi‑Fi, battery 82 percent".
struct PopoverHeader: View {
    let controller: SeamController
    let phone: Phone
    /// The header's slot in the opening stagger; the tip under it isn't part of it.
    let rise: (Int, Bool)
    /// Changes when the popover opens, to draw the stitch again.
    let replay: Int
    @State private var why = false
    @State private var batteryHover = false
    #if DEBUG
    /// SEAM_DEMO_DETAILS: open with the battery's details showing, to capture them.
    @State private var power = ProcessInfo.processInfo.environment["SEAM_DEMO_DETAILS"] != nil
    #else
    @State private var power = false
    #endif
    @State private var visible = true
    @Environment(\.accessibilityReduceMotion) private var reduce

    var body: some View {
        let look = controller.link
        let battery = controller.status.status.flatMap { Looks.phone($0, link: look, at: controller.status.statusAt) }
        VStack(alignment: .leading, spacing: 0) {
            TimelineView(.periodic(from: .now, by: 30)) { tl in
                let line = controller.link(at: tl.date).statusLine
                HStack(spacing: 10) {
                    PhoneDisc(connected: look.connected)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(name).font(.system(size: 13, weight: .semibold))
                        HStack(spacing: 8) { // room for the dot's ring
                            StatusDot(on: look.connected)
                            SwapText(line)
                            if look.connected, let symbol = look.icon?.symbol {
                                // The path as an icon; clicking it says which path, in words
                                Button { why.toggle() } label: { Image(systemName: symbol).contentTransition(.symbolEffect(.replace)) }
                                    .buttonStyle(.plain).help(look.sentence)
                            } else if look.detail != nil {
                                InfoButton(open: $why, label: "Why", turns: false)
                            }
                        }
                        .font(.system(size: 11))
                        .foregroundStyle(SeamStyle.Pop.fg2)
                        .frame(height: 15)
                    }
                    Spacer(minLength: 0)
                    if let battery { batteryColumn(battery, look.connected) }
                }
                .padding(EdgeInsets(top: 12, leading: 14, bottom: 10, trailing: 12))
                .accessibilityElement(children: .ignore)
                .accessibilityLabel([name, (look.connected ? look.sentence : line).replacingOccurrences(of: " · ", with: ", "), battery?.spoken].compactMap { $0 }.joined(separator: ", "))
                .accessibilityAction(named: "Why") { why.toggle() }
                .accessibilityAction(named: "Battery details") { if look.connected { power.toggle() } }
            }
            .rise(rise.0, rise.1)
            // The stitch stays under the phone it belongs to; what unfolds goes below it
            Stitch(line: look.line, replay: replay).padding(.horizontal, 14).padding(.bottom, why || power ? 10 : 0).rise(1, rise.1)
            if why, let detail = look.detail {
                unfolded(Text(detail).font(.system(size: 11)).foregroundStyle(SeamStyle.Pop.fg2).lineSpacing(11 * 0.45 - 2))
            }
            if power, look.connected, let battery {
                unfolded(BatteryDetails(look: battery))
            }
        }
        .clipped()
        .background(OnScreen(visible: $visible))
        .animation(Motion.spatial(reduce), value: why)
        .animation(Motion.spatial(reduce), value: power && look.connected)
        .onChange(of: look.connected) { if look.connected { why = false } else { power = false } }
        .onChange(of: why) { if why { power = false } }
        .onChange(of: power) { if power { why = false } }
        .task(id: power && visible && look.connected) {
            // Only while the details are open and on screen: the closed popover asks for nothing
            guard power && visible && look.connected else { return }
            while !Task.isCancelled {
                controller.askPhoneStatus()
                try? await Task.sleep(for: .seconds(10))
            }
        }
    }

    /// A box that unfolds under the stitch (catalogue 7), drawn like the control module below it:
    /// why it's offline, or the battery's details.
    private func unfolded(_ content: some View) -> some View {
        content
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 12).padding(.vertical, 10)
            .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(SeamStyle.Pop.module))
            .padding(.horizontal, 12)
            .transition(.unfold(reduce))
    }

    private var name: String { phone.name.isEmpty ? "Your phone" : phone.name }

    /// The pill, and under it the network and a moon for Do Not Disturb, or "as of 14:02" offline.
    /// Connected, clicking the pill unfolds its power and time.
    private func batteryColumn(_ b: DeviceStatusLook, _ connected: Bool) -> some View {
        VStack(alignment: .trailing, spacing: 4) {
            // Offline it can't open, but isn't disabled: that would fade a low battery's warning
            Button { if connected { power.toggle() } } label: {
                HStack(spacing: 2) {
                    BatteryPill(look: b)
                    // A disclosure, as Apple's menus show one, so the battery reads as something to open
                    if connected {
                        Image(systemName: "chevron.down").font(.system(size: 9, weight: .bold)).foregroundStyle(power ? SeamStyle.tealInk : SeamStyle.Pop.fg2)
                            .rotationEffect(.degrees(power ? 180 : 0))
                            .animation(Motion.springFast, value: power)
                            .padding(.trailing, 6)
                    }
                }
                // A faint capsule at rest, firmer on hover: it reads as something to open
                // The same capsule in every state, so the battery never jumps; firmer on hover
                .background(Capsule().fill(power && connected ? SeamStyle.teal.opacity(0.15) : batteryHover && connected ? SeamStyle.Pop.fill : SeamStyle.Pop.fill2))
                .animation(Motion.ease(0.2), value: power)
            }
                .buttonStyle(.plain)
                .onHover { h in withAnimation(Motion.ease(0.15)) { batteryHover = h } }
                .allowsHitTesting(connected)
                .help([connected ? "Battery details" : nil, b.network.map { "Phone on \($0)" }].compactMap { $0 }.joined(separator: " · "))
                .accessibilityHint(connected ? "Shows the battery's charge rate and time" : "")
            // "Do Not Disturb ☾", or "as of 14:02" offline. The phone's network is in the pill's tooltip:
            // here it read as the path, which the icon by "Connected" already shows
            HStack(spacing: 3) {
                // The icon leads, as everywhere else; low is said in words too, not only in colour
                if b.dnd && !b.low && !b.stale { Image(systemName: "moon.fill").font(.system(size: 9)).foregroundStyle(SeamStyle.Pop.fg2).transition(.scale.combined(with: .opacity)) }
                if b.low { Text("Low").foregroundStyle(SeamStyle.warn).fontWeight(.medium) }
                SwapText(b.low ? (b.stale ? "· \(b.meta ?? "")" : "battery") : b.stale ? (b.meta ?? "") : b.dnd ? "Do Not Disturb" : "")
                    .foregroundStyle(SeamStyle.Pop.fg2)
            }
            .font(.system(size: 11))
            .foregroundStyle(SeamStyle.Pop.fg2)
            .frame(height: 14)
            .animation(Motion.spring, value: b.dnd)
        }
    }
}

/// Offline with copies waiting: how many, and that they go when the phone is back. The clock turns
/// once every 6 s (catalogue 10), and stops under Reduce Motion.
struct QueueBar: View {
    let count: Int
    var phone = "your phone"
    @Environment(\.accessibilityReduceMotion) private var reduce
    @State private var visible = true

    var body: some View {
        HStack(spacing: 8) {
            TimelineView(.animation(paused: reduce || !visible)) { tl in
                Image(systemName: "clock").font(.system(size: 12))
                    .rotationEffect(.degrees(reduce ? 0 : tl.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 6) / 6 * 360))
            }
            Text("\(Text(waitingWords(count: UInt32(count))).foregroundStyle(SeamStyle.Pop.fg).fontWeight(.semibold)) · sends when \(phone) is back")
                .fixedSize(horizontal: false, vertical: true)
                .monospacedDigit()
                .contentTransition(.numericText(value: Double(count)))
            Spacer(minLength: 0)
        }
        .font(.system(size: 11.5))
        .foregroundStyle(SeamStyle.Pop.fg2)
        .padding(.horizontal, 10).padding(.vertical, 8)
        .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(SeamStyle.Pop.module))
        .padding(EdgeInsets(top: 10, leading: 14, bottom: 0, trailing: 14))
        .background(OnScreen(visible: $visible))
        .animation(Motion.settle, value: count)
        .accessibilityElement(children: .combine)
    }
}
