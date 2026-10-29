// SPDX-License-Identifier: AGPL-3.0-only
import Foundation

/// The one-time grant that turns on automatic clipboard from the phone (docs/platforms/android-limits.md):
/// Android only lets adb grant log access, so the Mac does it over USB or wireless debugging.
public enum AdbSetup {
    /// The sideloaded `full` build: the only one that declares READ_LOGS.
    public static let package = "com.avetero.seam.full"

    /// Where adb usually lives. A fixed list, never a PATH lookup, so nothing else can stand in for it.
    public static func locate(home: String, exists: (String) -> Bool) -> String? {
        ["/opt/homebrew/bin/adb", "/usr/local/bin/adb", "\(home)/Library/Android/sdk/platform-tools/adb"].first(where: exists)
    }

    /// Serials from `adb devices` that are connected and authorised.
    public static func devices(from output: String) -> [String] {
        output.split(separator: "\n").compactMap { line in
            let parts = line.split(whereSeparator: \.isWhitespace)
            return parts.count >= 2 && parts[1] == "device" ? String(parts[0]) : nil
        }
    }

    /// How long one adb call may take before it's stopped (SR-4-11).
    public static let stepTimeout: TimeInterval = 20

    public struct Step: Sendable, Equatable {
        public let what: String
        public let arguments: [String]
        /// What this changes on the phone, in the words the user agrees to. Nil for a step that only looks.
        public var grants: String? = nil
    }

    /// The consent text: every change the steps make, listed, so the user agrees to exactly what happens.
    public static var consent: String {
        steps(serial: "").compactMap(\.grants).map { "• \($0)" }.joined(separator: "\n")
    }

    /// In order. The first one fails if the full build isn't installed. The restart at the end
    /// is needed: Android only hands a new log permission to a freshly started process.
    public static func steps(serial: String) -> [Step] {
        let shell = ["-s", serial, "shell"]
        return [
            Step(what: "Find Seam on the phone", arguments: shell + ["pm", "path", package]),
            Step(
                what: "Allow reading clipboard events", arguments: shell + ["pm", "grant", package, "android.permission.READ_LOGS"],
                grants: "Seam may read the phone's system log. It only looks for the line Android writes when something is copied."
            ),
            Step(
                what: "Allow the invisible focus window", arguments: shell + ["appops", "set", package, "SYSTEM_ALERT_WINDOW", "allow"],
                grants: "Seam may show a 1-pixel window over other apps for a moment, which Android requires before reading the clipboard."
            ),
            Step(
                what: "Keep Seam running in the background", arguments: shell + ["dumpsys", "deviceidle", "whitelist", "+\(package)"],
                grants: "Seam is exempt from battery optimisation, so the phone doesn't put it to sleep."
            ),
            Step(what: "Restart Seam", arguments: shell + ["am", "force-stop", package], grants: "Seam on the phone restarts once to pick these up."),
            Step(what: "Open Seam", arguments: shell + ["am", "start", "-n", "\(package)/com.avetero.seam.MainActivity"]),
        ]
    }
}
