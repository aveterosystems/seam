// SPDX-License-Identifier: AGPL-3.0-only
//! The surface the apps see. Everything crosses as plain bytes and strings; peers are named by
//! their Seam device id. Swift and Kotlin code is generated from this file (scripts/gen-bindings.sh).

use std::sync::{Arc, Mutex};

use seam_core::{SignalProtocolError, bundle, file, peer_address};

uniffi::setup_scaffolding!();

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum SeamError {
    /// The peer was never paired (its identity key wasn't pinned).
    #[error("peer isn't paired")]
    NotPaired,
    /// The peer's key doesn't match the one from pairing: treat as an attack, don't retry.
    #[error("peer's identity doesn't match the paired one")]
    UntrustedIdentity,
    /// Anything else: malformed, tampered or replayed input.
    #[error("{detail}")]
    Invalid { detail: String },
}

impl From<seam_core::Error> for SeamError {
    fn from(e: seam_core::Error) -> Self {
        match e {
            seam_core::Error::NotPaired => Self::NotPaired,
            seam_core::Error::Signal(SignalProtocolError::UntrustedIdentity(_)) => {
                Self::UntrustedIdentity
            }
            other => Self::Invalid {
                detail: other.to_string(),
            },
        }
    }
}

type Result<T> = std::result::Result<T, SeamError>;

/// An encrypted message; `kind` goes in Envelope.signal_type.
#[derive(uniffi::Record)]
pub struct SealedMessage {
    pub kind: u8,
    pub bytes: Vec<u8>,
}

/// This device's identity and sessions. Safe to share across threads.
#[derive(uniffi::Object)]
pub struct SeamDevice {
    inner: Mutex<seam_core::Device>,
}

impl SeamDevice {
    fn with<T>(&self, f: impl FnOnce(&mut seam_core::Device) -> seam_core::Result<T>) -> Result<T> {
        let mut d = self.inner.lock().unwrap_or_else(|p| p.into_inner());
        Ok(f(&mut d)?)
    }
}

#[uniffi::export]
impl SeamDevice {
    #[uniffi::constructor]
    pub fn new(device_id: String) -> Result<Arc<Self>> {
        Ok(Arc::new(Self {
            inner: Mutex::new(seam_core::Device::new(&device_id)?),
        }))
    }

    pub fn identity_key(&self) -> Vec<u8> {
        self.inner
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .identity_key()
    }

    pub fn pin_peer(&self, peer: String, identity_key: Vec<u8>) -> Result<()> {
        self.with(|d| d.pin_peer(&peer_address(&peer), &identity_key))
    }

    /// A fresh signed bundle to upload to the relay.
    pub fn prekey_bundle(&self) -> Result<Vec<u8>> {
        self.with(|d| bundle::encode(&d.prekey_bundle()?))
    }

    pub fn start_session(&self, peer: String, bundle: Vec<u8>) -> Result<()> {
        let bundle = bundle::decode(&bundle)?;
        self.with(|d| d.start_session(&peer_address(&peer), &bundle))
    }

    pub fn encrypt(&self, peer: String, plaintext: Vec<u8>) -> Result<SealedMessage> {
        let s = self.with(|d| d.encrypt(&peer_address(&peer), &plaintext))?;
        Ok(SealedMessage {
            kind: s.kind,
            bytes: s.bytes,
        })
    }

    pub fn decrypt(&self, peer: String, sealed: SealedMessage) -> Result<Vec<u8>> {
        let s = seam_core::Sealed {
            kind: sealed.kind,
            bytes: sealed.bytes,
        };
        self.with(|d| d.decrypt(&peer_address(&peer), &s))
    }
}

#[uniffi::export]
pub fn new_file_key() -> Vec<u8> {
    file::new_file_key().to_vec()
}

#[uniffi::export]
pub fn new_file_id() -> Vec<u8> {
    file::new_file_id().to_vec()
}

#[uniffi::export]
pub fn seal_chunk(
    key: Vec<u8>,
    file_id: Vec<u8>,
    index: u32,
    last: bool,
    plain: Vec<u8>,
) -> Result<Vec<u8>> {
    let (key, id) = file_args(&key, &file_id)?;
    if plain.len() > file::CHUNK_SIZE {
        return Err(invalid("chunk larger than 1 MiB"));
    }
    Ok(file::seal_chunk(&key, &id, index, last, &plain)?)
}

#[uniffi::export]
pub fn open_chunk(
    key: Vec<u8>,
    file_id: Vec<u8>,
    index: u32,
    last: bool,
    sealed: Vec<u8>,
) -> Result<Vec<u8>> {
    let (key, id) = file_args(&key, &file_id)?;
    Ok(file::open_chunk(&key, &id, index, last, &sealed)?)
}

#[uniffi::export]
pub fn chunk_size() -> u32 {
    file::CHUNK_SIZE as u32
}

fn file_args(key: &[u8], id: &[u8]) -> Result<(file::FileKey, file::FileId)> {
    let key = key
        .try_into()
        .map_err(|_| invalid("file key must be 32 bytes"))?;
    let id = id
        .try_into()
        .map_err(|_| invalid("file id must be 16 bytes"))?;
    Ok((key, id))
}

fn invalid(detail: &str) -> SeamError {
    SeamError::Invalid {
        detail: detail.to_owned(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    // The same calls the apps make, through the exported API
    #[test]
    fn two_devices_talk_through_the_exported_api() {
        let mac = SeamDevice::new("mac".into()).unwrap();
        let phone = SeamDevice::new("phone".into()).unwrap();
        mac.pin_peer("phone".into(), phone.identity_key()).unwrap();
        phone.pin_peer("mac".into(), mac.identity_key()).unwrap();
        mac.start_session("phone".into(), phone.prekey_bundle().unwrap())
            .unwrap();

        let sealed = mac.encrypt("phone".into(), b"hi".to_vec()).unwrap();
        assert_eq!(phone.decrypt("mac".into(), sealed).unwrap(), b"hi");
    }

    #[test]
    fn errors_map_to_what_the_apps_handle() {
        let mac = SeamDevice::new("mac".into()).unwrap();
        let stranger = SeamDevice::new("stranger".into()).unwrap();
        let err = mac
            .start_session("stranger".into(), stranger.prekey_bundle().unwrap())
            .unwrap_err();
        assert!(matches!(err, SeamError::NotPaired));

        let phone = SeamDevice::new("phone".into()).unwrap();
        mac.pin_peer("phone".into(), phone.identity_key()).unwrap();
        let err = mac
            .start_session("phone".into(), stranger.prekey_bundle().unwrap())
            .unwrap_err();
        assert!(matches!(err, SeamError::UntrustedIdentity));

        assert!(matches!(
            seal_chunk(vec![0; 31], vec![0; 16], 0, true, vec![]),
            Err(SeamError::Invalid { .. })
        ));
    }
}
