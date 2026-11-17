// SPDX-License-Identifier: AGPL-3.0-only
//! Takes out of an event anything that names a place or a key (CONTRIBUTING rule 6), whoever wrote
//! it: onion addresses (whole, or Arti's redacted `[…]xyz.onion`), IP addresses with or without
//! a port, and long hex or base64 runs (relay ids, keys, tokens). Arti already redacts what it
//! knows is sensitive; this is the second layer, so a line Arti didn't mark can't leak either.

use std::borrow::Cow;
use std::net::{IpAddr, SocketAddr};
use std::sync::LazyLock;

use regex::{Captures, Regex};

static ONION: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(?:\[…\])?[A-Za-z0-9]*\.onion\b").expect("valid"));
/// A computer's service nickname, `seam-` and the first 16 characters of its address
/// (`seam-tor/src/service.rs`).
static NICKNAME: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"\bseam-[a-z2-7]{16}\b").expect("valid"));
/// Bytes printed as a list (`body: [185, 220, 101, 137, 43, 129]`): an address or an id in Arti's
/// debug output. Four numbers or more, so `[1, 2]`-style counts survive.
static BYTE_LIST: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"\[\d{1,3}(?:, ?\d{1,3}){3,}\]").expect("valid"));
/// A relay id Arti shortened itself (`ed25519:rk…`): still part of an id.
static SHORT_ID: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(ed25519:|\$)[A-Za-z0-9+/]+…").expect("valid"));
/// `[v6]` or `[v6]:port`; checked by parsing, not by shape.
static BRACKETED: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"\[[0-9A-Fa-f:.]+\](?::\d+)?").expect("valid"));
/// A run that could be an address or an id; each is classified by [`token`].
static TOKEN: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"[A-Za-z0-9:.%_+/=\-]+").expect("valid"));

pub(crate) fn filter(event: &str) -> Cow<'_, str> {
    let out = ONION.replace_all(event, "[onion]");
    let out = owned_if_changed(out, |s| NICKNAME.replace_all(s, "seam-[onion]"));
    let out = owned_if_changed(out, |s| BYTE_LIST.replace_all(s, "[bytes]"));
    let out = owned_if_changed(out, |s| SHORT_ID.replace_all(s, "${1}[id]"));
    let out = match BRACKETED.replace_all(&out, |c: &Captures| bracketed(&c[0])) {
        Cow::Borrowed(_) => out,
        Cow::Owned(o) => Cow::Owned(o),
    };
    let replaced = TOKEN.replace_all(&out, |c: &Captures| token(&c[0]));
    match replaced {
        Cow::Borrowed(_) => out,
        Cow::Owned(o) => Cow::Owned(o),
    }
}

/// Applies `f`, keeping `text` borrowed when nothing changed.
fn owned_if_changed<'a>(text: Cow<'a, str>, f: impl Fn(&str) -> Cow<'_, str>) -> Cow<'a, str> {
    match f(&text) {
        Cow::Borrowed(_) => text,
        Cow::Owned(o) => Cow::Owned(o),
    }
}

fn bracketed(s: &str) -> String {
    let inner = s.trim_start_matches('[').split(']').next().unwrap_or("");
    if inner.parse::<IpAddr>().is_ok() || s.parse::<SocketAddr>().is_ok() {
        "[ip]".into()
    } else {
        s.into()
    }
}

/// One run: an address becomes `[ip]`, each id-like part `[id]`; anything else stays.
fn token(run: &str) -> String {
    // A sentence's full stop or a label's colon isn't part of an address
    let body = run.trim_end_matches(['.', ':', ',']);
    let tail = &run[body.len()..];
    if body.parse::<IpAddr>().is_ok() || body.parse::<SocketAddr>().is_ok() {
        return format!("[ip]{tail}");
    }
    let parts: Vec<String> = body
        .split(':')
        .map(|p| if is_id(p) { "[id]".into() } else { p.into() })
        .collect();
    format!("{}{tail}", parts.join(":"))
}

/// Long hex (a fingerprint), or a long base64 run mixing letters and digits (a key, an Ed25519
/// relay id). Long words, even with underscores, don't count: they have no digits.
fn is_id(part: &str) -> bool {
    let digits = part.chars().filter(char::is_ascii_digit).count();
    let letters = part.chars().filter(char::is_ascii_alphabetic).count();
    let hex = part.len() >= 16 && part.chars().all(|c| c.is_ascii_hexdigit()) && digits > 0;
    let base64 = part.len() >= 20 && digits >= 2 && letters >= 2;
    hex || base64
}
