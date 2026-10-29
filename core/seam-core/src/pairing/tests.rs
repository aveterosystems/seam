// SPDX-License-Identifier: AGPL-3.0-only
use super::*;
use prost::Message;

/// Runs the phone's half of pairing against `mac` with `token`, returning the phone and its first envelope.
fn phone_pairs(mac: &mut Device, token: &[u8]) -> (Device, String, Vec<u8>) {
    let mut phone = Device::generate().unwrap();
    let bundle = mac.prekey_bundle().unwrap();
    let (mac_id, env) = phone
        .pair_with(
            &mac.identity_key(),
            mac.relay_key(),
            "MacBook Pro",
            &bundle,
            token,
            "Galaxy S25 Ultra",
            "SM-S938B",
        )
        .unwrap();
    (phone, mac_id, env)
}

#[test]
fn full_pairing_flow_links_both_devices() {
    let mut mac = Device::generate().unwrap();
    let offer = mac.start_pairing();
    let (mut phone, mac_id, env) = phone_pairs(&mut mac, &offer.token);

    let request = mac.accept_pairing(&env).unwrap();
    assert_eq!(request.device_name, "Galaxy S25 Ultra");
    assert_eq!(request.model, "SM-S938B");
    assert_eq!(request.peer_id, phone.id());
    assert_eq!(mac.peers()[phone.id()].name, "Galaxy S25 Ultra");

    // Both screens show the same code
    assert_eq!(request.safety_code, phone.safety_code(&mac_id).unwrap());
    assert_eq!(request.safety_code.len(), 7);

    // And they can talk both ways
    let accept = Payload {
        body: Some(payload::Body::Control(Control {
            body: Some(control::Body::PairAccept(Default::default())),
        })),
    };
    let env = mac
        .seal(&request.peer_id, &accept.encode_to_vec(), 0)
        .unwrap();
    assert_eq!(phone.open(&env).unwrap().payload, accept.encode_to_vec());
}

#[test]
fn token_hash_is_what_the_relay_gets() {
    let mut mac = Device::generate().unwrap();
    let offer = mac.start_pairing();
    assert_eq!(
        offer.token_hash,
        <[u8; 32]>::from(Sha256::digest(offer.token))
    );
    assert_ne!(offer.token_hash, offer.token);
}

#[test]
fn wrong_token_is_refused_and_leaves_no_peer() {
    let mut mac = Device::generate().unwrap();
    mac.start_pairing();
    let (phone, _, env) = phone_pairs(&mut mac, &[0u8; 32]);
    assert!(matches!(
        mac.accept_pairing(&env),
        Err(Error::BadPairing("wrong pairing code"))
    ));
    assert!(!mac.peers().contains_key(phone.id()));
}

#[test]
fn a_bad_attempt_does_not_use_up_the_code() {
    let mut mac = Device::generate().unwrap();
    let offer = mac.start_pairing();
    let (_, _, wrong) = phone_pairs(&mut mac, &[0u8; 32]);
    assert!(mac.accept_pairing(&wrong).is_err());
    let (phone, _, right) = phone_pairs(&mut mac, &offer.token);
    assert_eq!(mac.accept_pairing(&right).unwrap().peer_id, phone.id());
}

#[test]
fn code_is_single_use() {
    let mut mac = Device::generate().unwrap();
    let offer = mac.start_pairing();
    let (_, _, env) = phone_pairs(&mut mac, &offer.token);
    mac.accept_pairing(&env).unwrap();
    let (_, _, again) = phone_pairs(&mut mac, &offer.token);
    assert!(matches!(
        mac.accept_pairing(&again),
        Err(Error::BadPairing("no pairing is open"))
    ));
}

#[test]
fn expired_code_is_refused() {
    let mut mac = Device::generate().unwrap();
    let offer = mac.start_pairing();
    mac.pending_pairing.as_mut().unwrap().expires_ms = 0;
    let (_, _, env) = phone_pairs(&mut mac, &offer.token);
    assert!(matches!(
        mac.accept_pairing(&env),
        Err(Error::BadPairing("the pairing code expired"))
    ));
}

#[test]
fn nothing_is_accepted_when_no_pairing_is_open() {
    let mut mac = Device::generate().unwrap();
    let (_, _, env) = phone_pairs(&mut mac, &[1u8; 32]);
    assert!(matches!(
        mac.accept_pairing(&env),
        Err(Error::BadPairing("no pairing is open"))
    ));
}

