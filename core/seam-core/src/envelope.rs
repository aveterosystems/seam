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
    ///
    /// The ratchet has advanced once this returns: save `export_state` durably before sending
    /// the envelope, or a crash could make the next seal reuse a message key.
    pub fn seal(&mut self, peer_id: &str, payload: &[u8], ttl_s: u32) -> Result<Vec<u8>> {
        let payload = Payload::decode(payload)?;
        let peer = self.peers.get(peer_id).ok_or(Error::NotPaired)?;
        if !peer.confirmed && !is_control(&payload) {
            return Err(Error::NotConfirmed);
        }
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
        let ttl_s = if ttl_s == 0 {
            DEFAULT_TTL_S
        } else {
            ttl_s.min(DEFAULT_TTL_S)
        };
        let from = self.relay_key().to_vec();
        // Every routing field is repeated inside the encryption, so the relay can't change them
        let inner = Inner {
            msg_id: msg_id.clone(),
            sent_at,
            payload: Some(payload),
            from: from.clone(),
            to: to.to_vec(),
            ttl_s,
        };
        let sealed = self.encrypt(&peer_address(peer_id), &inner.encode_to_vec())?;
        Ok(Envelope {
            version: VERSION,
            msg_id,
            from,
            to: to.to_vec(),
            sent_at,
            ttl_s,
            signal_type: u32::from(sealed.kind),
            ciphertext: sealed.bytes,
        })
    }

    /// Open an Envelope (bytes) from a paired peer. `Error::Duplicate` means it was already
    /// handled: ack it and move on.
    ///
    /// From a peer that isn't confirmed yet only pairing messages are accepted. A PairAccept
    /// confirms only the Mac this phone asked to pair with (`pair_with`), never a peer on the Mac.
    pub fn open(&mut self, envelope: &[u8]) -> Result<Opened> {
        let env = self.decode_for_me(envelope)?;
        let peer_id = self.peer_by_relay_key(&env.from).ok_or(Error::NotPaired)?;
        let opened = self.open_from(&peer_id, &env, true)?;
        if !self.peers[&peer_id].confirmed {
            let payload = Payload::decode(opened.payload.as_slice())?;
            if !is_control(&payload) {
                return Err(Error::NotConfirmed);
            }
            if is_pair_accept(&payload) && self.awaiting_accept.as_deref() == Some(peer_id.as_str())
            {
                self.confirm_peer(&peer_id)?;
                self.awaiting_accept = None;
            }
        }
        Ok(opened)
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

    /// `trusted`: false only for a pairing request, whose sender isn't in the peers map yet.
    pub(crate) fn open_from(
        &mut self,
        peer_id: &str,
        env: &Envelope,
        trusted: bool,
    ) -> Result<Opened> {
        let kind = u8::try_from(env.signal_type).map_err(|_| Error::UnknownKind(u8::MAX))?;
        let sealed = Sealed {
            kind,
            bytes: env.ciphertext.clone(),
        };
        let address = peer_address(peer_id);
        let result = if trusted {
            self.decrypt(&address, &sealed)
        } else {
            self.decrypt_unchecked(&address, &sealed)
        };
        let plain = result.map_err(|e| match e {
            Error::Signal(SignalProtocolError::DuplicatedMessage(..)) => Error::Duplicate,
            e => e,
        })?;
        let inner = Inner::decode(plain.as_slice())?;
        let bound = inner.msg_id == env.msg_id
            && inner.sent_at == env.sent_at
            && inner.from == env.from
            && inner.to == env.to
            && inner.ttl_s == env.ttl_s;
        if !bound {
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

fn is_control(p: &Payload) -> bool {
    matches!(p.body, Some(crate::proto::v1::payload::Body::Control(_)))
}

fn is_pair_accept(p: &Payload) -> bool {
    use crate::proto::v1::{Control, control, payload::Body};
    matches!(
        &p.body,
        Some(Body::Control(Control {
            body: Some(control::Body::PairAccept(_))
        }))
    )
}

#[cfg(test)]
mod tests;
