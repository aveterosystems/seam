// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import Testing

@testable import SeamSession

struct AdbSetupTests {
    /// SR-4-11: the user agrees to every change the setup makes.
    @Test func consentListsEveryChange() {
        let consent = AdbSetup.consent
        #expect(consent.contains("system log"))
        #expect(consent.contains("over other apps"))
        #expect(consent.contains("restarts"))
        #expect(AdbSetup.steps(serial: "x").filter { $0.grants != nil }.count == 3)
        // No battery-optimisation exemption any more
        #expect(!AdbSetup.steps(serial: "x").contains { $0.arguments.contains("deviceidle") })
        #expect(!consent.contains("battery"))
        #expect(AdbSetup.stepTimeout <= 30)
    }

    @Test func findsAdbOnlyInKnownPlaces() {
        #expect(AdbSetup.locate(home: "/Users/m", exists: { $0 == "/usr/local/bin/adb" }) == "/usr/local/bin/adb")
        #expect(AdbSetup.locate(home: "/Users/m", exists: { $0 == "/Users/m/Library/Android/sdk/platform-tools/adb" }) != nil)
        #expect(AdbSetup.locate(home: "/Users/m", exists: { _ in false }) == nil)
    }

    @Test func listsOnlyAuthorisedDevices() {
        let out = "List of devices attached\nR5CX123\tdevice\nemulator-5554\toffline\nR5CY999\tunauthorized\n192.168.1.4:5555\tdevice\n\n"
        #expect(AdbSetup.devices(from: out) == ["R5CX123", "192.168.1.4:5555"])
        #expect(AdbSetup.devices(from: "List of devices attached\n\n").isEmpty)
    }

    @Test func grantsOnlyTheFullBuildAndRestartsIt() {
        let steps = AdbSetup.steps(serial: "R5CX123")
        #expect(steps.allSatisfy { $0.arguments.starts(with: ["-s", "R5CX123", "shell"]) })
        #expect(steps.contains { $0.arguments.suffix(2) == ["com.avetero.seam.full", "android.permission.READ_LOGS"] })
        #expect(steps.contains { $0.arguments.contains("SYSTEM_ALERT_WINDOW") })
        #expect(steps.last?.arguments.contains("com.avetero.seam.full/com.avetero.seam.MainActivity") == true)
    }
}
