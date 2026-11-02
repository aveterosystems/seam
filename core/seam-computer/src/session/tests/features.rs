// SPDX-License-Identifier: AGPL-3.0-only
//! The features behind the session: what arrives from the phone, what goes back, and that
//! forgetting the phone clears them all.

use std::sync::atomic::Ordering;

use prost::Message;
use seam_core::proto::v1::{
    self, Notification, NotificationSettings, Payload, control, payload::Body,
};

use super::rig::*;
use crate::Event;
#[allow(unused_imports)]
use crate::features::{clipboard, notifications};

fn note(key: &str) -> Notification {
    Notification {
        key: key.into(),
        ..Default::default()
    }
}

fn settings(s: NotificationSettings) -> Payload {
    body(Body::NotificationSettings(s))
}

fn clip_texts(rig: &Rig) -> Vec<String> {
    rig.session
        .features
        .clipboard
        .clips()
        .iter()
        .map(|c| c.text.clone())
        .collect()
}

#[tokio::test]
async fn a_clip_from_the_phone_is_pasted_here_and_not_sent_back() {
    let mut rig = Rig::new().await;
    rig.tor_up().await;
    assert!(rig.receive(clip("from the phone")).await);
    assert_eq!(rig.pasteboard.text().as_deref(), Some("from the phone"));
    assert_eq!(clip_texts(&rig), ["from the phone"]);
    assert!(
        rig.session
            .features
            .clipboard
            .clips()
            .iter()
            .all(|c| c.from_phone)
    );
    rig.advance(1.0).await;
    assert!(clips(&rig.on_tor()).is_empty());
}

#[tokio::test]
async fn a_copy_here_goes_to_the_phone_only_with_sync_on() {
    let mut rig = Rig::new().await;
    rig.tor_up().await;
    rig.pasteboard.copy("one");
    rig.advance(1.0).await;
    assert_eq!(clips(&rig.on_tor()), ["one"]);
    rig.clipboard(clipboard::Command::SetSync(false)).await;
    rig.pasteboard.copy("private");
    rig.advance(1.0).await;
    rig.clipboard(clipboard::Command::SetSync(true)).await;
    rig.advance(1.0).await;
    assert_eq!(
        clips(&rig.on_tor()),
        ["one"],
        "nothing copied while off goes, even after it's back on"
    );
    assert!(
        clip_texts(&rig).is_empty(),
        "turning sync off takes the history off the screen (SR-10-04)"
    );
}

#[tokio::test]
async fn the_phone_hears_sync_off_and_on_in_a_hello() {
    let mut rig = Rig::new().await;
    rig.tor_up().await;
    rig.clipboard(clipboard::Command::SetSync(false)).await;
    rig.clipboard(clipboard::Command::SetSync(false)).await; // no change, no Hello
    rig.clipboard(clipboard::Command::SetSync(true)).await;
    let caps: Vec<Vec<String>> = rig
        .on_tor()
        .iter()
        .filter_map(hello_of)
        .map(|h| h.capabilities.clone())
        .collect();
    let has = |c: &Vec<String>, s: &str| c.iter().any(|x| x == s);
    assert_eq!(
        caps.iter()
            .map(|c| has(c, "clipboard.v1"))
            .collect::<Vec<_>>(),
        [true, false, true]
    );
    assert!(
        caps.iter().all(|c| has(c, "status.v1")),
        "only the clipboard is left out"
    );
}

#[tokio::test]
async fn sr_29_05_turning_sync_off_mid_read_stops_that_copy() {
    let mut rig = Rig::new().await;
    rig.tor_up().await;
    let sync = rig.sync_on.clone();
    // The app's switch flips the shared flag at once, while the read is still in progress
    *lock(&rig.pasteboard.while_reading) =
        Some(Box::new(move || sync.store(false, Ordering::SeqCst)));
    rig.pasteboard.copy("private");
    rig.advance(1.0).await;
    assert!(clips(&rig.on_tor()).is_empty());
}

#[tokio::test]
async fn a_password_managers_copy_is_never_sent() {
    let mut rig = Rig::new().await;
    rig.tor_up().await;
    rig.pasteboard.copy_typed(
        "hunter2",
        &["public.utf8-plain-text", "org.nspasteboard.ConcealedType"],
    );
    rig.advance(1.0).await;
    assert!(clips(&rig.on_tor()).is_empty());
}

#[tokio::test]
async fn a_copy_while_the_phone_is_away_goes_when_its_back() {
    let mut rig = Rig::new().await;
    rig.pasteboard.copy("older");
    rig.advance(1.0).await;
    rig.pasteboard.copy("latest");
    rig.advance(1.0).await;
    rig.tor_up().await;
    assert_eq!(clips(&rig.on_tor()), ["latest"]);
}

#[tokio::test]
async fn copying_a_phone_clip_again_doesnt_send_it_back() {
    let mut rig = Rig::new().await;
    rig.tor_up().await;
    rig.clipboard(clipboard::Command::CopyAgain("again".into()))
        .await;
    rig.advance(1.0).await;
    assert_eq!(rig.pasteboard.text().as_deref(), Some("again"));
    assert!(clips(&rig.on_tor()).is_empty());
}

