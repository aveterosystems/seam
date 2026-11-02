// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import SeamCore
import SeamProtocol
import SeamSession
import Testing

// The looks' rules and words are tested in Rust (core/seam-looks). Here: this Mac's side of them,
// the icons and the clock, and that the bridge carries a look through.
@Suite struct LooksTests {
    @Test func eachIconMeaningHasItsSymbol() {
        #expect(LookIcon.sameWifi.symbol == "wifi" && LookIcon.throughTor.symbol == "globe")
        #expect(LookIcon.battery(level: 75, charging: false).symbol == "battery.75percent")
        #expect(LookIcon.battery(level: 100, charging: true).symbol == "battery.100percent.bolt")
        #expect(LookIcon.mobileSignal(fraction: 0.75).variableValue == 0.75)
        #expect(LookIcon.storage.variableValue == nil)
    }

    @Test func theClockCountsCalendarDays() {
        let c = Calendar.current
        let noon = c.date(bySettingHour: 12, minute: 0, second: 0, of: Date())!
        let lateYesterday = c.date(byAdding: .hour, value: -13, to: noon)!
        #expect(SystemTime.shared.daysBetween(ms: noon.ms, nowMs: noon.ms) == 0)
        #expect(SystemTime.shared.daysBetween(ms: lateYesterday.ms, nowMs: noon.ms) == 1)
    }

    @Test func aLookComesThroughTheBridge() {
        var wifi = LinkInput.offline
        wifi.route = .lan
        let look = Looks.link(wifi)
        #expect(look.kind == .sameWifi && look.short == "Connected" && look.sentence == "Connected · Same Wi‑Fi")
        let away = Looks.link(.offline, lastSeen: Date().addingTimeInterval(-12 * 60))
        #expect(away.statusLine == "Last seen 12 min ago")
        let phone = Looks.phone(.with { $0.batteryPct = 82; $0.network = "wifi" }, link: look, at: nil)
        #expect(phone?.battery == "82%" && phone?.network == nil, "Same Wi‑Fi's icon already says Wi‑Fi")
    }
}
