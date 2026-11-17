// SPDX-License-Identifier: AGPL-3.0-only
//! UTC timestamps, so a phone's export and a computer's line up whatever their time zones.

use std::time::{SystemTime, UNIX_EPOCH};

/// `2026-11-17T15:06:17.123Z`. A clock before 1970 shows as 1970.
pub(crate) fn utc(t: SystemTime) -> String {
    let ms = t.duration_since(UNIX_EPOCH).map_or(0, |d| d.as_millis());
    let secs = (ms / 1000) as i64;
    let (days, rem) = (secs.div_euclid(86_400), secs.rem_euclid(86_400));
    let (y, m, d) = civil(days);
    format!(
        "{y:04}-{m:02}-{d:02}T{:02}:{:02}:{:02}.{:03}Z",
        rem / 3600,
        rem % 3600 / 60,
        rem % 60,
        ms % 1000
    )
}

/// Days since 1970-01-01 to a calendar date (Howard Hinnant's `civil_from_days`).
fn civil(days: i64) -> (i64, u32, u32) {
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z.rem_euclid(146_097);
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = (doy - (153 * mp + 2) / 5 + 1) as u32;
    let m = if mp < 10 { mp + 3 } else { mp - 9 } as u32;
    let y = yoe + era * 400 + i64::from(m <= 2);
    (y, m, d)
}

#[cfg(test)]
mod tests {
    use std::time::Duration;

    use super::*;

    #[test]
    fn known_instants() {
        let at = |s: u64, ms: u64| utc(UNIX_EPOCH + Duration::from_millis(s * 1000 + ms));
        assert_eq!(at(0, 0), "1970-01-01T00:00:00.000Z");
        // 2000-02-29 (a leap day in a century leap year), 12:34:56.789
        assert_eq!(at(951_827_696, 789), "2000-02-29T12:34:56.789Z");
        // The screenshot in #92: 2026-11-13 11:52:37 (UTC)
        assert_eq!(at(1_794_570_757, 0), "2026-11-13T11:52:37.000Z");
    }
}
