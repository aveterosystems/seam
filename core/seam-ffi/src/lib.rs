// SPDX-License-Identifier: AGPL-3.0-only
//! The surface the apps see, generated into Swift and Kotlin (scripts/gen-bindings.sh).
//!
//! Only high-level operations are exposed: seal and open envelopes, pair, save and restore. The
//! apps can't encrypt, decrypt or pin keys directly, so they can't skip a check by accident.

use std::sync::{Arc, Mutex, MutexGuard};

use seam_core::{Device, SignalProtocolError, bundle, file};

uniffi::setup_scaffolding!();

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum SeamError {
    /// The other device isn't paired (or was unpaired).
    #[error("peer isn't paired")]
    NotPaired,
    /// A key doesn't match the one from pairing: treat as an attack, don't retry.
    #[error("peer's identity doesn't match the paired one")]
    UntrustedIdentity,
    /// Already received: ack it to the relay and ignore it.
    #[error("already received")]
    Duplicate,
    /// Pairing was refused (wrong or expired code, nothing open, already paired…).
    #[error("pairing failed: {reason}")]
    PairingFailed { reason: String },
    /// Saved state can't be opened: wrong storage key or damaged file.
    #[error("saved state can't be opened")]
    BadState,
    /// Anything else: malformed, tampered, misaddressed or unsupported input. Drop it.
    #[error("{detail}")]
    Invalid { detail: String },
}

impl From<seam_core::Error> for SeamError {
    fn from(e: seam_core::Error) -> Self {
        use seam_core::Error as E;
        match e {
            E::NotPaired => Self::NotPaired,
            E::Signal(SignalProtocolError::UntrustedIdentity(_)) => Self::UntrustedIdentity,
            E::Duplicate => Self::Duplicate,
            E::BadPairing(reason) => Self::PairingFailed {
                reason: reason.to_owned(),
            },
            E::BadState => Self::BadState,
            other => Self::Invalid {
                detail: other.to_string(),
            },
        }
    }
}

type Result<T> = std::result::Result<T, SeamError>;

#[derive(uniffi::Record)]
pub struct PeerInfo {
    pub id: String,
    pub name: String,
    pub relay_key: Vec<u8>,
}

/// A message that passed every check. `payload` is a seam.v1.Payload.
#[derive(uniffi::Record)]
pub struct OpenedMessage {
    pub peer_id: String,
    pub msg_id: Vec<u8>,
    pub sent_at: i64,
    pub payload: Vec<u8>,
}

/// What the Mac shows in the QR code (`token`) and sends the relay (`token_hash`).
#[derive(uniffi::Record)]
pub struct PairingOffer {
    pub token: Vec<u8>,
    pub token_hash: Vec<u8>,
}

/// The phone's first envelope to the Mac, and the Mac's id.
#[derive(uniffi::Record)]
pub struct PairingStart {
    pub peer_id: String,
    pub envelope: Vec<u8>,
}

/// A pairing request the Mac has verified, waiting for the user to confirm the code.
#[derive(uniffi::Record)]
pub struct PairingRequest {
    pub peer_id: String,
    pub device_name: String,
    pub model: String,
    pub safety_code: String,
}

/// This device: identity, peers and sessions. Safe to share across threads.
#[derive(uniffi::Object)]
pub struct SeamDevice {
    inner: Mutex<Device>,
}

impl SeamDevice {
    fn lock(&self) -> MutexGuard<'_, Device> {
        self.inner.lock().unwrap_or_else(|p| p.into_inner())
    }
}

#[uniffi::export]
impl SeamDevice {
    /// A brand-new device identity. Call once, then `export_state` and keep the blob.
    #[uniffi::constructor]
    pub fn generate() -> Result<Arc<Self>> {
        Ok(Arc::new(Self {
            inner: Mutex::new(Device::generate()?),
        }))
    }

    /// Rebuild from `export_state`'s blob and the storage key it was sealed with.
    #[uniffi::constructor]
    pub fn restore(state: Vec<u8>, storage_key: Vec<u8>) -> Result<Arc<Self>> {
        let key = key32(&storage_key)?;
        Ok(Arc::new(Self {
            inner: Mutex::new(Device::restore(&state, &key)?),
        }))
    }

    /// Everything to carry on after a restart, sealed under `storage_key` (32 bytes). Save it
    /// after anything that changes state: a seal, an open, pairing, new bundles.
    pub fn export_state(&self, storage_key: Vec<u8>) -> Result<Vec<u8>> {
        Ok(self.lock().export_state(&key32(&storage_key)?)?)
    }

    pub fn id(&self) -> String {
        self.lock().id().to_owned()
    }

    pub fn identity_key(&self) -> Vec<u8> {
        self.lock().identity_key()
    }

    pub fn relay_key(&self) -> Vec<u8> {
        self.lock().relay_key().to_vec()
    }

    /// The answer to the relay's login challenge.
    pub fn sign_relay_login(&self, nonce: Vec<u8>, group_id: Vec<u8>) -> Vec<u8> {
        self.lock().sign_relay_login(&nonce, &group_id).to_vec()
    }

    /// `count` fresh signed prekey bundles to upload to the relay.
    pub fn prekey_bundles(&self, count: u32) -> Result<Vec<Vec<u8>>> {
        let mut d = self.lock();
        (0..count)
            .map(|_| Ok(bundle::encode(&d.prekey_bundle()?)?))
            .collect()
    }

    pub fn peers(&self) -> Vec<PeerInfo> {
        self.lock()
            .peers()
            .iter()
            .map(|(id, p)| PeerInfo {
                id: id.clone(),
                name: p.name.clone(),
                relay_key: p.relay_key.to_vec(),
            })
            .collect()
    }

