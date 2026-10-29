// SPDX-License-Identifier: AGPL-3.0-only
//! Envelopes: what actually travels between devices (protocol/seam/v1/envelope.proto).
//!
//! Sealing wraps the feature's Payload in an Inner that repeats the envelope's msg_id and
//! sent_at, encrypts it with the peer's Signal session, and addresses it by relay keys. Opening
//! checks, in order: the version, that it's for this device, that the sender is a paired peer,
//! the Signal decryption (tamper, replay), and that the Inner matches the plaintext envelope, so
//! a relay can't move a ciphertext under different routing.

use libsignal_protocol::SignalProtocolError;
use prost::Message;
use rand::RngCore;

use crate::device::{now_millis, peer_address};
use crate::proto::v1::{Envelope, Inner, Payload};
use crate::{Device, Error, Result, Sealed};

pub const VERSION: u32 = 1;
/// Default relay time to live: 24 hours.
pub const DEFAULT_TTL_S: u32 = 86_400;

/// A message that passed every check.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Opened {
    pub peer_id: String,
    pub msg_id: Vec<u8>,
    pub sent_at: i64,
    /// The feature's Payload, re-encoded.
    pub payload: Vec<u8>,
}

impl Device {
    /// Seal a Payload (protobuf bytes) for a paired peer. Returns the Envelope's bytes.
    pub fn seal(&mut self, peer_id: &str, payload: &[u8], ttl_s: u32) -> Result<Vec<u8>> {
        let payload = Payload::decode(payload)?;
        Ok(self.seal_payload(peer_id, payload, ttl_s)?.encode_to_vec())
    }

    pub(crate) fn seal_payload(
        &mut self,
        peer_id: &str,
        payload: Payload,
        ttl_s: u32,
    ) -> Result<Envelope> {
        let to = self.peers.get(peer_id).ok_or(Error::NotPaired)?.relay_key;
        let mut msg_id = vec![0u8; 16];
        rand::rng().fill_bytes(&mut msg_id);
        let sent_at = now_millis() as i64;
        let inner = Inner {
            msg_id: msg_id.clone(),
            sent_at,
            payload: Some(payload),
        };
        let sealed = self.encrypt(&peer_address(peer_id), &inner.encode_to_vec())?;
        Ok(Envelope {
            version: VERSION,
            msg_id,
            from: self.relay_key().to_vec(),
            to: to.to_vec(),
            sent_at,
            ttl_s: if ttl_s == 0 {
                DEFAULT_TTL_S
            } else {
                ttl_s.min(DEFAULT_TTL_S)
            },
            signal_type: u32::from(sealed.kind),
            ciphertext: sealed.bytes,
        })
    }

    /// Open an Envelope (bytes) from a paired peer. `Error::Duplicate` means it was already
    /// handled: ack it and move on.
    pub fn open(&mut self, envelope: &[u8]) -> Result<Opened> {
        let env = self.decode_for_me(envelope)?;
        let peer_id = self.peer_by_relay_key(&env.from).ok_or(Error::NotPaired)?;
        self.open_from(&peer_id, &env)
    }

    pub(crate) fn decode_for_me(&self, envelope: &[u8]) -> Result<Envelope> {
        let env = Envelope::decode(envelope)?;
        if env.version != VERSION {
            return Err(Error::UnsupportedVersion(env.version));
        }
        if env.to != self.relay_key() {
            return Err(Error::NotForMe);
        }
        Ok(env)
    }

    pub(crate) fn open_from(&mut self, peer_id: &str, env: &Envelope) -> Result<Opened> {
        let kind = u8::try_from(env.signal_type).map_err(|_| Error::UnknownKind(u8::MAX))?;
        let sealed = Sealed {
            kind,
            bytes: env.ciphertext.clone(),
        };
        let plain = self
            .decrypt(&peer_address(peer_id), &sealed)
            .map_err(|e| match e {
                Error::Signal(SignalProtocolError::DuplicatedMessage(..)) => Error::Duplicate,
                e => e,
            })?;
        let inner = Inner::decode(plain.as_slice())?;
        if inner.msg_id != env.msg_id || inner.sent_at != env.sent_at {
            return Err(Error::EnvelopeMismatch);
        }
        let payload = inner
            .payload
            .ok_or(Error::Malformed("inner has no payload"))?;
        Ok(Opened {
            peer_id: peer_id.to_owned(),
            msg_id: env.msg_id.clone(),
            sent_at: env.sent_at,
            payload: payload.encode_to_vec(),
        })
    }

    pub(crate) fn peer_by_relay_key(&self, relay_key: &[u8]) -> Option<String> {
        self.peers
            .iter()
            .find(|(_, p)| p.relay_key == relay_key)
            .map(|(id, _)| id.clone())
    }
}

#[cfg(test)]
mod tests;
