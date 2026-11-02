// SPDX-License-Identifier: AGPL-3.0-only
//! What the session keeps for the app: the switches, the clipboard setting, the pairing date and
//! when the phone was last seen.

use seam_core::links::Path;

use super::rig::*;
use crate::features::clipboard;
use crate::lan::LanEvent;

#[tokio::test]
async fn the_switches_are_saved_and_one_always_stays_on() {
    let mut rig = Rig::new().await;
    assert!(rig.session.set_switches(false, true));
    assert!(!lock(&rig.saved.prefs).wifi);
    assert!(
        !rig.session.set_switches(false, false),
        "one always stays on"
    );
    let s = rig.session.state();
    assert!(!s.wifi && s.tor);
    rig.restart().await;
    assert!(!rig.session.state().wifi, "kept across a restart");
}

#[tokio::test]
async fn the_clipboard_setting_is_saved() {
    let mut rig = Rig::new().await;
    rig.clipboard(clipboard::Command::SetSync(false)).await;
    assert!(!lock(&rig.saved.prefs).clipboard_sync);
}

#[tokio::test]
async fn the_phone_is_last_seen_when_its_link_goes() {
    let mut rig = Rig::new().await;
    rig.session.settle();
    assert_eq!(
        rig.session.state().last_seen,
        None,
        "never connected this run"
    );
    rig.tor_up().await;
    rig.session.settle();
    rig.advance(30.0).await;
    let gone = rig.now();
    rig.event(LanEvent::Closed(rig.phone_id()), Path::Tor).await;
    rig.session.settle();
    assert_eq!(rig.session.state().last_seen, Some(gone));
}

#[tokio::test]
async fn the_safety_code_and_pairing_date_come_with_the_phone_and_go_with_it() {
    let mut rig = Rig::new().await;
    let phone = rig.session.state().phone.unwrap();
    let code = rig.mac().safety_code(&rig.phone_id()).unwrap();
    assert_eq!(phone.safety_code, code);
    assert_eq!(phone.paired_at, None, "paired before Seam kept the date");
    lock(&rig.saved.prefs).paired_at = Some(5);
    rig.restart().await;
    assert_eq!(rig.session.state().phone.unwrap().paired_at, Some(5));
    rig.session.unpair_phone().await;
    assert_eq!(lock(&rig.saved.prefs).paired_at, None);
}
