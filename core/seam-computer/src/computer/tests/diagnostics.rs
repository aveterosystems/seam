// SPDX-License-Identifier: AGPL-3.0-only
//! The computer's diagnostics export (specs/diagnostics.md): what it says when Tor misbehaves.

use std::time::Duration;

use super::*;

/// Tor that's ready at once, but refuses to launch any service.
struct RefusingTor;

impl TorRunner for RefusingTor {
    fn start(&self, on_state: StateSink) {
        on_state(TorState::Ready);
    }
    fn host(&self, _: &TorServiceSpec, _: StreamSink) -> Option<Box<dyn TorHosted>> {
        None
    }
}

#[tokio::test(start_paused = true)]
async fn a_failed_launch_shows_in_the_diagnostics_export() {
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
    let computer = Computer::start_on(
        &tokio::runtime::Handle::current(),
        settings,
        os,
        Arc::new(RefusingTor),
    );
    until(&mut rx, |s| s.health == Health::Ready).await;
    computer.start_pairing("Mac".into());
    until(&mut rx, |s| s.tor_state == TorState::Ready).await;
    tokio::time::sleep(Duration::from_secs(1)).await;

    let out = computer.diagnostics("macOS 26.1");
    assert!(
        out.starts_with("Seam test on macos, macOS 26.1\nexported "),
        "{out}"
    );
    assert!(out.contains("pairing code shown"), "{out}");
    assert!(out.contains("tor service launch failed"), "{out}");
}
