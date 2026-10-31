// SPDX-License-Identifier: AGPL-3.0-only
use prost::Message;

use super::*;
use crate::proto::v1::{Clipboard, Status, payload};

/// A Mac and phone that know each other (identity + relay key), with a session from the phone's bundle.
fn linked() -> (Device, Device, String, String) {
    let mut mac = Device::generate().unwrap();
    let mut phone = Device::generate().unwrap();
    let p = mac
        .add_peer(&phone.identity_key(), phone.relay_key(), "Phone")
        .unwrap();
    let m = phone
        .add_peer(&mac.identity_key(), mac.relay_key(), "Mac")
        .unwrap();
    let bundle = phone.prekey_bundle().unwrap();
    mac.start_session(&crate::peer_address(&p), &bundle)
        .unwrap();
    (mac, phone, m, p)
}

fn status(pct: u32) -> Vec<u8> {
    Payload {
        body: Some(payload::Body::Status(Status {
            battery_pct: pct,
            charging: true,
            ..Default::default()
        })),
    }
    .encode_to_vec()
}

fn tamper(env: &[u8], f: impl FnOnce(&mut Envelope)) -> Vec<u8> {
    let mut e = Envelope::decode(env).unwrap();
    f(&mut e);
    e.encode_to_vec()
}

#[test]
fn payload_round_trips_both_ways() {
    let (mut mac, mut phone, m, p) = linked();
    let env = mac.seal(&p, &status(82), 0).unwrap();
    let opened = phone.open(&env).unwrap();
    assert_eq!(opened.peer_id, m);
    assert_eq!(opened.payload, status(82));

    let reply = phone.seal(&m, &status(81), 0).unwrap();
    assert_eq!(mac.open(&reply).unwrap().payload, status(81));
}

#[test]
fn a_pause_crosses_intact() {
    // The core decodes and re-encodes every payload, so a message it didn't know would be dropped
    use crate::proto::v1::{Control, Pause, control};
    let pause = |until_ms| {
        Payload {
            body: Some(payload::Body::Control(Control {
                body: Some(control::Body::Pause(Pause { until_ms })),
            })),
        }
        .encode_to_vec()
    };
    let (mut mac, mut phone, _, p) = linked();
    for until in [0, 1_793_455_000_000, i64::MAX] {
        let env = mac.seal(&p, &pause(until), 0).unwrap();
        assert_eq!(phone.open(&env).unwrap().payload, pause(until));
    }
}

#[test]
fn envelope_is_addressed_by_relay_keys_and_carries_no_device_ids() {
    let (mut mac, phone, _, p) = linked();
    let env = Envelope::decode(mac.seal(&p, &status(50), 0).unwrap().as_slice()).unwrap();
    assert_eq!(env.from, mac.relay_key());
    assert_eq!(env.to, phone.relay_key());
    assert_eq!(env.version, VERSION);
    assert_eq!(env.ttl_s, DEFAULT_TTL_S);
}

#[test]
fn relay_moving_ciphertext_under_other_routing_is_caught() {
    let (mut mac, mut phone, _, p) = linked();
    let moved = tamper(&mac.seal(&p, &status(50), 0).unwrap(), |e| e.sent_at += 1);
    assert!(matches!(phone.open(&moved), Err(Error::EnvelopeMismatch)));
    let renamed = tamper(&mac.seal(&p, &status(51), 0).unwrap(), |e| e.msg_id[0] ^= 1);
    assert!(matches!(phone.open(&renamed), Err(Error::EnvelopeMismatch)));
}

#[test]
fn a_tampered_copy_delivered_first_makes_the_genuine_one_a_duplicate() {
    // Decryption runs before the binding check, so the tampered copy uses up the message key.
    // A relay can therefore drop a message this way, which it could always do by not sending it:
    // availability isn't protected (threat model), confidentiality and integrity are.
    let (mut mac, mut phone, _, p) = linked();
    let genuine = mac.seal(&p, &status(50), 0).unwrap();
    assert!(matches!(
        phone.open(&tamper(&genuine, |e| e.sent_at += 1)),
        Err(Error::EnvelopeMismatch)
    ));
    assert!(matches!(phone.open(&genuine), Err(Error::Duplicate)));
}

