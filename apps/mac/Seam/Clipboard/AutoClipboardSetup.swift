// SPDX-License-Identifier: AGPL-3.0-only
import AppKit
import SeamSession

/// "Turn On Automatic Clipboard from Phone…": runs the one-time adb grant ([AdbSetup]) and says
/// how it went. Only ever runs the adb found in a known place, with fixed arguments.
@MainActor
enum AutoClipboardSetup {
    /// One run at a time: overlapping runs would interleave the restart steps (SR-4-11).
    private static var running = false

    static func run() {
        guard !running else { return show("Setup is already running", "Wait for it to finish, then try again if it didn't work.") }
        guard let adb = AdbSetup.locate(home: NSHomeDirectory(), exists: FileManager.default.isExecutableFile(atPath:)) else {
            return show("Seam needs Android's adb tool", "Install it with Homebrew (brew install android-platform-tools), then try again.")
        }
        let intro = NSAlert()
        intro.messageText = "Turn on automatic clipboard from your phone?"
        intro.informativeText = """
            The easy way needs no cable: in Seam on your phone, turn on Automatic clipboard.

            To set it up without Android's Accessibility, connect the phone by USB or wireless debugging, \
            with USB debugging on in Settings → Developer options. Then, once, on the phone:

            \(AdbSetup.consent)

            You need the Seam build from GitHub: the Play Store build can't do this.
            """
        intro.addButton(withTitle: "Continue")
        intro.addButton(withTitle: "Cancel")
        NSApp.activate()
        guard intro.runModal() == .alertFirstButtonReturn else { return }

        running = true
        Task.detached {
            let outcome = grant(adb: adb)
            await MainActor.run {
                running = false
                show(outcome.title, outcome.detail)
            }
        }
    }

    private struct Outcome: Sendable { let title: String; let detail: String }

    nonisolated private static func grant(adb: String) -> Outcome {
        guard let list = try? execute(adb, ["devices"]), list.status == 0 else {
            return Outcome(title: "adb didn't run", detail: "Check that adb works in Terminal, then try again.")
        }
        let devices = AdbSetup.devices(from: list.output)
        guard devices.count == 1, let serial = devices.first else {
            return devices.isEmpty
                ? Outcome(title: "No phone found", detail: "Plug the phone in, unlock it and tap Allow on \"Allow USB debugging?\", then try again.")
                : Outcome(title: "More than one phone is connected", detail: "Leave only the phone you paired connected, then try again.")
        }
        for step in AdbSetup.steps(serial: serial) {
            guard let r = try? execute(adb, step.arguments), r.status == 0 else {
                if step == AdbSetup.steps(serial: serial).first {
                    return Outcome(title: "The Seam build from GitHub isn't on this phone", detail: "Install it, pair it with this Mac, then try again.")
                }
                return Outcome(title: "Setup stopped at \"\(step.what)\"", detail: "Unlock the phone, check USB debugging is still on, then try again.")
            }
        }
        return Outcome(
            title: "Automatic clipboard is on",
            detail: "If the phone asks \"Allow access to all device logs?\", tap Allow. It asks again after the phone restarts. You can unplug the phone now."
        )
    }

    nonisolated private static func execute(_ path: String, _ arguments: [String]) throws -> (status: Int32, output: String) {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: path)
        p.arguments = arguments
        let pipe = Pipe()
        p.standardOutput = pipe
        p.standardError = pipe
        try p.run()
        // A stuck adb or a phone that never answers can't hang setup: stopped after the timeout (SR-4-11)
        nonisolated(unsafe) let watched = p // Process isn't Sendable; isRunning and terminate are safe from any thread
        DispatchQueue.global().asyncAfter(deadline: .now() + AdbSetup.stepTimeout) { if watched.isRunning { watched.terminate() } }
        let data = pipe.fileHandleForReading.readDataToEndOfFile()
        p.waitUntilExit()
        return (p.terminationStatus, String(decoding: data, as: UTF8.self))
    }

    private static func show(_ title: String, _ detail: String) {
        let alert = NSAlert()
        alert.messageText = title
        alert.informativeText = detail
        NSApp.activate()
        alert.runModal()
    }
}
