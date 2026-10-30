// SPDX-License-Identifier: AGPL-3.0-only
use super::*;
use Path::{Lan, Relay};

const LAN: i32 = 1;
const RELAY: i32 = 2;
const BLUETOOTH: i32 = 3;
const FUTURE: i32 = 99;

fn data() -> AppData {
    AppData::new(vec![7; 16], "wss://relay.avetero.com".into(), None)
}

fn heard(list: &[i32]) -> AppData {
    let mut d = data();
    assert!(d.apply_hello(1, list));
    d
}

// Decision 0006: only paths switched on at both ends, as one table both apps share.
#[test]
fn allowed_paths_table() {
    type Case<'a> = (bool, bool, Option<&'a [i32]>, &'a [Path]);
    let cases: &[Case] = &[
        // my wifi, my relay, peer's Hello list (None = never heard), allowed
        (true, true, None, &[Lan, Relay]),
        (true, false, None, &[Lan]),
        (false, true, None, &[Relay]),
        (true, true, Some(&[]), &[Lan, Relay]), // an older peer's Hello has no list
        (true, true, Some(&[LAN]), &[Lan]),
        (true, true, Some(&[RELAY]), &[Relay]),
        (true, true, Some(&[LAN, RELAY]), &[Lan, Relay]),
        (false, true, Some(&[LAN]), &[]),
        (true, false, Some(&[RELAY]), &[]),
        (true, true, Some(&[FUTURE]), &[]), // a newer peer using only paths this build lacks
        (true, true, Some(&[BLUETOOTH]), &[]), // known to the schema, not a path yet
        (true, true, Some(&[FUTURE, RELAY]), &[Relay]),
    ];
    for (wifi, relay, peer, want) in cases {
        let d = match peer {
            None => data(),
            Some(list) => heard(list),
        };
        assert_eq!(
            d.allowed(*wifi, *relay),
            *want,
            "wifi {wifi} relay {relay} peer {peer:?}"
        );
    }
}

#[test]
fn route_prefers_same_wifi_then_relay_else_waits() {
    let both = heard(&[LAN, RELAY]);
    assert_eq!(both.route(true, true, true, true, false), Some(Lan));
    assert_eq!(both.route(true, true, false, true, false), Some(Relay));
    assert_eq!(both.route(true, true, false, false, false), None);
    let lan_only = heard(&[LAN]);
    assert_eq!(
        lan_only.route(true, true, false, true, false),
        None,
        "waits instead of using the relay"
    );
    assert_eq!(heard(&[RELAY]).route(true, true, true, false, false), None);
}

#[test]
fn a_hello_takes_any_path_my_switches_allow() {
    // The phone last said Same Wi-Fi only, the Mac now allows only the relay: the Hello still goes
    let d = heard(&[LAN]);
    assert_eq!(
        d.route(false, true, false, true, false),
        None,
        "a payload waits"
    );
    assert_eq!(
        d.route(false, true, false, true, true),
        Some(Relay),
        "a Hello still goes"
    );
    assert_eq!(
        d.route(false, true, true, false, true),
        None,
        "but never on a path I switched off"
    );
    assert_eq!(d.route(true, true, true, true, true), Some(Lan));
    assert_eq!(
        d.route(true, true, false, false, true),
        None,
        "waits in the outbox"
    );
}

#[test]
fn hellos_apply_only_when_newer() {
    let mut d = data();
    assert!(!d.apply_hello(0, &[LAN]), "0 never counts");
    assert!(d.apply_hello(5, &[LAN]));
    assert!(!d.apply_hello(5, &[RELAY]), "equal is a replay");
    assert!(!d.apply_hello(4, &[RELAY]), "older");
    assert_eq!(d.peer_allowed, Some(vec![Lan]));
    assert!(
        d.apply_hello(u64::MAX, &[RELAY]),
        "compared unsigned, up to the limit"
    );
    assert_eq!(d.peer_allowed, Some(vec![Relay]));
    assert!(!d.apply_hello(1 << 63, &[LAN]));
}

#[test]
fn next_hello_counts_up_and_lists_my_switches() {
    let mut d = data();
    assert_eq!(d.next_hello(true, false), (1, vec![LAN]));
    assert_eq!(d.next_hello(true, true), (2, vec![LAN, RELAY]));
    assert_eq!(d.next_hello(false, true), (3, vec![RELAY]));
}

#[test]
fn forgetting_the_peer_starts_hello_state_again() {
    let mut d = heard(&[LAN]);
    d.next_hello(true, true);
    d.forget_peer();
    assert_eq!(
        (
            d.hello_seq_sent,
            d.hello_seq_applied,
            d.peer_allowed.clone()
        ),
        (0, 0, None)
    );
    assert_eq!(d.group_id, vec![7; 16], "the relay group stays");
}

#[test]
fn round_trip() {
    let mut d = AppData::new(
        vec![1; 16],
        "wss://relay.example.com".into(),
        Some(vec![9; 32]),
    );
    assert_eq!(AppData::decode(&d.encode()), Some(d.clone()));
    d.next_hello(true, true);
    assert!(d.apply_hello(3, &[RELAY]));
    assert_eq!(AppData::decode(&d.encode()), Some(d.clone()));
    let empty = heard(&[FUTURE]);
    assert_eq!(
        AppData::decode(&empty.encode()).unwrap().peer_allowed,
        Some(vec![]),
        "nothing in common isn't 'never heard'"
    );
}

