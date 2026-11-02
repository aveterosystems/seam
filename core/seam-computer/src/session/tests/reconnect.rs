// SPDX-License-Identifier: AGPL-3.0-only
//! "Reconnect" on the computer: the phone is asked on every open link, never later, and this
//! computer closes its end so the phone dials again at once.

use std::sync::atomic::Ordering;

use seam_core::proto::v1::{Payload, control, payload::Body};

use super::rig::*;
use crate::session::Reconnect;

fn asks(ps: &[Payload]) -> usize {
    ps.iter()
        .filter(|p| matches!(&p.body, Some(Body::Control(c)) if matches!(c.body, Some(control::Body::Reconnect(_)))))
        .count()
}

#[tokio::test]
async fn the_phone_is_asked_on_every_open_link_then_they_close() {
    let mut rig = Rig::new().await;
    rig.lan_up().await;
    rig.tor_up().await;
    assert_eq!(rig.session.reconnect(), Some(Reconnect::Asked));
    assert_eq!(asks(&rig.on_lan()), 1);
    assert_eq!(asks(&rig.on_tor()), 1);
    assert_eq!(rig.lan.closed(), [rig.phone_id()]);
    assert_eq!(rig.tor.closed(), [rig.phone_id()]);
    assert_eq!(rig.session.state().route, None);
}

#[tokio::test]
async fn with_the_phone_out_of_reach_nothing_is_sent() {
    let mut rig = Rig::new().await;
    assert_eq!(
        rig.session.reconnect(),
        Some(Reconnect::PhoneOffline),
        "only this computer's side can start again"
    );
    rig.lan_up().await;
    assert_eq!(
        asks(&rig.on_lan()),
        0,
        "and nothing waits to cut the next link"
    );
}

#[tokio::test]
async fn the_ask_is_never_sent_again_on_the_new_link() {
    let mut rig = Rig::new().await;
    rig.lan_up().await;
    let _ = rig.session.reconnect();
    rig.lan_up().await; // the phone dialled again
    rig.advance(60.0).await;
    assert_eq!(asks(&rig.on_lan()), 1);
}

#[tokio::test]
async fn sr_2_11_an_ask_that_cant_be_saved_is_not_sent_but_the_links_still_close() {
    let mut rig = Rig::new().await;
    rig.lan_up().await;
    rig.saved.failing.store(true, Ordering::SeqCst);
    assert_eq!(rig.session.reconnect(), Some(Reconnect::Asked));
    assert_eq!(asks(&rig.on_lan()), 0);
    assert_eq!(rig.lan.closed(), [rig.phone_id()]);
}

#[tokio::test]
async fn sr_55_03_a_second_tap_while_one_is_under_way_does_nothing() {
    let mut rig = Rig::new().await;
    assert_eq!(rig.session.reconnect(), Some(Reconnect::PhoneOffline));
    assert_eq!(
        rig.session.reconnect(),
        None,
        "it would restart this computer's side as the phone dials back"
    );
    assert_eq!(rig.session.state().reconnect, Some(Reconnect::PhoneOffline));
}

#[tokio::test]
async fn it_shows_for_the_usual_connect_time_or_until_the_phone_is_back() {
    let mut rig = Rig::new().await;
    rig.session.reconnect();
    rig.advance(59.0).await;
    assert!(rig.session.state().reconnect.is_some(), "Tor on: a minute");
    rig.advance(1.0).await;
    assert_eq!(rig.session.state().reconnect, None);

    rig.session.reconnect();
    rig.tor_up().await;
    rig.session.settle();
    assert_eq!(rig.session.state().reconnect, None, "back: it's over");
}

#[tokio::test]
async fn with_tor_off_it_shows_for_fifteen_seconds() {
    let mut rig = Rig::with(Options {
        tor: false,
        ..Default::default()
    })
    .await;
    rig.session.reconnect();
    rig.advance(15.0).await;
    assert_eq!(rig.session.state().reconnect, None);
}
