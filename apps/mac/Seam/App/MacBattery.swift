// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import IOKit.ps
import SeamCore
import SeamProtocol

/// This Mac's status for the phone: its battery (or none, on a Mac without one), free storage, CPU
/// and memory. Read when it's sent: on connect, when the battery changes, and when the phone asks.
enum MacBattery {
    @Sendable static func read() -> Seam_V1_Status {
        var s = battery() ?? .with { $0.noBattery = true }
        let root = try? URL(fileURLWithPath: "/").resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey, .volumeTotalCapacityKey])
        if let free = root?.volumeAvailableCapacityForImportantUsage, let total = root?.volumeTotalCapacity, total > 0 {
            s.storageFreeBytes = UInt64(max(0, free))
            s.storageTotalBytes = UInt64(total)
        }
        if let used = memoryUsed() {
            s.memoryUsedBytes = used
            s.memoryTotalBytes = ProcessInfo.processInfo.physicalMemory
            // The last minute's load across every core, as Activity Monitor's CPU load reads it
            var load = [Double](repeating: 0, count: 1)
            if getloadavg(&load, 1) == 1 {
                s.cpuPct = UInt32(min(100, max(0, load[0] / Double(ProcessInfo.processInfo.activeProcessorCount) * 100)))
            }
        }
        return s
    }

    /// Memory in use as Activity Monitor counts it: app memory, wired and compressed.
    private static func memoryUsed() -> UInt64? {
        var info = vm_statistics64()
        var count = mach_msg_type_number_t(MemoryLayout<vm_statistics64>.size / MemoryLayout<integer_t>.size)
        let result = withUnsafeMutablePointer(to: &info) {
            $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) { host_statistics64(mach_host_self(), HOST_VM_INFO64, $0, &count) }
        }
        guard result == KERN_SUCCESS else { return nil }
        let pages = UInt64(info.internal_page_count) - UInt64(info.purgeable_count) + UInt64(info.wire_count) + UInt64(info.compressor_page_count)
        return pages * UInt64(getpagesize())
    }

    private static func battery() -> Seam_V1_Status? {
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

/// This Mac's status, as the core reads it.
final class MacBatteryReader: Battery, @unchecked Sendable {
    func read() -> Data? {
        try? MacBattery.read().serializedData()
    }
}
