// SPDX-License-Identifier: AGPL-3.0-only
//! The pairing QR code, version 2 (docs/tor/13-implementation-contract.md):
//! `seam://pair?v=2&o=<onion address>&d=<discovery secret>&t=<token>&i=<identity key>&k=<channel key>&n=<name>`
//! Binary values are base64url without padding; text values are percent-encoded with RFC 3986
//! unreserved characters only, so every QR reader decodes them the same way.

use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;

#[derive(Clone, PartialEq, Eq)]
pub struct PairingQr {
    /// The new onion service this pairing will use.
    pub onion_address: String,
    /// The phone's restricted-discovery secret: only it can find that service.
    pub discovery_secret: Vec<u8>,
    pub token: Vec<u8>,
    pub identity_key: Vec<u8>,
    /// The computer's relay key, which pins the channel.
    pub relay_key: Vec<u8>,
    pub name: String,
}

// Secrets never print
impl std::fmt::Debug for PairingQr {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("PairingQr(..)")
    }
}

#[cfg(test)]
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ParseError {
    NotSeamPairing,
    UnsupportedVersion,
    Missing(&'static str),
    BadLength(&'static str),
}

impl PairingQr {
    pub fn url(&self) -> String {
        let b = |v: &[u8]| URL_SAFE_NO_PAD.encode(v);
        format!(
            "seam://pair?v=2&o={}&d={}&t={}&i={}&k={}&n={}",
            percent_encode(&self.onion_address),
            b(&self.discovery_secret),
            b(&self.token),
            b(&self.identity_key),
            b(&self.relay_key),
            percent_encode(&self.name),
        )
    }

    /// Version 1 (a relay code) is refused: those devices update and pair again. Phones parse
    /// codes themselves; this checks what the computer shows.
    #[cfg(test)]
    pub fn parse(text: &str) -> Result<Self, ParseError> {
        let query = text
            .strip_prefix("seam://pair?")
            .ok_or(ParseError::NotSeamPairing)?;
        let mut q = std::collections::HashMap::new();
        for pair in query.split('&') {
            let (k, v) = pair.split_once('=').unwrap_or((pair, ""));
            q.entry(k).or_insert(v); // the first wins
        }
        if q.get("v") != Some(&"2") {
            return Err(ParseError::UnsupportedVersion);
        }
        let field = |k: &'static str| -> Result<String, ParseError> {
            let v =
                percent_decode(q.get(k).copied().unwrap_or("")).ok_or(ParseError::Missing(k))?;
            if v.is_empty() {
                return Err(ParseError::Missing(k));
            }
            Ok(v)
        };
        let bytes = |k: &'static str, len: usize| -> Result<Vec<u8>, ParseError> {
            let d = URL_SAFE_NO_PAD
                .decode(field(k)?)
                .map_err(|_| ParseError::Missing(k))?;
            if d.len() != len {
                return Err(ParseError::BadLength(k));
            }
            Ok(d)
        };
        Ok(Self {
            onion_address: field("o")?,
            discovery_secret: bytes("d", 32)?,
            token: bytes("t", 32)?,
            identity_key: bytes("i", 33)?,
            relay_key: bytes("k", 32)?,
            name: field("n")?,
        })
    }
}

fn percent_encode(s: &str) -> String {
    let mut out = String::new();
    for b in s.bytes() {
        if b.is_ascii_alphanumeric() || b"-._~".contains(&b) {
            out.push(b as char);
        } else {
            out.push_str(&format!("%{b:02X}"));
        }
    }
    out
}

#[cfg(test)]
fn percent_decode(s: &str) -> Option<String> {
    let b = s.as_bytes();
    let mut out = Vec::with_capacity(b.len());
    let mut i = 0;
    while i < b.len() {
        if b[i] == b'%' {
            let hex = std::str::from_utf8(b.get(i + 1..i + 3)?).ok()?;
            out.push(u8::from_str_radix(hex, 16).ok()?);
            i += 3;
        } else {
            out.push(b[i]);
            i += 1;
        }
    }
    String::from_utf8(out).ok()
}
