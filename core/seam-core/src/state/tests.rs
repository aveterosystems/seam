// SPDX-License-Identifier: AGPL-3.0-only
use prost::Message;

use super::*;
use crate::proto::v1::{Payload, Status, payload};

const KEY: [u8; 32] = [42; 32];

fn status(pct: u32) -> Vec<u8> {
    Payload {
        body: Some(payload::Body::Status(Status {
            battery_pct: pct,
            ..Default::default()
        })),
    }
    .encode_to_vec()
}

fn linked() -> (Device, Device, String, String) {
    let mut mac = Device::generate().unwrap();
    let mut phone = Device::generate().unwrap();
    let p = mac
        .add_peer(&phone.identity_key(), phone.relay_key(), "Phone")
        .unwrap();
    let m = phone
        .add_peer(&mac.identity_key(), mac.relay_key(), "Mac")
        .unwrap();
    mac.start_session(&crate::peer_address(&p), &phone.prekey_bundle().unwrap())
        .unwrap();
    (mac, phone, m, p)
}

#[test]
fn a_conversation_survives_a_restart_on_both_sides() {
    let (mut mac, mut phone, m, p) = linked();
    phone.open(&mac.seal(&p, &status(1), 0).unwrap()).unwrap();
    mac.open(&phone.seal(&m, &status(2), 0).unwrap()).unwrap();

    let mut mac = Device::restore(&mac.export_state(&KEY).unwrap(), &KEY).unwrap();
    let mut phone = Device::restore(&phone.export_state(&KEY).unwrap(), &KEY).unwrap();

    assert_eq!(
        phone
            .open(&mac.seal(&p, &status(3), 0).unwrap())
            .unwrap()
            .payload,
        status(3)
    );
    assert_eq!(
        mac.open(&phone.seal(&m, &status(4), 0).unwrap())
            .unwrap()
            .payload,
        status(4)
    );
}

#[test]
fn identity_relay_key_and_peers_are_kept() {
    let (mac, _, _, p) = linked();
    let back = Device::restore(&mac.export_state(&KEY).unwrap(), &KEY).unwrap();
    assert_eq!(back.id(), mac.id());
    assert_eq!(back.identity_key(), mac.identity_key());
    assert_eq!(back.relay_key(), mac.relay_key());
    assert_eq!(back.peers(), mac.peers());
    assert_eq!(back.safety_code(&p).unwrap(), mac.safety_code(&p).unwrap());
}

#[test]
fn a_bundle_issued_before_the_restart_still_works() {
    let mut mac = Device::generate().unwrap();
    let mut phone = Device::generate().unwrap();
    let p = mac
        .add_peer(&phone.identity_key(), phone.relay_key(), "Phone")
        .unwrap();
    phone
        .add_peer(&mac.identity_key(), mac.relay_key(), "Mac")
        .unwrap();
    let bundle = phone.prekey_bundle().unwrap(); // uploaded to the relay, then the phone restarts
    let mut phone = Device::restore(&phone.export_state(&KEY).unwrap(), &KEY).unwrap();

    mac.start_session(&crate::peer_address(&p), &bundle)
        .unwrap();
    assert_eq!(
        phone
            .open(&mac.seal(&p, &status(9), 0).unwrap())
            .unwrap()
            .payload,
        status(9)
    );
    // and the restored phone keeps issuing fresh prekey ids
    assert!(phone.next_prekey_id >= 2);
}

#[test]
fn wrong_key_and_any_tampering_fail() {
    let (mac, ..) = linked();
    let blob = mac.export_state(&KEY).unwrap();
    assert!(matches!(
        Device::restore(&blob, &[0; 32]),
        Err(Error::BadState)
    ));
    for i in [0, 5, blob.len() / 2, blob.len() - 1] {
        let mut bad = blob.clone();
        bad[i] ^= 1;
        assert!(Device::restore(&bad, &KEY).is_err(), "byte {i} flipped");
    }
    assert!(Device::restore(&blob[..10], &KEY).is_err());
}

#[test]
fn two_exports_never_reuse_a_nonce() {
    let (mac, ..) = linked();
    let (a, b) = (
        mac.export_state(&KEY).unwrap(),
        mac.export_state(&KEY).unwrap(),
    );
    assert_ne!(a[1..13], b[1..13]);
}

#[test]
fn app_data_is_sealed_with_the_keys() {
    let (mut mac, ..) = linked();
    assert_eq!(mac.app_data(), None, "nothing saved yet");
    let mut data = crate::links::AppData::new(vec![5; 16], "wss://relay.example.com".into(), None);
    data.apply_hello(3, &[1]);
    mac.set_app_data(&data).unwrap();
    let back = Device::restore(&mac.export_state(&KEY).unwrap(), &KEY).unwrap();
    assert_eq!(back.app_data(), Some(data));
}

#[test]
fn app_data_edits_save_only_when_there_is_some() {
    let (mut mac, ..) = linked();
    assert_eq!(
        mac.edit_app_data(|d| d.next_hello(&[crate::links::Path::Lan, crate::links::Path::Relay])),
        None
    );
    mac.set_app_data(&crate::links::AppData::new(
        vec![5; 16],
        "wss://r".into(),
        None,
    ))
    .unwrap();
    assert_eq!(
        mac.edit_app_data(|d| d.next_hello(&[crate::links::Path::Lan])),
        Some((1, vec![1]))
    );
    assert_eq!(mac.app_data().unwrap().hello_seq_sent, 1);
    mac.clear_app_data();
    let back = Device::restore(&mac.export_state(&KEY).unwrap(), &KEY).unwrap();
    assert_eq!(back.app_data(), None, "cleared stays cleared");
}

#[test]
fn app_data_that_cant_be_read_back_is_refused_and_nothing_changes() {
    let (mut mac, ..) = linked();
    let good = crate::links::AppData::new(vec![5; 16], "wss://r".into(), None);
    mac.set_app_data(&good).unwrap();
    let bad = crate::links::AppData::new(vec![5; 3], "wss://r".into(), None);
    assert!(mac.set_app_data(&bad).is_err());
    assert_eq!(mac.app_data(), Some(good));
}