#[test]
fn rejects_damaged_bytes() {
    let good = heard(&[LAN]).encode();
    assert_eq!(
        AppData::decode(&[]),
        None,
        "state from before app data existed"
    );
    assert_eq!(AppData::decode(&good[..good.len() - 1]), None, "truncated");
    assert_eq!(
        AppData::decode(&[good.as_slice(), &[0]].concat()),
        None,
        "trailing bytes"
    );
    let short_group = AppData::new(vec![1, 2], "wss://r".into(), None).encode();
    assert_eq!(
        AppData::decode(&short_group),
        None,
        "group id must be 16 bytes"
    );
}

// Bytes as the Mac's current release writes them (AppData.swift v1-v3).
fn mac_bytes(v: u8, key: &[u8], hello: Option<(u64, u64, Option<&[u8]>)>) -> Vec<u8> {
    let url = b"wss://relay.example.com";
    let mut out = vec![v, 16];
    out.extend([3; 16]);
    out.extend((url.len() as u16).to_be_bytes());
    out.extend(url);
    if v >= 2 {
        out.extend((key.len() as u16).to_be_bytes());
        out.extend(key);
    }
    if let Some((sent, applied, list)) = hello {
        out.extend(sent.to_be_bytes());
        out.extend(applied.to_be_bytes());
        match list {
            None => out.push(0xFF),
            Some(l) => {
                out.push(l.len() as u8);
                out.extend(l);
            }
        }
    }
    out
}

// Bytes as Android's current release writes them (RelayTarget v1-v2).
fn android_bytes(hello: Option<(u64, u64, Option<&[u8]>)>) -> Vec<u8> {
    let url = b"wss://relay.example.com";
    let mut out = vec![if hello.is_some() { 2 } else { 1 }];
    out.extend((url.len() as u16).to_be_bytes());
    out.extend(url);
    out.extend([3; 16]);
    if let Some((sent, applied, list)) = hello {
        out.extend(sent.to_be_bytes());
        out.extend(applied.to_be_bytes());
        out.push(list.is_some() as u8);
        let l = list.unwrap_or(&[]);
        out.push(l.len() as u8);
        out.extend(l);
    }
    out
}

#[test]
fn reads_the_macs_earlier_formats() {
    let plain = AppData::decode(&mac_bytes(1, &[], None)).unwrap();
    assert_eq!(
        (
            plain.relay_url.as_str(),
            plain.group_id.clone(),
            plain.owner_key.clone()
        ),
        ("wss://relay.example.com", vec![3; 16], None)
    );
    let owned = AppData::decode(&mac_bytes(2, &[9; 32], None)).unwrap();
    assert_eq!(owned.owner_key, Some(vec![9; 32]));
    let v3 = AppData::decode(&mac_bytes(3, &[], Some((4, 6, Some(&[1]))))).unwrap();
    assert_eq!(
        (
            v3.hello_seq_sent,
            v3.hello_seq_applied,
            v3.peer_allowed.clone(),
            v3.owner_key.clone()
        ),
        (4, 6, Some(vec![Lan]), None)
    );
    let never = AppData::decode(&mac_bytes(3, &[], Some((1, 0, None)))).unwrap();
    assert_eq!(never.peer_allowed, None);
    let unknown = AppData::decode(&mac_bytes(3, &[], Some((1, 2, Some(&[0]))))).unwrap();
    assert_eq!(
        unknown.peer_allowed,
        Some(vec![]),
        "the Mac stored an unknown value: nothing in common"
    );
    assert_eq!(
        AppData::decode(&mac_bytes(2, &[], None)),
        None,
        "v2 always had a key"
    );
}

#[test]
fn reads_androids_earlier_formats() {
    let v1 = AppData::decode(&android_bytes(None)).unwrap();
    assert_eq!(
        (
            v1.relay_url.as_str(),
            v1.group_id.clone(),
            v1.peer_allowed.clone()
        ),
        ("wss://relay.example.com", vec![3; 16], None)
    );
    let v2 = AppData::decode(&android_bytes(Some((5, 7, Some(&[2]))))).unwrap();
    assert_eq!(
        (
            v2.hello_seq_sent,
            v2.hello_seq_applied,
            v2.peer_allowed.clone()
        ),
        (5, 7, Some(vec![Relay]))
    );
    let never = AppData::decode(&android_bytes(Some((1, 0, None)))).unwrap();
    assert_eq!(never.peer_allowed, None);
    // Android stored a Mac's old empty Hello as "allows nothing"; it meant "older Mac, everything"
    let old_mac = AppData::decode(&android_bytes(Some((1, 2, Some(&[]))))).unwrap();
    assert_eq!(old_mac.allowed(true, true), vec![Lan, Relay]);
}

#[test]
fn writes_one_format_whichever_it_read() {
    for bytes in [
        mac_bytes(3, &[], Some((4, 6, Some(&[1])))),
        android_bytes(Some((4, 6, Some(&[1])))),
    ] {
        let d = AppData::decode(&bytes).unwrap();
        assert_eq!(d.encode()[0], VERSION);
        assert_eq!(AppData::decode(&d.encode()), Some(d));
    }
}
