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
