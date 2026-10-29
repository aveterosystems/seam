// SPDX-License-Identifier: AGPL-3.0-only
use super::*;

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
