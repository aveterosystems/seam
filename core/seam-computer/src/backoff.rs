// SPDX-License-Identifier: AGPL-3.0-only
//! Retry delays: doubling from 1 s up to 60 s, scaled by 75–100% so many clients don't retry in step.

use std::time::Duration;

/// `jitter` in 0..1.
pub fn delay(attempt: u32, jitter: f64) -> Duration {
    let base = (2f64.powi(attempt.min(30) as i32)).min(60.0);
    Duration::from_secs_f64(base * (0.75 + 0.25 * jitter))
}

#[cfg(test)]
mod tests {
    use super::delay;

    #[test]
    fn doubles_from_one_second_and_caps_at_sixty() {
        assert_eq!(delay(0, 0.0).as_secs_f64(), 0.75);
        assert_eq!(delay(1, 0.0).as_secs_f64(), 1.5);
        assert!(delay(0, 0.999).as_secs_f64() < 1.0);
        assert!(delay(6, 0.999).as_secs_f64() <= 60.0);
        assert_eq!(
            delay(100, 0.0).as_secs_f64(),
            45.0,
            "capped, still jittered"
        );
    }
}
