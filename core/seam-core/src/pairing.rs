// SPDX-License-Identifier: AGPL-3.0-only
//! QR pairing (docs: security/cryptography.md §2).
//!
//! 1. The Mac calls `start_pairing` and shows a QR code with its identity key, relay key and the
//!    one-time token. The relay only ever gets the token's SHA-256.
//! 2. The phone calls `pair_with`: it pins the Mac's key from the QR code (never from the network),
//!    starts a PQXDH session from the Mac's bundle and seals a PairRequest carrying the token.
//! 3. The Mac calls `accept_pairing` on that first envelope: the token proves the sender scanned
//!    this Mac's screen. Only then is the phone's identity (from its PQXDH message) pinned.
//! 4. Both show `safety_code`; the user confirms on the Mac, which sends PairAccept.

use futures::executor::block_on;
use libsignal_protocol::{CiphertextMessageType, PreKeySignalMessage, SessionStore};
use prost::Message;
use rand::RngCore;
use sha2::{Digest, Sha256};
use subtle::ConstantTimeEq;

use crate::device::now_millis;
use crate::proto::v1::{Control, PairRequest, Payload, control, payload};
use crate::{Device, Error, PreKeyBundle, Result};

/// How long a pairing QR code stays valid.
pub const PAIRING_TTL_MS: u64 = 5 * 60 * 1000;

pub(crate) struct PendingPairing {
    token: [u8; 32],
    expires_ms: u64,
}

impl Device {
    #[cfg(test)]
    pub(crate) fn expire_pairing_for_test(&mut self) {
        if let Some(p) = self.pending_pairing.as_mut() {
            p.expires_ms = 0;
        }
    }

    /// The open pairing's token, if one is open and unexpired (for the direct channel's pairing proof).
    pub(crate) fn open_pairing_token(&self) -> Option<[u8; 32]> {
        self.pending_pairing
            .as_ref()
            .filter(|p| now_millis() <= p.expires_ms)
            .map(|p| p.token)
    }
}

/// What the Mac puts in the QR code (`token`) and sends the relay (`token_hash`).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PairingOffer {
    pub token: [u8; 32],
    pub token_hash: [u8; 32],
}

/// A pairing request that passed every check, waiting for the user to confirm the code.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PairingRequest {
    pub peer_id: String,
    pub device_name: String,
    pub model: String,
    pub safety_code: String,
}

impl Device {
    /// Mac side: open a pairing slot. Replaces any earlier one.
    pub fn start_pairing(&mut self) -> PairingOffer {
        let mut token = [0u8; 32];
        rand::rng().fill_bytes(&mut token);
        self.pending_pairing = Some(PendingPairing {
            token,
            expires_ms: now_millis() + PAIRING_TTL_MS,
        });
        PairingOffer {
            token,
            token_hash: Sha256::digest(token).into(),
        }
    }

    /// Phone side, after scanning the QR code and fetching the Mac's bundle from the relay.
    /// Returns the Mac's peer id and the first envelope (a sealed PairRequest). The Mac stays
    /// unconfirmed until its PairAccept arrives (`open` confirms it).
    #[allow(clippy::too_many_arguments)]
    pub fn pair_with(
        &mut self,
        identity_key: &[u8],
        relay_key: [u8; 32],
        name: &str,
        bundle: &PreKeyBundle,
        token: &[u8],
        my_name: &str,
        my_model: &str,
    ) -> Result<(String, Vec<u8>)> {
        // A re-pair with a Mac this phone already knows must not cost the existing pairing if it fails
        let previous_id = crate::device_id_for(identity_key);
        let previous = match self.peers.get(&previous_id).cloned() {
            Some(peer) => {
                let address = crate::peer_address(&previous_id);
                let session = block_on(self.store.session_store.load_session(&address))?;
                Some((peer, session))
            }
            None => None,
        };
        let peer_id = self.insert_peer(identity_key, relay_key, name, false)?;
        let result = self
            .start_session(&crate::peer_address(&peer_id), bundle)
            .and_then(|()| {
                let request = PairRequest {
                    pairing_token: token.to_vec(),
                    device_name: crate::device::sanitize_name(my_name),
                    model: crate::device::sanitize_name(my_model),
                    relay_key: self.relay_key().to_vec(),
                };
                let body = control::Body::PairRequest(request);
                let payload = Payload {
                    body: Some(payload::Body::Control(Control { body: Some(body) })),
                };
                self.seal_payload(&peer_id, payload, 0)
            });
        match result {
            Ok(env) => {
                self.awaiting_accept = Some(peer_id.clone());
                Ok((peer_id, env.encode_to_vec()))
            }
            Err(e) => {
                self.remove_peer(&peer_id);
                if let Some((peer, session)) = previous {
                    if let Some(session) = session {
                        let address = crate::peer_address(&peer_id);
                        block_on(self.store.session_store.store_session(&address, &session))?;
                    }
                    self.peers.insert(peer_id, peer);
                }
                Err(e)
            }
        }
    }

