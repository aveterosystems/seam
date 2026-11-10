// SPDX-License-Identifier: AGPL-3.0-only
//! Small words several looks share: sizes and how long ago.

use crate::TimeWords;

/// Whole GB, MB under 1 GB, TB to one place over 1 TB: the way phones label storage.
pub fn size(bytes: u64) -> String {
    let b = bytes as f64;
    if b >= 1e12 {
        let tb = (b / 1e11).round() / 10.0;
        return if tb.fract() == 0.0 {
            format!("{} TB", tb as u64)
        } else {
            format!("{tb} TB")
        };
    }
    if b >= 1e9 {
        return format!("{} GB", (b / 1e9).round() as u64);
    }
    format!("{} MB", (b / 1e6).round() as u64)
}

/// Memory, counted the way Apple and Android label it (24 GB is 24 × 1024³ bytes), to one place
/// under 10 GB: "9.5 GB", "24 GB".
pub fn memory(bytes: u64) -> String {
    let gb = bytes as f64 / (1u64 << 30) as f64;
    if gb < 10.0 {
        let one = (gb * 10.0).round() / 10.0;
        if one.fract() == 0.0 {
            format!("{} GB", one as u64)
        } else {
            format!("{one} GB")
        }
    } else {
        format!("{} GB", gb.round() as u64)
    }
}

/// Power, to one place under 10 W: "1.2 W", "6 W", "18 W".
pub fn watts(mw: u32) -> String {
    let w = mw as f64 / 1000.0;
    if w < 10.0 {
        let one = (w * 10.0).round() / 10.0;
        if one.fract() == 0.0 {
            format!("{} W", one as u64)
        } else {
            format!("{one} W")
        }
    } else {
        format!("{} W", w.round() as u64)
    }
}

/// A battery's time: "42 min", "1 hr 5 min", "7 hr"; whole hours from 10 hours on.
pub fn duration(minutes: u32) -> String {
    let (h, m) = (minutes / 60, minutes % 60);
    match (h, m) {
        (0, m) => format!("{m} min"),
        (h, _) if h >= 10 => format!("{} hr", (minutes + 30) / 60),
        (h, 0) => format!("{h} hr"),
        (h, m) => format!("{h} hr {m} min"),
    }
}

/// "just now", "12 min ago", "today at 14:02", "yesterday at 09:15", or a date. Notifications
/// pass "now" for `just_now` (the popover prototype's format).
pub fn ago(at_ms: i64, now_ms: i64, time: &dyn TimeWords, just_now: &str) -> String {
    let seconds = (now_ms - at_ms) / 1000;
    if seconds < 60 {
        return just_now.to_string();
    }
    if seconds < 3600 {
        return format!("{} min ago", seconds / 60);
    }
    match time.days_between(at_ms, now_ms) {
        0 => format!("today at {}", time.time(at_ms)),
        1 => format!("yesterday at {}", time.time(at_ms)),
        _ => time.date(at_ms),
    }
}

/// What the shield means, when it's tapped or hovered.
pub fn encrypted(names: &crate::Names) -> String {
    format!(
        "End-to-end encrypted: only {} and {} can read what Seam sends.",
        names.this, names.peer
    )
}

/// Asked before Updates › Nightly builds turns on, in both apps.
pub const NIGHTLY_TITLE: &str = "Turn on nightly builds?";
pub const NIGHTLY_WARNING: &str = "Nightly builds are Seam's newest work, built each night from what changed that day. They're less tested than releases and may have bugs, including ones that stop syncing. Turning this off later keeps the nightly you have until a newer release comes out.";

/// The popover's queue bar: "3 waiting".
pub fn waiting(n: u32) -> String {
    format!("{n} waiting")
}

/// When a pause ends: "until 14:44", "until tomorrow 14:44", "until 3 Nov 14:44". None means
/// until it's resumed.
pub fn until(until_ms: Option<i64>, now_ms: i64, time: &dyn TimeWords) -> Option<String> {
    let ms = until_ms?;
    Some(match time.days_between(now_ms, ms) {
        0 => format!("until {}", time.time(ms)),
        1 => format!("until tomorrow {}", time.time(ms)),
        _ => format!("until {} {}", time.date(ms), time.time(ms)),
    })
}

#[cfg(test)]
mod tests;
