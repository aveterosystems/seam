// SPDX-License-Identifier: AGPL-3.0-only
//! What the recorder keeps never names a place or a key (CONTRIBUTING rule 6), whatever a line
//! from Arti or Seam carried in.

use seam_diagnostics::{Limits, Recorder};

fn kept(event: &str) -> String {
    let r = Recorder::new(Limits {
        events: 10,
        bytes: 1 << 20,
        line: 2000,
    });
    r.record("arti", event);
    r.export("").lines().last().unwrap().to_owned()
}

const ONION: &str = "vww6ybal4bd7szmgncyruucpgfkqahzddi37ktceo3ah7ngmcopnpyyd.onion";

#[test]
fn onion_addresses_full_or_redacted_are_gone() {
    let out = kept(&format!("HS desc fetch for {ONION}, for period 20521"));
    assert!(!out.contains("vww6ybal"), "{out}");
    assert!(
        out.contains("HS desc fetch for [onion], for period 20521"),
        "{out}"
    );
    // Arti's own redaction keeps the last three characters: still a few bits of the address
    let out = kept("Introduction to […]yyd.onion NACKed, refetching descriptor and retrying");
    assert!(out.contains("Introduction to [onion] NACKed"), "{out}");
    assert!(!out.contains("yyd"), "{out}");
}

#[test]
fn ip_addresses_are_gone() {
    let out = kept(
        "Connecting to relay at 192.0.2.15:9001 via [2001:db8::1]:443 and 2001:db8:0:0:1:0:0:1",
    );
    for leaked in ["192.0.2.15", "9001", "2001:db8", "db8::1"] {
        assert!(!out.contains(leaked), "{leaked} in {out}");
    }
    assert!(
        out.contains("Connecting to relay at [ip] via [ip] and [ip]"),
        "{out}"
    );
}

#[test]
fn relay_ids_and_key_material_are_gone() {
    let out = kept(
        "failed hsdir desc fetch from $9695DFC35FFEB861329B9F1AB04C46397020CE31/\
         9AOx3Dcb4ZWbAzcEGVF3+9NGkUt2Ry3D7VInXVuNKqA ed25519:9AOx3Dcb4ZWbAzcEGVF3+9NGkUt2Ry3D7VInXVuNKqA",
    );
    for leaked in ["9695DFC35FFEB861", "9AOx3Dcb4ZWb"] {
        assert!(!out.contains(leaked), "{leaked} in {out}");
    }
    assert!(
        out.contains("failed hsdir desc fetch from $[id] ed25519:[id]"),
        "{out}"
    );
}

#[test]
fn ordinary_words_numbers_and_times_survive() {
    let line = "tor_hsclient::connect: bootstrap 85%, circuit timeout 60 s at 12:34:56, \
                arti 0.47.0, isolation_group_reused_after_failure, OnionServiceNotFound";
    assert!(kept(line).ends_with(line), "{}", kept(line));
}

// Lines Arti really printed in the live test (seam-tor/tests/live.rs), each of which leaked
// before this filter knew them

#[test]
fn a_service_nickname_made_from_its_address_is_gone() {
    let out =
        kept("Launching onion service in restricted discovery mode nickname=seam-fiieaxgx3elunaku");
    assert!(!out.contains("fiieaxgx"), "{out}");
    assert!(
        out.ends_with("restricted discovery mode nickname=seam-[onion]"),
        "{out}"
    );
}

#[test]
fn addresses_and_ids_printed_as_byte_lists_are_gone() {
    let out = kept(
        "IptStatus { status: Good(GoodIptDetails { link_specifiers: [EncodedLinkSpec { lstype: \
         LinkSpecType(ORPORT_V4), body: [185, 220, 101, 137, 43, 129] }, EncodedLinkSpec { lstype: \
         LinkSpecType(RSAID), body: [237, 18, 203, 34, 203, 51, 193, 67, 60, 220, 212, 197] }",
    );
    for leaked in ["185, 220", "101, 137", "237, 18"] {
        assert!(!out.contains(leaked), "{leaked} in {out}");
    }
    assert!(
        out.contains("LinkSpecType(ORPORT_V4), body: [bytes]"),
        "{out}"
    );
}

#[test]
fn arti_shortened_relay_ids_are_gone() {
    let out = kept("Successfully established introduction point with ed25519:rk…");
    assert!(
        out.ends_with("introduction point with ed25519:[id]"),
        "{out}"
    );
}

// Found in review (#97): forms the filter let through

#[test]
fn labelled_addresses_are_gone() {
    let out = kept("connect failed addr=192.0.2.15:9001 peer=[2001:db8::1]:443 via=2001:db8::5");
    for leaked in ["192.0.2.15", "9001", "2001:db8"] {
        assert!(!out.contains(leaked), "{leaked} in {out}");
    }
    assert!(
        out.ends_with("connect failed addr=[ip] peer=[ip] via=[ip]"),
        "{out}"
    );
}

#[test]
fn an_ipv6_address_ending_in_double_colon_is_gone() {
    let out = kept("relay 2001:db8:: refused, then fe80:: too.");
    assert!(!out.contains("2001:db8") && !out.contains("fe80"), "{out}");
    assert!(out.ends_with("relay [ip] refused, then [ip] too."), "{out}");
}

#[test]
fn keys_with_few_or_no_digits_are_gone() {
    // 32 bytes in base64 is 43 characters: some have no digits, some one
    let none = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopA";
    let one = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmno7A";
    let out = kept(&format!("relay ed25519:{none} and key {one}"));
    assert!(!out.contains("ABCDEFGHIJ"), "{out}");
    assert!(out.ends_with("relay ed25519:[id] and key [id]"), "{out}");
}

#[test]
fn addresses_in_urls_and_cidr_are_gone() {
    let out = kept(
        "request failed https://192.0.2.15:9001/ and http://198.51.100.7/path, net 192.0.2.0/24.",
    );
    for leaked in ["192.0.2", "198.51.100", "9001"] {
        assert!(!out.contains(leaked), "{leaked} in {out}");
    }
    assert!(
        out.ends_with("request failed https://[ip]/ and http://[ip]/path, net [ip]/24."),
        "{out}"
    );
    // A key with a slash in its base64 is still one id, not split around it
    let key = "9AOx3Dcb4ZWbAzcEGVF3/9NGkUt2Ry3D7VInXVuNKqA";
    assert!(kept(&format!("key {key}")).ends_with("key [id]"));
}
