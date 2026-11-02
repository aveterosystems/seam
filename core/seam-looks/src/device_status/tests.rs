// SPDX-License-Identifier: AGPL-3.0-only
use super::*;
use crate::testing::{NOW, Utc};

fn status(f: impl FnOnce(&mut Status)) -> Status {
    let mut s = Status::default();
    f(&mut s);
    s
}

fn on(s: &Status) -> DeviceStatusLook {
    look(s, true, Some(NOW), false, &Utc)
}

#[test]
fn the_battery_warns_at_fifteen_only_when_not_charging() {
    assert!(on(&status(|s| s.battery_pct = 15)).low);
    assert!(
        !on(&status(|s| {
            s.battery_pct = 15;
            s.charging = true
        }))
        .low
    );
    assert!(!on(&status(|s| s.battery_pct = 16)).low);
    assert_eq!(
        on(&status(|s| s.battery_pct = 15)).spoken,
        "battery 15 percent"
    );
    assert_eq!(
        on(&status(|s| s.battery_pct = 250)).percent,
        100,
        "never nonsense on screen"
    );
}

#[test]
fn the_battery_icon_follows_the_level() {
    assert_eq!(
        on(&status(|s| s.battery_pct = 82)).battery_icon.unwrap(),
        Icon::Battery {
            level: 75,
            charging: false
        }
    );
    assert_eq!(
        on(&status(|s| s.battery_pct = 100)).battery_icon.unwrap(),
        Icon::Battery {
            level: 100,
            charging: false
        }
    );
    assert_eq!(
        on(&status(|s| s.battery_pct = 10)).battery_icon.unwrap(),
        Icon::Battery {
            level: 0,
            charging: false
        }
    );
    let charging = on(&status(|s| {
        s.battery_pct = 10;
        s.charging = true
    }));
    assert_eq!(
        charging.battery_icon.unwrap(),
        Icon::Battery {
            level: 100,
            charging: true
        }
    );
    assert_eq!(charging.battery_detail.unwrap(), "10%, charging");
}

#[test]
fn the_network_shows_only_while_connected_and_not_twice() {
    let wifi = status(|s| {
        s.battery_pct = 82;
        s.network = "wifi".into();
        s.dnd = true
    });
    let l = on(&wifi);
    assert_eq!(
        (
            l.meta.as_deref(),
            l.network.as_deref(),
            l.network_icon,
            l.dnd
        ),
        (Some("Wi‑Fi"), Some("Wi‑Fi"), Some(Icon::Wifi), true)
    );
    let off = look(&wifi, false, Some(NOW), false, &Utc);
    assert_eq!(
        (off.network, off.dnd, off.stale),
        (None, false, true),
        "an old status doesn't claim the network or DND now"
    );
    assert_eq!(off.meta.unwrap(), "as of 12:00");
    let home = look(&wifi, true, Some(NOW), true, &Utc);
    assert_eq!(
        (home.network, home.network_icon, home.meta),
        (None, None, None),
        "Same Wi‑Fi's icon already says Wi‑Fi"
    );
    assert_eq!(on(&status(|s| s.network = String::new())).meta, None);
}

#[test]
fn mobile_signal_fills_to_the_bars_or_full_when_none_are_sent() {
    let cell = on(&status(|s| {
        s.network = "cellular".into();
        s.signal_bars = 3
    }));
    assert_eq!(
        (cell.network.as_deref(), cell.network_icon),
        (
            Some("Mobile data"),
            Some(Icon::MobileSignal { fraction: 0.75 })
        )
    );
    assert_eq!(
        on(&status(|s| s.network = "cellular".into())).network_icon,
        Some(Icon::MobileSignal { fraction: 1.0 })
    );
}

#[test]
fn storage_shows_free_space_short_and_in_full() {
    let s = on(&status(|s| {
        s.storage_free_bytes = 112_000_000_000;
        s.storage_total_bytes = 256_000_000_000
    }));
    assert_eq!(
        (s.storage.as_deref(), s.storage_detail.as_deref()),
        (Some("112 GB"), Some("112 GB free of 256 GB"))
    );
    assert_eq!(
        on(&status(|s| s.battery_pct = 50)).storage,
        None,
        "an older device sends no storage"
    );
}

#[test]
fn a_computer_sends_cpu_and_memory_and_may_have_no_battery() {
    let mac = on(&status(|s| {
        s.no_battery = true;
        s.cpu_pct = 12;
        s.memory_used_bytes = 9 << 30;
        s.memory_total_bytes = 16 << 30;
    }));
    assert_eq!(
        (mac.battery, mac.battery_icon, mac.low),
        (None, None, false),
        "a Mac mini has no battery to show"
    );
    assert_eq!(
        (mac.cpu.as_deref(), mac.memory.as_deref()),
        (Some("12%"), Some("9 GB"))
    );
    assert_eq!(mac.memory_detail.as_deref(), Some("9 GB of 16 GB in use"));
    assert_eq!(
        mac.spoken, "",
        "nothing to say about a battery it hasn't got"
    );
    assert_eq!(
        on(&status(|s| s.battery_pct = 50)).cpu,
        None,
        "a phone sends no CPU"
    );
}
