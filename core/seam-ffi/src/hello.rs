// SPDX-License-Identifier: AGPL-3.0-only
//! The Hello for the apps (seam_core::hello): the app hands in its facts and gets a payload to
//! seal; from the peer's Hello it gets what changed.

use prost::Message;
use seam_core::hello;

use crate::{PeerPath, Result, SeamDevice, invalid, paths};

/// What this device says about itself in its next Hello.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct HelloFacts {
    pub app_version: String,
    /// "macos" | "android".
    pub platform: String,
    /// This device's connection switches, any order.
    pub enabled: Vec<PeerPath>,
    /// The links open to the peer now, any order.
    pub open: Vec<PeerPath>,
    /// Phone: paused until then (unix ms, 0: not paused). A computer sends 0.
    pub paused_until_ms: i64,
    /// What this device can do now: `core_capabilities()` plus the app's own.
    pub capabilities: Vec<String>,
}

/// What a newer Hello from the peer says.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct PeerHello {
    /// The phone's pause end (unix ms); 0: not paused.
    pub paused_until_ms: i64,
    /// The links the peer has open now; None from an older peer that doesn't say.
    pub open_links: Option<Vec<PeerPath>>,
    pub capabilities: Vec<String>,
}

#[uniffi::export]
impl SeamDevice {
    /// The next Hello, as `seam.v1.Payload` bytes ready to seal. Counts up in the app data: save
    /// `export_state` before it leaves. None without app data.
    pub fn next_hello(&self, facts: HelloFacts) -> Option<Vec<u8>> {
        let facts = hello::Facts {
            app_version: facts.app_version,
            platform: facts.platform,
            enabled: paths(facts.enabled),
            open: paths(facts.open),
            paused_until_ms: facts.paused_until_ms,
            capabilities: facts.capabilities,
        };
        let payload = self.lock().edit_app_data(|d| d.hello(&facts))?;
        Some(payload.encode_to_vec())
    }

    /// Applies the peer's Hello (`seam.v1.Hello` bytes) if its seq is newer than any before;
    /// save `export_state` then. None: old, replayed, or no app data, and nothing changed.
    pub fn apply_hello(&self, hello: Vec<u8>) -> Result<Option<PeerHello>> {
        let hello = seam_core::proto::v1::Hello::decode(hello.as_slice())
            .map_err(|_| invalid("not a Hello"))?;
        let peer = self
            .lock()
            .edit_app_data(|d| d.take_hello(&hello))
            .flatten();
        Ok(peer.map(|p| PeerHello {
            paused_until_ms: p.paused_until_ms,
            open_links: p
                .open_links
                .map(|l| l.into_iter().map(Into::into).collect()),
            capabilities: p.capabilities,
        }))
    }

    /// Whether the peer said it can do `capability` in its last Hello.
    pub fn peer_supports(&self, capability: String) -> bool {
        self.lock()
            .app_data()
            .is_some_and(|d| d.peer_supports(&capability))
    }
}
