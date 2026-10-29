// SPDX-License-Identifier: AGPL-3.0-only
//! One device's identity and its Signal sessions with paired peers.
//!
//! Setup uses PQXDH (X25519 + ML-KEM-1024) from the peer's signed prekey bundle; messages then
//! go through libsignal's triple ratchet, so each message has its own key and recovers from a
//! leaked key. Peers must be pinned first (from the pairing QR code): Seam never trusts a key
//! it sees for the first time inside a message.

use std::collections::BTreeMap;
use std::time::SystemTime;

use ed25519_dalek::SigningKey;

use futures::executor::block_on;
use libsignal_protocol::{
    CiphertextMessage, CiphertextMessageType, DeviceId, GenericSignedPreKey, IdentityKey,
    IdentityKeyPair, IdentityKeyStore, InMemSignalProtocolStore, KeyPair, KyberPreKeyRecord,
    KyberPreKeyStore, PreKeyBundle, PreKeyRecord, PreKeySignalMessage, PreKeyStore,
    ProtocolAddress, SignalMessage, SignalProtocolError, SignedPreKeyRecord, SignedPreKeyStore,
    Timestamp, kem,
};
use rand::{Rng, RngCore};
use sha2::{Digest, Sha256};

use crate::{Error, Result, padding};

/// An encrypted message ready for the envelope: `kind` says which Signal message type it is.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Sealed {
    pub kind: u8,
    pub bytes: Vec<u8>,
}

/// A paired device, as Seam knows it. The peers map is the source of trust: a device that
/// isn't in it can't be sealed to or opened from, whatever libsignal's store holds.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Peer {
    pub identity_key: Vec<u8>,
    pub relay_key: [u8; 32],
    pub name: String,
}

pub struct Device {
    pub(crate) address: ProtocolAddress,
    // In memory while running; saved and restored as one sealed blob (state.rs)
    pub(crate) store: InMemSignalProtocolStore,
    pub(crate) next_prekey_id: u32,
    pub(crate) relay_key: SigningKey,
    pub(crate) peers: BTreeMap<String, Peer>,
    pub(crate) pending_pairing: Option<crate::pairing::PendingPairing>,
}

impl Device {
    /// A new device: fresh identity, registration id and relay-login key.
    pub fn generate() -> Result<Self> {
        let mut rng = rand::rng();
        let identity = IdentityKeyPair::generate(&mut rng);
        let registration_id = rng.random_range(1..16380);
        let mut seed = [0u8; 32];
        rng.fill_bytes(&mut seed);
        Self::from_parts(identity, registration_id, 1, SigningKey::from_bytes(&seed))
    }

    pub(crate) fn from_parts(
        identity: IdentityKeyPair,
        registration_id: u32,
        next_prekey_id: u32,
        relay_key: SigningKey,
    ) -> Result<Self> {
        Ok(Self {
            address: peer_address(&device_id_for(&identity.identity_key().serialize())),
            store: InMemSignalProtocolStore::new(identity, registration_id)?,
            next_prekey_id,
            relay_key,
            peers: BTreeMap::new(),
            pending_pairing: None,
        })
    }

    pub fn address(&self) -> &ProtocolAddress {
        &self.address
    }

    /// This device's id: derived from its identity key, so it can't be chosen or spoofed.
    pub fn id(&self) -> &str {
        self.address.name()
    }

    pub fn peers(&self) -> &BTreeMap<String, Peer> {
        &self.peers
    }

    /// Record a paired device and pin its identity key. Returns its id.
    pub fn add_peer(
        &mut self,
        identity_key: &[u8],
        relay_key: [u8; 32],
        name: &str,
    ) -> Result<String> {
        let id = device_id_for(identity_key);
        self.pin_peer(&peer_address(&id), identity_key)?;
        self.peers.insert(
            id.clone(),
            Peer {
                identity_key: identity_key.to_vec(),
                relay_key,
                name: name.to_owned(),
            },
        );
        Ok(id)
    }

    /// Forget a peer: it can no longer be sealed to or opened from.
    pub fn remove_peer(&mut self, peer_id: &str) {
        self.peers.remove(peer_id);
    }

    /// This device's public identity key, as it goes into the pairing QR code.
    pub fn identity_key(&self) -> Vec<u8> {
        let pair =
            block_on(self.store.identity_store.get_identity_key_pair()).expect("in-memory store");
        pair.identity_key().serialize().to_vec()
    }

    /// Trust `peer`'s identity key, learned out of band at pairing. Required before any session.
    pub fn pin_peer(&mut self, peer: &ProtocolAddress, identity_key: &[u8]) -> Result<()> {
        let key = IdentityKey::decode(identity_key)?;
        block_on(self.store.identity_store.save_identity(peer, &key))?;
        Ok(())
    }