#[tokio::test]
async fn a_copy_here_is_in_the_history_until_cleared() {
    let mut rig = Rig::new().await;
    rig.tor_up().await;
    rig.pasteboard.copy("mine");
    rig.advance(1.0).await;
    let c = rig.session.features.clipboard.clips();
    assert_eq!(
        (c.len(), c[0].text.as_str(), c[0].from_phone),
        (1, "mine", false)
    );
    rig.clipboard(clipboard::Command::ClearClips).await;
    assert!(rig.session.features.clipboard.clips().is_empty());
}

#[tokio::test]
async fn only_what_arrives_from_the_phone_is_announced() {
    let mut rig = Rig::new().await;
    rig.tor_up().await;
    rig.session.core.take_events();
    rig.pasteboard.copy("mine");
    rig.advance(1.0).await;
    rig.receive(clip("theirs")).await;
    let n = body(Body::Notification(Notification {
        app_name: "Chat".into(),
        posted_at: 1,
        ..note("a")
    }));
    rig.receive(n.clone()).await;
    rig.receive(n).await; // the same one again isn't new
    assert_eq!(
        rig.session.core.take_events(),
        [
            Event::ClipFromPhone,
            Event::NotificationFromPhone {
                app_name: "Chat".into()
            }
        ]
    );
    assert_eq!(
        rig.session.features.notifications.arrivals(),
        1,
        "the bell rings once"
    );
}

fn statuses(rig: &mut Rig) -> Vec<u32> {
    rig.on_tor()
        .iter()
        .filter_map(|p| match &p.body {
            Some(Body::Status(s)) => Some(s.battery_pct),
            _ => None,
        })
        .collect()
}

#[tokio::test]
async fn this_computers_battery_goes_when_the_phone_connects_and_when_it_changes() {
    let mut rig = Rig::new().await;
    *lock(&rig.saved.own_status) = Some(v1::Status {
        battery_pct: 64,
        charging: true,
        ..Default::default()
    });
    rig.battery_changed().await;
    assert!(
        statuses(&mut rig).is_empty(),
        "nothing is queued while the phone is away"
    );
    rig.tor_up().await;
    assert_eq!(statuses(&mut rig), [64]);
    *lock(&rig.saved.own_status) = Some(v1::Status {
        battery_pct: 63,
        ..Default::default()
    });
    rig.battery_changed().await;
    assert_eq!(statuses(&mut rig), [64, 63]);
}

#[tokio::test]
async fn when_the_phone_unpairs_every_features_data_goes() {
    let mut rig = Rig::new().await;
    rig.tor_up().await;
    rig.receive(body(Body::Status(v1::Status {
        battery_pct: 50,
        ..Default::default()
    })))
    .await;
    rig.receive(clip("a clip")).await;
    rig.receive(settings(NotificationSettings {
        enabled: true,
        ..Default::default()
    }))
    .await;
    rig.receive(body(Body::Notification(note("k")))).await;
    rig.receive(control_payload(control::Body::Unpair(Default::default())))
        .await;
    let state = rig.session.state();
    let f = &rig.session.features;
    assert_eq!(state.phone, None);
    assert!(f.status.status().is_none());
    assert!(f.clipboard.clips().is_empty());
    assert!(f.notifications.shown().is_empty());
    assert!(f.notifications.settings().is_none());
    assert!(lock(&rig.centre.shown).is_empty());
    assert!(rig.mac().peers().is_empty());
    assert!(
        state.tor_services.is_empty() && rig.mac().app_data().is_none(),
        "its onion service goes too"
    );
    assert_eq!(rig.tor.closed(), [rig.phone_id()]);
}

#[tokio::test]
async fn sr_7_04_an_unpair_that_cant_be_saved_sends_no_goodbye() {
    let mut rig = Rig::new().await;
    rig.tor_up().await;
    rig.lan_up().await;
    let (tor, lan) = (rig.tor.frames().len(), rig.lan.frames().len());
    rig.saved.failing.store(true, Ordering::SeqCst);
    assert!(!rig.session.unpair_phone().await);
    assert_eq!(rig.tor.frames().len(), tor);
    assert_eq!(rig.lan.frames().len(), lan);
}

#[tokio::test]
async fn sr_29_06_unpairing_with_no_phone_has_nothing_to_forget() {
    let mut rig = Rig::unpaired().await;
    rig.tor_up().await;
    assert!(rig.session.unpair_phone().await);
    assert!(rig.tor.frames().is_empty(), "and nothing is sent");
}

#[tokio::test]
async fn unpairing_tells_the_phone_once_on_the_path_thats_up() {
    let mut rig = Rig::new().await;
    rig.tor_up().await;
    rig.receive(body(Body::Status(v1::Status {
        battery_pct: 50,
        ..Default::default()
    })))
    .await;
    let before = rig.tor.delivered().len();
    assert!(rig.session.unpair_phone().await);
    let after = rig.tor.delivered()[before..].to_vec();
    assert_eq!(after.len(), 1);
    let opened = rig.phone.open(&after[0].encode_to_vec()).unwrap();
    let goodbye = Payload::decode(opened.payload.as_slice()).unwrap();
    assert!(
        matches!(&goodbye.body, Some(Body::Control(c)) if matches!(c.body, Some(control::Body::Unpair(_))))
    );
    assert_eq!(rig.tor.closed(), [rig.phone_id()], "then the link closes");
    assert!(
        rig.session.state().tor_services.is_empty(),
        "and the service goes"
    );
    assert!(rig.lan.frames().is_empty());
    assert!(rig.session.features.status.status().is_none() && rig.mac().peers().is_empty());
}
