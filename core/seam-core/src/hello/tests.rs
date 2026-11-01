// SPDX-License-Identifier: AGPL-3.0-only
use super::*;
use crate::links::Path::{Lan, Tor};
use crate::proto::v1::Hello;

fn data() -> AppData {
    AppData::new(vec![7; 32], String::new(), vec![])
}

fn facts() -> Facts {
    Facts {
        app_version: "1.2.0".into(),
        platform: "macos".into(),
        enabled: vec![Tor, Lan],
        open: vec![Tor],
        paused_until_ms: 0,
        capabilities: vec!["status.v1".into(), "clipboard.v1".into()],
    }
}

fn hello_of(p: Payload) -> Hello {
    match p.body {
        Some(payload::Body::Control(Control {
            body: Some(control::Body::Hello(h)),
        })) => h,
        other => panic!("not a Hello: {other:?}"),
    }
}

#[test]
fn one_device_hello_reaches_the_other() {
    let (mut mac, mut phone) = (data(), data());
    let mut f = facts();
    f.platform = "android".into();
    f.paused_until_ms = 1_800_000_000_000;
    let h = hello_of(phone.hello(&f));
    assert_eq!((h.seq, h.platform.as_str()), (1, "android"));
    assert_eq!(h.allowed_transports, vec![1, 4], "in order of preference");
    let peer = mac.take_hello(&h).unwrap();
    assert_eq!(peer.paused_until_ms, 1_800_000_000_000);
    assert_eq!(peer.open_links, Some(vec![Tor]));
    assert_eq!(peer.capabilities, vec!["clipboard.v1", "status.v1"]);
    assert!(mac.peer_supports("status.v1"));
    assert!(!mac.peer_supports("ring_computer.v1"));
    assert_eq!(mac.allowed(&[Lan, Tor]), vec![Lan, Tor]);
}

// SR-32-01: a late or replayed Hello changes nothing, capabilities included
#[test]
fn an_old_hello_changes_nothing() {
    let (mut mac, mut phone) = (data(), data());
    let first = hello_of(phone.hello(&facts()));
    let mut f = facts();
    f.capabilities = vec!["status.v1".into()];
    let second = hello_of(phone.hello(&f));
    assert!(mac.take_hello(&second).is_some());
    assert_eq!(mac.take_hello(&first), None);
    assert_eq!(mac.take_hello(&second), None, "equal is a replay");
    assert_eq!(mac.peer_capabilities, vec!["status.v1"]);
}

#[test]
fn an_older_peer_without_open_links_or_pause() {
    let mut mac = data();
    let peer = mac
        .take_hello(&Hello {
            seq: 1,
            paused_until_ms: -5,
            ..Hello::default()
        })
        .unwrap();
    assert_eq!(peer.open_links, None);
    assert_eq!(peer.paused_until_ms, 0, "a negative end is no pause");
    assert!(peer.capabilities.is_empty());
}

#[test]
fn capabilities_are_bounded() {
    let mut list: Vec<String> = (0..100).map(|i| format!("c{i:03}")).collect();
    list.push(String::new());
    list.push("x".repeat(MAX_CAPABILITY_LEN + 1));
    list.push("c000".into());
    let kept = capabilities(&list);
    assert_eq!(kept.len(), MAX_CAPABILITIES);
    assert!(kept.windows(2).all(|w| w[0] < w[1]), "sorted, each once");
    assert!(
        kept.iter()
            .all(|c| (1..=MAX_CAPABILITY_LEN).contains(&c.len()))
    );
}

#[test]
fn capabilities_survive_a_restart_and_v5_still_reads() {
    let mut mac = data();
    let mut phone = data();
    mac.take_hello(&hello_of(phone.hello(&facts()))).unwrap();
    let back = AppData::decode(&mac.encode()).unwrap();
    assert_eq!(back, mac);
    // A v5 save (before capabilities): the same bytes with the old version and no list
    let mut v5 = data();
    v5.apply_hello(3, &[1]);
    let mut bytes = v5.encode();
    bytes[0] = 5;
    bytes.pop(); // the empty capability count
    assert_eq!(AppData::decode(&bytes), Some(v5));
}

#[test]
fn a_forgotten_peer_takes_its_capabilities_with_it() {
    let mut mac = data();
    mac.take_hello(&hello_of(data().hello(&facts()))).unwrap();
    mac.forget_peer();
    assert!(mac.peer_capabilities.is_empty());
}
