// SPDX-License-Identifier: AGPL-3.0-only
//! Wall-clock time for the session. Production reads the system clock (it keeps counting while the
//! computer sleeps); tests run it from tokio's paused clock, so moving time on moves both.

use std::time::{SystemTime, UNIX_EPOCH};

#[derive(Debug, Clone)]
pub enum Clock {
    System,
    /// Unix ms at `start`, then tokio's (pausable) clock.
    Test {
        base_ms: i64,
        start: tokio::time::Instant,
    },
    /// Tests: only moves when set, like a wall clock that jumped while the computer slept.
    #[cfg(test)]
    Manual(std::sync::Arc<std::sync::atomic::AtomicI64>),
}

impl Clock {
    /// Tests: `base_ms` now, moving with `tokio::time::advance`.
    pub fn test(base_ms: i64) -> Self {
        Clock::Test {
            base_ms,
            start: tokio::time::Instant::now(),
        }
    }

    /// Unix ms.
    pub fn now_ms(&self) -> i64 {
        match self {
            Clock::System => SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .map_or(0, |d| d.as_millis() as i64),
            Clock::Test { base_ms, start } => base_ms + start.elapsed().as_millis() as i64,
            #[cfg(test)]
            Clock::Manual(now) => now.load(std::sync::atomic::Ordering::SeqCst),
        }
    }
}
