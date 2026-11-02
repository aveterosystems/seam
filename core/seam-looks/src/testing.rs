// SPDX-License-Identifier: AGPL-3.0-only
//! A UTC `TimeWords` and the usual names, for tests.

use crate::{Names, Side, TimeWords};

pub struct Utc;

const DAY: i64 = 86_400_000;

impl TimeWords for Utc {
    fn time(&self, ms: i64) -> String {
        let m = ms.rem_euclid(DAY) / 60_000;
        format!("{:02}:{:02}", m / 60, m % 60)
    }
    fn date(&self, ms: i64) -> String {
        format!("day {}", ms.div_euclid(DAY))
    }
    fn days_between(&self, ms: i64, now_ms: i64) -> i32 {
        (now_ms.div_euclid(DAY) - ms.div_euclid(DAY)) as i32
    }
}

pub fn mac() -> Names {
    Names::new(Side::Computer, "this Mac", "your phone")
}

pub fn phone() -> Names {
    Names::new(Side::Phone, "this phone", "your Mac")
}

/// 2 Nov 2026, 12:00 UTC.
pub const NOW: i64 = 1_793_620_800_000;
pub const MIN: i64 = 60_000;
pub const HOUR: i64 = 60 * MIN;
