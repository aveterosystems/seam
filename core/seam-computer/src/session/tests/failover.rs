// SPDX-License-Identifier: AGPL-3.0-only
//! A link that dies without closing (the phone left the Wi‑Fi) is given up quickly: the phone
//! says which links it has open, and an envelope left unacked on one link goes on the other.

use seam_core::links::Path;

use super::rig::*;
use crate::lan::LanEvent;
use crate::session::ACK_TIMEOUT;

/// The phone acks everything that reached it on `path` so far, as a live phone does.
async fn ack_all(rig: &mut Rig, path: Path) {
    let carrier = if path == Path::Lan {
        rig.lan.clone()
    } else {
        rig.tor.clone()
    };
    for e in carrier.delivered() {
        rig.event(LanEvent::Delivered(rig.phone_id(), e.msg_id), path)
            .await;
    }
}

/// Both links up, and what they carried so far acked.
async fn both_up(rig: &mut Rig) {
    rig.lan_up().await;
    rig.tor_up().await;
    ack_all(rig, Path::Lan).await;
    ack_all(rig, Path::Tor).await;
}

fn open_links(rig: &mut Rig) -> Vec<Vec<i32>> {
    rig.on_tor()
        .iter()
        .filter_map(|p| hello_of(p).map(|h| h.open_links.clone().unwrap_or_default().transports))
        .collect()
}

#[tokio::test]
async fn when_the_phone_says_its_wifi_link_is_gone_its_closed_and_what_it_held_goes_through_tor() {
    let mut rig = Rig::new().await;
    both_up(&mut rig).await;
    rig.pasteboard.copy("held by the dead link");
    rig.advance(1.0).await;
    assert_eq!(clips(&rig.on_lan()), ["held by the dead link"]);
    let hello = rig.phone_hello(true, true, 0, Some(vec![Path::Tor]));
    assert!(rig.session.received(hello).await);
    assert_eq!(rig.lan.closed(), [rig.phone_id()]);
    assert_eq!(clips(&rig.on_tor()), ["held by the dead link"]);
    assert_eq!(rig.session.state().route, Some(Path::Tor));
}

#[tokio::test]
async fn an_older_phone_that_doesnt_list_its_links_closes_nothing() {
    let mut rig = Rig::new().await;
    rig.lan_up().await;
    rig.tor_up().await;
    let hello = rig.phone_hello(true, true, 0, None);
    assert!(rig.session.received(hello).await);
    assert!(rig.lan.closed().is_empty() && rig.tor.closed().is_empty());
    assert_eq!(rig.session.state().route, Some(Path::Lan));
}

#[tokio::test]
async fn a_link_that_leaves_a_copy_unacked_is_given_up_for_the_other() {
    let mut rig = Rig::new().await;
    both_up(&mut rig).await;
    rig.pasteboard.copy("into the void");
    rig.advance(1.0).await;
    rig.advance(ACK_TIMEOUT.as_secs_f64() - 2.0).await;
    assert!(rig.lan.closed().is_empty(), "not yet");
    rig.advance(2.0).await;
    assert_eq!(rig.lan.closed(), [rig.phone_id()]);
    assert_eq!(clips(&rig.on_tor()), ["into the void"]);
    let last = open_links(&mut rig).pop().unwrap();
    assert_eq!(
        last,
        [Path::Tor.transport()],
        "and the phone hears the link is gone"
    );
}

#[tokio::test]
async fn an_ack_in_time_keeps_the_link() {
    let mut rig = Rig::new().await;
    both_up(&mut rig).await;
    rig.pasteboard.copy("arrived");
    rig.advance(1.0).await;
    let sent = rig.lan.delivered().last().unwrap().msg_id.clone();
    rig.event(LanEvent::Delivered(rig.phone_id(), sent), Path::Lan)
        .await;
    rig.advance(ACK_TIMEOUT.as_secs_f64() * 2.0).await;
    assert!(rig.lan.closed().is_empty());
}

#[tokio::test]
async fn the_only_link_isnt_closed_for_being_slow() {
    let mut rig = Rig::new().await;
    rig.tor_up().await;
    ack_all(&mut rig, Path::Tor).await;
    rig.pasteboard.copy("slow but alone");
    rig.advance(1.0).await;
    rig.advance(ACK_TIMEOUT.as_secs_f64() * 2.0).await;
    assert!(
        rig.tor.closed().is_empty(),
        "with nothing to fall back to, the link's own timeouts decide"
    );
}

#[tokio::test]
async fn every_hello_lists_the_links_open_now() {
    let mut rig = Rig::new().await;
    rig.lan_up().await;
    rig.tor_up().await;
    let mut all = rig.on_lan();
    all.extend(rig.on_tor());
    let both = [Path::Lan.transport(), Path::Tor.transport()];
    assert!(
        all.iter()
            .filter_map(hello_of)
            .any(|h| h.open_links.as_ref().unwrap().transports == both)
    );
}