#[test]
fn replayed_pairing_envelope_from_a_paired_device_is_refused() {
    let mut mac = Device::generate().unwrap();
    let offer = mac.start_pairing();
    let (_, _, env) = phone_pairs(&mut mac, &offer.token);
    mac.accept_pairing(&env).unwrap();
    mac.start_pairing();
    assert!(matches!(
        mac.accept_pairing(&env),
        Err(Error::BadPairing("that device is already paired"))
    ));
}

#[test]
fn safety_code_changes_if_a_key_is_swapped() {
    let mut mac = Device::generate().unwrap();
    let offer = mac.start_pairing();
    let (_, _, env) = phone_pairs(&mut mac, &offer.token);
    let real = mac.accept_pairing(&env).unwrap();

    let mut mac2 = Device::generate().unwrap();
    let offer2 = mac2.start_pairing();
    let (_, _, env2) = phone_pairs(&mut mac2, &offer2.token);
    assert_ne!(
        real.safety_code,
        mac2.accept_pairing(&env2).unwrap().safety_code
    );
}

#[test]
fn sr_2_01_replay_with_a_new_from_cannot_unpair_an_existing_phone() {
    let mut mac = Device::generate().unwrap();
    let offer = mac.start_pairing();
    let (phone, _, env) = phone_pairs(&mut mac, &offer.token);
    mac.accept_pairing(&env).unwrap();
    mac.start_pairing(); // a second pairing slot, e.g. adding another device
    let mut e = crate::proto::v1::Envelope::decode(env.as_slice()).unwrap();
    e.from = vec![7; 32]; // replay the phone's old request under a different relay key
    let _ = mac.accept_pairing(&e.encode_to_vec());
    assert!(
        mac.peers().contains_key(phone.id()),
        "existing phone was unpaired"
    );
    assert_eq!(
        mac.peers()[phone.id()].relay_key,
        phone.relay_key(),
        "existing phone's relay key was replaced"
    );
}

#[test]
fn sr_2_01_a_crafted_request_with_a_paired_identity_is_refused_without_side_effects() {
    let mut mac = Device::generate().unwrap();
    let offer = mac.start_pairing();
    let (phone, _, env) = phone_pairs(&mut mac, &offer.token);
    mac.accept_pairing(&env).unwrap();
    mac.confirm_pairing(phone.id()).unwrap();
    let before = mac.peers().clone();
    mac.start_pairing();
    let mut e = crate::proto::v1::Envelope::decode(env.as_slice()).unwrap();
    e.from = vec![7; 32];
    assert!(matches!(
        mac.accept_pairing(&e.encode_to_vec()),
        Err(Error::BadPairing("that device is already paired"))
    ));
    assert_eq!(mac.peers(), &before);
}

#[test]
fn sr_2_08_an_unconfirmed_phone_can_only_pair() {
    use crate::proto::v1::Status;
    let mut mac = Device::generate().unwrap();
    let offer = mac.start_pairing();
    let (mut phone, mac_id, env) = phone_pairs(&mut mac, &offer.token);
    let req = mac.accept_pairing(&env).unwrap();
    assert!(!mac.peers()[&req.peer_id].confirmed);

    // The phone can't send anything but pairing messages until it's confirmed…
    let status = Payload {
        body: Some(payload::Body::Status(Status {
            battery_pct: 5,
            ..Default::default()
        })),
    };
    assert!(matches!(
        phone.seal(&mac_id, &status.encode_to_vec(), 0),
        Err(Error::NotConfirmed)
    ));
    let sneaked = phone
        .seal_payload(&mac_id, status.clone(), 0)
        .unwrap()
        .encode_to_vec();
    assert!(
        matches!(mac.open(&sneaked), Err(Error::NotConfirmed)),
        "the Mac drops it too"
    );

    // …and an unconfirmed phone isn't saved: a restart mid-pairing forgets it
    let key = [1u8; 32];
    assert!(
        Device::restore(&mac.export_state(&key).unwrap(), &key)
            .unwrap()
            .peers()
            .is_empty()
    );

    // The user confirms; PairAccept confirms the Mac on the phone; then anything goes
    mac.confirm_pairing(&req.peer_id).unwrap();
    let accept = Payload {
        body: Some(payload::Body::Control(Control {
            body: Some(control::Body::PairAccept(Default::default())),
        })),
    };
    phone
        .open(&mac.seal(&req.peer_id, &accept.encode_to_vec(), 0).unwrap())
        .unwrap();
    assert!(phone.peers()[&mac_id].confirmed);
    let ok = phone.seal(&mac_id, &status.encode_to_vec(), 0).unwrap();
    assert_eq!(mac.open(&ok).unwrap().payload, status.encode_to_vec());
}

