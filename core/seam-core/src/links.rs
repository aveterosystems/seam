// SPDX-License-Identifier: AGPL-3.0-only
//! Which path may carry a message to the paired device (decision 0006), the Hello state that
//! decides it, and the app's own saved data (relay, group, Hello state). Plain logic, no I/O: the
//! apps feed it their switches and which paths are up, and it answers. Written once so the Mac
//! and Android can't drift apart.
//!
//! Saved as `version 4 | group id length (u8) | group id | relay URL length (u16 BE) | URL UTF-8 |
//! owner key length (u16 BE, may be 0) | owner key | Hello seq sent (u64 BE) | Hello seq applied
//! (u64 BE) | the peer's allowed list: 0xFF (never heard) or a count (u8) | values (u8 each)`.
//! Also reads what earlier app versions wrote: the Mac's v1–v3 and Android's v1–v2.

#[cfg(test)]
mod tests;

/// The current format. Earlier ones are read, never written.
pub const VERSION: u8 = 4;
const GROUP_LEN: usize = 16;
const NEVER_HEARD: u8 = 0xFF;

/// A way to reach the other device. Numbers match `seam.v1.Transport`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub enum Path {
    /// Same Wi-Fi, direct.
    Lan,
    /// Through the relay (ours or the user's own).
    Relay,
}

impl Path {
    pub const ALL: [Path; 2] = [Path::Lan, Path::Relay];

    pub fn transport(self) -> i32 {
        match self {
            Path::Lan => 1,
            Path::Relay => 2,
        }
    }

    /// None for anything this build can't use (unspecified, Bluetooth, a newer device's value).
    pub fn from_transport(n: i32) -> Option<Path> {
        Path::ALL.into_iter().find(|p| p.transport() == n)
    }
}

/// This device's paths from its own switches.
pub fn mine(wifi: bool, relay: bool) -> Vec<Path> {
    let mut out = Vec::new();
    if wifi {
        out.push(Path::Lan);
    }
    if relay {
        out.push(Path::Relay);
    }
    out
}

/// Same Wi-Fi first, then the relay, among `allowed` paths that are up. None: wait in the outbox.
pub fn route(allowed: &[Path], lan_up: bool, relay_up: bool) -> Option<Path> {
    [(Path::Lan, lan_up), (Path::Relay, relay_up)]
        .into_iter()
        .find(|(p, up)| *up && allowed.contains(p))
        .map(|(p, _)| p)
}

/// The app's saved data for its one pairing: where the relay group is, and the Hello state.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AppData {
    pub group_id: Vec<u8>,
    pub relay_url: String,
    /// A private relay's owner key (decision 0007); None on Seam's relay. Unused on the phone.
    pub owner_key: Option<Vec<u8>>,
    /// The seq of the last Hello this device sent.
    pub hello_seq_sent: u64,
    /// The seq of the last Hello applied from the peer.
    pub hello_seq_applied: u64,
    /// The peer's paths from its last applied Hello. None: never heard, or an older version that
    /// sends no list; either way everything this device allows. Some(empty): nothing in common.
    pub peer_allowed: Option<Vec<Path>>,
}

impl AppData {
    pub fn new(group_id: Vec<u8>, relay_url: String, owner_key: Option<Vec<u8>>) -> Self {
        Self {
            group_id,
            relay_url,
            owner_key: owner_key.filter(|k| !k.is_empty()),
            hello_seq_sent: 0,
            hello_seq_applied: 0,
            peer_allowed: None,
        }
    }

    /// Paths on at both ends.
    pub fn allowed(&self, wifi: bool, relay: bool) -> Vec<Path> {
        let mut out = mine(wifi, relay);
        if let Some(peer) = &self.peer_allowed {
            out.retain(|p| peer.contains(p));
        }
        out
    }

    /// The path to send on now. A Hello may take any up path this device's own switches allow,
    /// even one the peer's last list excludes, so a switch change always reaches it (two devices
    /// that each remember the other's old single-path choice could otherwise never reach each other).
    pub fn route(
        &self,
        wifi: bool,
        relay: bool,
        lan_up: bool,
        relay_up: bool,
        hello: bool,
    ) -> Option<Path> {
        let allowed = if hello {
            mine(wifi, relay)
        } else {
            self.allowed(wifi, relay)
        };
        route(&allowed, lan_up, relay_up)
    }

