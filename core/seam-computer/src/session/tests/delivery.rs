// SPDX-License-Identifier: AGPL-3.0-only
//! Getting a message to the phone, through the session's interface only.

use seam_core::links::Path;
use seam_core::proto::v1::{Hello, control};

use super::rig::*;
use crate::lan::LanEvent;
use crate::outbox::CAP;
use crate::session::Warning;

#[tokio::test]
async fn goes_on_same_wifi_first_and_once() {
    let mut rig = Rig::new().await;
    rig.tor_up().await;
    rig.lan_up().await;
    rig.send_something(1).await;
    assert_eq!(numbers(&rig.on_lan()), [1]);
    assert!(numbers(&rig.on_tor()).is_empty());
}

#[tokio::test]
async fn a_message_that_couldnt_be_saved_is_never_sent() {
    let mut rig = Rig::new().await;
    rig.tor_up().await;
    rig.saved
        .failing
        .store(true, std::sync::atomic::Ordering::SeqCst);
    rig.send_something(1).await;
    assert!(numbers(&rig.on_tor()).is_empty());
    assert_eq!(rig.session.state().warning, Some(Warning::SaveFailed));
}

#[tokio::test]
async fn nothing_goes_that_isnt_in_the_saved_outbox_first() {
    let mut rig = Rig::new().await;
    rig.send_something(1).await; // no path: it waits
    assert_eq!(rig.session.state().waiting, 1);
    assert_eq!(rig.outbox.load(rig.now()).waiting().len(), 1);
}

#[tokio::test]
async fn unacked_on_same_wifi_goes_again_through_tor_in_order_when_the_link_drops() {
    let mut rig = Rig::new().await;
    rig.lan_up().await;
    rig.send_something(1).await;
    rig.send_something(2).await;
    assert_eq!(numbers(&rig.on_lan()), [1, 2]);
    rig.event(LanEvent::Closed(rig.phone_id()), Path::Lan).await;
    rig.tor_up().await;
    assert_eq!(numbers(&rig.on_tor()), [1, 2]);
}

#[tokio::test]
async fn what_the_phone_acked_isnt_resent() {
    let mut rig = Rig::new().await;
    rig.lan_up().await;
    rig.send_something(1).await;
    let msg_id = rig.lan.delivered().last().unwrap().msg_id.clone();
    rig.event(LanEvent::Delivered(rig.phone_id(), msg_id), Path::Lan)
        .await;
    rig.event(LanEvent::Closed(rig.phone_id()), Path::Lan).await;
    rig.tor_up().await;
    assert!(numbers(&rig.on_tor()).is_empty());
}

#[tokio::test]
async fn a_new_link_picks_up_what_the_old_one_never_got_acked() {
    let mut rig = Rig::new().await;
    rig.lan_up().await;
    rig.send_something(1).await;
    rig.event(LanEvent::Authenticated(rig.phone_id()), Path::Lan)
        .await; // replaced without a close
    assert_eq!(numbers(&rig.on_lan()), [1, 1]);
}

#[tokio::test]
async fn a_send_the_tor_stream_refused_waits_for_the_phone_to_reconnect() {
    let mut rig = Rig::with(Options {
        wifi: false,
        ..Default::default()
    })
    .await;
    rig.tor
        .failing
        .store(true, std::sync::atomic::Ordering::SeqCst);
    rig.tor_up().await;
    rig.send_something(1).await;
    rig.send_something(2).await;
    assert!(rig.on_tor().is_empty());
    assert_eq!(
        rig.session.state().waiting,
        3,
        "both, and this computer's Hello"
    );
    rig.tor
        .failing
        .store(false, std::sync::atomic::Ordering::SeqCst);
    rig.event(LanEvent::Authenticated(rig.phone_id()), Path::Tor)
        .await;
    assert_eq!(numbers(&rig.on_tor()), [1, 2], "in order");
}

#[tokio::test]
async fn through_tor_a_message_stays_until_the_phone_acks_it() {
    let mut rig = Rig::with(Options {
        wifi: false,
        ..Default::default()
    })
    .await;
    rig.tor_up().await;
    rig.send_something(1).await;
    rig.send_something(2).await;
    assert_eq!(numbers(&rig.on_tor()), [1, 2]);
    let ids: Vec<_> = rig
        .tor
        .delivered()
        .iter()
        .map(|e| e.msg_id.clone())
        .collect();
    rig.event(
        LanEvent::Delivered(rig.phone_id(), ids[1].clone()),
        Path::Tor,
    )
    .await; // 1 acked (after the Hello)
    rig.event(LanEvent::Closed(rig.phone_id()), Path::Tor).await;
    let before = rig.tor.frames().len();
    rig.tor_up().await;
    let again: Vec<_> = rig.tor.delivered()[before..]
        .iter()
        .map(|e| e.msg_id.clone())
        .collect();
    assert!(!again.contains(&ids[1]), "acked: not resent");
    assert!(again.contains(&ids[2]), "not acked: resent");
}

