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

use libsignal_protocol::{CiphertextMessageType, PreKeySignalMessage};
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
    /// Returns the Mac's peer id and the first envelope (a sealed PairRequest).
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
        let peer_id = self.add_peer(identity_key, relay_key, name)?;
        let result = self
            .start_session(&crate::peer_address(&peer_id), bundle)
            .and_then(|()| {
                let request = PairRequest {
                    pairing_token: token.to_vec(),
                    device_name: my_name.to_owned(),
                    model: my_model.to_owned(),
                };
                let body = control::Body::PairRequest(request);
                let payload = Payload {
                    body: Some(payload::Body::Control(Control { body: Some(body) })),
                };
                self.seal_payload(&peer_id, payload, 0)
            });
        match result {
            Ok(env) => Ok((peer_id, env.encode_to_vec())),
            Err(e) => {
                self.remove_peer(&peer_id);
                Err(e)
            }
        }
    }

    /// Mac side: check the phone's first envelope against the open pairing slot.
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

    fn check_pairing_request(
        &mut self,
        envelope: &[u8],
        token: &[u8; 32],
    ) -> Result<PairingRequest> {
        let env = self.decode_for_me(envelope)?;
        if self.peer_by_relay_key(&env.from).is_some() {
            return Err(Error::BadPairing("that device is already paired"));
        }
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

        let peer_id = self.add_peer(&identity, relay_key, "")?;
        let checked = self.open_from(&peer_id, &env).and_then(|opened| {
            let payload = Payload::decode(opened.payload.as_slice())?;
            match payload.body {
                Some(payload::Body::Control(Control {
                    body: Some(control::Body::PairRequest(r)),
                })) => Ok(r),
                _ => Err(Error::BadPairing("expected a pairing request")),
            }
        });
        let request = match checked {
            Ok(r) if bool::from(r.pairing_token.ct_eq(token)) => r,
            Ok(_) => Err(Error::BadPairing("wrong pairing code"))
                .inspect_err(|_| self.remove_peer(&peer_id))?,
            Err(e) => Err(e).inspect_err(|_| self.remove_peer(&peer_id))?,
        };
        if let Some(p) = self.peers.get_mut(&peer_id) {
            p.name = request.device_name.clone();
        }
        Ok(PairingRequest {
            safety_code: self.safety_code(&peer_id)?,
            peer_id,
            device_name: request.device_name,
            model: request.model,
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
