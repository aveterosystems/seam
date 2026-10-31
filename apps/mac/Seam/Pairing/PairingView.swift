// SPDX-License-Identifier: AGPL-3.0-only
import AppKit
import SeamCore
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
            // Board M02: traffic lights only, the content runs under a transparent title bar
            w.styleMask = [.titled, .closable, .fullSizeContentView]
            w.titlebarAppearsTransparent = true
            w.titleVisibility = .hidden
            w.isReleasedWhenClosed = false
            // The QR code is a live pairing secret: keep it out of screenshots and screen sharing
            w.sharingType = .none
            let d = Delegate(controller: controller)
            w.delegate = d
            delegate = d
            window = w
        }
        switch controller.pairing {
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

/// Board M02: a 740×520 window. Left: what to do and why it's safe. Right: the code to scan, then
/// the code to compare.
struct PairingView: View {
    let controller: SeamController

    var body: some View {
        HStack(alignment: .top, spacing: 40) {
            steps
            VStack(spacing: 16) { right }.frame(width: 260)
        }
        .padding(.horizontal, 48)
        .padding(.top, 8)
        .padding(.bottom, 40)
        .frame(width: 740, height: 480)
        .tint(SeamStyle.teal)
    }

    private var steps: some View {
        VStack(alignment: .leading, spacing: 20) {
            Image(nsImage: NSApp.applicationIconImage).resizable().frame(width: 56, height: 56)
            VStack(alignment: .leading, spacing: 6) {
                Text("Pair your phone").font(.system(size: 24, weight: .bold))
                Text("Open Seam on your Android phone and scan this code. That's the whole setup.")
                    .font(.system(size: 14)).foregroundStyle(.secondary)
            }
            VStack(alignment: .leading, spacing: 14) {
                step(1, Text("Get **Seam** on your phone"))
                step(2, Text("Tap **Scan pairing code** and point the camera here"))
                step(3, Text("Check both screens show the same code, then pair"))
            }
            Spacer()
            Label("Keys are made on this Mac and never leave it. No account, no password.", systemImage: "lock")
                .font(.caption).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func step(_ n: Int, _ text: Text) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            Text("\(n)")
                .font(.caption.weight(.semibold))
                .frame(width: 22, height: 20)
                .background(Capsule().fill(SeamStyle.teal.opacity(0.13)))
                .foregroundStyle(SeamStyle.tealInk)
            text
        }
    }

    /// While Tor isn't ready the phone can still pair on the same Wi‑Fi: say why Tor waits (decision 0004).
    private var torNote: String? {
        guard controller.torOn, controller.torState != .ready else { return nil }
        return LinkLook.of(route: nil, allowed: [.tor], tor: controller.torState).label
    }

    /// Pairing works over either path, and one is always on: a code can always show.
    @ViewBuilder private var right: some View {
        switch controller.pairing {
        case .idle:
            outcome("Show a code to scan with Seam on your phone.", button: "Show code") { controller.startPairing() }
        case .waiting(let qr, let deadline):
            if let image = QRCodeImage.make(qr) {
                Image(nsImage: image)
                    .interpolation(.none)
                    .resizable()
                    .frame(width: 200, height: 200)
                    .padding(14)
                    .background(.white, in: RoundedRectangle(cornerRadius: 16))
                    .overlay(RoundedRectangle(cornerRadius: 16).stroke(.black.opacity(0.09)))
                    .accessibilityLabel("Pairing QR code")
            }
            HStack(spacing: 6) {
                Circle().fill(SeamStyle.warn).frame(width: 8, height: 8)
                TimelineView(.periodic(from: .now, by: 1)) { context in
                    let left = Int(deadline.remaining(at: context.date))
                    Text("Waiting for your phone… code expires in \(left / 60):\(String(format: "%02d", left % 60))")
                        .font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                }
            }
            if let torNote { Text(torNote).font(.caption).foregroundStyle(.secondary) }
        case .confirm(let request):
            ConfirmView(controller: controller, name: request.deviceName, code: request.safetyCode, sending: false)
        case .confirming(let request):
            ConfirmView(controller: controller, name: request.deviceName, code: request.safetyCode, sending: true)
        case .paired(let name):
            outcome("Paired with \(name).", button: "Done") { PairingWindow.close() }
        case .expired:
            outcome("Codes work for 5 minutes. Show a new one to pair.", button: "Show a new code") { controller.startPairing() }
        case .failed(let reason):
            outcome(reason, button: "Show a new code") { controller.startPairing() }
        }
    }

    private func outcome(_ text: String, button: String, action: @escaping () -> Void) -> some View {
        VStack(spacing: 14) {
            Spacer()
            Text(text).multilineTextAlignment(.center).foregroundStyle(.secondary)
            Button(button, action: action).buttonStyle(.borderedProminent)
            Spacer()
        }
    }
}

/// The safety code check, in the right column. Return and Escape both mean Cancel, and Pair only
/// takes a click once the screen has been up for a moment, so a keypress meant for something else
/// can't pair a phone the user never looked at (SR-2-23).
private struct ConfirmView: View {
    let controller: SeamController
    let name: String
    let code: String
    let sending: Bool
    @State private var armed = false

    var body: some View {
        VStack(spacing: 14) {
            Spacer()
            Text("Pair with \(name)?").font(.headline).multilineTextAlignment(.center)
            VStack(spacing: 4) {
                Text("Code check").font(.caption).foregroundStyle(.tertiary)
                Text(code)
                    .font(.system(size: 26, weight: .medium, design: .monospaced))
                    .tracking(3)
                    .accessibilityLabel("Code \(code)")
            }
            Text("Check your phone shows the same code.").font(.caption).foregroundStyle(.secondary)
            HStack {
                // Cancel is the prominent, default button: Return cancels, and the look says so (SR-10-02)
                Button("Cancel", role: .cancel) { PairingWindow.close() }
                    .keyboardShortcut(.defaultAction)
                    .buttonStyle(.borderedProminent)
                    .disabled(sending)
                Button("Pair") { controller.confirmPairing() }
                    .disabled(!armed || sending)
                if sending { ProgressView().controlSize(.small) }
            }
            Spacer()
        }
        .onExitCommand { if !sending { PairingWindow.close() } }
        .task {
            try? await Task.sleep(for: .seconds(1.5))
            armed = true
        }
    }
}
