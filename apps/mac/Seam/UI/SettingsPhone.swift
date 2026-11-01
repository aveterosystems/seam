// SPDX-License-Identifier: AGPL-3.0-only
import SeamCore
import SeamSession
import SwiftUI

/// The phone, by name: a live card, which connections may reach it, and its pairing.
struct PhonePane: View {
    let controller: SeamController
    let shown: Bool
    @State private var showCode = false
    @State private var unpairing = false

    var body: some View {
        PaneScroll {
            if let phone = controller.phone {
                PhoneCard(controller: controller, phone: phone, replay: shown ? 1 : 0).rise(0, shown, pane: true)
            } else {
                SettingsGroup {
                    SettingsRow("No phone is paired with this Mac", "Scan a code with Seam on your Android phone") {
                        Button("Pair your phone") { PairingWindow.show(controller) }.buttonStyle(CapButtonStyle(kind: .primary))
                    }
                }
                .rise(0, shown, pane: true)
            }
            // Which paths may reach the phone. The last one on can't be turned off,
            // and the footnote says why.
            GroupTitle("Connections").rise(1, shown, pane: true)
            SettingsGroup {
                SettingsRow("Same Wi‑Fi", "Straight to your phone on the same network. Nothing leaves it.") {
                    Toggle("Same Wi‑Fi", isOn: Binding(get: { controller.wifiOn }, set: { controller.setWifi($0) })).labelsHidden()
                        .disabled(controller.wifiOn && !controller.torOn)
                }
                SettingsRow("Internet, through Tor", "Reaches your phone anywhere and hides where you are") {
                    Toggle("Internet, through Tor", isOn: Binding(get: { controller.torOn }, set: { controller.setTor($0) })).labelsHidden()
                        .disabled(controller.torOn && !controller.wifiOn)
                }
            }
            .rise(2, shown, pane: true)
            GroupFootnote { SwapText(Words.connectionsFootnote(wifi: controller.wifiOn, tor: controller.torOn), lines: nil, pane: true) }
                .rise(3, shown, pane: true)
            if let phone = controller.phone {
                GroupTitle("Pairing").rise(4, shown, pane: true)
                pairing(phone).rise(5, shown, pane: true)
            }
        }
    }

    private func pairing(_ phone: PeerInfo) -> some View {
        let name = phone.name.isEmpty ? "your phone" : phone.name
        return SettingsGroup {
            if let code = controller.phoneCode() {
                SettingsRow("Verification code", "Matches your phone if nobody came between them") {
                    HStack(spacing: 12) {
                        Text(code).font(.system(size: 13, design: .monospaced)).tracking(1.56)
                            .blur(radius: showCode ? 0 : 5)
                            .animation(Motion.ease(0.4), value: showCode)
                            .onTapGesture { showCode.toggle() }
                            .accessibilityLabel(showCode ? code : "Hidden")
                        Button { showCode.toggle() } label: { SwapText(showCode ? "Hide" : "Show", pane: true) }
                            .buttonStyle(CapButtonStyle())
                    }
                }
            }
            // Only phones paired since Seam kept the date have one
            SettingsRow(controller.pairedAt.map { "Paired \($0.formatted(date: .long, time: .omitted))" } ?? "Paired with this Mac", nil) {
                // The prototype renders this grey at 11.5 pt (its row-detail style wins over its danger class)
                Button { unpairing = true } label: { Text("Unpair").font(.system(size: 11.5)).foregroundStyle(SeamStyle.Win.fg2) }
                    .buttonStyle(CapButtonStyle())
            }
        }
        .alert("Unpair \(name)?", isPresented: $unpairing) {
            Button("Cancel", role: .cancel) {}
            Button("Unpair", role: .destructive) { controller.unpairPhone() }
        } message: {
            Text("Both devices forget each other's keys. Nothing more syncs until you pair again.")
        }
    }
}

/// The phone's live card (board M03): a phone with its battery filling it, the link in words and
/// as the stitch, and its battery, network and Do Not Disturb.
private struct PhoneCard: View {
    let controller: SeamController
    let phone: PeerInfo
    let replay: Int