#[test]
fn envelope_for_another_device_is_refused() {
    let (mut mac, _phone, _, p) = linked();
    let mut other = Device::generate().unwrap();
    other
        .add_peer(&mac.identity_key(), mac.relay_key(), "Mac")
        .unwrap();
    let env = mac.seal(&p, &status(50), 0).unwrap();
    assert!(matches!(other.open(&env), Err(Error::NotForMe)));
}

#[test]
fn unknown_or_removed_sender_is_refused() {
    let (mut mac, mut phone, m, p) = linked();
    let env = mac.seal(&p, &status(50), 0).unwrap();
    let forged = tamper(&env, |e| e.from = vec![7; 32]);
    assert!(matches!(phone.open(&forged), Err(Error::NotPaired)));

    phone.remove_peer(&m);
    assert!(matches!(phone.open(&env), Err(Error::NotPaired)));
    assert!(matches!(
        phone.seal(&m, &status(1), 0),
        Err(Error::NotPaired)
    ));
}

#[test]
fn redelivered_envelope_is_reported_as_duplicate() {
    let (mut mac, mut phone, _, p) = linked();
    let first = mac.seal(&p, &status(50), 0).unwrap();
    phone.open(&first).unwrap();
    // The phone replies so the Mac's next message is a plain ratchet message, then the relay redelivers it
    let second = mac.seal(&p, &status(49), 0).unwrap();
    phone.open(&second).unwrap();
    assert!(matches!(phone.open(&second), Err(Error::Duplicate)));
}

#[test]
fn wrong_version_and_garbage_are_refused() {
    let (mut mac, mut phone, _, p) = linked();
    let env = mac.seal(&p, &status(50), 0).unwrap();
    let v2 = tamper(&env, |e| e.version = 2);
    assert!(matches!(phone.open(&v2), Err(Error::UnsupportedVersion(2))));
    assert!(phone.open(b"not a protobuf at all \xff\xff").is_err());
    assert!(
        mac.seal(&p, b"\xff\xff\xff", 0).is_err(),
        "garbage payload is refused before sealing"
    );
}

#[test]
fn ttl_is_capped_at_a_day() {
    let (mut mac, _, _, p) = linked();
    let clip = Payload {
        body: Some(payload::Body::Clipboard(Clipboard {
            text: "x".into(),
            ..Default::default()
        })),
    };
    let env = mac.seal_payload(&p, clip.clone(), 600).unwrap();
    assert_eq!(env.ttl_s, 600);
    assert_eq!(
        mac.seal_payload(&p, clip, 999_999).unwrap().ttl_s,
        DEFAULT_TTL_S
    );
}

#[test]
fn sr_2_10_every_routing_field_is_bound_inside_the_encryption() {
    let (mut mac, mut phone, _, p) = linked();
    let retimed = tamper(&mac.seal(&p, &status(1), 0).unwrap(), |e| e.ttl_s = 60);
    assert!(matches!(phone.open(&retimed), Err(Error::EnvelopeMismatch)));
    let rerouted = tamper(&mac.seal(&p, &status(2), 0).unwrap(), |e| {
        e.from = phone.relay_key().to_vec()
    });
    assert!(phone.open(&rerouted).is_err());
}

#[test]
fn sr_2_09_a_removed_peer_is_refused_on_every_path_and_its_session_is_gone() {
    let (mut mac, mut phone, m, p) = linked();
    phone.open(&mac.seal(&p, &status(1), 0).unwrap()).unwrap(); // the phone now has a session too
    mac.remove_peer(&p);
    let addr = crate::peer_address(&p);
    assert!(matches!(mac.encrypt(&addr, b"x"), Err(Error::NotPaired)));
    assert!(matches!(
        mac.start_session(&addr, &phone.prekey_bundle().unwrap()),
        Err(Error::NotPaired)
    ));
    let from_phone = phone.seal(&m, &status(2), 0).unwrap();
    assert!(matches!(mac.open(&from_phone), Err(Error::NotPaired)));

    // Re-adding the same phone doesn't revive the old session
    mac.add_peer(&phone.identity_key(), phone.relay_key(), "Phone")
        .unwrap();
    assert!(
        mac.seal(&p, &status(3), 0).is_err(),
        "no session until a fresh one is set up"
    );
    assert!(mac.open(&from_phone).is_err(), "old session state is gone");
}
