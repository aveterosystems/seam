// SPDX-License-Identifier: AGPL-3.0-only
//! The other device's status: its battery, network, storage and Do Not Disturb, and a computer's
//! CPU and memory. The computer shows the phone's; the phone shows the computer's.

use seam_core::proto::v1::Status;

use crate::{Icon, TimeWords, words};

/// A figure and what it is, drawn as a value over a quieter label: "42 min" over "until full".
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Readout {
    pub value: String,
    pub label: String,
}

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
    /// The same as figures: the time ("42 min" until full, "7 hr" left) and the rate ("18 W"
    /// charging, "1.2 W" in use). Each None when it wasn't sent.
    pub power_time: Option<Readout>,
    pub power_rate: Option<Readout>,
    /// "Wi‑Fi" or "Mobile data", only while connected (never a network's name), and not on
    /// Same Wi‑Fi, where the path's icon already says it.
    pub network: Option<String>,
    pub network_icon: Option<Icon>,
    /// Free space, short ("112 GB"), and in full with what's used ("144 GB of 256 GB used · 112 GB free"). None from a device
    /// that doesn't send storage.
    pub storage: Option<String>,
    pub storage_detail: Option<String>,
    /// 90% or more used: shown in `warn`, and the full words end "· almost full".
    pub storage_low: bool,
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
    let (storage, storage_detail, storage_low) = if s.storage_total_bytes > 0 {
        let free_bytes = s.storage_free_bytes.min(s.storage_total_bytes);
        let free = words::size(free_bytes);
        // 10% or less free, in integers: f32 can't hold byte counts exactly
        let low = free_bytes as u128 * 10 <= s.storage_total_bytes as u128;
        // Used first, as a bar under it fills to the used share; free last, as the short value says it
        let detail = format!(
            "{} of {} used · {free} free{}",
            words::size(s.storage_total_bytes - free_bytes),
            words::size(s.storage_total_bytes),
            if low { " · almost full" } else { "" }
        );
        (Some(free), Some(detail), low)
    } else {
        (None, None, false)
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
    let (power_time, power_rate) = if connected && has_battery {
        readouts(s, charging)
    } else {
        (None, None)
    };
    let power = sentence(power_time.as_ref(), power_rate.as_ref(), charging);
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
        power_time,
        power_rate,
        network,
        network_icon,
        storage,
        storage_detail,
        storage_low,
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

fn readouts(s: &Status, charging: bool) -> (Option<Readout>, Option<Readout>) {
    let rate = s
        .power_mw
        .filter(|&mw| (100..=MAX_MW).contains(&mw))
        .map(|mw| Readout {
            value: words::watts(mw),
            label: if charging { "charging" } else { "in use" }.to_string(),
        });
    let time = if charging {
        s.minutes_to_full
    } else {
        s.minutes_left
    }
    .filter(|&m| (1..=MAX_MINUTES).contains(&m))
    .map(|m| Readout {
        value: words::duration(m),
        label: if charging { "until full" } else { "left" }.to_string(),
    });
    (time, rate)
}

/// "Charging at 18 W · full in 42 min", "Using 1.2 W · about 7 hr left", or either half.
fn sentence(time: Option<&Readout>, rate: Option<&Readout>, charging: bool) -> Option<String> {
    let rate = rate.map(|r| {
        if charging {
            format!("Charging at {}", r.value)
        } else {
            format!("Using {}", r.value)
        }
    });
    let time = time.map(|t| {
        if charging {
            format!("full in {}", t.value)
        } else {
            format!("about {} left", t.value)
        }
    });
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
