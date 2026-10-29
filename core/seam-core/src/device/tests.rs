// SPDX-License-Identifier: AGPL-3.0-only
use super::*;

/// A Mac and a phone that have paired: each pinned the other's identity key from the QR code.
fn paired() -> (Device, Device) {
    let mut mac = Device::generate().unwrap();
    let mut phone = Device::generate().unwrap();
    let (mac_key, phone_key) = (mac.identity_key(), phone.identity_key());
    mac.add_peer(&phone_key, phone.relay_key(), "phone")
        .unwrap();
    phone.add_peer(&mac_key, mac.relay_key(), "mac").unwrap();
    (mac, phone)
}

/// Mac starts a session from the phone's bundle (as it would from the relay).
fn connected() -> (Device, Device) {
    let (mut mac, mut phone) = paired();
    let bundle = phone.prekey_bundle().unwrap();
    mac.start_session(&phone.address().clone(), &bundle)
        .unwrap();
    (mac, phone)
}

#[test]
fn messages_flow_both_ways_after_pqxdh_setup() {
    let (mut mac, mut phone) = connected();
    let (m, p) = (mac.address().clone(), phone.address().clone());

    let first = mac.encrypt(&p, b"hello from the mac").unwrap();
    assert_eq!(
        first.kind,
        CiphertextMessageType::PreKey as u8,
        "first message carries the PQXDH setup"
    );
    assert_eq!(phone.decrypt(&m, &first).unwrap(), b"hello from the mac");

    let reply = phone.encrypt(&m, b"hello back").unwrap();
    assert_eq!(reply.kind, CiphertextMessageType::Whisper as u8);
    assert_eq!(mac.decrypt(&p, &reply).unwrap(), b"hello back");

    for i in 0..20 {
        let text = format!("ping {i}");
        let s = mac.encrypt(&p, text.as_bytes()).unwrap();
        assert_eq!(phone.decrypt(&m, &s).unwrap(), text.as_bytes());
        let r = phone.encrypt(&m, b"pong").unwrap();
        assert_eq!(mac.decrypt(&p, &r).unwrap(), b"pong");
    }
}

#[test]
fn out_of_order_delivery_still_decrypts() {
    let (mut mac, mut phone) = connected();
    let (m, p) = (mac.address().clone(), phone.address().clone());
    let a = mac.encrypt(&p, b"one").unwrap();
    let b = mac.encrypt(&p, b"two").unwrap();
    let c = mac.encrypt(&p, b"three").unwrap();
    assert_eq!(phone.decrypt(&m, &c).unwrap(), b"three");
    assert_eq!(phone.decrypt(&m, &a).unwrap(), b"one");
    assert_eq!(phone.decrypt(&m, &b).unwrap(), b"two");
}

#[test]
fn replayed_message_is_rejected() {
    let (mut mac, mut phone) = connected();
    let (m, p) = (mac.address().clone(), phone.address().clone());
    let s = mac.encrypt(&p, b"once").unwrap();
    phone.decrypt(&m, &s).unwrap();
    assert!(phone.decrypt(&m, &s).is_err());
}

#[test]
fn tampered_message_is_rejected() {
    let (mut mac, mut phone) = connected();
    let (m, p) = (mac.address().clone(), phone.address().clone());
    let mut s = mac.encrypt(&p, b"original").unwrap();
    let last = s.bytes.len() - 1;
    s.bytes[last] ^= 1;
    assert!(phone.decrypt(&m, &s).is_err());
}

#[test]
fn impostor_bundle_is_refused() {
    // The relay hands the Mac a bundle from a different identity than the one in the QR code
    let (mut mac, phone) = paired();
    let mut impostor = Device::generate().unwrap();
    let fake = impostor.prekey_bundle().unwrap();
    let err = mac
        .start_session(&phone.address().clone(), &fake)
        .unwrap_err();
    assert!(
        matches!(
            err,
            Error::Signal(SignalProtocolError::UntrustedIdentity(_))
        ),
        "got {err:?}"
    );
}

#[test]
fn unpaired_peers_are_refused() {
    let mut mac = Device::generate().unwrap();
    let mut stranger = Device::generate().unwrap();
    let bundle = stranger.prekey_bundle().unwrap();
    let err = mac
        .start_session(&stranger.address().clone(), &bundle)
        .unwrap_err();
    assert!(matches!(err, Error::NotPaired));
}

#[test]
fn ciphertext_size_does_not_reveal_message_length() {
    let (mut mac, phone) = connected();
    let p = phone.address().clone();
    let short = mac.encrypt(&p, b"k").unwrap();
    let long = mac.encrypt(&p, &[b'x'; 800]).unwrap();
    assert_eq!(short.bytes.len(), long.bytes.len());
}

#[test]
fn one_time_prekey_is_used_up() {
    // A second session from the same bundle can't reuse the consumed one-time prekey
    let (mut mac, mut phone) = paired();
    let (m, p) = (mac.address().clone(), phone.address().clone());
    let bundle = phone.prekey_bundle().unwrap();
    mac.start_session(&p, &bundle).unwrap();
    let first = mac.encrypt(&p, b"first").unwrap();
    phone.decrypt(&m, &first).unwrap();

    let mut mac2 = Device::generate().unwrap();
    // A second device replays the old bundle (as if it were copied off the relay)
    let m2 = mac2.address().clone();
    phone
        .add_peer(&mac2.identity_key(), mac2.relay_key(), "mac2")
        .unwrap();
    mac2.add_peer(&phone.identity_key(), phone.relay_key(), "phone")
        .unwrap();
    mac2.start_session(&p, &bundle).unwrap();
    let replayed_setup = mac2.encrypt(&p, b"second").unwrap();
    let err = phone.decrypt(&m2, &replayed_setup).unwrap_err();
    assert!(
        matches!(err, Error::Signal(SignalProtocolError::InvalidPreKeyId)),
        "got {err:?}"
    );
}
