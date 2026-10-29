// SPDX-License-Identifier: AGPL-3.0-only
import AppKit
import SwiftUI

/// The pairing window (design board M02): QR code, then the safety code to confirm.
@MainActor
enum PairingWindow {
    private static var window: NSWindow?

    static func show(_ controller: SeamController) {
        if window == nil {
            let w = NSWindow(contentViewController: NSHostingController(rootView: PairingView(controller: controller)))
            w.title = "Pair your phone"
            w.styleMask = [.titled, .closable]
            w.isReleasedWhenClosed = false
            window = w
        }
        if case .idle = controller.pairing { controller.startPairing() }
        if case .paired = controller.pairing { controller.startPairing() }
        NSApp.activate()
        window?.center()
        window?.makeKeyAndOrderFront(nil)
    }

    static func close() {
        window?.close()
    }
}

struct PairingView: View {
    let controller: SeamController

    var body: some View {
        Group {
            switch controller.pairing {
            case .idle:
                ProgressView()
            case .waiting(let qr):
                waiting(qr)
            case .confirm(let request):
                confirm(name: request.deviceName, code: request.safetyCode)
            case .paired(let name):
                message("Paired with \(name)", detail: "Its battery and notifications will appear in the menu bar.", button: "Done") {
                    PairingWindow.close()
                }
            case .failed(let reason):
                message("Pairing didn't finish", detail: reason, button: "Try again") { controller.startPairing() }
            }
        }
        .padding(32)
        .frame(width: 640, height: 400)
    }

    private func waiting(_ qr: String) -> some View {
        HStack(spacing: 40) {
            VStack(alignment: .leading, spacing: 16) {
                Text("Pair your phone").font(.title.bold())
                Text("Open Seam on your Android phone and scan this code. That's the whole setup.")
                    .foregroundStyle(.secondary)
                Label("Keys are made on this Mac and never leave it. No account, no password.", systemImage: "lock.shield")
                    .font(.callout)
                    .foregroundStyle(.secondary)
                Spacer()
                Label(
                    controller.connected ? "Waiting for your phone…" : "Connecting to the relay at \(controller.relayURL)…",
                    systemImage: controller.connected ? "dot.radiowaves.left.and.right" : "network"
                )
                .font(.callout)
            }
            if let image = QRCodeImage.make(qr) {
                Image(nsImage: image)
                    .interpolation(.none)
                    .resizable()
                    .frame(width: 240, height: 240)
                    .padding(12)
                    .background(.white, in: RoundedRectangle(cornerRadius: 16))
                    .accessibilityLabel("Pairing QR code")
            }
        }
    }

    private func confirm(name: String, code: String) -> some View {
        VStack(spacing: 20) {
            Text("Pair with \(name)?").font(.title.bold())
            Text("Check your phone shows the same code.").foregroundStyle(.secondary)
            Text(code)
                .font(.system(size: 44, weight: .medium, design: .monospaced))
                .accessibilityLabel("Code \(code)")
            HStack {
                Button("Cancel", role: .cancel) { controller.cancelPairing() }
                Button("Pair") { controller.confirmPairing() }
                    .keyboardShortcut(.defaultAction)
                    .buttonStyle(.borderedProminent)
            }
            .controlSize(.large)
        }
    }

    private func message(_ title: String, detail: String, button: String, action: @escaping () -> Void) -> some View {
        VStack(spacing: 16) {
            Text(title).font(.title.bold())
            Text(detail).foregroundStyle(.secondary).multilineTextAlignment(.center)
            Button(button, action: action).buttonStyle(.borderedProminent).controlSize(.large)
        }
    }
}
