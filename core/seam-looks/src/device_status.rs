// SPDX-License-Identifier: AGPL-3.0-only
//! The other device's status: its battery, network, storage and Do Not Disturb. The computer
//! shows the phone's; the phone shows the computer's battery.

use seam_core::proto::v1::Status;

use crate::{Icon, TimeWords, words};

#[derive(Debug, Clone, PartialEq)]
pub struct DeviceStatusLook {
    pub percent: u32,
    pub charging: bool,
    /// 15% or below and not charging: shown in `warn`.
    pub low: bool,
    /// "82%".
    pub battery: String,
    pub battery_icon: Icon,
    /// In full, for a tooltip: "82%, charging".
    pub battery_detail: String,
    /// "Wi‑Fi" or "Mobile data", only while connected (never a network's name), and not on
    /// Same Wi‑Fi, where the path's icon already says it.
    pub network: Option<String>,
    pub network_icon: Option<Icon>,
    /// Free space, short ("112 GB") and in full ("112 GB free of 256 GB"). None from a device
    /// that doesn't send storage.
    pub storage: Option<String>,
    pub storage_detail: Option<String>,
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
    let percent = s.battery_pct.min(100);
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
    let meta = if connected {
        network.clone()
    } else {
        at_ms.map(|at| format!("as of {}", time.time(at)))
    };
    let charging = if s.charging { ", charging" } else { "" };
    DeviceStatusLook {
        percent,
        charging: s.charging,
        low: percent <= 15 && !s.charging,
        battery: format!("{percent}%"),
        battery_icon: Icon::battery(percent, s.charging),
        battery_detail: format!("{percent}%{charging}"),
        network,
        network_icon,
        storage,
        storage_detail,
        dnd,
        stale: !connected,
        meta,
        spoken: format!(
            "battery {percent} percent{charging}{}",
            if dnd { ", Do Not Disturb" } else { "" }
        ),
    }
}

#[cfg(test)]
mod tests;
