// SPDX-License-Identifier: AGPL-3.0-only
use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use seam_core::PairingRequest;

use super::qr::{PairingQr, ParseError};
use super::{LIFETIME_MS, PairingFlow, PairingState};

const START: i64 = 1_000_000_000;

fn request() -> PairingRequest {
    PairingRequest {
        peer_id: "p".into(),
        device_name: "Pixel".into(),
        model: "Pixel 10".into(),
        safety_code: "12345".into(),
    }
}

#[test]
fn a_code_lasts_five_minutes() {
    let mut flow = PairingFlow::default();
    flow.start("seam://pair".into(), START);
    assert_eq!(
        flow.state(),
        &PairingState::Waiting {
            qr: "seam://pair".into(),
            expires_at: START + LIFETIME_MS
        }
    );
    assert_eq!(LIFETIME_MS, 300_000);
}

#[test]
fn pair_can_only_be_clicked_once() {
    let mut flow = PairingFlow::default();
    flow.start("seam://pair".into(), START);
    assert!(flow.received(request()));
    assert_eq!(flow.begin_confirming(), Some(request()));
    assert_eq!(
        flow.begin_confirming(),
        None,
        "a double click sends nothing twice"
    );
    assert_eq!(flow.cancel(), None, "and Cancel can't race the PairAccept");
    flow.finish("Pixel".into());
    assert_eq!(
        flow.state(),
        &PairingState::Paired {
            name: "Pixel".into()
        }
    );
}

#[test]
fn late_events_cant_skip_a_step() {
    let mut flow = PairingFlow::default();
    assert!(!flow.received(request()), "no QR showing");
    assert_eq!(flow.begin_confirming(), None);
    flow.start("seam://pair".into(), START);
    assert!(flow.expire());
    assert_eq!(flow.state(), &PairingState::Expired);
    assert!(
        !flow.received(request()),
        "an expired code can't be answered"
    );
}

#[test]
fn cancel_returns_what_to_undo() {
    let mut flow = PairingFlow::default();
    flow.start("seam://pair".into(), START);
    flow.received(request());
    assert_eq!(flow.cancel(), Some(PairingState::Confirm(request())));
    assert_eq!(flow.state(), &PairingState::Idle);
    assert!(
        !flow.expire(),
        "a cancelled code doesn't later show expired"
    );
}

fn qr() -> PairingQr {
    PairingQr {
        onion_address: "seam".repeat(14) + "abcdef.onion",
        discovery_secret: (0..32).collect(),
        token: (0..32).map(|i| 255 - i).collect(),
        identity_key: [5].into_iter().chain(0..32).collect(),
        relay_key: vec![0xFB; 32],
        name: "Matt's MacBook Pro + Café".into(),
    }
}

/// The Swift app's exact output for the same code, so phones read it the same way.
#[test]
fn matches_the_swift_apps_url() {
    let url = qr().url();
    assert!(url.starts_with("seam://pair?v=2&o=seamseam"));
    assert!(url.ends_with("&n=Matt%27s%20MacBook%20Pro%20%2B%20Caf%C3%A9"));
}

#[test]
fn round_trips() {
    assert_eq!(PairingQr::parse(&qr().url()).unwrap(), qr());
}

#[test]
fn uses_only_unreserved_characters_in_values() {
    let url = qr().url();
    let query = url.strip_prefix("seam://pair?").unwrap();
    for pair in query.split('&') {
        let value = pair.split_once('=').unwrap().1;
        assert!(!value.contains(['+', ' ', '/', '\'']), "{value}");
    }
}

#[test]
fn a_version_1_relay_code_is_refused() {
    let q = qr();
    let b = |v: &[u8]| URL_SAFE_NO_PAD.encode(v);
    let v1 = format!(
        "seam://pair?v=1&r=wss%3A%2F%2Frelay.avetero.com&g=AAECAwQFBgcICQoLDA0ODw&t={}&i={}&k={}&n=Mac",
        b(&q.token),
        b(&q.identity_key),
        b(&q.relay_key)
    );
    assert_eq!(PairingQr::parse(&v1), Err(ParseError::UnsupportedVersion));
}

#[test]
fn rejects_wrong_scheme_and_lengths() {
    assert_eq!(
        PairingQr::parse("https://pair?v=2"),
        Err(ParseError::NotSeamPairing)
    );
    let mut short = qr();
    short.token = vec![0; 31];
    assert_eq!(
        PairingQr::parse(&short.url()),
        Err(ParseError::BadLength("t"))
    );
    let mut short = qr();
    short.discovery_secret = vec![0; 16];
    assert_eq!(
        PairingQr::parse(&short.url()),
        Err(ParseError::BadLength("d"))
    );
    let url = qr().url();
    let cut = url.split("&n=").next().unwrap();
    assert_eq!(PairingQr::parse(cut), Err(ParseError::Missing("n")));
}

#[test]
fn base64url_has_no_padding() {
    assert_eq!(URL_SAFE_NO_PAD.encode([0xFF, 0xFE]), "__4");
}

/// Rule 6: the code's token and discovery secret never reach a log, even through a debug print.
#[test]
fn the_code_never_prints() {
    let mut flow = PairingFlow::default();
    flow.start(qr().url(), START);
    assert!(!format!("{:?}", flow.state()).contains("seam://"));
    assert!(!format!("{:?}", qr()).contains("seam"));
}
