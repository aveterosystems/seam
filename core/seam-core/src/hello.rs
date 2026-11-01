// SPDX-License-Identifier: AGPL-3.0-only
//! The Hello each device sends on every connection, built and applied here so the Mac and Android
//! (and later computers) can't drift: its counter and switches (`links`), the pause, the links
//! open now and this device's capabilities. The app hands in its facts and gets a payload to
//! seal; from the peer's Hello it gets one [`PeerHello`], and the peer's capabilities are kept in
//! the app data.

#[cfg(test)]
mod tests;

use crate::links::{AppData, Path};
use crate::proto::v1::{Control, Hello, OpenLinks, Payload, control, payload};

/// At most this many capabilities are kept from a peer, each 1–64 bytes: anything else is dropped.
pub const MAX_CAPABILITIES: usize = 64;
pub const MAX_CAPABILITY_LEN: usize = 64;

/// What this device says about itself in its next Hello.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Facts {
    pub app_version: String,
    /// "macos" | "android" (later "linux", "windows").
    pub platform: String,
    /// This device's connection switches.
    pub enabled: Vec<Path>,
    /// The links this device has open to the peer now, so it can close any that died.
    pub open: Vec<Path>,
    /// Phone: paused until then (unix ms, 0: not paused). A computer sends 0.
    pub paused_until_ms: i64,
    /// What this device can do now (see `catalogue`), such as "ring_computer.v1".
    pub capabilities: Vec<String>,
}

/// What a newer Hello from the peer says.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PeerHello {
    /// The phone's pause end (unix ms); 0, or an older phone that doesn't send it: not paused.
    pub paused_until_ms: i64,
    /// The links the peer has open now; None from an older peer that doesn't say.
    pub open_links: Option<Vec<Path>>,
    /// Sorted, each once, bounded ([`capabilities`]).
    pub capabilities: Vec<String>,
}

/// Capabilities as kept: each 1–64 bytes, at most 64, sorted, each once. Anything else is dropped.
pub fn capabilities(list: &[String]) -> Vec<String> {
    let mut out: Vec<String> = list
        .iter()
        .filter(|c| (1..=MAX_CAPABILITY_LEN).contains(&c.len()))
        .cloned()
        .collect();
    out.sort();
    out.dedup();
    out.truncate(MAX_CAPABILITIES);
    out
}

impl AppData {
    /// The next Hello, as a payload ready to seal. Counts up the seq: save before it leaves.
    pub fn hello(&mut self, facts: &Facts) -> Payload {
        let (seq, allowed) = self.next_hello(&facts.enabled);
        let hello = Hello {
            app_version: facts.app_version.clone(),
            platform: facts.platform.clone(),
            capabilities: capabilities(&facts.capabilities),
            allowed_transports: allowed,
            seq,
            paused_until_ms: facts.paused_until_ms,
            open_links: Some(OpenLinks {
                transports: crate::links::mine(&facts.open)
                    .into_iter()
                    .map(Path::transport)
                    .collect(),
            }),
        };
        Payload {
            body: Some(payload::Body::Control(Control {
                body: Some(control::Body::Hello(hello)),
            })),
        }
    }

    /// Applies the peer's Hello if its seq is newer than any before. None: an old or replayed
    /// one, which must change nothing (SR-32-01).
    pub fn take_hello(&mut self, hello: &Hello) -> Option<PeerHello> {
        if !self.apply_hello(hello.seq, &hello.allowed_transports) {
            return None;
        }
        self.peer_capabilities = capabilities(&hello.capabilities);
        Some(PeerHello {
            paused_until_ms: hello.paused_until_ms.max(0),
            open_links: hello.open_links.as_ref().map(|o| {
                o.transports
                    .iter()
                    .filter_map(|&t| Path::from_transport(t))
                    .collect()
            }),
            capabilities: self.peer_capabilities.clone(),
        })
    }

    /// Whether the peer said it can do `capability` in its last Hello.
    pub fn peer_supports(&self, capability: &str) -> bool {
        self.peer_capabilities.iter().any(|c| c == capability)
    }
}
