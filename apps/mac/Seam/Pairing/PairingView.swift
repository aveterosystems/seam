// SPDX-License-Identifier: AGPL-3.0-only
import AppKit
import SeamSession
import SwiftUI

/// The pairing window (design board M02): QR code, then the safety code to confirm.
@MainActor
enum PairingWindow {
    private static var window: NSWindow?
    private static var delegate: Delegate?

    static func show(_ controller: SeamController) {
        if window == nil {
            let w = NSWindow(contentViewController: NSHostingController(rootView: PairingView(controller: controller)))
            w.title = "Pair your phone"
            w.styleMask = [.titled, .closable]
            w.isReleasedWhenClosed = false
            // The QR code is a live pairing secret: keep it out of screenshots and screen sharing
            w.sharingType = .none
            let d = Delegate(controller: controller)
            w.delegate = d
            delegate = d
            window = w
        }
        switch controller.flow.state {
        case .waiting, .confirm, .confirming: break
        case .idle, .paired, .expired, .failed: controller.startPairing()
        }
        NSApp.activate()
        window?.center()
        window?.makeKeyAndOrderFront(nil)
    }

    static func close() {
        window?.close()
    }

    /// Closing the window is the same as Cancel: the code stops working at once.
    @MainActor
    private final class Delegate: NSObject, NSWindowDelegate {
        weak var controller: SeamController?

        init(controller: SeamController) { self.controller = controller }

        func windowWillClose(_ notification: Notification) {
            controller?.cancelPairing()
        }
    }
}

struct PairingView: View {
    let controller: SeamController

    var body: some View {
        Group {
            switch controller.flow.state {
            case .idle:
                message("Pair your phone", detail: "Show a code to scan with Seam on your Android phone.", button: "Show code") {
                    controller.startPairing()
                }
            case .waiting(let qr, let deadline):
                waiting(qr, deadline: deadline)
            case .confirm(let request):
                ConfirmView(controller: controller, name: request.deviceName, code: request.safetyCode, sending: false)
            case .confirming(let request):
                ConfirmView(controller: controller, name: request.deviceName, code: request.safetyCode, sending: true)
            case .paired(let name):
                message("Paired with \(name)", detail: "Its battery and notifications will appear in the menu bar.", button: "Done") {
                    PairingWindow.close()
                }
            case .expired:
                message("Code expired", detail: "Pairing codes work for 5 minutes. Show a new one to pair your phone.", button: "Show a new code") {
                    controller.startPairing()
                }
            case .failed(let reason):
                message("Pairing didn't finish", detail: reason, button: "Show a new code") { controller.startPairing() }
            }
        }
        .padding(32)
        .frame(width: 640, height: 400)
    }

    private func waiting(_ qr: String, deadline: PairingDeadline) -> some View {
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
                    controller.connected ? "Waiting for your phone…" : "Connecting to \(controller.relayHost)…",
                    systemImage: controller.connected ? "dot.radiowaves.left.and.right" : "network"
                )
                .font(.callout)
                TimelineView(.periodic(from: .now, by: 1)) { context in
                    let left = Int(deadline.remaining(at: context.date))
                    Text("Code works for \(left / 60):\(String(format: "%02d", left % 60))")
                        .font(.callout.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
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

    private func message(_ title: String, detail: String, button: String, action: @escaping () -> Void) -> some View {
        VStack(spacing: 16) {
            Text(title).font(.title.bold())
            Text(detail).foregroundStyle(.secondary).multilineTextAlignment(.center)
            Button(button, action: action).buttonStyle(.borderedProminent).controlSize(.large)
        }
    }
}

/// The safety code check. Return and Escape both mean Cancel, and Pair only takes a click once
/// the screen has been up for a moment, so a keypress meant for something else can't pair a
/// phone the user never looked at (SR-2-23).
private struct ConfirmView: View {
    let controller: SeamController
    let name: String
    let code: String
    let sending: Bool
    @State private var armed = false

    var body: some View {
        VStack(spacing: 20) {
            Text("Pair with \(name)?").font(.title.bold())
            Text("Check your phone shows the same code.").foregroundStyle(.secondary)
            Text(code)
                .font(.system(size: 44, weight: .medium, design: .monospaced))
                .accessibilityLabel("Code \(code)")
            Text("Through the relay at \(controller.relayHost)")
                .font(.callout)
                .foregroundStyle(.secondary)
            HStack {
                Button("Cancel", role: .cancel) { PairingWindow.close() }
                    .keyboardShortcut(.defaultAction)
                    .disabled(sending)
                Button("Pair") { controller.confirmPairing() }
                    .disabled(!armed || sending)
                if sending { ProgressView().controlSize(.small) }
            }
            .controlSize(.large)
        }
        .onExitCommand { if !sending { PairingWindow.close() } }
        .task {
            try? await Task.sleep(for: .seconds(1.5))
            armed = true
        }
    }
}
