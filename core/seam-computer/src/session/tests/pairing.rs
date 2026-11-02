// SPDX-License-Identifier: AGPL-3.0-only
//! Pairing over the direct channel (docs/tor/13-implementation-contract.md): the code's life, the
//! exchange on a pairing link, the new onion service, and what's undone when it fails.

use std::sync::Arc;

use prost::Message;
use seam_core::links::Path;
use seam_core::proto::v1::{Envelope, Payload, control, payload::Body, server_frame};
use seam_core::{Device, PairingRequest};
use seam_tor::DiscoveryKey;

use super::rig::*;
use crate::lan::{LanEvent, PAIRING};
use crate::pairing::qr::PairingQr;
use crate::pairing::{PairingFailure, PairingState};
use crate::session::{ACCEPT_DEADLINE, TorServiceSpec};

fn discovery_public(secret: &[u8]) -> Vec<u8> {
    DiscoveryKey::from_bytes(secret)
        .unwrap()
        .public()
        .to_bytes()
        .to_vec()
}

async fn show_code(rig: &mut Rig) -> PairingQr {
    rig.session.start_pairing("Mac").await;
    let PairingState::Waiting { qr, .. } = rig.session.state().pairing else {
        panic!("no code");
    };
    PairingQr::parse(&qr).unwrap()
}

fn link(rig: &Rig, path: Path) -> Arc<FakeLan> {
    if path == Path::Lan {
        rig.lan.clone()
    } else {
        rig.tor.clone()
    }
}

/// The phone's half, up to the safety code: it reaches the computer on `path`, gets the bundle
/// and sends its request. `phone`: another device than the rig's.
async fn scan(rig: &mut Rig, qr: &PairingQr, path: Path, phone: Option<&mut Device>) {
    let l = link(rig, path);
    rig.session.set_link(Some(l.clone()), path);
    rig.event(LanEvent::Authenticated(PAIRING.into()), path)
        .await;
    rig.event(LanEvent::BundleRequest(PAIRING.into()), path)
        .await;
    let Some(server_frame::Frame::Bundle(b)) = l.last() else {
        panic!("no bundle");
    };
    assert_eq!(b.device_key, qr.relay_key);
    let bundle = seam_core::bundle::decode(&b.bundle).unwrap();
    let relay_key: [u8; 32] = qr.relay_key.clone().try_into().unwrap();
    let phone = phone.unwrap_or(&mut rig.phone);
    let (_, request) = phone
        .pair_with(
            &qr.identity_key,
            relay_key,
            &qr.name,
            &bundle,
            &qr.token,
            "Pixel",
            "Test",
        )
        .unwrap();
    let request = Envelope::decode(request.as_slice()).unwrap();
    rig.event(LanEvent::Envelope(PAIRING.into(), request.clone()), path)
        .await;
    assert!(
        matches!(rig.session.state().pairing, PairingState::Confirm(_)),
        "no request"
    );
    let Some(server_frame::Frame::Delivered(d)) = l.last() else {
        panic!("the request is acked on the pairing link");
    };
    assert_eq!(d.msg_id, request.msg_id);
}

/// The user clicks Pair; returns the PairAccept the phone got.
async fn confirm(rig: &mut Rig, path: Path, phone: Option<&mut Device>) -> Envelope {
    rig.session.confirm_pairing().await;
    let Some(server_frame::Frame::Deliver(accept)) = link(rig, path).last() else {
        panic!("no accept");
    };
    let phone = phone.unwrap_or(&mut rig.phone);
    let opened = phone.open(&accept.encode_to_vec()).unwrap();
    let p = Payload::decode(opened.payload.as_slice()).unwrap();
    let Some(Body::Control(c)) = p.body else {
        panic!()
    };
    assert!(matches!(c.body, Some(control::Body::PairAccept(_))));
    accept
}

#[tokio::test]
async fn pairs_over_either_carrier() {
    for path in [Path::Lan, Path::Tor] {
        let mut rig = Rig::unpaired().await;
        let qr = show_code(&mut rig).await;
        scan(&mut rig, &qr, path, None).await;
        let accept = confirm(&mut rig, path, None).await;
        let (peer, code) = {
            let mac = rig.mac();
            let peer = mac.peers().keys().next().unwrap().clone();
            let code = mac.safety_code(&peer).unwrap();
            (peer, code)
        };
        let request = PairingRequest {
            peer_id: peer,
            device_name: "Pixel".into(),
            model: "Test".into(),
            safety_code: code,
        };
        assert_eq!(
            rig.session.state().pairing,
            PairingState::Confirming(request)
        );
        rig.event(LanEvent::Delivered(PAIRING.into(), accept.msg_id), path)
            .await;
        assert_eq!(
            rig.session.state().pairing,
            PairingState::Paired {
                name: "Pixel".into()
            }
        );
        assert_eq!(
            rig.session.state().phone.map(|p| p.id),
            Some(rig.phone_id())
        );
        assert!(
            link(&rig, path).closed().contains(&PAIRING.to_string()),
            "both close the pairing link"
        );
        // The code's service is now the pairing's: only that phone can find it
        let data = rig.mac().app_data().unwrap();
        assert_eq!(onion_address(&data.tor_secret), qr.onion_address);
        assert_eq!(data.peer_discovery, discovery_public(&qr.discovery_secret));
    }
}