    /// Mac side: check the phone's first envelope against the open pairing slot. On success
    /// the phone is a peer but unconfirmed: show the safety code, then `confirm_pairing` or
    /// `reject_pairing`.
    pub fn accept_pairing(&mut self, envelope: &[u8]) -> Result<PairingRequest> {
        let pending = self
            .pending_pairing
            .take()
            .ok_or(Error::BadPairing("no pairing is open"))?;
        if now_millis() > pending.expires_ms {
            return Err(Error::BadPairing("the pairing code expired"));
        }
        match self.check_pairing_request(envelope, &pending.token) {
            Ok(request) => Ok(request),
            Err(e) => {
                // Not used up by a bad attempt: the real phone can still pair with this code
                self.pending_pairing = Some(pending);
                Err(e)
            }
        }
    }

    /// Mac side: the user confirmed the safety code.
    pub fn confirm_pairing(&mut self, peer_id: &str) -> Result<()> {
        self.confirm_peer(peer_id)
    }

    /// Mac side: the user said the codes don't match (or cancelled). The phone is forgotten.
    pub fn reject_pairing(&mut self, peer_id: &str) {
        if self.peers.get(peer_id).is_some_and(|p| !p.confirmed) {
            self.remove_peer(peer_id);
        }
    }

    /// Mac side: close the pairing slot early (window closed, QR expired).
    pub fn cancel_pairing(&mut self) {
        self.pending_pairing = None;
    }

    fn check_pairing_request(
        &mut self,
        envelope: &[u8],
        token: &[u8; 32],
    ) -> Result<PairingRequest> {
        let env = self.decode_for_me(envelope)?;
        if env.signal_type != CiphertextMessageType::PreKey as u32 {
            return Err(Error::BadPairing("expected a session-setup message"));
        }
        let relay_key: [u8; 32] = env
            .from
            .as_slice()
            .try_into()
            .map_err(|_| Error::Malformed("relay key must be 32 bytes"))?;
        let identity = PreKeySignalMessage::try_from(env.ciphertext.as_slice())?
            .identity_key()
            .serialize();
        let peer_id = crate::device_id_for(&identity);
        // Judge by the identity the message carries, never by the unauthenticated `from`: an
        // attempt must not be able to touch a device that's already paired.
        if self.peers.contains_key(&peer_id) || self.peer_by_relay_key(&env.from).is_some() {
            return Err(Error::BadPairing("that device is already paired"));
        }

        // Pinned in libsignal only for this one decryption; trusted only once the token checks out
        let address = crate::peer_address(&peer_id);
        self.pin_peer(&address, &identity)?;
        let checked = self.open_from(&peer_id, &env, false).and_then(|opened| {
            let payload = Payload::decode(opened.payload.as_slice())?;
            match payload.body {
                Some(payload::Body::Control(Control {
                    body: Some(control::Body::PairRequest(r)),
                })) => Ok(r),
                _ => Err(Error::BadPairing("expected a pairing request")),
            }
        });
        let request = match checked {
            Ok(r) if !bool::from(r.pairing_token.ct_eq(token)) => {
                Err(Error::BadPairing("wrong pairing code"))
            }
            Ok(r) if r.relay_key != env.from => {
                Err(Error::BadPairing("relay key doesn't match the request"))
            }
            other => other,
        };
        let request = match request {
            Ok(r) => r,
            Err(e) => {
                self.reset_session(&address)?;
                return Err(e);
            }
        };
        let name = crate::device::sanitize_name(&request.device_name);
        self.peers.insert(
            peer_id.clone(),
            crate::Peer {
                identity_key: identity.to_vec(),
                relay_key,
                name: name.clone(),
                confirmed: false,
            },
        );
        Ok(PairingRequest {
            safety_code: self.safety_code(&peer_id)?,
            peer_id,
            device_name: name,
            model: crate::device::sanitize_name(&request.model),
        })
    }

    /// Six digits from both identity keys, shown on both screens ("482 913"). The same on both
    /// devices; different if either key was swapped.
    pub fn safety_code(&self, peer_id: &str) -> Result<String> {
        let peer = self.peers.get(peer_id).ok_or(Error::NotPaired)?;
        let (mine, theirs) = (self.identity_key(), peer.identity_key.clone());
        let (a, b) = if mine <= theirs {
            (mine, theirs)
        } else {
            (theirs, mine)
        };
        let digest = Sha256::new()
            .chain_update(b"seam-safety-v1")
            .chain_update(&a)
            .chain_update(&b)
            .finalize();
        let n = u32::from_be_bytes(digest[..4].try_into().unwrap()) % 1_000_000;
        Ok(format!("{:03} {:03}", n / 1000, n % 1000))
    }
}

#[cfg(test)]
mod tests;