    pub fn remove_peer(&self, peer_id: String) {
        self.lock().remove_peer(&peer_id);
    }

    pub fn safety_code(&self, peer_id: String) -> Result<String> {
        Ok(self.lock().safety_code(&peer_id)?)
    }

    /// Seal a seam.v1.Payload for a paired peer. Returns the Envelope to send. `ttl_s` 0 = 24 h.
    pub fn seal(&self, peer_id: String, payload: Vec<u8>, ttl_s: u32) -> Result<Vec<u8>> {
        Ok(self.lock().seal(&peer_id, &payload, ttl_s)?)
    }

    /// Open an Envelope from a paired peer.
    pub fn open(&self, envelope: Vec<u8>) -> Result<OpenedMessage> {
        let o = self.lock().open(&envelope)?;
        Ok(OpenedMessage {
            peer_id: o.peer_id,
            msg_id: o.msg_id,
            sent_at: o.sent_at,
            payload: o.payload,
        })
    }

    /// Mac: open a 5-minute pairing slot.
    pub fn start_pairing(&self) -> PairingOffer {
        let o = self.lock().start_pairing();
        PairingOffer {
            token: o.token.to_vec(),
            token_hash: o.token_hash.to_vec(),
        }
    }

    /// Phone: pair with the Mac from its QR code and bundle. Send the returned envelope.
    #[allow(clippy::too_many_arguments)]
    pub fn pair_with(
        &self,
        identity_key: Vec<u8>,
        relay_key: Vec<u8>,
        name: String,
        bundle: Vec<u8>,
        token: Vec<u8>,
        my_name: String,
        my_model: String,
    ) -> Result<PairingStart> {
        let bundle = bundle::decode(&bundle)?;
        let (peer_id, envelope) = self.lock().pair_with(
            &identity_key,
            key32(&relay_key)?,
            &name,
            &bundle,
            &token,
            &my_name,
            &my_model,
        )?;
        Ok(PairingStart { peer_id, envelope })
    }

    /// Mac: check the phone's first envelope against the open pairing slot.
    pub fn accept_pairing(&self, envelope: Vec<u8>) -> Result<PairingRequest> {
        let r = self.lock().accept_pairing(&envelope)?;
        Ok(PairingRequest {
            peer_id: r.peer_id,
            device_name: r.device_name,
            model: r.model,
            safety_code: r.safety_code,
        })
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

fn key32(bytes: &[u8]) -> Result<[u8; 32]> {
    bytes
        .try_into()
        .map_err(|_| invalid("key must be 32 bytes"))
}

fn file_args(key: &[u8], id: &[u8]) -> Result<(file::FileKey, file::FileId)> {
    let id = id
        .try_into()
        .map_err(|_| invalid("file id must be 16 bytes"))?;
    Ok((key32(key)?, id))
}

fn invalid(detail: &str) -> SeamError {
    SeamError::Invalid {
        detail: detail.to_owned(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The whole M1 flow through the exported API, as the apps will run it.
    #[test]
    fn pair_save_restore_and_talk_through_the_exported_api() {
        let key = vec![7u8; 32];
        let mac = SeamDevice::generate().unwrap();
        let phone = SeamDevice::generate().unwrap();

        // Mac shows a QR code and uploads bundles; the phone scans and fetches one
        let offer = mac.start_pairing();
        let bundles = mac.prekey_bundles(3).unwrap();
        assert_eq!(bundles.len(), 3);
        let start = phone
            .pair_with(
                mac.identity_key(),
                mac.relay_key(),
                "Mac".into(),
                bundles[0].clone(),
                offer.token,
                "Phone".into(),
                "SM-S938B".into(),
            )
            .unwrap();
        assert_eq!(start.peer_id, mac.id());

        let request = mac.accept_pairing(start.envelope).unwrap();
        assert_eq!(request.peer_id, phone.id());
        assert_eq!(request.safety_code, phone.safety_code(mac.id()).unwrap());

        // Both restart from saved state
        let mac = SeamDevice::restore(mac.export_state(key.clone()).unwrap(), key.clone()).unwrap();
        let phone =
            SeamDevice::restore(phone.export_state(key.clone()).unwrap(), key.clone()).unwrap();
        assert_eq!(mac.peers()[0].name, "Phone");

        // Payload bytes: field 50 (status), battery_pct = 82
        let status = vec![0x92, 0x03, 0x02, 0x08, 82];
        let opened = mac
            .open(phone.seal(mac.id(), status.clone(), 0).unwrap())
            .unwrap();
        assert_eq!(opened.payload, status);
        assert_eq!(opened.peer_id, phone.id());
    }

    #[test]
    fn errors_map_to_what_the_apps_handle() {
        let mac = SeamDevice::generate().unwrap();
        let stranger = SeamDevice::generate().unwrap();
        let bundle = mac.prekey_bundles(1).unwrap().remove(0);
        let env = stranger
            .pair_with(
                mac.identity_key(),
                mac.relay_key(),
                "Mac".into(),
                bundle,
                vec![1; 32],
                "x".into(),
                "y".into(),
            )
            .unwrap()
            .envelope;
        assert!(matches!(mac.open(env.clone()), Err(SeamError::NotPaired)));
        assert!(matches!(
            mac.accept_pairing(env),
            Err(SeamError::PairingFailed { .. })
        ));
        assert!(matches!(
            SeamDevice::restore(vec![1, 2, 3], vec![0; 32]),
            Err(SeamError::BadState)
        ));
        assert!(matches!(
            mac.export_state(vec![0; 31]),
            Err(SeamError::Invalid { .. })
        ));
        assert!(matches!(
            seal_chunk(vec![0; 31], vec![0; 16], 0, true, vec![]),
            Err(SeamError::Invalid { .. })
        ));
    }
}