    /// A fresh signed bundle (one-time EC prekey, signed prekey, signed ML-KEM prekey) for a peer
    /// to start a session with. Private halves stay in this device's store.
    pub fn prekey_bundle(&mut self) -> Result<PreKeyBundle> {
        let mut rng = rand::rng();
        let identity = block_on(self.store.identity_store.get_identity_key_pair())?;
        let registration_id = block_on(self.store.identity_store.get_local_registration_id())?;
        let id = self.next_prekey_id;
        self.next_prekey_id += 1;

        let one_time = KeyPair::generate(&mut rng);
        let signed = KeyPair::generate(&mut rng);
        let sign = |data: &[u8], rng: &mut _| {
            identity
                .private_key()
                .calculate_signature(data, rng)
                .map_err(SignalProtocolError::from)
        };
        let signed_sig = sign(&signed.public_key.serialize(), &mut rng)?;
        let kyber = kem::KeyPair::generate(kem::KeyType::Kyber1024, &mut rng);
        let kyber_sig = sign(&kyber.public_key.serialize(), &mut rng)?;
        let now = Timestamp::from_epoch_millis(now_millis());

        block_on(
            self.store
                .pre_key_store
                .save_pre_key(id.into(), &PreKeyRecord::new(id.into(), &one_time)),
        )?;
        block_on(self.store.signed_pre_key_store.save_signed_pre_key(
            id.into(),
            &SignedPreKeyRecord::new(id.into(), now, &signed, &signed_sig),
        ))?;
        block_on(self.store.kyber_pre_key_store.save_kyber_pre_key(
            id.into(),
            &KyberPreKeyRecord::new(id.into(), now, &kyber, &kyber_sig),
        ))?;

        Ok(PreKeyBundle::new(
            registration_id,
            device_id(),
            Some((id.into(), one_time.public_key)),
            id.into(),
            signed.public_key,
            signed_sig.to_vec(),
            id.into(),
            kyber.public_key.clone(),
            kyber_sig.to_vec(),
            *identity.identity_key(),
        )?)
    }

    /// Start a session with a pinned peer from their bundle. Fails if the bundle's identity
    /// isn't the pinned one, or its signatures don't check out.
    pub fn start_session(&mut self, peer: &ProtocolAddress, bundle: &PreKeyBundle) -> Result<()> {
        self.require_pinned(peer)?;
        let s = &mut self.store;
        block_on(libsignal_protocol::initiate_session(
            peer,
            &self.address,
            &mut s.session_store,
            &mut s.identity_store,
            bundle,
            SystemTime::now(),
            &mut rand::rng(),
        ))?;
        Ok(())
    }

    /// Pad and encrypt `plaintext` for `peer`.
    pub fn encrypt(&mut self, peer: &ProtocolAddress, plaintext: &[u8]) -> Result<Sealed> {
        let padded = padding::pad(plaintext)?;
        let s = &mut self.store;
        let msg = block_on(libsignal_protocol::message_encrypt(
            &padded,
            peer,
            &self.address,
            &mut s.session_store,
            &mut s.identity_store,
            SystemTime::now(),
            &mut rand::rng(),
        ))?;
        Ok(Sealed {
            kind: msg.message_type() as u8,
            bytes: msg.serialize().to_vec(),
        })
    }

    /// Decrypt and unpad a message from a pinned `peer`. Replays, tampering and unknown
    /// identities all fail.
    pub fn decrypt(&mut self, peer: &ProtocolAddress, sealed: &Sealed) -> Result<Vec<u8>> {
        self.require_pinned(peer)?;
        let msg = match sealed.kind {
            k if k == CiphertextMessageType::PreKey as u8 => {
                CiphertextMessage::PreKeySignalMessage(PreKeySignalMessage::try_from(
                    sealed.bytes.as_slice(),
                )?)
            }
            k if k == CiphertextMessageType::Whisper as u8 => {
                CiphertextMessage::SignalMessage(SignalMessage::try_from(sealed.bytes.as_slice())?)
            }
            k => return Err(Error::UnknownKind(k)),
        };
        let s = &mut self.store;
        let padded = block_on(libsignal_protocol::message_decrypt(
            &msg,
            peer,
            &self.address,
            &mut s.session_store,
            &mut s.identity_store,
            &mut s.pre_key_store,
            &s.signed_pre_key_store,
            &mut s.kyber_pre_key_store,
            &mut rand::rng(),
        ))?;
        padding::unpad(&padded)
    }

    fn require_pinned(&self, peer: &ProtocolAddress) -> Result<()> {
        match block_on(self.store.identity_store.get_identity(peer))? {
            Some(_) => Ok(()),
            None => Err(Error::NotPaired),
        }
    }
}

/// Seam runs one Signal "device" per Seam device, so the Signal device id is always 1.
fn device_id() -> DeviceId {
    DeviceId::try_from(1u8).expect("1 is a valid device id")
}

/// A device id: the first 16 bytes of SHA-256 over the identity key, in hex.
pub fn device_id_for(identity_key: &[u8]) -> String {
    let digest = Sha256::digest(identity_key);
    digest[..16].iter().map(|b| format!("{b:02x}")).collect()
}

/// The libsignal address for a Seam device id.
pub fn peer_address(name: &str) -> ProtocolAddress {
    ProtocolAddress::new(name.to_owned(), device_id())
}

pub(crate) fn now_millis() -> u64 {
    SystemTime::now()
        .duration_since(SystemTime::UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

#[cfg(test)]
mod tests;
