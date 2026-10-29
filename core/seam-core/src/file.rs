// SPDX-License-Identifier: AGPL-3.0-only
//! File transfer encryption. A [`FileSealer`] makes a random 256-bit key and 16-byte file id
//! (both sent inside a Signal message with the file offer) and seals the file in chunks with
//! AES-256-GCM; a [`FileReceiver`] opens them on the other side.
//!
//! The types make misuse impossible rather than merely documented:
//! - the key never leaves the sealer except to go into the offer, so it can't be reused;
//! - chunks are sealed strictly in order and exactly one is marked last, so the nonce (the chunk
//!   index) is never reused under a key;
//! - the receiver insists on the same order and on seeing the last chunk, so a file can't be
//!   reordered, spliced or silently cut short;
//! - every chunk's plaintext is padded to a bucket, so the last chunk doesn't reveal the exact
//!   file size.
//!
//! The associated data binds the file id, the index and the last flag.

use aes_gcm::aead::{Aead, KeyInit, Payload};
use aes_gcm::{Aes256Gcm, Nonce};
use rand::RngCore;

use crate::{Error, Result, padding};

/// Sealed chunk plaintext size (the largest padding bucket).
pub const CHUNK_SIZE: usize = 1024 * 1024;
/// File bytes per chunk: the padding's 4-byte length prefix comes out of the chunk.
pub const CHUNK_DATA: usize = CHUNK_SIZE - 4;

pub type FileKey = [u8; 32];
pub type FileId = [u8; 16];

pub struct FileSealer {
    key: FileKey,
    id: FileId,
    next: u32,
    done: bool,
}

impl FileSealer {
    pub fn new() -> Self {
        let mut key = [0u8; 32];
        let mut id = [0u8; 16];
        rand::rng().fill_bytes(&mut key);
        rand::rng().fill_bytes(&mut id);
        Self {
            key,
            id,
            next: 0,
            done: false,
        }
    }

    /// For the FileOffer only.
    pub fn key(&self) -> &FileKey {
        &self.key
    }

    pub fn id(&self) -> &FileId {
        &self.id
    }

    /// Seal the next chunk. Every chunk but the last must be exactly [`CHUNK_DATA`] bytes.
    pub fn seal_next(&mut self, data: &[u8], last: bool) -> Result<Vec<u8>> {
        if self.done {
            return Err(Error::BadChunk);
        }
        if data.len() > CHUNK_DATA || (!last && data.len() != CHUNK_DATA) {
            return Err(Error::Malformed("chunk size"));
        }
        let index = self.next;
        self.next = self
            .next
            .checked_add(1)
            .ok_or(Error::Malformed("too many chunks"))?;
        self.done = last;
        seal(&self.key, &self.id, index, last, &padding::pad(data)?)
    }
}

impl Default for FileSealer {
    fn default() -> Self {
        Self::new()
    }
}

pub struct FileReceiver {
    key: FileKey,
    id: FileId,
    next: u32,
    done: bool,
}

impl FileReceiver {
    pub fn new(key: FileKey, id: FileId) -> Self {
        Self {
            key,
            id,
            next: 0,
            done: false,
        }
    }

    /// Open the next chunk in order. `last` comes from the transfer metadata; a false claim
    /// fails authentication.
    pub fn open_next(&mut self, sealed: &[u8], last: bool) -> Result<Vec<u8>> {
        if self.done {
            return Err(Error::BadChunk);
        }
        let plain = open(&self.key, &self.id, self.next, last, sealed)?;
        let data = padding::unpad(&plain)?;
        if !last && data.len() != CHUNK_DATA {
            return Err(Error::BadChunk);
        }
        self.next = self.next.checked_add(1).ok_or(Error::BadChunk)?;
        self.done = last;
        Ok(data)
    }

    /// Call when the transfer ends: fails unless the last chunk arrived (no silent truncation).
    pub fn finish(&self) -> Result<()> {
        if self.done {
            Ok(())
        } else {
            Err(Error::BadChunk)
        }
    }
}

fn nonce(index: u32) -> [u8; 12] {
    let mut n = [0u8; 12];
    n[8..].copy_from_slice(&index.to_be_bytes());
    n
}

fn aad(file_id: &FileId, index: u32, last: bool) -> Vec<u8> {
    let mut a = b"seam-file-v2".to_vec();
    a.extend_from_slice(file_id);
    a.extend_from_slice(&index.to_be_bytes());
    a.push(last as u8);
    a
}

fn seal(key: &FileKey, id: &FileId, index: u32, last: bool, plain: &[u8]) -> Result<Vec<u8>> {
    let aad = aad(id, index, last);
    Aes256Gcm::new(key.into())
        .encrypt(
            Nonce::from_slice(&nonce(index)),
            Payload {
                msg: plain,
                aad: &aad,
            },
        )
        .map_err(|_| Error::BadChunk)
}

fn open(key: &FileKey, id: &FileId, index: u32, last: bool, sealed: &[u8]) -> Result<Vec<u8>> {
    let aad = aad(id, index, last);
    Aes256Gcm::new(key.into())
        .decrypt(
            Nonce::from_slice(&nonce(index)),
            Payload {
                msg: sealed,
                aad: &aad,
            },
        )
        .map_err(|_| Error::BadChunk)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn pair() -> (FileSealer, FileReceiver) {
        let s = FileSealer::new();
        let r = FileReceiver::new(*s.key(), *s.id());
        (s, r)
    }

    #[test]
    fn a_multi_chunk_file_round_trips() {
        let (mut s, mut r) = pair();
        let full = vec![7u8; CHUNK_DATA];
        let c0 = s.seal_next(&full, false).unwrap();
        let c1 = s.seal_next(b"tail", true).unwrap();
        assert_eq!(r.open_next(&c0, false).unwrap(), full);
        assert_eq!(r.open_next(&c1, true).unwrap(), b"tail");
        r.finish().unwrap();
    }

    #[test]
    fn sealer_refuses_anything_that_would_reuse_a_nonce() {
        let mut s = FileSealer::new();
        s.seal_next(b"only", true).unwrap();
        assert!(
            s.seal_next(b"again", true).is_err(),
            "no chunk after the last one"
        );
        assert!(
            FileSealer::new().seal_next(b"short", false).is_err(),
            "non-last chunks are full"
        );
        assert_ne!(
            FileSealer::new().key(),
            FileSealer::new().key(),
            "a fresh key per file"
        );
    }

    #[test]
    fn receiver_catches_reordering_truncation_swaps_and_tampering() {
        let (mut s, mut r) = pair();
        let c0 = s.seal_next(&vec![1u8; CHUNK_DATA], false).unwrap();
        let c1 = s.seal_next(b"end", true).unwrap();
        assert!(r.open_next(&c1, true).is_err(), "out of order");
        assert!(
            r.open_next(&c0, true).is_err(),
            "passed off as the last chunk"
        );
        r.open_next(&c0, false).unwrap();
        assert!(r.finish().is_err(), "cut short before the last chunk");

        let mut flipped = c1.clone();
        flipped[0] ^= 1;
        assert!(r.open_next(&flipped, true).is_err(), "tampered");

        let other = FileReceiver::new(*s.key(), [9u8; 16]).open_next(&c0, false);
        assert!(other.is_err(), "swapped into another file");
    }

    #[test]
    fn last_chunk_size_is_hidden_inside_its_bucket() {
        let (mut a, mut b) = (FileSealer::new(), FileSealer::new());
        assert_eq!(
            a.seal_next(b"x", true).unwrap().len(),
            b.seal_next(&[0u8; 900], true).unwrap().len()
        );
    }
}
