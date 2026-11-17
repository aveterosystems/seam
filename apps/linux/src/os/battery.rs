// SPDX-License-Identifier: AGPL-3.0-only
//! This computer's status for the phone: the battery from UPower (or none), free storage, CPU and
//! memory. Read when it's sent: on connect, when the battery changes, and when the phone asks.

use std::sync::Mutex;
use std::time::{Duration, Instant};

use futures_util::StreamExt;
use seam_computer::features::status::Battery;
use seam_core::proto::v1::Status;

const UPOWER: &str = "org.freedesktop.UPower";
const DISPLAY: &str = "/org/freedesktop/UPower/devices/DisplayDevice";

pub struct LinuxBattery {
    cpu: Mutex<Option<(u64, u64, Instant)>>,
}

impl LinuxBattery {
    pub fn new() -> Self {
        Self {
            cpu: Mutex::new(None),
        }
    }
}

impl Battery for LinuxBattery {
    fn read(&self) -> Option<Status> {
        let mut s = battery().unwrap_or(Status {
            no_battery: true,
            ..Default::default()
        });
        if let Some((free, total)) = storage() {
            s.storage_free_bytes = free;
            s.storage_total_bytes = total;
        }
        if let Some((used, total)) = memory() {
            s.memory_used_bytes = used;
            s.memory_total_bytes = total;
            s.cpu_pct = self.cpu_busy();
        }
        Some(s)
    }
}

impl LinuxBattery {
    /// The share of time every core was busy since the last reading. A last reading older than
    /// 30 s would average over all that time, so then it reads twice, a quarter of a second apart.
    fn cpu_busy(&self) -> Option<u32> {
        let stale =
            crate::lock(&self.cpu).is_none_or(|(_, _, at)| at.elapsed() > Duration::from_secs(30));
        if stale {
            self.sample();
            std::thread::sleep(Duration::from_millis(250));
        }
        self.sample()
    }

    fn sample(&self) -> Option<u32> {
        let (busy, idle) = cpu_ticks()?;
        let mut last = crate::lock(&self.cpu);
        let (db, di) = last.map_or((busy, idle), |(b, i, _)| {
            (busy.saturating_sub(b), idle.saturating_sub(i))
        });
        *last = Some((busy, idle, Instant::now()));
        let total = db + di;
        (total > 0).then(|| (db * 100 / total) as u32)
    }
}

/// Busy and idle ticks over every core, from the first line of /proc/stat.
fn cpu_ticks() -> Option<(u64, u64)> {
    parse_cpu(&std::fs::read_to_string("/proc/stat").ok()?)
}

pub fn parse_cpu(stat: &str) -> Option<(u64, u64)> {
    let n: Vec<u64> = stat
        .lines()
        .next()?
        .split_whitespace()
        .skip(1)
        .filter_map(|v| v.parse().ok())
        .collect();
    // user nice system idle iowait irq softirq steal
    let idle = n.get(3)? + n.get(4).unwrap_or(&0);
    let busy = n.iter().take(8).sum::<u64>() - idle;
    Some((busy, idle))
}

/// In use as the desktop's system monitor counts it: total less what's available.
fn memory() -> Option<(u64, u64)> {
    parse_memory(&std::fs::read_to_string("/proc/meminfo").ok()?)
}

pub fn parse_memory(info: &str) -> Option<(u64, u64)> {
    let kb = |key: &str| -> Option<u64> {
        info.lines()
            .find(|l| l.starts_with(key))?
            .split_whitespace()
            .nth(1)?
            .parse::<u64>()
            .ok()
            .map(|k| k * 1024)
    };
    let (total, available) = (kb("MemTotal:")?, kb("MemAvailable:")?);
    Some((total.saturating_sub(available), total))
}

/// Free and total space where the user's files are.
fn storage() -> Option<(u64, u64)> {
    let home = std::env::var("HOME").ok()?;
    let path = std::ffi::CString::new(home).ok()?;
    let mut v: libc::statvfs = unsafe { std::mem::zeroed() };
    // SAFETY: a valid C string and a zeroed struct the call fills in
    if unsafe { libc::statvfs(path.as_ptr(), &mut v) } != 0 {
        return None;
    }
    let block = v.f_frsize as u64;
    Some((v.f_bavail as u64 * block, v.f_blocks as u64 * block))
}