#[tokio::test]
async fn the_code_is_version_2_and_names_a_new_service_each_time() {
    let mut rig = Rig::unpaired().await;
    let first = show_code(&mut rig).await;
    let second = show_code(&mut rig).await;
    assert!(first.onion_address.ends_with(".onion") && first.onion_address != second.onion_address);
    assert!(first.discovery_secret != second.discovery_secret && first.token != second.token);
    assert_eq!(first.identity_key, rig.mac().identity_key());
    assert_eq!(first.relay_key, rig.mac().relay_key().to_vec());
    assert_eq!(
        rig.session.state().tor_services.len(),
        1,
        "the earlier code's service went"
    );
}

#[tokio::test]
async fn the_code_lasts_five_minutes_then_its_service_stops() {
    let mut rig = Rig::unpaired().await;
    show_code(&mut rig).await;
    assert_eq!(rig.session.state().tor_services.len(), 1);
    assert!(
        rig.session.state().wants_links,
        "Same Wi‑Fi listens while a code shows"
    );
    rig.advance(299.0).await;
    assert_eq!(rig.session.state().tor_services.len(), 1);
    rig.advance(1.0).await;
    assert_eq!(rig.session.state().pairing, PairingState::Expired);
    assert!(rig.session.state().tor_services.is_empty());
    assert!(!rig.session.state().wants_links);
}

#[tokio::test]
async fn cancelling_stops_the_new_service_and_closes_the_pairing_link() {
    let mut rig = Rig::unpaired().await;
    let qr = show_code(&mut rig).await;
    scan(&mut rig, &qr, Path::Tor, None).await;
    rig.session.cancel_pairing();
    assert_eq!(rig.session.state().pairing, PairingState::Idle);
    assert!(rig.mac().peers().is_empty(), "the requester is forgotten");
    assert!(rig.session.state().tor_services.is_empty());
    assert!(rig.tor.closed().contains(&PAIRING.to_string()));
}

#[tokio::test]
async fn confirming_swaps_the_old_service_for_the_new_one() {
    let mut rig = Rig::new().await; // paired, hosting its service
    let old = TorServiceSpec {
        secret: MAC_SECRET.to_vec(),
        clients: vec![mac_data().peer_discovery],
    };
    assert_eq!(rig.session.state().tor_services, std::slice::from_ref(&old));
    let qr = show_code(&mut rig).await;
    assert_eq!(
        rig.session.state().tor_services.len(),
        2,
        "the old one stays while the code shows"
    );
    let mut new_phone = Device::generate().unwrap();
    scan(&mut rig, &qr, Path::Tor, Some(&mut new_phone)).await;
    let accept = confirm(&mut rig, Path::Tor, Some(&mut new_phone)).await;
    let services = rig.session.state().tor_services;
    assert!(
        services.len() == 1 && services != [old],
        "saved, so the old one stops"
    );
    assert_eq!(onion_address(&services[0].secret), qr.onion_address);
    rig.event(
        LanEvent::Delivered(PAIRING.into(), accept.msg_id),
        Path::Tor,
    )
    .await;
    assert_eq!(
        rig.session.state().phone.map(|p| p.id),
        Some(new_phone.id().to_owned())
    );
    assert_eq!(rig.session.state().tor_services, services);
}

#[tokio::test]
async fn a_pair_accept_never_acked_is_undone_and_the_old_data_put_back() {
    let mut rig = Rig::new().await;
    let qr = show_code(&mut rig).await;
    let mut new_phone = Device::generate().unwrap();
    scan(&mut rig, &qr, Path::Tor, Some(&mut new_phone)).await;
    confirm(&mut rig, Path::Tor, Some(&mut new_phone)).await;
    rig.advance(ACCEPT_DEADLINE.as_secs_f64()).await;
    assert_eq!(
        rig.session.state().pairing,
        PairingState::Failed(PairingFailure::Unreachable)
    );
    let ids: Vec<_> = rig.mac().peers().keys().cloned().collect();
    assert_eq!(ids, [rig.phone_id()], "only the phone from before");
    assert_eq!(rig.mac().app_data(), Some(mac_data()));
    assert_eq!(
        rig.session.state().phone.map(|p| p.id),
        Some(rig.phone_id())
    );
}

#[tokio::test]
async fn a_pairing_link_that_drops_before_the_ack_undoes_the_pairing() {
    let mut rig = Rig::unpaired().await;
    let qr = show_code(&mut rig).await;
    scan(&mut rig, &qr, Path::Tor, None).await;
    confirm(&mut rig, Path::Tor, None).await;
    rig.event(LanEvent::Closed(PAIRING.into()), Path::Tor).await;
    assert!(matches!(
        rig.session.state().pairing,
        PairingState::Failed(_)
    ));
    assert!(rig.mac().peers().is_empty());
    assert!(rig.mac().app_data().is_none());
    assert!(rig.session.state().tor_services.is_empty());
}

