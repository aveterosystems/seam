// SPDX-License-Identifier: AGPL-3.0-only
//! What the session waits for. A timer never runs code itself: when it's due, its [`Due`] goes
//! back to the session, in order with everything else, so tests can move time on instead of waiting.

use std::time::Duration;

use seam_core::links::Path;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Due {
    /// An envelope sent on `path` hasn't been acked in time.
    AckOverdue { msg_id: Vec<u8>, path: Path },
    /// A timed pause on the phone ended.
    PauseEnded,
    /// The pairing code's five minutes are up.
    PairingExpired,
    /// The PairAccept wasn't acked in time.
    AcceptOverdue(Vec<u8>),
    /// Look at the pasteboard again.
    ClipboardPoll,
    /// A Reconnect has shown for the usual time to connect.
    ReconnectEnded,
}

pub trait Timers: Send + Sync {
    /// Unix ms.
    fn now_ms(&self) -> i64;
    /// `due` comes back to the session after `delay`, unless the token is cancelled first.
    fn after(&self, delay: Duration, due: Due) -> TimerToken;
}

/// Cancels its timer.
pub struct TimerToken(Box<dyn Fn() + Send + Sync>);

impl TimerToken {
    pub fn new(cancel: impl Fn() + Send + Sync + 'static) -> Self {
        Self(Box::new(cancel))
    }

    pub fn cancel(&self) {
        (self.0)();
    }
}

impl std::fmt::Debug for TimerToken {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("TimerToken")
    }
}

/// Timers on tokio: each due goes to `deliver` (the session's queue). Deadlines are wall-clock
/// times: tokio's clock stops while a Mac sleeps, so after `woke` each timer checks the wall clock
/// again, and one that came due during sleep fires at once.
pub struct TokioTimers {
    deliver: std::sync::Arc<dyn Fn(Due) + Send + Sync>,
    clock: crate::clock::Clock,
    woke: std::sync::Arc<tokio::sync::Notify>,
}

impl TokioTimers {
    pub fn new(
        clock: crate::clock::Clock,
        deliver: std::sync::Arc<dyn Fn(Due) + Send + Sync>,
    ) -> Self {
        Self {
            deliver,
            clock,
            woke: Default::default(),
        }
    }

    /// The computer woke from sleep.
    pub fn woke(&self) {
        self.woke.notify_waiters();
    }
}

impl Timers for TokioTimers {
    fn now_ms(&self) -> i64 {
        self.clock.now_ms()
    }

    fn after(&self, delay: Duration, due: Due) -> TimerToken {
        let (deliver, clock, woke) = (self.deliver.clone(), self.clock.clone(), self.woke.clone());
        let at = clock.now_ms() + delay.as_millis() as i64;
        let task = tokio::spawn(async move {
            loop {
                let left = at - clock.now_ms();
                if left <= 0 {
                    break;
                }
                tokio::select! {
                    () = tokio::time::sleep(Duration::from_millis(left as u64)) => {}
                    () = woke.notified() => {}
                }
            }
            deliver(due);
        });
        let abort = task.abort_handle();
        TimerToken::new(move || abort.abort())
    }
}

#[cfg(test)]
mod tests {
    use std::sync::Arc;
    use std::sync::atomic::{AtomicI64, Ordering};
    use std::time::Duration;

    use super::{Due, Timers, TokioTimers};
    use crate::clock::Clock;

    /// A pairing code's five minutes pass while the Mac sleeps: it expires on wake, not five
    /// minutes later.
    #[tokio::test]
    async fn a_timer_that_came_due_during_sleep_fires_on_wake() {
        let now = Arc::new(AtomicI64::new(1_000_000));
        let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel();
        let timers = TokioTimers::new(
            Clock::Manual(now.clone()),
            Arc::new(move |d| tx.send(d).unwrap()),
        );
        let _token = timers.after(Duration::from_secs(300), Due::PairingExpired);
        tokio::task::yield_now().await;
        now.fetch_add(301_000, Ordering::SeqCst); // asleep: tokio's clock didn't move
        timers.woke();
        let due = tokio::time::timeout(Duration::from_secs(2), rx.recv())
            .await
            .unwrap();
        assert_eq!(due, Some(Due::PairingExpired));
    }

    #[tokio::test]
    async fn a_cancelled_timer_never_fires() {
        let now = Arc::new(AtomicI64::new(0));
        let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel();
        let timers = TokioTimers::new(
            Clock::Manual(now.clone()),
            Arc::new(move |d| tx.send(d).unwrap()),
        );
        timers
            .after(Duration::from_secs(1), Due::PauseEnded)
            .cancel();
        now.fetch_add(5_000, Ordering::SeqCst);
        timers.woke();
        assert!(
            tokio::time::timeout(Duration::from_millis(200), rx.recv())
                .await
                .is_err()
        );
    }
}
