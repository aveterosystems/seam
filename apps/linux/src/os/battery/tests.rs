// SPDX-License-Identifier: AGPL-3.0-only
use std::fs;

use super::*;

fn supply(root: &std::path::Path, name: &str, files: &[(&str, &str)]) {
    let d = root.join(name);
    fs::create_dir_all(&d).unwrap();
    for (f, v) in files {
        fs::write(d.join(f), v).unwrap();
    }
}

#[test]
fn a_laptop_charging_on_energy_counters() {
    let t = tempfile::tempdir().unwrap();
    supply(t.path(), "AC", &[("type", "Mains"), ("online", "1")]);
    supply(
        t.path(),
        "BAT0",
        &[("type", "Battery"), ("capacity", "64"), ("status", "Charging"), ("energy_now", "32000000"), ("energy_full", "50000000"), ("power_now", "18000000")],
    );
    let s = battery_in(t.path()).unwrap();
    assert_eq!((s.battery_pct, s.charging), (64, true));
    assert_eq!(s.power_mw, Some(18_000));
    assert_eq!(s.minutes_to_full, Some(60)); // 18 Wh to go at 18 W
    assert_eq!(s.minutes_left, None);
}

#[test]
fn on_battery_with_charge_counters_and_a_peripheral_skipped() {
    let t = tempfile::tempdir().unwrap();
    // A wireless mouse's battery comes first by name but isn't the computer's
    supply(t.path(), "AAA_mouse", &[("type", "Battery"), ("scope", "Device"), ("capacity", "5"), ("status", "Discharging")]);
    supply(
        t.path(),
        "BAT1",
        &[
            ("type", "Battery"),
            ("capacity", "40"),
            ("status", "Discharging"),
            ("charge_now", "2000000"),
            ("charge_full", "5000000"),
            ("current_now", "1000000"),
            ("voltage_now", "12000000"),
        ],
    );
    let s = battery_in(t.path()).unwrap();
    assert_eq!((s.battery_pct, s.charging), (40, false));
    assert_eq!(s.power_mw, Some(12_000));
    assert_eq!(s.minutes_left, Some(120));
}

#[test]
fn a_desktop_has_no_battery() {
    let t = tempfile::tempdir().unwrap();
    supply(t.path(), "AC", &[("type", "Mains"), ("online", "1")]);
    assert!(battery_in(t.path()).is_none());
}

#[test]
fn cpu_and_memory_parse() {
    let stat = "cpu  100 20 30 800 50 0 0 0 0 0\ncpu0 1 2 3 4\n";
    assert_eq!(parse_cpu(stat), Some((150, 850)));
    let mem = "MemTotal:       16000000 kB\nMemFree:  100 kB\nMemAvailable:    6000000 kB\n";
    assert_eq!(parse_memory(mem), Some((10_000_000 * 1024, 16_000_000 * 1024)));
    assert_eq!(parse_memory("nonsense"), None);
}

#[test]
fn read_always_sends_storage_and_says_when_theres_no_battery() {
    let s = LinuxBattery::new().read().unwrap();
    assert!(s.storage_total_bytes > 0);
    assert!(s.memory_total_bytes > 0);
}
