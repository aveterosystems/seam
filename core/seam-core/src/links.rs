// SPDX-License-Identifier: AGPL-3.0-only
//! Which path may carry a message to the paired device, the Hello state that
//! decides it, and the app's own saved data (the Tor details and Hello state). Plain logic, no
//! I/O: the apps feed it their switches and which paths are up, and it answers. Written once so the
//! Mac and Android can't drift apart.
//!
//! Saved as `version 5 | Tor secret (32) | onion address length (u8) | address UTF-8 | peer
//! discovery key length (u8, 0 or 32) | key | Hello seq sent (u64 BE) | Hello seq applied (u64 BE)
//! | the peer's allowed list: 0xFF (never heard) or a count (u8) | values (u8 each)`.
//! Earlier versions held a relay group (Tor replaced relays): they aren't read,
//! so those devices pair again.

#[cfg(test)]
mod tests;

/// The current format. Earlier (relay) ones aren't read.
pub const VERSION: u8 = 5;
const SECRET_LEN: usize = 32;
/// A v3 onion address is 62 characters; anything near this is wrong.
const MAX_ADDRESS: usize = 255;
const NEVER_HEARD: u8 = 0xFF;

/// A way to reach the other device. Numbers match `seam.v1.Transport`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub enum Path {
    /// Same Wi-Fi, direct.
    Lan,
    /// Through Tor, to the Mac's private onion service.
    Tor,
}

impl Path {
    /// Every path this build knows, in order of preference (Same Wi-Fi first).
    /// The one place that order lives: everything below picks by it, whatever order callers use.
    pub const ALL: [Path; 2] = [Path::Lan, Path::Tor];

    pub fn transport(self) -> i32 {
        match self {
            Path::Lan => 1,
            Path::Tor => 4,
        }
    }

    /// None for anything this build can't use (unspecified, Bluetooth, a newer device's value).
    pub fn from_transport(n: i32) -> Option<Path> {
        Path::ALL.into_iter().find(|p| p.transport() == n)
    }
}

/// This device's paths from its own switches (`enabled`), in order of preference, each once.
pub fn mine(enabled: &[Path]) -> Vec<Path> {
    Path::ALL
        .into_iter()
        .filter(|p| enabled.contains(p))
        .collect()
}

/// The most preferred path that's both `allowed` and `up`. None: wait in the outbox.
pub fn route(allowed: &[Path], up: &[Path]) -> Option<Path> {
    Path::ALL
        .into_iter()
        .find(|p| allowed.contains(p) && up.contains(p))
}

/// The app's saved data for its one pairing: how to reach the other device over Tor, and the
/// Hello state. Sealed with the device's keys: the Tor secret never leaves it unsealed.
#[derive(Clone, PartialEq, Eq)]
pub struct AppData {
    /// Mac: its onion service's secret seed. Phone: its restricted-discovery secret. 32 bytes.
    pub tor_secret: Vec<u8>,
    /// Phone: the Mac's onion address. Mac: empty (it follows from `tor_secret`).
    pub onion_address: String,
    /// Mac: the paired phone's discovery public key (32 bytes), the one client its service lets
    /// discover it. Phone: empty.
    pub peer_discovery: Vec<u8>,
    /// The seq of the last Hello this device sent.
    pub hello_seq_sent: u64,
    /// The seq of the last Hello applied from the peer.
    pub hello_seq_applied: u64,
    /// The peer's paths from its last applied Hello. None: never heard, or an older version that
    /// sends no list; either way everything this device allows. Some(empty): nothing in common.
    pub peer_allowed: Option<Vec<Path>>,
}

// The secret never prints, even in a panic message
impl std::fmt::Debug for AppData {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("AppData")
            .field("tor_secret", &"..")
            .field("onion_address", &self.onion_address)
            .field("peer_discovery", &self.peer_discovery.len())
            .field("hello_seq_sent", &self.hello_seq_sent)
            .field("hello_seq_applied", &self.hello_seq_applied)
            .field("peer_allowed", &self.peer_allowed)
            .finish()
    }
}

impl AppData {
    pub fn new(tor_secret: Vec<u8>, onion_address: String, peer_discovery: Vec<u8>) -> Self {
        Self {
            tor_secret,
            onion_address,
            peer_discovery,
            hello_seq_sent: 0,
            hello_seq_applied: 0,
            peer_allowed: None,
        }
    }

    /// Paths on at both ends, in order of preference.
    pub fn allowed(&self, enabled: &[Path]) -> Vec<Path> {
        let mut out = mine(enabled);
        if let Some(peer) = &self.peer_allowed {
            out.retain(|p| peer.contains(p));
        }
        out
    }

    /// The path to send on now. A Hello may take any up path this device's own switches allow,
    /// even one the peer's last list excludes, so a switch change always reaches it (two devices
    /// that each remember the other's old single-path choice could otherwise never reach each other).
    pub fn route(&self, enabled: &[Path], up: &[Path], hello: bool) -> Option<Path> {
        let allowed = if hello {
            mine(enabled)
        } else {
            self.allowed(enabled)
        };
        route(&allowed, up)
    }

