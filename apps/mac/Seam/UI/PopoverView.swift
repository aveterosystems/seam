// SPDX-License-Identifier: AGPL-3.0-only
import AppKit
import SeamCore
import SeamProtocol
import SwiftUI

/// Board M01: everything daily, two clicks away. Only what's built is shown: no dead buttons.
struct PopoverView: View {
    let controller: SeamController
    @Environment(\.openSettings) private var openSettings

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if let error = controller.startupError {
                Label(error, systemImage: "exclamationmark.triangle")
                if controller.needsReset { Button("Reset Seam…") { ResetPrompt.run(controller) } }
            } else if let phone = controller.phone {
                header(phone)
                Stitch(connected: controller.connected)
                modules
                if !controller.recentFromPhone.isEmpty { fromPhone }
            } else {
                notPaired
            }
            if let warning = controller.warning {
                Label(warning, systemImage: "exclamationmark.triangle").font(.callout).foregroundStyle(SeamStyle.warn)
            }
            Divider()
            footer
        }
        .padding(14)
        .frame(width: 360)
    }

    private func header(_ phone: PeerInfo) -> some View {
        HStack(spacing: 10) {
            Image(systemName: "iphone").font(.title2).foregroundStyle(SeamStyle.teal).frame(width: 28)
            VStack(alignment: .leading, spacing: 2) {
                Text(phone.name.isEmpty ? "Your phone" : phone.name).font(.headline)
                Text(controller.connected ? "Connected · Through Seam relay" : "Offline · will send when your phone is back")
                    .font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            if let s = controller.status { battery(s) }
            Button { open() } label: { Image(systemName: "slider.horizontal.3") }
                .buttonStyle(.borderless)
                .accessibilityLabel("Settings")
                .keyboardShortcut(",")
        }
        .accessibilityElement(children: .combine)
    }

    private func battery(_ s: Seam_V1_Status) -> some View {
        let low = s.batteryPct <= 15 && !s.charging
        return HStack(spacing: 3) {
            if s.charging { Image(systemName: "bolt.fill") }
            Text("\(s.batteryPct)%").monospacedDigit()
        }
        .font(.caption.weight(.semibold))
        .padding(.horizontal, 7)
        .padding(.vertical, 3)
        .background(Capsule().fill((low ? SeamStyle.warn : SeamStyle.teal).opacity(0.15)))
        .foregroundStyle(low ? SeamStyle.warn : SeamStyle.tealInk)
        .accessibilityLabel("Battery \(s.batteryPct) percent\(s.charging ? ", charging" : "")")
    }

    /// Control Centre-style round modules. Clipboard sync is the one that exists today.
    private var modules: some View {
        HStack {
            Button {
                controller.setClipboardSync(!controller.clipboardSync)
            } label: {
                VStack(spacing: 6) {
                    Image(systemName: "doc.on.clipboard")
                        .font(.title3)
                        .frame(width: 44, height: 44)
                        .background(Circle().fill(controller.clipboardSync ? SeamStyle.teal : Color.secondary.opacity(0.15)))
                        .foregroundStyle(controller.clipboardSync ? Color.white : Color.primary)
                    Text("Clipboard sync").font(.caption)
                }
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Clipboard sync")
            .accessibilityValue(controller.clipboardSync ? "On" : "Off")
            Spacer()
        }
    }

    private var fromPhone: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("From your phone's clipboard").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
            ForEach(controller.recentFromPhone) { clip in
                HStack {
                    VStack(alignment: .leading, spacing: 1) {
                        Text(clip.text).lineLimit(1).truncationMode(.middle)
                        Text("Copied on your phone · \(clip.at, style: .relative) ago").font(.caption2).foregroundStyle(.secondary)
                    }
                    Spacer()
                    if let url = URL(string: clip.text), url.scheme == "https" || url.scheme == "http" {
                        Button("Open") { NSWorkspace.shared.open(url) }.buttonStyle(.bordered).controlSize(.small)
                    } else {
                        Button("Copy") { controller.copyAgain(clip.text) }.buttonStyle(.bordered).controlSize(.small)
                    }
                }
            }
        }
    }

    private var notPaired: some View {
        VStack(spacing: 10) {
            Image(nsImage: NSApp.applicationIconImage).resizable().frame(width: 56, height: 56)
            Text("Pair your phone").font(.headline)
            Text("Scan a code with Seam on your Android phone. It takes a minute.")
                .font(.callout).foregroundStyle(.secondary).multilineTextAlignment(.center)
            Button("Pair a Phone…") { PairingWindow.show(controller) }.buttonStyle(.borderedProminent)
        }
        .frame(maxWidth: .infinity)
    }

    private var footer: some View {
        HStack {
            Label("End-to-end encrypted", systemImage: "checkmark.shield").font(.caption).foregroundStyle(.secondary)
            Spacer()
            Button("Settings…") { open() }.buttonStyle(.borderless).font(.caption)
            Button("Quit") { NSApplication.shared.terminate(nil) }.buttonStyle(.borderless).font(.caption).keyboardShortcut("q")
        }
    }

    private func open() {
        NSApp.activate() // a menu bar app's windows otherwise open behind the frontmost app
        openSettings()
    }
}
