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

    public struct Step: Sendable, Equatable {
        public let what: String
        public let arguments: [String]
    }

    /// In order. The first one fails if the full build isn't installed. The restart at the end
    /// is needed: Android only hands a new log permission to a freshly started process.
    public static func steps(serial: String) -> [Step] {
        let shell = ["-s", serial, "shell"]
        return [
            Step(what: "Find Seam on the phone", arguments: shell + ["pm", "path", package]),
            Step(what: "Allow reading clipboard events", arguments: shell + ["pm", "grant", package, "android.permission.READ_LOGS"]),
            Step(what: "Allow the invisible focus window", arguments: shell + ["appops", "set", package, "SYSTEM_ALERT_WINDOW", "allow"]),
            Step(what: "Keep Seam running in the background", arguments: shell + ["dumpsys", "deviceidle", "whitelist", "+\(package)"]),
            Step(what: "Restart Seam", arguments: shell + ["am", "force-stop", package]),
            Step(what: "Open Seam", arguments: shell + ["am", "start", "-n", "\(package)/com.avetero.seam.MainActivity"]),
        ]
    }
}
