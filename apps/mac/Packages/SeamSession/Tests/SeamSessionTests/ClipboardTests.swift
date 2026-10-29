// SPDX-License-Identifier: AGPL-3.0-only
import Testing

@testable import SeamSession

struct ClipRulesTests {
    @Test func sendsNewCopiesOnce() {
        var r = ClipRules()
        #expect(r.outgoing("a") == "a")
        #expect(r.outgoing("a") == nil) // the same change seen twice
        #expect(r.outgoing("b") == "b")
        #expect(r.outgoing("a") == "a") // copying it again later still sends it
    }

    @Test func neverEchoesWhatThePhoneSent() {
        var r = ClipRules()
        #expect(r.incoming("from phone") == "from phone")
        #expect(r.outgoing("from phone") == nil)
        #expect(r.outgoing("mine") == "mine")
    }

    @Test func skipsEmptyAndOversized() {
        var r = ClipRules(maxBytes: 4)
        #expect(r.outgoing(nil) == nil)
        #expect(r.outgoing("") == nil)
        #expect(r.outgoing("12345") == nil)
        #expect(r.incoming("12345") == nil)
        #expect(r.outgoing("ééé") == nil) // bytes, not characters
        #expect(r.outgoing("1234") == "1234")
    }

    @Test func passwordManagerItemsAreNeverSent() {
        #expect(ClipRules.isPrivate(types: ["public.utf8-plain-text", "org.nspasteboard.ConcealedType"]))
        #expect(ClipRules.isPrivate(types: ["org.nspasteboard.TransientType"]))
        #expect(!ClipRules.isPrivate(types: ["public.utf8-plain-text", "public.html"]))
    }
}

struct AdbSetupTests {
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