/// The laptop's battery from the kernel (what UPower reads too). None on a computer without one.
fn battery() -> Option<Status> {
    battery_in(std::path::Path::new("/sys/class/power_supply"))
}

/// The first system battery under `root` (tests pass a made-up tree).
pub fn battery_in(root: &std::path::Path) -> Option<Status> {
    let mut dirs: Vec<_> = std::fs::read_dir(root)
        .ok()?
        .flatten()
        .map(|e| e.path())
        .collect();
    dirs.sort();
    let read = |d: &std::path::Path, f: &str| {
        std::fs::read_to_string(d.join(f))
            .ok()
            .map(|s| s.trim().to_string())
    };
    let num = |d: &std::path::Path, f: &str| read(d, f).and_then(|s| s.parse::<u64>().ok());
    let dir = dirs.into_iter().find(|d| {
        read(d, "type").as_deref() == Some("Battery")
            && read(d, "scope").as_deref() != Some("Device")
            && read(d, "present").as_deref() != Some("0")
    })?;
    let pct = num(&dir, "capacity").unwrap_or(0).min(100) as u32;
    let status = read(&dir, "status").unwrap_or_default();
    let charging =
        status == "Charging" || status == "Full" || (status == "Not charging" && on_mains(root));
    // Energy in µWh and power in µW, or charge in µAh and current in µA (then × voltage)
    let (now, full, rate) = match (
        num(&dir, "energy_now"),
        num(&dir, "energy_full"),
        num(&dir, "power_now"),
    ) {
        (Some(n), Some(f), p) => (n, f, p.unwrap_or(0)),
        _ => {
            let v = num(&dir, "voltage_now").unwrap_or(0);
            let to_energy = |x: u64| x.saturating_mul(v) / 1_000_000;
            (
                to_energy(num(&dir, "charge_now")?),
                to_energy(num(&dir, "charge_full")?),
                to_energy(num(&dir, "current_now").unwrap_or(0)),
            )
        }
    };
    let minutes = |energy: u64| {
        (rate > 0)
            .then(|| (energy.saturating_mul(60) / rate) as u32)
            .filter(|m| *m > 0)
    };
    Some(Status {
        battery_pct: pct,
        charging,
        power_mw: (rate > 0).then_some((rate / 1000) as u32),
        minutes_to_full: if status == "Charging" {
            minutes(full.saturating_sub(now))
        } else {
            None
        },
        minutes_left: if status == "Discharging" {
            minutes(now)
        } else {
            None
        },
        ..Default::default()
    })
}

/// Whether a mains adapter is plugged in.
fn on_mains(root: &std::path::Path) -> bool {
    std::fs::read_dir(root)
        .into_iter()
        .flatten()
        .flatten()
        .any(|e| {
            let d = e.path();
            std::fs::read_to_string(d.join("type")).is_ok_and(|t| t.trim() == "Mains")
                && std::fs::read_to_string(d.join("online")).is_ok_and(|o| o.trim() == "1")
        })
}

/// Calls `changed` whenever the battery's level or charging changes.
pub async fn watch(changed: impl Fn() + Send + 'static) {
    let Ok(conn) = zbus::Connection::system().await else {
        return;
    };
    let Ok(builder) = zbus::fdo::PropertiesProxy::builder(&conn)
        .destination(UPOWER)
        .and_then(|b| b.path(DISPLAY))
    else {
        return;
    };
    let Ok(p) = builder.build().await else { return };
    let Ok(mut changes) = p.receive_properties_changed().await else {
        return;
    };
    while let Some(signal) = changes.next().await {
        if let Ok(args) = signal.args()
            && ["Percentage", "State"]
                .iter()
                .any(|k| args.changed_properties().contains_key(*k))
        {
            changed();
        }
    }
}

#[cfg(test)]
mod tests;