    var body: some View {
        let look = controller.link
        let status = controller.status.status.map { PhoneStatusLook($0, connected: look.connected, at: controller.status.statusAt) }
        HStack(spacing: 16) {
            PhoneGlyph(level: status?.percent, low: status?.low == true, on: look.connected)
            VStack(alignment: .leading, spacing: 0) {
                Text(phone.name.isEmpty ? "Your phone" : phone.name).font(.system(size: 19, weight: .bold)).foregroundStyle(SeamStyle.Win.fg)
                TimelineView(.periodic(from: .now, by: 30)) { tl in
                    HStack(spacing: 6) {
                        StatusDot(on: look.connected)
                        SwapText(look.heroLine(lastSeen: controller.lastSeen, now: tl.date), pane: true)
                    }
                }
                .font(.system(size: 12.5)).foregroundStyle(SeamStyle.Win.fg2)
                .frame(height: 17).padding(.top, 3)
                Stitch(line: look.line, replay: replay).padding(.top, 10).padding(.bottom, 9)
                HStack(spacing: 16) {
                    stat("Battery") {
                        Text(status.map { "\($0.percent)%" } ?? "—").contentTransition(.numericText(value: Double(status?.percent ?? 0)))
                        if status?.charging == true { Image(systemName: "bolt.fill").foregroundStyle(SeamStyle.glow).transition(.scale.combined(with: .opacity)) }
                    }
                    stat("Network") { SwapText(status?.network ?? "—", pane: true) }
                    if status?.dnd == true {
                        HStack(spacing: 6) { Image(systemName: "moon.fill").foregroundStyle(SeamStyle.glow); Text("DND").fontWeight(.semibold).foregroundStyle(SeamStyle.Win.fg) }
                            .transition(.opacity)
                            .accessibilityLabel("Do Not Disturb is on")
                    }
                }
                .font(.system(size: 12)).foregroundStyle(SeamStyle.Win.fg2)
                .animation(Motion.count, value: status?.percent)
                .animation(Motion.spring, value: status)
            }
        }
        .padding(18)
        .background(RoundedRectangle(cornerRadius: 14).fill(look.connected
            ? AnyShapeStyle(LinearGradient(colors: [SeamStyle.teal.opacity(0.16), SeamStyle.teal.opacity(0.04)], startPoint: .topLeading, endPoint: .bottomTrailing))
            : AnyShapeStyle(SeamStyle.Win.group)))
        .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(look.connected ? SeamStyle.teal.opacity(0.3) : SeamStyle.Win.line, lineWidth: 0.5))
        .animation(Motion.ease(0.5), value: look.connected)
        .padding(.bottom, 16)
        .accessibilityElement(children: .combine)
    }

    private func stat<V: View>(_ name: String, @ViewBuilder value: () -> V) -> some View {
        HStack(spacing: 4) {
            Text(name)
            HStack(spacing: 2) { value() }.fontWeight(.semibold).monospacedDigit().foregroundStyle(SeamStyle.Win.fg)
        }
    }
}

/// A phone outline whose inside fills to the battery level; amber when low, grey when offline.
private struct PhoneGlyph: View {
    let level: Int?
    let low: Bool
    let on: Bool

    var body: some View {
        RoundedRectangle(cornerRadius: 11)
            .strokeBorder(on ? SeamStyle.tealInk : SeamStyle.Win.fg3, lineWidth: 2)
            .frame(width: 46, height: 70)
            .overlay(alignment: .bottom) {
                RoundedRectangle(cornerRadius: 6)
                    .fill(low ? SeamStyle.warn.opacity(0.4) : SeamStyle.teal.opacity(0.3))
                    .frame(height: 62 * CGFloat(level ?? 0) / 100)
                    .padding(4)
                    .frame(height: 70, alignment: .bottom)
            }
            .overlay(alignment: .top) { Capsule().fill(SeamStyle.Win.fg.opacity(0.5)).frame(width: 12, height: 3).padding(.top, 5) }
            .animation(Motion.settle, value: level)
            .animation(Motion.fade, value: low)
            .animation(Motion.ease(0.5), value: on)
            .accessibilityHidden(true)
    }
}