#[test]
fn sr_2_08_rejecting_forgets_the_phone_and_cancel_closes_the_slot() {
    let mut mac = Device::generate().unwrap();
    let offer = mac.start_pairing();
    let (_, _, env) = phone_pairs(&mut mac, &offer.token);
    let req = mac.accept_pairing(&env).unwrap();
    mac.reject_pairing(&req.peer_id);
    assert!(mac.peers().is_empty());

    mac.start_pairing();
    mac.cancel_pairing();
    let (_, _, env) = phone_pairs(&mut mac, &offer.token);
    assert!(matches!(
        mac.accept_pairing(&env),
        Err(Error::BadPairing("no pairing is open"))
    ));
}

#[test]
fn sr_2_10_pairing_refuses_a_relay_key_swapped_by_the_relay() {
    let mut mac = Device::generate().unwrap();
    let offer = mac.start_pairing();
    let (_, _, env) = phone_pairs(&mut mac, &offer.token);
    let mut e = crate::proto::v1::Envelope::decode(env.as_slice()).unwrap();
    e.from = vec![9; 32]; // the relay substitutes a key it controls
    assert!(mac.accept_pairing(&e.encode_to_vec()).is_err());
    assert!(mac.peers().is_empty());
}

#[test]
fn names_from_the_other_device_are_sanitised() {
    let mut mac = Device::generate().unwrap();
    let offer = mac.start_pairing();
    let mut phone = Device::generate().unwrap();
    let bundle = mac.prekey_bundle().unwrap();
    let long = format!("Evil\u{202E}name\n{}", "x".repeat(200));
    let (_, env) = phone
        .pair_with(
            &mac.identity_key(),
            mac.relay_key(),
            "Mac",
            &bundle,
            &offer.token,
            &long,
            "m",
        )
        .unwrap();
    let req = mac.accept_pairing(&env).unwrap();
    assert!(!req.device_name.contains('\u{202E}') && !req.device_name.contains('\n'));
    assert!(req.device_name.chars().count() <= 64);
}

#[test]
fn sr_2_56_the_phone_cannot_confirm_itself_on_the_mac() {
    use crate::proto::v1::Status;
    let mut mac = Device::generate().unwrap();
    let offer = mac.start_pairing();
    let (mut phone, mac_id, env) = phone_pairs(&mut mac, &offer.token);
    let req = mac.accept_pairing(&env).unwrap();

    // The phone (or whoever swapped keys) sends its own PairAccept before the user compares codes
    let accept = Payload {
        body: Some(payload::Body::Control(Control {
            body: Some(control::Body::PairAccept(Default::default())),
        })),
    };
    let forged = phone.seal(&mac_id, &accept.encode_to_vec(), 0).unwrap();
    mac.open(&forged).unwrap();
    assert!(
        !mac.peers()[&req.peer_id].confirmed,
        "only the user confirms on the Mac"
    );

    // So everything else is still refused, nothing is saved, and Cancel still forgets it
    let status = Payload {
        body: Some(payload::Body::Status(Status::default())),
    };
    let sneaked = phone
        .seal_payload(&mac_id, status, 0)
        .unwrap()
        .encode_to_vec();
    assert!(matches!(mac.open(&sneaked), Err(Error::NotConfirmed)));
    let key = [1u8; 32];
    let restored = Device::restore(&mac.export_state(&key).unwrap(), &key).unwrap();
    assert!(restored.peers().is_empty());
    mac.reject_pairing(&req.peer_id);
    assert!(mac.peers().is_empty());
}

#[test]
fn sr_2_56_only_the_mac_the_phone_asked_confirms_it() {
    let mut mac = Device::generate().unwrap();
    let offer = mac.start_pairing();
    let (mut phone, mac_id, env) = phone_pairs(&mut mac, &offer.token);
    let req = mac.accept_pairing(&env).unwrap();
    let accept = Payload {
        body: Some(payload::Body::Control(Control {
            body: Some(control::Body::PairAccept(Default::default())),
        })),
    };
    mac.confirm_pairing(&req.peer_id).unwrap();
    let env = mac.seal(&req.peer_id, &accept.encode_to_vec(), 0).unwrap();
    // The phone accepts it from the Mac it asked, and stops waiting
    phone.open(&env).unwrap();
    assert!(phone.peers()[&mac_id].confirmed);
    assert!(phone.awaiting_accept.is_none());
}
