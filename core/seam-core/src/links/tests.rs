// SPDX-License-Identifier: AGPL-3.0-only
use super::*;
use Path::{Lan, Tor};

const LAN: i32 = 1;
const TOR: i32 = 4;
const OLD_RELAY: i32 = 2;
const BLUETOOTH: i32 = 3;
const FUTURE: i32 = 99;

fn data() -> AppData {
    AppData::new(vec![7; 32], ADDRESS.into(), vec![])
}

const ADDRESS: &str = "seamseamseamseamseamseamseamseamseamseamseamseamseam2.onion";

/// The switches as a list: Same Wi-Fi and Tor on or off.
fn on(lan: bool, tor: bool) -> Vec<Path> {
    [(Lan, lan), (Tor, tor)]
        .into_iter()
        .filter_map(|(p, on)| on.then_some(p))
        .collect()
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
        (true, true, None, &[Lan, Tor]),
        (true, false, None, &[Lan]),
        (false, true, None, &[Tor]),
        (true, true, Some(&[]), &[Lan, Tor]), // an older peer's Hello has no list
        (true, true, Some(&[LAN]), &[Lan]),
        (true, true, Some(&[TOR]), &[Tor]),
        (true, true, Some(&[LAN, TOR]), &[Lan, Tor]),
        (false, true, Some(&[LAN]), &[]),
        (true, false, Some(&[TOR]), &[]),
        (true, true, Some(&[FUTURE]), &[]), // a newer peer using only paths this build lacks
        (true, true, Some(&[BLUETOOTH]), &[]), // known to the schema, not a path yet
        (true, true, Some(&[FUTURE, TOR]), &[Tor]),
    ];
    for (wifi, relay, peer, want) in cases {
        let d = match peer {
            None => data(),
            Some(list) => heard(list),
        };
        assert_eq!(
            d.allowed(&on(*wifi, *relay)),
            *want,
            "wifi {wifi} relay {relay} peer {peer:?}"
        );
    }
}

#[test]
fn route_prefers_same_wifi_then_relay_else_waits() {
    let both = heard(&[LAN, TOR]);
    assert_eq!(
        both.route(&on(true, true), &on(true, true), false),
        Some(Lan)
    );
    assert_eq!(
        both.route(&on(true, true), &on(false, true), false),
        Some(Tor)
    );
    assert_eq!(both.route(&on(true, true), &on(false, false), false), None);
    let lan_only = heard(&[LAN]);
    assert_eq!(
        lan_only.route(&on(true, true), &on(false, true), false),
        None,
        "waits instead of using the relay"
    );
    assert_eq!(
        heard(&[TOR]).route(&on(true, true), &on(true, false), false),
        None
    );
}

#[test]
fn a_hello_takes_any_path_my_switches_allow() {
    // The phone last said Same Wi-Fi only, the Mac now allows only the relay: the Hello still goes
    let d = heard(&[LAN]);
    assert_eq!(
        d.route(&on(false, true), &on(false, true), false),
        None,
        "a payload waits"
    );
    assert_eq!(
        d.route(&on(false, true), &on(false, true), true),
        Some(Tor),
        "a Hello still goes"
    );
    assert_eq!(
        d.route(&on(false, true), &on(true, false), true),
        None,
        "but never on a path I switched off"
    );
    assert_eq!(d.route(&on(true, true), &on(true, true), true), Some(Lan));
    assert_eq!(
        d.route(&on(true, true), &on(false, false), true),
        None,
        "waits in the outbox"
    );
}

#[test]
fn preference_is_the_cores_whatever_order_callers_use() {
    let both = heard(&[LAN, TOR]);
    assert_eq!(
        both.allowed(&[Tor, Lan, Tor]),
        vec![Lan, Tor],
        "ordered, each once"
    );
    assert_eq!(both.route(&[Tor, Lan], &[Tor, Lan], false), Some(Lan));
    assert_eq!(both.route(&[Tor, Lan], &[Tor], false), Some(Tor));
    assert_eq!(
        both.route(&[], &[Lan, Tor], false),
        None,
        "nothing switched on"
    );
    assert_eq!(both.route(&[Lan, Tor], &[], false), None, "nothing up");
    let mut d = data();
    assert_eq!(d.next_hello(&[Tor, Lan, Lan]), (1, vec![LAN, TOR]));
}

