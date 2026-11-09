// SPDX-License-Identifier: AGPL-3.0-only
//! The other device's status: its battery, network, storage and Do Not Disturb, and a computer's
//! CPU and memory. The computer shows the phone's; the phone shows the computer's.

use seam_core::proto::v1::Status;

use crate::{Icon, TimeWords, words};

#[derive(Debug, Clone, PartialEq)]
pub struct DeviceStatusLook {
    pub percent: u32,
    pub charging: bool,
    /// 15% or below and not charging: shown in `warn`.
    pub low: bool,
    /// "82%"; None for a computer without a battery.
    pub battery: Option<String>,
    pub battery_icon: Option<Icon>,
    /// In full, for a tooltip: "82%, charging".
    pub battery_detail: Option<String>,
    /// The battery's power and time, only while connected: "Charging at 18 W · full in 42 min",
    /// "Using 1.2 W · about 7 hr left". None when the device sent neither.
    pub power: Option<String>,
    /// "Wi‑Fi" or "Mobile data", only while connected (never a network's name), and not on
    /// Same Wi‑Fi, where the path's icon already says it.
    pub network: Option<String>,
    pub network_icon: Option<Icon>,
    /// Free space, short ("112 GB") and in full ("112 GB free of 256 GB"). None from a device
    /// that doesn't send storage.
    pub storage: Option<String>,
    pub storage_detail: Option<String>,
    /// A computer's CPU in use ("12%") and memory in use ("9 GB", in full "9 GB of 16 GB in use").
    /// None from a device that doesn't send them.
    pub cpu: Option<String>,
    pub memory: Option<String>,
    pub memory_detail: Option<String>,
    /// Only while connected: an old status may be wrong.
    pub dnd: bool,
    /// The status is from before this connection: the numbers may be old.
    pub stale: bool,
    /// The line under the battery: the network while connected, "as of 14:02" when it's old.
    pub meta: Option<String>,
    /// For screen readers: "battery 82 percent, charging, Do Not Disturb".
    pub spoken: String,
}

pub fn look(
    s: &Status,
    connected: bool,
    at_ms: Option<i64>,
    same_wifi: bool,
    time: &dyn TimeWords,
) -> DeviceStatusLook {
    let has_battery = !s.no_battery;
    let percent = if has_battery {
        s.battery_pct.min(100)
    } else {
        0
    };
    let charging = has_battery && s.charging;
    let dnd = connected && s.dnd;
    let (network, network_icon) = match (connected && !same_wifi, s.network.as_str()) {
        (true, "wifi") => (Some("Wi‑Fi".to_string()), Some(Icon::Wifi)),
        // Android doesn't send bars yet (0): full, since an empty icon would say "no signal"
        (true, "cellular") => (
            Some("Mobile data".to_string()),
            Some(Icon::MobileSignal {
                fraction: if s.signal_bars > 0 {
                    (s.signal_bars as f32 / 4.0).min(1.0)
                } else {
                    1.0
                },
            }),
        ),
        _ => (None, None),
    };
    let (storage, storage_detail) = if s.storage_total_bytes > 0 {
        let free = words::size(s.storage_free_bytes.min(s.storage_total_bytes));
        (
            Some(free.clone()),
            Some(format!(
                "{free} free of {}",
                words::size(s.storage_total_bytes)
            )),
        )
    } else {
        (None, None)
    };
    let (cpu, memory, memory_detail) = if s.memory_total_bytes > 0 {
        let used = words::memory(s.memory_used_bytes.min(s.memory_total_bytes));
        let detail = format!("{used} of {} in use", words::memory(s.memory_total_bytes));
        (
            s.cpu_pct.map(|c| format!("{}%", c.min(100))),
            Some(used),
            Some(detail),
        )
    } else {
        (None, None, None)
    };
    let power = (connected && has_battery)
        .then(|| power_words(s, charging))
        .flatten();
    let meta = if connected {
        network.clone()
    } else {
        at_ms.map(|at| format!("as of {}", time.time(at)))
    };
    let charging_words = if charging { ", charging" } else { "" };
    let spoken = [
        has_battery.then(|| format!("battery {percent} percent{charging_words}")),
        dnd.then(|| "Do Not Disturb".to_string()),
    ];
    DeviceStatusLook {
        percent,
        charging,
        low: has_battery && percent <= 15 && !charging,
        battery: has_battery.then(|| format!("{percent}%")),
        battery_icon: has_battery.then(|| Icon::battery(percent, charging)),
        battery_detail: has_battery.then(|| format!("{percent}%{charging_words}")),
        power,
        network,
        network_icon,
        storage,
        storage_detail,
        cpu,
        memory,
        memory_detail,
        dnd,
        stale: !connected,
        meta,
        spoken: spoken.into_iter().flatten().collect::<Vec<_>>().join(", "),
    }
}

/// Nonsense from a device that misreads its battery shows as nothing: no battery here takes
/// 500 W, and no estimate runs past 4 days.
const MAX_MW: u32 = 500_000;
const MAX_MINUTES: u32 = 4 * 24 * 60;

fn power_words(s: &Status, charging: bool) -> Option<String> {
    let watts = s
        .power_mw
        .filter(|&mw| (100..=MAX_MW).contains(&mw))
        .map(words::watts);
    let minutes = if charging {
        s.minutes_to_full
    } else {
        s.minutes_left
    }
    .filter(|&m| (1..=MAX_MINUTES).contains(&m))
    .map(words::duration);
    let (rate, time) = if charging {
        (
            watts.map(|w| format!("Charging at {w}")),
            minutes.map(|m| format!("full in {m}")),
        )
    } else {
        (
            watts.map(|w| format!("Using {w}")),
            minutes.map(|m| format!("about {m} left")),
        )
    };
    match (rate, time) {
        (Some(r), Some(t)) => Some(format!("{r} · {t}")),
        (Some(r), None) => Some(r),
        (None, Some(t)) => Some(capitalised(&t)),
        (None, None) => None,
    }
}

fn capitalised(s: &str) -> String {
    let mut c = s.chars();
    c.next()
        .map(|f| f.to_uppercase().chain(c).collect())
        .unwrap_or_default()
}

#[cfg(test)]
mod tests;
