// SPDX-License-Identifier: AGPL-3.0-only
use std::sync::Mutex;

use super::{KeyStore, Legacy, Store, StoreError};
use crate::lock;
use crate::session::{Preferences, Storage, UNTIL_RESUMED};
use crate::testing::paired_devices;

/// The OS's secret store, in memory.
#[derive(Default)]
struct Keys(Mutex<Option<Vec<u8>>>);

impl KeyStore for Keys {
    fn storage_key(&self, create: bool) -> Result<Option<Vec<u8>>, String> {
        let mut k = lock(&self.0);
        if k.is_none() && create {
            *k = Some(vec![9; 32]);
        }
        Ok(k.clone())
    }
}

fn open(dir: &std::path::Path, keys: &Keys) -> Result<Store, StoreError> {
    Store::open(dir, keys, Legacy::default())
}

#[test]
fn first_launch_makes_an_identity_and_the_next_opens_it() {
    let dir = tempfile::tempdir().unwrap();
    let keys = Keys::default();
    let id = lock(&open(dir.path(), &keys).unwrap().device)
        .id()
        .to_owned();
    let again = open(dir.path(), &keys).unwrap();
    assert_eq!(lock(&again.device).id(), id);
    assert!(!again.pair_again);
}

/// The state file the Swift app wrote: the core's sealed blob under the Keychain key, as is.
#[test]
fn opens_the_state_file_the_swift_app_wrote() {
    let dir = tempfile::tempdir().unwrap();
    let (mac, _) = paired_devices();
    let id = mac.id().to_owned();
    std::fs::write(
        dir.path().join("state.bin"),
        mac.export_state(&[9; 32]).unwrap(),
    )
    .unwrap();
    let keys = Keys(Mutex::new(Some(vec![9; 32])));
    let store = open(dir.path(), &keys).unwrap();
    assert_eq!(lock(&store.device).id(), id);
}

/// Saved state without its key is never overwritten by a fresh identity.
#[test]
fn saved_state_without_its_key_is_unreadable_never_replaced() {
    let dir = tempfile::tempdir().unwrap();
    open(dir.path(), &Keys::default()).unwrap();
    let before = std::fs::read(dir.path().join("state.bin")).unwrap();
    assert_eq!(
        open(dir.path(), &Keys::default()).err(),
        Some(StoreError::Unreadable)
    );
    let other = Keys(Mutex::new(Some(vec![1; 32])));
    assert_eq!(open(dir.path(), &other).err(), Some(StoreError::Unreadable));
    assert_eq!(std::fs::read(dir.path().join("state.bin")).unwrap(), before);
}

#[test]
fn a_reset_keeps_the_old_files_aside_and_starts_a_new_identity() {
    let dir = tempfile::tempdir().unwrap();
    let keys = Keys::default();
    let first = open(dir.path(), &keys).unwrap();
    let old = lock(&first.device).id().to_owned();
    first.save_preferences(&Preferences {
        wifi: false,
        paused_until: Some(UNTIL_RESUMED),
        paired_at: Some(5),
        ..Default::default()
    });
    let new = Store::reset(dir.path(), &keys, 42).unwrap();
    assert_ne!(lock(&new.device).id(), old);
    assert!(dir.path().join("state.bin.unreadable-42").exists());
    let kept = new.preferences();
    assert!(!kept.wifi, "the switches stay");
    assert_eq!(
        (kept.paused_until, kept.paired_at),
        (None, None),
        "what was the phone's goes"
    );
}

#[test]
fn a_relay_era_pairing_is_dropped_and_the_phone_pairs_again() {
    let dir = tempfile::tempdir().unwrap();
    let keys = Keys::default();
    let (mac, _) = paired_devices(); // a peer, and no app data: as the relay builds left it
    let store = open(dir.path(), &keys).unwrap();
    *lock(&store.device) = mac;
    store.save().unwrap();
    store.outbox_file().file().write(b"SOB1").unwrap();
    let opened = open(dir.path(), &keys).unwrap();
    assert!(opened.pair_again);
    assert!(lock(&opened.device).peers().is_empty());
    assert!(
        !opened.outbox_file().file().path().exists(),
        "sealed for the forgotten phone"
    );
    assert!(!open(dir.path(), &keys).unwrap().pair_again, "said once");
}

#[test]
fn the_swift_apps_settings_are_copied_across_once() {
    let dir = tempfile::tempdir().unwrap();
    let legacy = Legacy {
        wifi: Some(false),
        tor: Some(true),
        clipboard_sync: Some(false),
        paused_until: Some(UNTIL_RESUMED),
        paired_at: Some(1234),
    };
    let store = Store::open(dir.path(), &Keys::default(), legacy).unwrap();
    let copied = store.preferences();
    assert_eq!(
        copied,
        Preferences {
            wifi: false,
            tor: true,
            clipboard_sync: false,
            paused_until: Some(UNTIL_RESUMED),
            paired_at: Some(1234)
        }
    );
    // Later, the file is what counts: a resume stays a resume
    store.save_preferences(&Preferences {
        paused_until: None,
        ..copied
    });
    let again = Store::open(
        dir.path(),
        &Keys(Mutex::new(Some(vec![9; 32]))),
        Legacy {
            paused_until: Some(UNTIL_RESUMED),
            ..Default::default()
        },
    )
    .unwrap();
    assert_eq!(again.preferences().paused_until, None);
}

#[test]
fn a_damaged_preferences_file_falls_back_to_defaults_and_one_switch_stays_on() {
    let dir = tempfile::tempdir().unwrap();
    let store = open(dir.path(), &Keys::default()).unwrap();
    std::fs::write(
        dir.path().join("preferences"),
        "wifi=0\ntor=0\nnonsense\npaused_until=x\n",
    )
    .unwrap();
    let p = store.preferences();
    assert!(p.wifi && !p.tor);
    assert_eq!(p.paused_until, None);
}
