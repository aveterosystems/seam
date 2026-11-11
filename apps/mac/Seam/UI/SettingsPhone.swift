// SPDX-License-Identifier: AGPL-3.0-only
import SeamCore
import SeamSession
import SwiftUI

/// The phone, by name: a live header like a device's in System Settings, which connections may
/// reach it, and its pairing.
struct PhonePane: View {
    let controller: SeamController
    @State private var showCode = false
    @State private var unpairing = false

    var body: some View {
        Form {
            if let phone = controller.phone {
                Section { PhoneHeader(controller: controller, phone: phone) }
            } else {
                Section {
                    LabeledContent {
                        Button("Show pairing code") { PairingWindow.show(controller) }.buttonStyle(.borderedProminent)
                    } label: {
                        RowLabel("No phone is paired with this Mac", "Scan a code with Seam on your Android phone")
                    }
                }
            }
            // Which paths may reach the phone. The last one on can't be turned off, and the footnote says why
            Section {
                Toggle(isOn: Binding(get: { controller.wifiOn }, set: { controller.setWifi($0) })) {
                    RowLabel("Same Wi‑Fi", "Straight to your phone on the same network. Nothing leaves it.")
                }
                .disabled(controller.wifiOn && !controller.torOn)
                Toggle(isOn: Binding(get: { controller.torOn }, set: { controller.setTor($0) })) {
                    RowLabel("Internet, through Tor", "Reaches your phone anywhere and hides where you are")
                }
                .disabled(controller.torOn && !controller.wifiOn)
                // For when you know it's stuck; a second press waits for the first (SR-55-03)
                if controller.phone != nil {
                    LabeledContent {
                        Button("Reconnect") { controller.reconnect() }.disabled(controller.reconnecting != nil)
                    } label: {
                        RowLabel("Connection stuck?", controller.reconnecting == nil ? "Seam tries every allowed path again, now" : controller.link.label)
                    }
                }
            } header: {
                Text("Connections")
            } footer: {
                Text(Looks.connectionsFootnote(wifi: controller.wifiOn, tor: controller.torOn))
            }
            if let phone = controller.phone { pairing(phone) }
        }
        .formStyle(.grouped)
    }

    private func pairing(_ phone: Phone) -> some View {
        let name = phone.name.isEmpty ? "your phone" : phone.name
        return Section("Pairing") {
            if let code = controller.phoneCode() {
                LabeledContent {
                    HStack(spacing: 10) {
                        Text(code).font(.system(.body, design: .monospaced)).tracking(1.5)
                            .blur(radius: showCode ? 0 : 5)
                            .animation(Motion.ease(0.4), value: showCode)
                            .accessibilityLabel(showCode ? code : "Hidden")
                        Button(showCode ? "Hide" : "Show") { showCode.toggle() }
                    }
                } label: {
                    RowLabel("Verification code", "Should match the code on your phone")
                }
            }
            // Only phones paired since Seam kept the date have one
            LabeledContent("Paired", value: controller.pairedAt.map { $0.formatted(date: .long, time: .omitted) } ?? "With this Mac")
            LabeledContent {
                Button("Unpair", role: .destructive) { unpairing = true }
            } label: {
                RowLabel("Unpair \(name)", "Both devices forget each other's keys")
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

/// The phone's header, like a device's in System Settings: the phone, its name, the link in words
/// and as the stitch, then its battery (the popover's pill), network, storage and Do Not Disturb.
private struct PhoneHeader: View {
    let controller: SeamController
    let phone: Phone

    var body: some View {
        let look = controller.link
        let status = controller.status.status.flatMap { Looks.phone($0, link: look, at: controller.status.statusAt) }
        HStack(alignment: .center, spacing: 16) {
            PhoneDisc(connected: look.connected, size: 56)
            VStack(alignment: .leading, spacing: 0) {
                Text(phone.name.isEmpty ? "Your phone" : phone.name).font(.title2.weight(.semibold))
                TimelineView(.periodic(from: .now, by: 30)) { tl in
                    HStack(spacing: 8) {
                        StatusDot(on: look.connected)
                        SwapText(controller.link(at: tl.date).statusLine, pane: true)
                        PathIcon(look: look).imageScale(.small)
                    }
                }
                .font(.callout).foregroundStyle(.secondary)
                .frame(height: 17).padding(.top, 2)
                Stitch(line: look.line).padding(.vertical, 10)
                HStack(spacing: 14) {
                    if let status {
                        stat(status.batteryIcon?.symbol ?? "battery.100percent", help: status.batteryDetail ?? status.spoken) {
                            // Offline it's the last reading, and says when it's from
                            Text(status.stale ? "\(status.battery ?? "\(status.percent)%") · \(status.meta ?? "")" : status.battery ?? "\(status.percent)%")
                                .contentTransition(.numericText(value: Double(status.percent)))
                        }
                        .foregroundStyle(status.low ? AnyShapeStyle(SeamStyle.warn) : AnyShapeStyle(.secondary))
                        .animation(Motion.count, value: status.percent)
                    }
                    if let icon = status?.networkIcon, let network = status?.network {
                        stat(icon.symbol, value: icon.variableValue, help: network) { Text(network) }
                    }
                    if let storage = status?.storage, let detail = status?.storageDetail {
                        stat(LookIcon.storage.symbol, help: detail) { Text("\(storage) free") }
                    }
                    if status?.dnd == true {
                        Label("Do Not Disturb", systemImage: "moon") // outline, like the other stats
                    }
                }
                .font(.callout).foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 6)
        .accessibilityElement(children: .combine)
    }

    /// An icon and its short value; hover shows it in full, and VoiceOver reads it in full.
    private func stat<V: View>(_ symbol: String, value: Double? = nil, help: String, @ViewBuilder content: () -> V) -> some View {
        HStack(spacing: 5) {
            Image(systemName: symbol, variableValue: value)
            content().monospacedDigit()
        }
        .help(help)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(help)
    }
}
