// SPDX-License-Identifier: AGPL-3.0-only
//! Time in the user's own format. The app implements this once, from its platform's formatter,
//! so "14:02" or "2:02 PM" and the date follow the user's locale and time zone.

pub trait TimeWords: Send + Sync {
    /// A clock time: "14:02".
    fn time(&self, ms: i64) -> String;
    /// A short date: "3 Nov".
    fn date(&self, ms: i64) -> String;
    /// Calendar days from `ms` to `now_ms` in the user's time zone: 0 the same day, 1 if `ms`
    /// was yesterday, -1 if it's tomorrow.
    fn days_between(&self, ms: i64, now_ms: i64) -> i32;
}