#[test]
fn hellos_apply_only_when_newer() {
    let mut d = data();
    assert!(!d.apply_hello(0, &[LAN]), "0 never counts");
    assert!(d.apply_hello(5, &[LAN]));
    assert!(!d.apply_hello(5, &[TOR]), "equal is a replay");
    assert!(!d.apply_hello(4, &[TOR]), "older");
    assert_eq!(d.peer_allowed, Some(vec![Lan]));
    assert!(
        d.apply_hello(u64::MAX, &[TOR]),
        "compared unsigned, up to the limit"
    );
    assert_eq!(d.peer_allowed, Some(vec![Tor]));
    assert!(!d.apply_hello(1 << 63, &[LAN]));
}

#[test]
fn next_hello_counts_up_and_lists_my_switches() {
    let mut d = data();
    assert_eq!(d.next_hello(&on(true, false)), (1, vec![LAN]));
    assert_eq!(d.next_hello(&on(true, true)), (2, vec![LAN, TOR]));
    assert_eq!(d.next_hello(&on(false, true)), (3, vec![TOR]));
}

#[test]
fn forgetting_the_peer_starts_hello_state_again() {
    let mut d = heard(&[LAN]);
    d.next_hello(&on(true, true));
    d.forget_peer();
    assert_eq!(
        (
            d.hello_seq_sent,
            d.hello_seq_applied,
            d.peer_allowed.clone()
        ),
        (0, 0, None)
    );
    assert_eq!(d.tor_secret, vec![7; 32], "the Tor details stay");
}

#[test]
fn round_trip() {
    let mut d = AppData::new(vec![1; 32], String::new(), vec![9; 32]); // a Mac's
    assert_eq!(AppData::decode(&d.encode()), Some(d.clone()));
    d.next_hello(&on(true, true));
    assert!(d.apply_hello(3, &[TOR]));
    assert_eq!(AppData::decode(&d.encode()), Some(d.clone()));
    let phone = data();
    assert_eq!(AppData::decode(&phone.encode()), Some(phone));
    let empty = heard(&[FUTURE]);
    assert_eq!(
        AppData::decode(&empty.encode()).unwrap().peer_allowed,
        Some(vec![]),
        "nothing in common isn't 'never heard'"
    );
}

#[test]
fn rejects_damaged_bytes() {
    let good = heard(&[LAN, TOR]).encode();
    for n in 0..good.len() {
        assert_eq!(AppData::decode(&good[..n]), None, "cut at {n}");
    }
    let mut longer = good.clone();
    longer.push(0);
    assert_eq!(AppData::decode(&longer), None, "trailing bytes");
    let mut wrong_key = data().encode();
    wrong_key[1 + 32 + 1 + ADDRESS.len()] = 31; // a peer key that's neither absent nor 32 bytes
    assert_eq!(AppData::decode(&wrong_key), None);
}

#[test]
fn earlier_relay_formats_are_not_read_so_the_devices_pair_again() {
    // v1-v4 held a relay group (decision 0009 replaces relays with Tor)
    for v in 1..=4u8 {
        let mut bytes = vec![v, 16];
        bytes.extend([7; 16]);
        bytes.extend(23u16.to_be_bytes());
        bytes.extend(b"wss://relay.avetero.com");
        assert_eq!(AppData::decode(&bytes), None, "v{v}");
    }
    // An old peer's relay value means nothing now
    assert_eq!(heard(&[OLD_RELAY]).peer_allowed, Some(vec![]));
}

#[test]
fn the_secret_never_prints() {
    let printed = format!("{:?}", AppData::new(vec![0xAB; 32], String::new(), vec![]));
    assert!(
        !printed.to_lowercase().contains("abab") && !printed.contains("171"),
        "{printed}"
    );
}

// SR-29-01..03: only app data that reads back can be saved
#[test]
fn only_app_data_that_reads_back_is_accepted() {
    for bad in [
        AppData::new(vec![1; 31], String::new(), vec![]),
        AppData::new(vec![1; 32], "x".repeat(256), vec![]),
        AppData::new(vec![1; 32], String::new(), vec![2; 31]),
    ] {
        assert!(bad.checked().is_err(), "{bad:?}");
    }
    let mut d = data();
    d.peer_allowed = Some(vec![Tor, Lan, Tor]);
    let ok = d.checked().unwrap();
    assert_eq!(ok.peer_allowed, Some(vec![Lan, Tor]));
    assert_eq!(AppData::decode(&ok.encode()), Some(ok));
}