#[tokio::test]
async fn a_path_switched_off_is_never_used() {
    let mut rig = Rig::with(Options {
        wifi: false,
        ..Default::default()
    })
    .await;
    rig.lan_up().await;
    rig.send_something(1).await;
    assert!(rig.lan.frames().is_empty());
    rig.session.set_switches(true, false);
    rig.tor_up().await;
    assert!(rig.on_tor().is_empty());
}

#[tokio::test]
async fn a_path_the_phone_switched_off_is_never_used_but_a_hello_still_gets_through() {
    let mut rig = Rig::new().await;
    rig.tor_up().await;
    // The phone says Same Wi‑Fi only, and it's away: only Tor is up
    let hello = rig.phone_hello(true, false, 0, None);
    assert!(rig.session.received(hello).await);
    assert_eq!(rig.session.state().allowed, [Path::Lan]);
    let before = rig.on_tor().len();
    rig.send_something(1).await;
    assert!(numbers(&rig.on_tor()).is_empty(), "a message waits");
    rig.session.set_switches(true, true);
    let after = rig.on_tor()[before..].to_vec();
    assert_eq!(after.len(), 1);
    let hello = hello_of(&after[0]).expect("the Hello announcing the change goes");
    assert_eq!(
        hello.allowed_transports,
        [Path::Lan.transport(), Path::Tor.transport()]
    );
}

#[tokio::test]
async fn an_older_phone_that_sent_an_empty_list_allows_everything() {
    let mut rig = Rig::new().await;
    let empty = control_payload(control::Body::Hello(Hello {
        seq: 1,
        ..Default::default()
    }));
    assert!(rig.receive(empty).await);
    assert_eq!(rig.session.state().allowed, [Path::Lan, Path::Tor]);
}

#[tokio::test]
async fn an_expired_message_is_dropped_not_sent_late() {
    let mut rig = Rig::new().await;
    rig.send_something(1).await; // 10 minutes to live
    rig.advance(660.0).await; // the core stamps sentAt with the real clock, just after the rig began
    rig.tor_up().await;
    assert!(numbers(&rig.on_tor()).is_empty());
    assert_eq!(
        rig.session.state().waiting,
        1,
        "only the new Hello, until the phone acks it"
    );
}

#[tokio::test]
async fn the_outbox_is_capped() {
    let mut rig = Rig::new().await;
    for n in 1..=(CAP as i64 + 5) {
        rig.send_something(n).await;
    }
    assert_eq!(rig.session.state().waiting, CAP);
    rig.tor_up().await;
    assert_eq!(
        numbers(&rig.on_tor()),
        (6..=CAP as i64 + 5).collect::<Vec<_>>(),
        "the oldest went"
    );
}

#[tokio::test]
async fn what_waited_is_sent_after_a_restart() {
    let mut rig = Rig::new().await;
    rig.send_something(1).await;
    rig.restart().await;
    assert_eq!(rig.session.state().waiting, 1);
    rig.tor_up().await;
    assert_eq!(numbers(&rig.on_tor()), [1]);
}

#[tokio::test]
async fn a_hello_goes_when_a_path_comes_up_and_its_seq_survives_a_restart() {
    let mut rig = Rig::new().await;
    rig.tor_up().await;
    let seqs = |rig: &mut Rig| {
        rig.on_tor()
            .iter()
            .filter_map(|p| hello_of(p).map(|h| h.seq))
            .collect::<Vec<_>>()
    };
    assert_eq!(seqs(&mut rig), [1]);
    let first = rig.tor.delivered()[0].msg_id.clone();
    rig.event(LanEvent::Delivered(rig.phone_id(), first), Path::Tor)
        .await;
    rig.restart().await;
    rig.tor_up().await;
    assert_eq!(seqs(&mut rig), [1, 2]);
}

#[tokio::test]
async fn shows_the_route_for_the_status_line() {
    let mut rig = Rig::new().await;
    assert_eq!(rig.session.state().route, None);
    rig.tor_up().await;
    assert_eq!(rig.session.state().route, Some(Path::Tor));
    rig.lan_up().await;
    assert_eq!(rig.session.state().route, Some(Path::Lan));
    rig.session.set_link(None, Path::Lan);
    assert_eq!(rig.session.state().route, Some(Path::Tor));
}
