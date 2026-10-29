// SPDX-License-Identifier: AGPL-3.0-only
//! File transfer encryption. Each file gets a random 256-bit key (sent inside a Signal message
//! with the file offer), and is sealed in 1 MiB chunks with AES-256-GCM.
//!
//! The nonce is the chunk index (unique per key, since every file has its own key). The
//! associated data binds the file id, the index and a "last chunk" flag, so chunks can't be
//! swapped between files, reordered, or the file silently cut short.

use aes_gcm::aead::{Aead, KeyInit, Payload};
use aes_gcm::{Aes256Gcm, Nonce};
use rand::RngCore;

use crate::{Error, Result};

pub const CHUNK_SIZE: usize = 1024 * 1024;
pub type FileKey = [u8; 32];
pub type FileId = [u8; 16];

pub fn new_file_key() -> FileKey {
    let mut key = [0u8; 32];
    rand::rng().fill_bytes(&mut key);
    key
}

pub fn new_file_id() -> FileId {
    let mut id = [0u8; 16];
    rand::rng().fill_bytes(&mut id);
    id
}

fn nonce(index: u32) -> [u8; 12] {
    let mut n = [0u8; 12];
    n[8..].copy_from_slice(&index.to_be_bytes());
    n
}

fn aad(file_id: &FileId, index: u32, last: bool) -> Vec<u8> {
    let mut a = b"seam-file-v1".to_vec();
    a.extend_from_slice(file_id);
    a.extend_from_slice(&index.to_be_bytes());
    a.push(last as u8);
    a
}

pub fn seal_chunk(
    key: &FileKey,
    file_id: &FileId,
    index: u32,
    last: bool,
    plain: &[u8],
) -> Result<Vec<u8>> {
    assert!(plain.len() <= CHUNK_SIZE, "chunk larger than CHUNK_SIZE");
    let cipher = Aes256Gcm::new(key.into());
    let aad = aad(file_id, index, last);
    cipher
        .encrypt(
            Nonce::from_slice(&nonce(index)),
            Payload {
                msg: plain,
                aad: &aad,
            },
        )
        .map_err(|_| Error::BadChunk)
}

pub fn open_chunk(
    key: &FileKey,
    file_id: &FileId,
    index: u32,
    last: bool,
    sealed: &[u8],
) -> Result<Vec<u8>> {
    let cipher = Aes256Gcm::new(key.into());
    let aad = aad(file_id, index, last);
    cipher
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

    #[test]
    fn round_trips() {
        let (key, id) = (new_file_key(), new_file_id());
        let sealed = seal_chunk(&key, &id, 0, true, b"photo bytes").unwrap();
        assert_eq!(
            open_chunk(&key, &id, 0, true, &sealed).unwrap(),
            b"photo bytes"
        );
    }

    #[test]
    fn rejects_reordering_truncation_swaps_and_tampering() {
        let (key, id) = (new_file_key(), new_file_id());
        let first = seal_chunk(&key, &id, 0, false, b"part one").unwrap();
        assert!(
            open_chunk(&key, &id, 1, false, &first).is_err(),
            "moved to another index"
        );
        assert!(
            open_chunk(&key, &id, 0, true, &first).is_err(),
            "passed off as the last chunk"
        );
        assert!(
            open_chunk(&key, &new_file_id(), 0, false, &first).is_err(),
            "swapped into another file"
        );
        assert!(
            open_chunk(&new_file_key(), &id, 0, false, &first).is_err(),
            "wrong key"
        );
        let mut flipped = first.clone();
        flipped[0] ^= 1;
        assert!(
            open_chunk(&key, &id, 0, false, &flipped).is_err(),
            "tampered"
        );
    }
}
