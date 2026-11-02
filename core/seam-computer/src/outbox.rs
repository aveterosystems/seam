// SPDX-License-Identifier: AGPL-3.0-only
//! Sealed envelopes for the phone not yet delivered (decision 0008, the computer side): sealed at
//! once, saved before any path sees them, resent byte for byte until the phone's Delivered.

use prost::Message;
use seam_core::proto::v1::Envelope;

use crate::durable::DurableFile;

/// Per list; the oldest goes first.
pub const CAP: usize = 100;
/// When an envelope sets no TTL.
const DEFAULT_TTL_S: i64 = 86_400;
const MAGIC: &[u8; 4] = b"SOB1";
const MAX_ENVELOPE: usize = 1_200_000;

/// Oldest first: `waiting` for an allowed path, `sent` directly but not yet acked.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct Outbox {
    waiting: Vec<Envelope>,
    sent: Vec<Envelope>,
}

impl Outbox {
    pub fn waiting(&self) -> &[Envelope] {
        &self.waiting
    }

    pub fn sent(&self) -> &[Envelope] {
        &self.sent
    }

    pub fn len(&self) -> usize {
        self.waiting.len() + self.sent.len()
    }

    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }

    /// Queues it unless it's already here (a retry).
    pub fn add(&mut self, envelope: Envelope) {
        if self.contains(&envelope.msg_id) {
            return;
        }
        self.waiting.push(envelope);
        trim(&mut self.waiting);
    }

    /// Handed to the direct link: kept until the phone's Delivered.
    pub fn mark_sent(&mut self, msg_id: &[u8]) {
        let Some(i) = self.waiting.iter().position(|e| e.msg_id == msg_id) else {
            return;
        };
        let e = self.waiting.remove(i);
        self.sent.push(e);
        trim(&mut self.sent);
    }

    /// Delivered (the phone acked it).
    pub fn remove(&mut self, msg_id: &[u8]) {
        self.waiting.retain(|e| e.msg_id != msg_id);
        self.sent.retain(|e| e.msg_id != msg_id);
    }

    /// The direct link dropped: what it never acked waits again, ahead of the rest.
    pub fn requeue_sent(&mut self) {
        let mut all = std::mem::take(&mut self.sent);
        all.append(&mut self.waiting);
        all.truncate(CAP);
        self.waiting = all;
    }

    /// True if anything went.
    pub fn drop_expired(&mut self, now_ms: i64) -> bool {
        let before = self.len();
        self.waiting.retain(|e| !expired(e, now_ms));
        self.sent.retain(|e| !expired(e, now_ms));
        self.len() != before
    }

    pub fn clear(&mut self) {
        self.waiting.clear();
        self.sent.clear();
    }

    pub fn contains(&self, msg_id: &[u8]) -> bool {
        self.waiting
            .iter()
            .chain(&self.sent)
            .any(|e| e.msg_id == msg_id)
    }

    /// `SOB1` | for waiting, then sent: count (u32 BE) | each: length (u32 BE) | Envelope bytes.
    /// The Swift app's format, so an update and a downgrade both read it.
    pub fn encode(&self) -> Vec<u8> {
        let mut out = MAGIC.to_vec();
        for list in [&self.waiting, &self.sent] {
            out.extend((list.len() as u32).to_be_bytes());
            for e in list {
                let b = e.encode_to_vec();
                out.extend((b.len() as u32).to_be_bytes());
                out.extend(b);
            }
        }
        out
    }

    /// None for anything malformed: the caller starts empty rather than trusting part of it.
    pub fn decode(bytes: &[u8]) -> Option<Self> {
        let rest = bytes.strip_prefix(MAGIC)?;
        let mut r = Reader(rest);
        let mut lists = [Vec::new(), Vec::new()];
        for list in &mut lists {
            let n = r.u32()? as usize;
            if n > CAP {
                return None;
            }
            for _ in 0..n {
                let len = r.u32()? as usize;
                if len > MAX_ENVELOPE {
                    return None;
                }
                list.push(Envelope::decode(r.take(len)?).ok()?);
            }
        }
        if !r.0.is_empty() {
            return None;
        }
        let [waiting, sent] = lists;
        Some(Self { waiting, sent })
    }
}

fn trim(list: &mut Vec<Envelope>) {
    if list.len() > CAP {
        list.drain(..list.len() - CAP);
    }
}

fn expired(e: &Envelope, now_ms: i64) -> bool {
    let ttl = if e.ttl_s == 0 {
        DEFAULT_TTL_S
    } else {
        i64::from(e.ttl_s)
    };
    now_ms > e.sent_at.saturating_add(ttl * 1000)
}

struct Reader<'a>(&'a [u8]);

impl<'a> Reader<'a> {
    fn take(&mut self, n: usize) -> Option<&'a [u8]> {
        if self.0.len() < n {
            return None;
        }
        let (head, tail) = self.0.split_at(n);
        self.0 = tail;
        Some(head)
    }

    fn u32(&mut self) -> Option<u32> {
        Some(u32::from_be_bytes(self.take(4)?.try_into().ok()?))
    }
}

/// Where the outbox is kept: written durably and atomically, mode 0600.
#[derive(Debug, Clone)]
pub struct OutboxFile {
    file: DurableFile,
}

impl OutboxFile {
    pub fn new(file: DurableFile) -> Self {
        Self { file }
    }

    pub fn file(&self) -> &DurableFile {
        &self.file
    }

    /// What was saved, minus anything expired. Empty for a missing, unreadable or damaged file:
    /// never a crash.
    pub fn load(&self, now_ms: i64) -> Outbox {
        let mut outbox = self
            .file
            .read()
            .ok()
            .flatten()
            .and_then(|b| Outbox::decode(&b))
            .unwrap_or_default();
        outbox.drop_expired(now_ms);
        outbox
    }

    pub fn save(&self, outbox: &Outbox) -> std::io::Result<()> {
        self.file.write(&outbox.encode())
    }

    pub fn delete(&self) {
        self.file.delete();
    }
}

#[cfg(test)]
mod tests;
