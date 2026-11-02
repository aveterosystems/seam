// SPDX-License-Identifier: AGPL-3.0-only
//! The Mac's whole surface, driven as Swift drives it: through the foreign interfaces.

use std::sync::mpsc::{Receiver, Sender, channel};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use super::*;
use crate::clipboard::PasteboardItem;

struct Keys(Mutex<Option<Vec<u8>>>);
impl KeyStore for Keys {
    fn storage_key(&self, create: bool) -> Result<Option<Vec<u8>>, OsError> {
        let mut k = self.0.lock().unwrap();
        if k.is_none() && create {
            *k = Some(vec![4; 32]);
        }
        Ok(k.clone())
    }
}

struct Quiet;
#[async_trait::async_trait]
impl Pasteboard for Quiet {
    async fn change_count(&self) -> i64 {
        0
    }
    async fn first_item(&self, _: i64) -> Option<PasteboardItem> {
        None
    }
    async fn write(&self, _: String) -> i64 {
        1
    }
}
#[async_trait::async_trait]
impl NotificationCentre for Quiet {
    async fn show(&self, _: Vec<u8>, _: Option<Vec<u8>>) {}
    async fn dismiss(&self, _: String) {}
    async fn remove_all(&self) {}
}
impl Battery for Quiet {
    fn read(&self) -> Option<Vec<u8>> {
        None
    }
}
impl SameWifi for Quiet {
    fn announce(&self, _: u16) {}
    fn stop_announcing(&self) {}
    fn is_lan_address(&self, _: String) -> bool {
        false
    }
}

struct Hear(Mutex<Sender<ComputerSnapshot>>);
impl ComputerListener for Hear {
    fn on_snapshot(&self, s: ComputerSnapshot) {
        let _ = self.0.lock().unwrap().send(s);
    }
    fn on_event(&self, _: Event) {}
}

fn until(
    rx: &Receiver<ComputerSnapshot>,
    what: impl Fn(&ComputerSnapshot) -> bool,
) -> ComputerSnapshot {
    loop {
        let s = rx
            .recv_timeout(Duration::from_secs(10))
            .expect("never got there");
        if what(&s) {
            return s;
        }
    }
}

#[test]
fn starts_shows_a_code_and_keeps_a_switch_across_a_restart() {
    let dir = tempfile::tempdir().unwrap();
    let start = |legacy: LegacyPreferences| {
        let (tx, rx) = channel();
        let settings = ComputerSettings {
            folder: dir.path().to_string_lossy().into_owned(),
            app_version: "test".into(),
            platform: "macos".into(),
            legacy,
        };
        let c = Computer::new(
            settings,
            Arc::new(Keys(Mutex::new(Some(vec![4; 32])))),
            Arc::new(Quiet),
            Arc::new(Quiet),
            Arc::new(Quiet),
            Arc::new(Quiet),
            Arc::new(Hear(Mutex::new(tx))),
        );
        (c, rx)
    };
    let legacy = LegacyPreferences {
        wifi: Some(false),
        ..Default::default()
    };
    let (c, rx) = start(legacy);
    let s = until(&rx, |s| matches!(s.health, Health::Ready));
    let session = s.session.unwrap();
    assert!(
        !session.wifi && session.tor,
        "the Swift app's switch, copied across"
    );
    assert!(s.features.unwrap().clipboard.sync_on);

    c.start_pairing("Mac".into());
    let s = until(&rx, |s| {
        matches!(
            s.session.as_ref().unwrap().pairing,
            PairingStep::Waiting { .. }
        )
    });
    let PairingStep::Waiting { qr, .. } = s.session.unwrap().pairing else {
        unreachable!()
    };
    assert!(qr.starts_with("seam://pair?v=2&"));

    // Bytes that aren't the message they claim to be are ignored, not a crash
    c.feature(FeatureCommand::ChangeNotificationSettings {
        change: vec![0xFF; 7],
    });
    c.set_tor(false); // the last switch stays on
    c.feature(FeatureCommand::SetClipboardSync { on: false });
    until(&rx, |s| !s.features.as_ref().unwrap().clipboard.sync_on);
    drop(c);

    let (_c, rx) = start(LegacyPreferences {
        wifi: Some(true),
        ..Default::default()
    });
    let s = until(&rx, |s| matches!(s.health, Health::Ready));
    let session = s.session.unwrap();
    assert!(
        !session.wifi && session.tor,
        "the saved switches count now, not the old ones"
    );
    assert!(!s.features.unwrap().clipboard.sync_on);
}