    /// The next Hello's seq and allowed list (`seam.v1.Transport` numbers). Save before it leaves.
    pub fn next_hello(&mut self, wifi: bool, relay: bool) -> (u64, Vec<i32>) {
        self.hello_seq_sent = self.hello_seq_sent.wrapping_add(1);
        (
            self.hello_seq_sent,
            mine(wifi, relay).into_iter().map(Path::transport).collect(),
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

    /// This app data, ready to save: Err for anything that wouldn't read back (a group id that
    /// isn't 16 bytes, a URL or owner key too long to store whole), with the peer's list sorted
    /// and without repeats (SR-29-01..03).
    pub fn checked(&self) -> crate::Result<Self> {
        let too_long = |n: usize| n > u16::MAX as usize;
        if self.group_id.len() != GROUP_LEN {
            return Err(crate::Error::Malformed("app data: group id isn't 16 bytes"));
        }
        if too_long(self.relay_url.len())
            || self.owner_key.as_ref().is_some_and(|k| too_long(k.len()))
        {
            return Err(crate::Error::Malformed(
                "app data: relay URL or owner key too long",
            ));
        }
        let mut out = self.clone();
        out.owner_key = out.owner_key.filter(|k| !k.is_empty());
        out.peer_allowed = out
            .peer_allowed
            .map(|l| known(l.into_iter().map(Path::transport)));
        Ok(out)
    }

    pub fn encode(&self) -> Vec<u8> {
        let group = &self.group_id[..self.group_id.len().min(255)];
        let url = &self.relay_url.as_bytes()[..self.relay_url.len().min(u16::MAX as usize)];
        let key = self.owner_key.as_deref().unwrap_or_default();
        let key = &key[..key.len().min(u16::MAX as usize)];
        let mut out = vec![VERSION, group.len() as u8];
        out.extend(group);
        out.extend((url.len() as u16).to_be_bytes());
        out.extend(url);
        out.extend((key.len() as u16).to_be_bytes());
        out.extend(key);
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

    /// None for anything that isn't a complete encoding with a 16-byte group id, in this format
    /// or one an earlier Mac or Android version wrote.
    pub fn decode(bytes: &[u8]) -> Option<Self> {
        match bytes.first()? {
            &VERSION => mac_layout(bytes),
            1..=3 => mac_layout(bytes).or_else(|| android_layout(bytes)),
            _ => None,
        }
    }
}

fn known(values: impl Iterator<Item = i32>) -> Vec<Path> {
    let mut out: Vec<Path> = values.filter_map(Path::from_transport).collect();
    out.sort();
    out.dedup();
    out
}

/// This format and the Mac's earlier ones, which it extends:
/// `v | group | url`, then v2 `| key (> 0)`, v3 and v4 `| key | seqs | list`.
fn mac_layout(bytes: &[u8]) -> Option<AppData> {
    let mut r = Reader(bytes);
    let v = r.u8()?;
    let g = r.u8()? as usize;
    if g != GROUP_LEN {
        return None;
    }
    let group = r.take(g)?.to_vec();
    let n = r.u16()? as usize;
    let url = String::from_utf8(r.take(n)?.to_vec()).ok()?;
    let mut data = AppData::new(group, url, None);
    if v >= 2 {
        let k = r.u16()? as usize;
        if v == 2 && k == 0 {
            return None;
        }
        data.owner_key = Some(r.take(k)?.to_vec()).filter(|k| !k.is_empty());
    }
    if v >= 3 {
        data.hello_seq_sent = r.u64()?;
        data.hello_seq_applied = r.u64()?;
        let count = r.u8()?;
        // v3 kept an older phone's empty Hello as count 0, meaning everything; v4's count 0 is
        // nothing in common. v3 stored values it didn't know as unspecified: nothing in common too.
        let everything = count == NEVER_HEARD || (v == 3 && count == 0);
        if !everything {
            data.peer_allowed = Some(known(r.take(count as usize)?.iter().map(|&b| b as i32)));
        }
    }
    r.0.is_empty().then_some(data)
}

/// Android's earlier format: `v | url | group`, then v2 `| seqs | known (0/1) | count | values`.
fn android_layout(bytes: &[u8]) -> Option<AppData> {
    let mut r = Reader(bytes);
    let v = r.u8()?;
    let n = r.u16()? as usize;
    let url = String::from_utf8(r.take(n)?.to_vec()).ok()?;
    let mut data = AppData::new(r.take(GROUP_LEN)?.to_vec(), url, None);
    if v == 2 {
        data.hello_seq_sent = r.u64()?;
        data.hello_seq_applied = r.u64()?;
        let heard = r.u8()?;
        let count = r.u8()? as usize;
        let values = r.take(count)?;
        data.peer_allowed = match heard {
            0 => None,
            // Android kept a Mac's empty Hello as "nothing"; an empty list means an older Mac
            1 if values.is_empty() => None,
            1 => Some(known(values.iter().map(|&b| b as i32))),
            _ => return None,
        };
    } else if v != 1 {
        return None;
    }
    r.0.is_empty().then_some(data)
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
    fn u16(&mut self) -> Option<u16> {
        self.take(2).map(|b| u16::from_be_bytes([b[0], b[1]]))
    }
    fn u64(&mut self) -> Option<u64> {
        self.take(8)
            .map(|b| u64::from_be_bytes(b.try_into().unwrap()))
    }
}
