// SPDX-License-Identifier: AGPL-3.0-only
//! Pairing through Tor when Tor misbehaves once (#92): with a code showing and no phone yet, the
//! computer itself tries again (Tor restarts at once; a failed service launch goes again on the
//! next tick of the clipboard poll, which runs paired or not). The timers run on tokio's paused
//! clock, so the minutes a code shows pass at once.

use std::time::Duration;

use super::*;
use crate::tor::TorBlock;

/// How long the phone keeps trying a scanned code before it gives up.
const PHONE_GIVES_UP: Duration = Duration::from_secs(3 * 60);

/// Tor that fails the way a real network can: its first start blocked, or its first launches of
/// a service refused. Then it works.
#[derive(Default)]
struct Flaky {
    block_first_start: bool,
    refuse_launches: Mutex<u32>,
    starts: Mutex<u32>,
    hosted: Mutex<Vec<TorServiceSpec>>,
}

impl TorRunner for Flaky {
    fn start(&self, on_state: StateSink) {
        let first = {
            let mut n = self.starts.lock().unwrap();
            *n += 1;
            *n == 1
        };
        on_state(if first && self.block_first_start {
            TorState::Blocked(TorBlock::Filtered)
        } else {
            TorState::Ready
        });
    }
    fn host(&self, spec: &TorServiceSpec, _: StreamSink) -> Option<Box<dyn TorHosted>> {
        let mut refuse = self.refuse_launches.lock().unwrap();
        if *refuse > 0 {
            *refuse -= 1;
            return None;
        }
        self.hosted.lock().unwrap().push(spec.clone());
        Some(Box::new(Up))
    }
}

impl Flaky {
    fn is_hosted(&self, spec: &TorServiceSpec) -> bool {
        self.hosted.lock().unwrap().contains(spec)
    }
}

/// A computer with no phone yet, Tor on and Same Wi‑Fi off, showing a pairing code. Returns the
/// code's service. `codes_before`: codes shown and cancelled first, so Tor is already running.
/// `refuse_its_launch`: launching this code's service fails, once.
async fn showing_a_code(
    tor: Arc<Flaky>,
    codes_before: usize,
    refuse_its_launch: bool,
) -> (Computer, TorServiceSpec, tempfile::TempDir) {
    let dir = tempfile::tempdir().unwrap();
    let (snapshots, mut rx) = watch::channel(None);
    let os = Os {
        keys: Arc::new(Keys::default()),
        pasteboard: Arc::new(Board::default()),
        notifications: Arc::new(Quiet),
        battery: Arc::new(Quiet),
        same_wifi: Arc::new(Quiet),
        listener: Arc::new(Watch(snapshots)),
    };
    let settings = Settings {
        folder: dir.path().to_owned(),
        app_version: "test".into(),
        platform: "macos".into(),
        legacy: Legacy {
            wifi: Some(false),
            ..Default::default()
        },
    };
    let computer = Computer::start_on_paused(
        &tokio::runtime::Handle::current(),
        settings,
        os,
        tor.clone(),
    );
    until(&mut rx, |s| s.health == Health::Ready).await;
    for _ in 0..codes_before {
        computer.start_pairing("Mac".into());
        until(&mut rx, |s| s.tor_state == TorState::Ready).await;
        computer.cancel_pairing();
        until(&mut rx, |s| {
            s.session.as_ref().unwrap().tor_services.is_empty()
        })
        .await;
    }
    if refuse_its_launch {
        *tor.refuse_launches.lock().unwrap() = 1;
    }
    computer.start_pairing("Mac".into());
    let s = until(&mut rx, |s| {
        matches!(
            s.session.as_ref().unwrap().pairing,
            PairingState::Waiting { .. }
        )
    })
    .await;
    let service = s.session.unwrap().tor_services[0].clone();
    (computer, service, dir)
}

/// Waits, on the paused clock, until `done` or the phone would have given up.
async fn before_the_phone_gives_up(done: impl Fn() -> bool) -> bool {
    let end = tokio::time::Instant::now() + PHONE_GIVES_UP;
    while tokio::time::Instant::now() < end {
        if done() {
            return true;
        }
        tokio::time::sleep(Duration::from_millis(500)).await;
    }
    done()
}

#[tokio::test(start_paused = true)]
async fn issue_92_tor_blocked_once_while_a_code_shows_is_started_again() {
    let tor = Arc::new(Flaky {
        block_first_start: true,
        ..Default::default()
    });
    let (_computer, service, _dir) = showing_a_code(tor.clone(), 0, false).await;
    assert!(
        before_the_phone_gives_up(|| tor.is_hosted(&service)).await,
        "Tor started {} time(s); the code's service was never published",
        tor.starts.lock().unwrap()
    );
}

#[tokio::test(start_paused = true)]
async fn issue_92_a_code_whose_service_failed_to_launch_is_launched_again() {
    let tor = Arc::new(Flaky::default());
    // Tor is already running (an earlier code), so nothing from Tor will nudge this computer again
    let (_computer, service, _dir) = showing_a_code(tor.clone(), 1, true).await;
    assert!(
        before_the_phone_gives_up(|| tor.is_hosted(&service)).await,
        "the code's service failed to launch once and was never launched again"
    );
}