#[tokio::test]
async fn a_phone_that_left_while_the_codes_were_compared_is_not_paired() {
    let mut rig = Rig::unpaired().await;
    let qr = show_code(&mut rig).await;
    scan(&mut rig, &qr, Path::Tor, None).await;
    rig.event(LanEvent::Closed(PAIRING.into()), Path::Tor).await;
    rig.session.confirm_pairing().await;
    assert!(matches!(
        rig.session.state().pairing,
        PairingState::Failed(_)
    ));
    assert!(rig.mac().peers().is_empty());
}

#[tokio::test]
async fn no_pairing_link_without_a_code_and_only_one_while_showing() {
    let mut rig = Rig::unpaired().await;
    rig.session.set_link(Some(rig.tor.clone()), Path::Tor);
    rig.session.set_link(Some(rig.lan.clone()), Path::Lan);
    rig.event(LanEvent::Authenticated(PAIRING.into()), Path::Tor)
        .await;
    assert_eq!(rig.tor.closed(), [PAIRING], "no code showing");
    show_code(&mut rig).await;
    rig.event(LanEvent::Authenticated(PAIRING.into()), Path::Tor)
        .await;
    rig.event(LanEvent::Authenticated(PAIRING.into()), Path::Lan)
        .await;
    assert_eq!(
        rig.lan.closed(),
        [PAIRING],
        "the first carrier to authenticate wins"
    );
    rig.event(LanEvent::BundleRequest(PAIRING.into()), Path::Lan)
        .await;
    assert!(rig.lan.frames().is_empty(), "nothing on the losing link");
}

#[tokio::test]
async fn asking_for_the_keys_again_gets_the_same_ones_not_more_saved() {
    let mut rig = Rig::unpaired().await;
    show_code(&mut rig).await;
    rig.session.set_link(Some(rig.tor.clone()), Path::Tor);
    rig.event(LanEvent::Authenticated(PAIRING.into()), Path::Tor)
        .await;
    rig.event(LanEvent::BundleRequest(PAIRING.into()), Path::Tor)
        .await;
    let saved = rig.mac().export_state(&[1; 32]).unwrap().len();
    for _ in 0..5 {
        rig.event(LanEvent::BundleRequest(PAIRING.into()), Path::Tor)
            .await;
    }
    let bundles: Vec<_> = rig
        .tor
        .frames()
        .into_iter()
        .filter_map(|f| match f.frame {
            Some(server_frame::Frame::Bundle(b)) => Some(b.bundle),
            _ => None,
        })
        .collect();
    assert_eq!(bundles.len(), 6);
    assert!(bundles.iter().all(|b| b == &bundles[0]));
    assert_eq!(
        rig.mac().export_state(&[1; 32]).unwrap().len(),
        saved,
        "no new keys kept"
    );
}

#[tokio::test]
async fn no_service_with_tor_switched_off_but_same_wifi_still_pairs() {
    let mut rig = Rig::with(Options {
        paired: false,
        tor: false,
        ..Default::default()
    })
    .await;
    let qr = show_code(&mut rig).await;
    assert!(rig.session.state().tor_services.is_empty());
    scan(&mut rig, &qr, Path::Lan, None).await;
}

/// Once a request is held, another pairing link can't take over and be sent its PairAccept, and a
/// Delivered it makes up doesn't finish the pairing.
#[tokio::test]
async fn a_second_pairing_link_cant_take_over_a_held_request() {
    let mut rig = Rig::unpaired().await;
    let qr = show_code(&mut rig).await;
    scan(&mut rig, &qr, Path::Tor, None).await;
    rig.event(LanEvent::Closed(PAIRING.into()), Path::Tor).await; // the phone's link drops
    rig.session.set_link(Some(rig.lan.clone()), Path::Lan);
    rig.event(LanEvent::Authenticated(PAIRING.into()), Path::Lan)
        .await; // someone else's
    assert_eq!(rig.lan.closed(), [PAIRING], "refused");
    rig.session.confirm_pairing().await;
    assert!(
        rig.lan.delivered().is_empty(),
        "the PairAccept went nowhere"
    );
    rig.event(LanEvent::Delivered(PAIRING.into(), vec![0; 16]), Path::Lan)
        .await;
    assert!(matches!(
        rig.session.state().pairing,
        PairingState::Failed(_)
    ));
    assert!(rig.mac().peers().is_empty());
}

/// A fresh code forgets the phone whose request the old one was holding, so it can try again.
#[tokio::test]
async fn a_new_code_forgets_the_request_the_old_one_held() {
    let mut rig = Rig::unpaired().await;
    let qr = show_code(&mut rig).await;
    scan(&mut rig, &qr, Path::Tor, None).await;
    assert!(!rig.mac().peers().is_empty(), "held, unconfirmed");
    show_code(&mut rig).await;
    assert!(rig.mac().peers().is_empty());
}