    /// The next Hello's seq and allowed list (`seam.v1.Transport` numbers). Save before it leaves.
    pub fn next_hello(&mut self, enabled: &[Path]) -> (u64, Vec<i32>) {
        self.hello_seq_sent = self.hello_seq_sent.wrapping_add(1);
        (
            self.hello_seq_sent,
            mine(enabled).into_iter().map(Path::transport).collect(),
        )
    }

    /// Applies a Hello only if its seq is higher than any before (0 never counts). Values this
    /// build can't use are dropped; an empty list on the wire is an older version: everything.
    pub fn apply_hello(&mut self, seq: u64, allowed: &[i32]) -> bool {
        if seq <= self.hello_seq_applied {
            return false;
        }
        self.hello_seq_applied = seq;
        self.peer_allowed = (!allowed.is_empty()).then(|| known(allowed.iter().copied()));
        true
    }

    /// The peer was forgotten, or a new one paired: Hello state starts again.
    pub fn forget_peer(&mut self) {
        self.hello_seq_sent = 0;
        self.hello_seq_applied = 0;
        self.peer_allowed = None;
    }

    /// This app data, ready to save: Err for anything that wouldn't read back (a secret that
    /// isn't 32 bytes, an address too long, a peer key neither empty nor 32 bytes), with the
    /// peer's list sorted and without repeats (SR-29-01..03).
    pub fn checked(&self) -> crate::Result<Self> {
        if self.tor_secret.len() != SECRET_LEN {
            return Err(crate::Error::Malformed(
                "app data: Tor secret isn't 32 bytes",
            ));
        }
        if self.onion_address.len() > MAX_ADDRESS
            || !matches!(self.peer_discovery.len(), 0 | SECRET_LEN)
        {
            return Err(crate::Error::Malformed(
                "app data: address or peer key the wrong size",
            ));
        }
        let mut out = self.clone();
        out.peer_allowed = out
            .peer_allowed
            .map(|l| known(l.into_iter().map(Path::transport)));
        Ok(out)
    }

    pub fn encode(&self) -> Vec<u8> {
        let address = &self.onion_address.as_bytes()[..self.onion_address.len().min(MAX_ADDRESS)];
        let peer = &self.peer_discovery[..self.peer_discovery.len().min(SECRET_LEN)];
        let mut out = vec![VERSION];
        out.extend(&self.tor_secret[..self.tor_secret.len().min(SECRET_LEN)]);
        out.push(address.len() as u8);
        out.extend(address);
        out.push(peer.len() as u8);
        out.extend(peer);
        out.extend(self.hello_seq_sent.to_be_bytes());
        out.extend(self.hello_seq_applied.to_be_bytes());
        match &self.peer_allowed {
            None => out.push(NEVER_HEARD),
            Some(list) => {
                out.push(list.len() as u8);
                out.extend(list.iter().map(|p| p.transport() as u8));
            }
        }
        out
    }

    /// None for anything that isn't a complete v5 encoding. Earlier (relay) versions aren't read:
    /// those devices pair again.
    pub fn decode(bytes: &[u8]) -> Option<Self> {
        let mut r = Reader(bytes);
        if r.u8()? != VERSION {
            return None;
        }
        let secret = r.take(SECRET_LEN)?.to_vec();
        let n = r.u8()? as usize;
        let address = String::from_utf8(r.take(n)?.to_vec()).ok()?;
        let k = r.u8()? as usize;
        if !matches!(k, 0 | SECRET_LEN) {
            return None;
        }
        let mut data = AppData::new(secret, address, r.take(k)?.to_vec());
        data.hello_seq_sent = r.u64()?;
        data.hello_seq_applied = r.u64()?;
        let count = r.u8()?;
        if count != NEVER_HEARD {
            data.peer_allowed = Some(known(r.take(count as usize)?.iter().map(|&b| b as i32)));
        }
        r.0.is_empty().then_some(data)
    }
}

fn known(values: impl Iterator<Item = i32>) -> Vec<Path> {
    let mut out: Vec<Path> = values.filter_map(Path::from_transport).collect();
    out.sort();
    out.dedup();
    out
}

struct Reader<'a>(&'a [u8]);

impl<'a> Reader<'a> {
    fn take(&mut self, n: usize) -> Option<&'a [u8]> {
        if self.0.len() < n {
            return None;
        }
        let (head, rest) = self.0.split_at(n);
        self.0 = rest;
        Some(head)
    }
    fn u8(&mut self) -> Option<u8> {
        self.take(1).map(|b| b[0])
    }
    fn u64(&mut self) -> Option<u64> {
        self.take(8)
            .map(|b| u64::from_be_bytes(b.try_into().unwrap()))
    }
}
