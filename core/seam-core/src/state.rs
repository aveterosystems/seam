// SPDX-License-Identifier: AGPL-3.0-only
//! Saving and restoring everything a device knows, as one blob sealed with AES-256-GCM under a
//! 32-byte storage key. The app keeps that key in the Keychain (Mac) or Android Keystore; the
//! blob alone is useless without it.
//!
//! Blob: version (1) | 12-byte random nonce | AES-256-GCM(state.proto), AAD "seam-state-v1".

use aes_gcm::aead::{Aead, KeyInit, Payload as AeadPayload};
use aes_gcm::{Aes256Gcm, Nonce};
use ed25519_dalek::SigningKey;
use futures::executor::block_on;
use libsignal_protocol::{
    GenericSignedPreKey, IdentityKeyPair, IdentityKeyStore, KyberPreKeyRecord, KyberPreKeyStore,
    PreKeyRecord, PreKeyStore, SessionRecord, SessionStore, SignedPreKeyRecord, SignedPreKeyStore,
};
use prost::Message;
use rand::RngCore;

use crate::proto::state;
use crate::{Device, Error, Peer, Result, peer_address};

const VERSION: u8 = 1;
const AAD: &[u8] = b"seam-state-v1";

impl Device {
    /// Everything needed to carry on after a restart, sealed under `storage_key`.
    pub fn export_state(&self, storage_key: &[u8; 32]) -> Result<Vec<u8>> {
        let s = &self.store;
        let identity = block_on(s.identity_store.get_identity_key_pair())?;
        let mut out = state::State {
            identity_key_pair: identity.serialize().to_vec(),
            registration_id: block_on(s.identity_store.get_local_registration_id())?,
            next_prekey_id: self.next_prekey_id,
            relay_key_seed: self.relay_key.to_bytes().to_vec(),
            app_data: self.app_data.clone(),
            ..Default::default()
        };
        // Unconfirmed peers are never saved: a restart mid-pairing forgets them
        for (id, peer) in self.peers.iter().filter(|(_, p)| p.confirmed) {
            let session = block_on(s.session_store.load_session(&peer_address(id)))?;
            out.peers.push(state::Peer {
                id: id.clone(),
                identity_key: peer.identity_key.clone(),
                relay_key: peer.relay_key.to_vec(),
                name: peer.name.clone(),
                session: session
                    .map(|r| r.serialize())
                    .transpose()?
                    .unwrap_or_default(),
            });
        }
        for id in s.all_pre_key_ids() {
            out.prekeys
                .push(block_on(s.pre_key_store.get_pre_key(*id))?.serialize()?);
        }
        for id in s.all_signed_pre_key_ids() {
            out.signed_prekeys
                .push(block_on(s.signed_pre_key_store.get_signed_pre_key(*id))?.serialize()?);
        }
        for id in s.all_kyber_pre_key_ids() {
            out.kyber_prekeys
                .push(block_on(s.kyber_pre_key_store.get_kyber_pre_key(*id))?.serialize()?);
        }

        let mut nonce = [0u8; 12];
        rand::rng().fill_bytes(&mut nonce);
        let sealed = Aes256Gcm::new(storage_key.into())
            .encrypt(
                Nonce::from_slice(&nonce),
                AeadPayload {
                    msg: &out.encode_to_vec(),
                    aad: AAD,
                },
            )
            .map_err(|_| Error::BadState)?;
        Ok([&[VERSION][..], &nonce, &sealed].concat())
    }

    /// Rebuild a device from `export_state`'s blob. Fails on a wrong key or any tampering.
    pub fn restore(blob: &[u8], storage_key: &[u8; 32]) -> Result<Self> {
        if blob.len() < 13 || blob[0] != VERSION {
            return Err(Error::BadState);
        }
        let plain = Aes256Gcm::new(storage_key.into())
            .decrypt(
                Nonce::from_slice(&blob[1..13]),
                AeadPayload {
                    msg: &blob[13..],
                    aad: AAD,
                },
            )
            .map_err(|_| Error::BadState)?;
        let st = state::State::decode(plain.as_slice())?;

        let seed: [u8; 32] = st
            .relay_key_seed
            .as_slice()
            .try_into()
            .map_err(|_| Error::BadState)?;
        let identity = IdentityKeyPair::try_from(st.identity_key_pair.as_slice())?;
        let mut device = Device::from_parts(
            identity,
            st.registration_id,
            st.next_prekey_id,
            SigningKey::from_bytes(&seed),
        )?;
        device.app_data = st.app_data;

        for p in st.peers {
            let relay_key: [u8; 32] = p
                .relay_key
                .as_slice()
                .try_into()
                .map_err(|_| Error::BadState)?;
            let id = device.add_peer(&p.identity_key, relay_key, &p.name)?;
            if id != p.id {
                return Err(Error::BadState);
            }
            if !p.session.is_empty() {
                let record = SessionRecord::deserialize(&p.session)?;
                block_on(
                    device
                        .store
                        .session_store
                        .store_session(&peer_address(&id), &record),
                )?;
            }
            device.peers.insert(
                id,
                Peer {
                    identity_key: p.identity_key,
                    relay_key,
                    name: p.name,
                    confirmed: true,
                },
            );
        }
        let s = &mut device.store;
        for bytes in st.prekeys {
            let r = PreKeyRecord::deserialize(&bytes)?;
            block_on(s.pre_key_store.save_pre_key(r.id()?, &r))?;
        }
        for bytes in st.signed_prekeys {
            let r = SignedPreKeyRecord::deserialize(&bytes)?;
            block_on(s.signed_pre_key_store.save_signed_pre_key(r.id()?, &r))?;
        }
        for bytes in st.kyber_prekeys {
            let r = KyberPreKeyRecord::deserialize(&bytes)?;
            block_on(s.kyber_pre_key_store.save_kyber_pre_key(r.id()?, &r))?;
        }
        Ok(device)
    }
}

#[cfg(test)]
mod tests;
