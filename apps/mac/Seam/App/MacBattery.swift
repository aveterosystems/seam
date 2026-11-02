// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import IOKit.ps
import SeamCore
import SeamProtocol

/// This Mac's battery, for the phone's home screen. A Mac without one reports nothing.
enum MacBattery {
    @Sendable static func read() -> Seam_V1_Status? {
        guard let info = IOPSCopyPowerSourcesInfo()?.takeRetainedValue(),
              let list = IOPSCopyPowerSourcesList(info)?.takeRetainedValue() as? [CFTypeRef] else { return nil }
        for source in list {
            guard let d = IOPSGetPowerSourceDescription(info, source)?.takeUnretainedValue() as? [String: Any],
                  d[kIOPSTypeKey] as? String == kIOPSInternalBatteryType,
                  let level = d[kIOPSCurrentCapacityKey] as? Int, let max = d[kIOPSMaxCapacityKey] as? Int, max > 0 else { continue }
            return .with {
                $0.batteryPct = UInt32(min(100, Swift.max(0, level * 100 / max)))
                $0.charging = d[kIOPSIsChargingKey] as? Bool ?? false || d[kIOPSPowerSourceStateKey] as? String == kIOPSACPowerValue && level >= max
            }
        }
        return nil
    }

    /// Calls `changed` on the main run loop whenever the power source changes; keep the source.
    static func watch(_ changed: @escaping () -> Void) -> CFRunLoopSource? {
        let box = Unmanaged.passRetained(Callback(changed)).toOpaque()
        guard let source = IOPSNotificationCreateRunLoopSource({ ctx in
            ctx.map { Unmanaged<Callback>.fromOpaque($0).takeUnretainedValue().run() }
        }, box)?.takeRetainedValue() else { return nil }
        CFRunLoopAddSource(CFRunLoopGetMain(), source, .defaultMode)
        return source
    }

    private final class Callback {
        let run: () -> Void
        init(_ run: @escaping () -> Void) { self.run = run }
    }
}

/// The battery, as the core's.
final class MacBatteryReader: Battery, @unchecked Sendable {
    func read() -> Data? {
        try? MacBattery.read()?.serializedData()
    }
}
