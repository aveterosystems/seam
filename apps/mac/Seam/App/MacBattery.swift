// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import IOKit
import IOKit.ps
import SeamCore
import SeamProtocol

/// This Mac's status for the phone: its battery (or none, on a Mac without one) with its power and
/// time left or to full, free storage, CPU and memory. Read when it's sent: on connect, when the battery changes, and when the phone asks.
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
            if let busy = cpu.busyPercent() { s.cpuPct = busy }
        }
        return s
    }

    private static let cpu = CPUTime()

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
                if let mw = powerMw() { $0.powerMw = mw }
                // macOS's own estimates, in minutes: -1 while it's working them out, 0 when there's none
                if let full = d[kIOPSTimeToFullChargeKey] as? Int, full > 0 { $0.minutesToFull = UInt32(full) }
                if let left = d[kIOPSTimeToEmptyKey] as? Int, left > 0 { $0.minutesLeft = UInt32(left) }
            }
        }
        return nil
    }

    /// The battery's power from its controller: amperage (mA, negative while draining) times voltage
    /// (mV). The power source's own "Current" is always 0. None at 0 A (full on the adapter).
    private static func powerMw() -> UInt32? {
        let service = IOServiceGetMatchingService(kIOMainPortDefault, IOServiceMatching("AppleSmartBattery"))
        guard service != 0 else { return nil }
        defer { IOObjectRelease(service) }
        func int(_ key: String) -> Int? {
            IORegistryEntryCreateCFProperty(service, key as CFString, kCFAllocatorDefault, 0)?.takeRetainedValue() as? Int
        }
        guard let mA = int("Amperage"), let mV = int("Voltage"), mA != 0, mV > 0 else { return nil }
        // A garbage reading must not trap: magnitude and the product never overflow
        let (mW, over) = mA.magnitude.multipliedReportingOverflow(by: UInt(mV))
        return UInt32(clamping: over ? UInt.max : mW / 1000)
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

/// The share of time every core was busy since the last reading (the phone's sheet asks every
/// 10 s), as Activity Monitor's CPU load counts it. A last reading older than 30 s would average
/// over all that time, so then it reads twice, a quarter of a second apart.
private final class CPUTime: @unchecked Sendable {
    private let lock = NSLock()
    private var last: (busy: UInt32, idle: UInt32, at: Date)?

    func busyPercent() -> UInt32? {
        lock.lock()
        let stale = last.map { Date().timeIntervalSince($0.at) > 30 } ?? true
        lock.unlock()
        if stale {
            _ = sample()
            Thread.sleep(forTimeInterval: 0.25)
        }
        return sample()
    }

    private func sample() -> UInt32? {
        var info = host_cpu_load_info()
        var count = mach_msg_type_number_t(MemoryLayout<host_cpu_load_info>.size / MemoryLayout<integer_t>.size)
        let result = withUnsafeMutablePointer(to: &info) {
            $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) { host_statistics(mach_host_self(), HOST_CPU_LOAD_INFO, $0, &count) }
        }
        guard result == KERN_SUCCESS else { return nil }
        let t = info.cpu_ticks // user, system, idle, nice
        let busy = t.0 &+ t.1 &+ t.3, idle = t.2
        lock.lock()
        defer { lock.unlock() }
        // The tick counters wrap, so differences wrap too
        let (db, di) = last.map { (busy &- $0.busy, idle &- $0.idle) } ?? (busy, idle)
        last = (busy, idle, Date())
        let total = UInt64(db) + UInt64(di)
        return total == 0 ? nil : UInt32(UInt64(db) * 100 / total)
    }
}
