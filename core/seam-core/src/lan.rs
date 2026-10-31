// SPDX-License-Identifier: AGPL-3.0-only
//! The direct link between devices, over any byte stream (Same Wi-Fi, Tor): a Noise NN handshake
//! (snow), then each side proves itself over the handshake hash. The Mac (responder) always signs
//! with its key first, so a fake Mac learns nothing about the phone. A paired phone signs with the
//! key pinned at pairing. A phone that's pairing can't sign with a key the Mac knows yet, so it
//! proves it scanned the Mac's code: an HMAC of the handshake hash keyed with the QR's one-time
//! token. Such a channel is marked `pairing`; what may travel over it is the apps' to limit, and
//! the token inside the PairRequest stays the authority (`accept_pairing`).
//!
//! The apps only move raw bytes: everything they read goes into [`LanChannel::receive`], and
//! everything it returns is written to the socket. Frames inside are what the relay would carry
//! (seam.v1 ClientFrame / ServerFrame), so anyone on the network sees only padded noise.
//!
//! Wire format: `u16 big-endian length || Noise message`. After the handshake, a frame is split
//! into Noise messages of `flag || chunk`, flag 1 on the last chunk.

use ed25519_dalek::{Signature, Signer, SigningKey, VerifyingKey};

use crate::{Device, Error, Result};

const PATTERN: &str = "Noise_NN_25519_ChaChaPoly_BLAKE2s";
const PROLOGUE: &[u8] = b"seam-lan-v1";
/// Role labels inside the signed message, so a signature can never be reflected back.
const RESPONDER: &[u8] = b"seam-lan-v1 responder";
const INITIATOR: &[u8] = b"seam-lan-v1 initiator";
const PAIRING: &[u8] = b"seam-lan-v1 pairing";
/// The peer id a pairing channel reports: the phone isn't a peer yet.
pub const PAIRING_PEER: &str = "pairing";
const PROOF_LEN: usize = 32;
/// Same ceiling as a relay frame: an envelope (up to ~1.1 MB) plus framing.
pub const MAX_FRAME: usize = 1_200_000;
/// Noise's own limit on one message.
const MAX_NOISE: usize = 65535;
const TAG: usize = 16;
const MAX_CHUNK: usize = MAX_NOISE - TAG - 1;

enum State {
    Handshake(Box<snow::HandshakeState>),
    /// Handshake done; waiting for the peer's signature over `hash`.
    Proving(Box<snow::TransportState>, Vec<u8>),
    Ready(Box<snow::TransportState>),
    Failed,
}

/// One direct connection. Any error closes it for good: drop the socket and start again.
pub struct LanChannel {
    state: State,
    initiator: bool,
    signer: SigningKey,
    /// Who may be on the other end: the pinned Mac (phone side), or every confirmed phone (Mac side).
    allowed: Vec<(String, [u8; 32])>,
    peer: Option<String>,
    /// The initiator's opening message, until taken by [`Self::start`].
    hello: Vec<u8>,
    /// Pairing: the QR's one-time token (phone side), or the open pairing's (Mac side).
    pairing_token: Option<[u8; 32]>,
    inbuf: Vec<u8>,
    partial: Vec<u8>,
}

/// What one [`LanChannel::receive`] produced.
#[derive(Debug, Default)]
pub struct LanOutput {
    /// Bytes to write to the socket now (handshake replies).
    pub send: Vec<u8>,
    /// Complete frames from the authenticated peer.
    pub frames: Vec<Vec<u8>>,
    /// Set on the call that finished authentication: the paired peer on the other end, or
    /// [`PAIRING_PEER`] for a phone that proved it scanned the Mac's code.
    pub authenticated: Option<String>,
}

fn noise(e: snow::Error) -> Error {
    let _ = e; // never echo snow's detail: it's attacker-triggered
    Error::Malformed("lan channel")
}

fn builder() -> Result<snow::Builder<'static>> {
    snow::Builder::new(PATTERN.parse().map_err(noise)?)
        .prologue(PROLOGUE)
        .map_err(noise)
}

fn signed(role: &[u8], hash: &[u8]) -> Vec<u8> {
    [role, hash].concat()
}

impl LanChannel {
    /// The phone's side, connecting to its pinned Mac `peer_id`. Send [`Self::start`] first.
    pub fn initiator(device: &Device, peer_id: &str) -> Result<Self> {
        let peer = device
            .peers
            .get(peer_id)
            .filter(|p| p.confirmed)
            .ok_or(Error::NotPaired)?;
        let mut hs = builder()?.build_initiator().map_err(noise)?;
        let mut ch = Self::new(device, true, vec![(peer_id.to_owned(), peer.relay_key)]);
        let mut msg = vec![0u8; MAX_NOISE];
        let n = hs.write_message(&[], &mut msg).map_err(noise)?;
        ch.state = State::Handshake(Box::new(hs));
        ch.hello = framed(&msg[..n]);
        Ok(ch)
    }

    /// The phone's side while pairing: the Mac is pinned from the QR code (its `relay_key`), and
    /// the phone proves it scanned that code with `token`. Send [`Self::start`] first.
    pub fn pairing_initiator(device: &Device, relay_key: [u8; 32], token: &[u8]) -> Result<Self> {
        let token: [u8; 32] = token
            .try_into()
            .map_err(|_| Error::Malformed("pairing token"))?;
        let mut hs = builder()?.build_initiator().map_err(noise)?;
        let mut ch = Self::new(device, true, vec![(PAIRING_PEER.to_owned(), relay_key)]);
        ch.pairing_token = Some(token);
        let mut msg = vec![0u8; MAX_NOISE];
        let n = hs.write_message(&[], &mut msg).map_err(noise)?;
        ch.state = State::Handshake(Box::new(hs));
        ch.hello = framed(&msg[..n]);
        Ok(ch)
    }

    /// The Mac's side, for an incoming connection: any confirmed peer may authenticate, and while
    /// a pairing is open, a phone that proves it has its token.
    pub fn responder(device: &Device) -> Result<Self> {
        let allowed: Vec<_> = device
            .peers
            .iter()
            .filter(|(_, p)| p.confirmed)
            .map(|(id, p)| (id.clone(), p.relay_key))
            .collect();
        let pairing_token = device.open_pairing_token();
        if allowed.is_empty() && pairing_token.is_none() {
            return Err(Error::NotPaired);
        }
        let hs = builder()?.build_responder().map_err(noise)?;
        let mut ch = Self::new(device, false, allowed);
        ch.pairing_token = pairing_token;
        ch.state = State::Handshake(Box::new(hs));
        Ok(ch)
    }

    /// Whether this is a pairing channel (a phone that proved it scanned the code), not a paired peer's.
    pub fn is_pairing(&self) -> bool {
        self.peer.as_deref() == Some(PAIRING_PEER)
    }

    fn new(device: &Device, initiator: bool, allowed: Vec<(String, [u8; 32])>) -> Self {
        Self {
            state: State::Failed,
            initiator,
            signer: device.relay_key.clone(),
            allowed,
            peer: None,
            hello: Vec::new(),
            pairing_token: None,
            inbuf: Vec::new(),
            partial: Vec::new(),
        }
    }

    /// The bytes to write as soon as the socket connects (empty on the responder, and after the first call).
    pub fn start(&mut self) -> Vec<u8> {
        std::mem::take(&mut self.hello)
    }

    /// The authenticated peer, once there is one.
    pub fn peer_id(&self) -> Option<&str> {
        self.peer.as_deref()
    }

    /// Feeds bytes read from the socket, in any split.
    pub fn receive(&mut self, bytes: &[u8]) -> Result<LanOutput> {
        let r = self.receive_inner(bytes);
        if r.is_err() {
            self.fail();
        }
        r
    }

    /// Seals one frame for an authenticated peer; write the result to the socket.
    pub fn send(&mut self, frame: &[u8]) -> Result<Vec<u8>> {
        if frame.len() > MAX_FRAME {
            return Err(Error::TooLarge(frame.len()));
        }
        let State::Ready(t) = &mut self.state else {
            return Err(Error::Malformed("lan channel not ready"));
        };
        let r = seal_frame(t, frame);
        if r.is_err() {
            self.fail();
        }
        r
    }

    fn fail(&mut self) {
        self.state = State::Failed;
        self.peer = None;
        self.inbuf.clear();
        self.partial.clear();
    }

    fn receive_inner(&mut self, bytes: &[u8]) -> Result<LanOutput> {
        if matches!(self.state, State::Failed) {
            return Err(Error::Malformed("lan channel closed"));
        }
        self.inbuf.extend_from_slice(bytes);
        let mut out = LanOutput::default();
        while self.inbuf.len() >= 2 {
            let len = u16::from_be_bytes([self.inbuf[0], self.inbuf[1]]) as usize;
            if self.inbuf.len() < 2 + len {
                break;
            }
            let msg: Vec<u8> = self.inbuf.drain(..2 + len).skip(2).collect();
            self.message(&msg, &mut out)?;
        }
        // What's left is at most one incomplete message: a length can't promise more
        debug_assert!(self.inbuf.len() < 2 + MAX_NOISE);
        Ok(out)
    }

    fn message(&mut self, msg: &[u8], out: &mut LanOutput) -> Result<()> {
        match std::mem::replace(&mut self.state, State::Failed) {
            State::Handshake(mut hs) => {
                let mut buf = vec![0u8; MAX_NOISE];
                hs.read_message(msg, &mut buf).map_err(noise)?;
                if !self.initiator {
                    let n = hs.write_message(&[], &mut buf).map_err(noise)?;
                    out.send.extend(framed(&buf[..n]));
                }
                if !hs.is_handshake_finished() {
                    return Err(Error::Malformed("lan handshake"));
                }
                let hash = hs.get_handshake_hash().to_vec();
                let mut t = Box::new(hs.into_transport_mode().map_err(noise)?);
                if !self.initiator {
                    // The Mac proves itself first
                    let sig = self.signer.sign(&signed(RESPONDER, &hash)).to_bytes();
                    out.send.extend(seal_frame(&mut t, &sig)?);
                }
                self.state = State::Proving(t, hash);
            }
            State::Proving(mut t, hash) => {
                let Some(sig) = open_chunk(&mut t, msg, &mut self.partial)? else {
                    return Err(Error::Malformed("lan proof"));
                };
                let peer = if self.initiator {
                    self.verify(RESPONDER, &hash, &sig)?
                } else if sig.len() == PROOF_LEN {
                    self.verify_pairing(&hash, &sig)?
                } else {
                    self.verify(INITIATOR, &hash, &sig)?
                };
                if self.initiator {
                    let mine = match &self.pairing_token {
                        Some(token) => pairing_proof(token, &hash).to_vec(),
                        None => self
                            .signer
                            .sign(&signed(INITIATOR, &hash))
                            .to_bytes()
                            .to_vec(),
                    };
                    out.send.extend(seal_frame(&mut t, &mine)?);
                }
                self.peer = Some(peer.clone());
                out.authenticated = Some(peer);
                self.state = State::Ready(t);
            }
            State::Ready(mut t) => {
                if let Some(frame) = open_chunk(&mut t, msg, &mut self.partial)? {
                    out.frames.push(frame);
                }
                self.state = State::Ready(t);
            }
            State::Failed => return Err(Error::Malformed("lan channel closed")),
        }
        Ok(())
    }

    /// A pairing phone's proof: only while a pairing is open, compared in constant time.
    fn verify_pairing(&self, hash: &[u8], proof: &[u8]) -> Result<String> {
        let token = self.pairing_token.as_ref().ok_or(Error::WrongPeer)?;
        let expected = pairing_proof(token, hash);
        if bool::from(subtle::ConstantTimeEq::ct_eq(&expected[..], proof)) {
            Ok(PAIRING_PEER.to_owned())
        } else {
            Err(Error::WrongPeer)
        }
    }

    /// `WrongPeer` only for a well-formed proof from another key (someone else's device); a
    /// damaged one stays `Malformed`, so a forged packet can't make our own peer look foreign.
    fn verify(&self, role: &[u8], hash: &[u8], sig: &[u8]) -> Result<String> {
        let sig = Signature::from_slice(sig).map_err(|_| Error::Malformed("lan proof"))?;
        let msg = signed(role, hash);
        self.allowed
            .iter()
            .find(|(_, key)| {
                VerifyingKey::from_bytes(key).is_ok_and(|k| k.verify_strict(&msg, &sig).is_ok())
            })
            .map(|(id, _)| id.clone())
            .ok_or(Error::WrongPeer)
    }
}

/// HMAC-SHA256 of the handshake hash keyed with the pairing token: binds this channel to having
/// scanned the Mac's code. Only someone who saw the QR can produce it.
fn pairing_proof(token: &[u8; 32], hash: &[u8]) -> [u8; PROOF_LEN] {
    use hmac::{KeyInit, Mac};
    let mut mac =
        hmac::Hmac::<sha2::Sha256>::new_from_slice(token).expect("HMAC takes any key length");
    mac.update(PAIRING);
    mac.update(hash);
    mac.finalize().into_bytes().into()
}

fn framed(msg: &[u8]) -> Vec<u8> {
    let mut v = (msg.len() as u16).to_be_bytes().to_vec();
    v.extend_from_slice(msg);
    v
}

fn seal_frame(t: &mut snow::TransportState, frame: &[u8]) -> Result<Vec<u8>> {
    let mut out = Vec::with_capacity(frame.len() + frame.len() / MAX_CHUNK * 19 + 19);
    // An empty frame is still one (empty) final chunk
    let chunks: Vec<&[u8]> = if frame.is_empty() {
        vec![&[]]
    } else {
        frame.chunks(MAX_CHUNK).collect()
    };
    let mut buf = vec![0u8; MAX_NOISE];
    let mut plain = Vec::with_capacity(MAX_CHUNK + 1);
    for (i, c) in chunks.iter().enumerate() {
        plain.clear();
        plain.push(u8::from(i + 1 == chunks.len()));
        plain.extend_from_slice(c);
        let n = t.write_message(&plain, &mut buf).map_err(noise)?;
        out.extend(framed(&buf[..n]));
    }
    Ok(out)
}

/// One Noise message into `partial`; the whole frame once its last chunk arrives.
fn open_chunk(
    t: &mut snow::TransportState,
    msg: &[u8],
    partial: &mut Vec<u8>,
) -> Result<Option<Vec<u8>>> {
    let mut buf = vec![0u8; MAX_NOISE];
    let n = t.read_message(msg, &mut buf).map_err(noise)?;
    let (&flag, chunk) = buf[..n]
        .split_first()
        .ok_or(Error::Malformed("lan chunk"))?;
    if flag > 1 || partial.len() + chunk.len() > MAX_FRAME {
        return Err(Error::Malformed("lan chunk"));
    }
    partial.extend_from_slice(chunk);
    Ok((flag == 1).then(|| std::mem::take(partial)))
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A paired phone and Mac, each pinning the other.
    fn pair() -> (Device, Device, String, String) {
        let mut mac = Device::generate().unwrap();
        let mut phone = Device::generate().unwrap();
        let phone_id = mac
            .add_peer(&phone.identity_key(), phone.relay_key(), "Phone")
            .unwrap();
        let mac_id = phone
            .add_peer(&mac.identity_key(), mac.relay_key(), "Mac")
            .unwrap();
        (mac, phone, mac_id, phone_id)
    }

    /// Runs the handshake, delivering bytes `split` at a time to exercise reassembly.
    fn connect(
        mac: &Device,
        phone: &Device,
        mac_id: &str,
        split: usize,
    ) -> Result<(LanChannel, LanChannel)> {
        let mut p = LanChannel::initiator(phone, mac_id)?;
        let hello = p.start();
        let mut m = LanChannel::responder(mac)?;
        let feed = |ch: &mut LanChannel, bytes: &[u8]| -> Result<LanOutput> {
            let mut all = LanOutput::default();
            for c in bytes.chunks(split) {
                let o = ch.receive(c)?;
                all.send.extend(o.send);
                all.frames.extend(o.frames);
                all.authenticated = all.authenticated.or(o.authenticated);
            }
            Ok(all)
        };
        let r1 = feed(&mut m, &hello)?;
        let r2 = feed(&mut p, &r1.send)?;
        let r3 = feed(&mut m, &r2.send)?;
        assert!(r3.send.is_empty());
        Ok((p, m))
    }

    #[test]
    fn paired_devices_authenticate_and_exchange_frames() {
        let (mac, phone, mac_id, phone_id) = pair();
        for split in [1, 7, usize::MAX] {
            let (mut p, mut m) = connect(&mac, &phone, &mac_id, split).unwrap();
            assert_eq!(p.peer_id(), Some(mac_id.as_str()));
            assert_eq!(m.peer_id(), Some(phone_id.as_str()));
            for frame in [vec![], vec![7u8; 10], vec![9u8; MAX_FRAME]] {
                let wire = p.send(&frame).unwrap();
                let got: Vec<_> = wire
                    .chunks(split.min(wire.len()).max(1))
                    .flat_map(|c| m.receive(c).unwrap().frames)
                    .collect();
                assert_eq!(got, vec![frame.clone()]);
                let back = m.send(&frame).unwrap();
                assert_eq!(p.receive(&back).unwrap().frames, vec![frame]);
            }
        }
    }

    #[test]
    fn a_stranger_is_refused_on_both_sides() {
        let (mac, phone, mac_id, _) = pair();
        let (stranger_mac, stranger_phone, _, _) = pair();
        // A fake Mac: the phone refuses it before sending anything that identifies the phone
        let mut p = LanChannel::initiator(&phone, &mac_id).unwrap();
        let hello = p.start();
        let mut fake = LanChannel::responder(&stranger_mac).unwrap();
        let r = fake.receive(&hello).unwrap();
        assert!(matches!(p.receive(&r.send), Err(Error::WrongPeer)));
        assert!(p.send(b"x").is_err(), "a failed channel stays closed");
        // A fake phone: pins our Mac's keys (as if it saw the QR) but not paired on the Mac
        let mut fake_phone = stranger_phone;
        let id = fake_phone
            .add_peer(&mac.identity_key(), mac.relay_key(), "Mac")
            .unwrap();
        assert!(connect(&mac, &fake_phone, &id, usize::MAX).is_err());
    }

    #[test]
    fn nothing_moves_before_authentication() {
        let (mac, phone, mac_id, _) = pair();
        let mut p = LanChannel::initiator(&phone, &mac_id).unwrap();
        assert!(p.send(b"early").is_err());
        let mut m = LanChannel::responder(&mac).unwrap();
        assert!(m.send(b"early").is_err());
        assert!(
            LanChannel::responder(&Device::generate().unwrap()).is_err(),
            "no paired phone, no listener"
        );
        assert!(LanChannel::initiator(&phone, "not-a-peer").is_err());
    }

    #[test]
    fn tampering_replay_and_garbage_close_the_channel() {
        let (mac, phone, mac_id, _) = pair();
        let (mut p, mut m) = connect(&mac, &phone, &mac_id, usize::MAX).unwrap();
        let mut wire = p.send(b"hello").unwrap();
        let replay = wire.clone();
        assert_eq!(m.receive(&wire).unwrap().frames, vec![b"hello".to_vec()]);
        assert!(m.receive(&replay).is_err(), "replayed message");

        let (mut p, mut m) = connect(&mac, &phone, &mac_id, usize::MAX).unwrap();
        wire = p.send(b"hello").unwrap();
        let last = wire.len() - 1;
        wire[last] ^= 1;
        assert!(m.receive(&wire).is_err(), "flipped bit");

        let mut m = LanChannel::responder(&mac).unwrap();
        assert!(m.receive(&[0, 3, 1, 2, 3]).is_err(), "garbage handshake");
        let mut m = LanChannel::responder(&mac).unwrap();
        assert!(m.receive(&vec![0xff; 3 * MAX_NOISE]).is_err(), "flood");
    }

    #[test]
    fn a_proof_is_bound_to_its_own_handshake() {
        // A signature from one connection can't authenticate another: each signs its own hash
        let (mac, phone, mac_id, _) = pair();
        let mut p1 = LanChannel::initiator(&phone, &mac_id).unwrap();
        let h1 = p1.start();
        let mut m1 = LanChannel::responder(&mac).unwrap();
        let r1 = m1.receive(&h1).unwrap();
        let mut p2 = LanChannel::initiator(&phone, &mac_id).unwrap();
        let h2 = p2.start();
        let mut m2 = LanChannel::responder(&mac).unwrap();
        let r2 = m2.receive(&h2).unwrap();
        // Swap the Mac's proof between the two connections (same lengths: handshake then proof)
        let split = r1.send.len() - (2 + 1 + 64 + TAG);
        let spliced = [&r2.send[..split], &r1.send[split..]].concat();
        assert!(
            matches!(p2.receive(&spliced), Err(Error::Malformed(_))),
            "damaged, not foreign"
        );
        assert!(p1.receive(&r1.send).is_ok());
    }

    /// The pairing handshake: the phone pins the Mac from its QR and proves it has the token.
    fn pairing_connect(
        mac: &Device,
        phone: &Device,
        token: &[u8],
    ) -> Result<(LanChannel, LanChannel, LanOutput)> {
        let mut p = LanChannel::pairing_initiator(phone, mac.relay_key(), token)?;
        let hello = p.start();
        let mut m = LanChannel::responder(mac)?;
        let r1 = m.receive(&hello)?;
        let r2 = p.receive(&r1.send)?;
        let r3 = m.receive(&r2.send)?;
        Ok((p, m, r3))
    }

    #[test]
    fn a_phone_that_scanned_the_code_opens_a_pairing_channel() {
        let mut mac = Device::generate().unwrap();
        let phone = Device::generate().unwrap();
        let offer = mac.start_pairing();
        let (mut p, mut m, done) = pairing_connect(&mac, &phone, &offer.token).unwrap();
        assert_eq!(done.authenticated.as_deref(), Some(PAIRING_PEER));
        assert!(m.is_pairing() && p.is_pairing());
        let wire = p.send(b"get bundle").unwrap();
        assert_eq!(
            m.receive(&wire).unwrap().frames,
            vec![b"get bundle".to_vec()]
        );
        let back = m.send(b"bundle").unwrap();
        assert_eq!(p.receive(&back).unwrap().frames, vec![b"bundle".to_vec()]);
    }

    #[test]
    fn a_wrong_token_or_no_open_pairing_is_refused() {
        let mut mac = Device::generate().unwrap();
        let phone = Device::generate().unwrap();
        assert!(
            matches!(LanChannel::responder(&mac), Err(Error::NotPaired)),
            "nothing to authenticate against"
        );
        let offer = mac.start_pairing();
        let mut wrong = offer.token;
        wrong[0] ^= 1;
        assert!(matches!(
            pairing_connect(&mac, &phone, &wrong),
            Err(Error::WrongPeer)
        ));
        mac.cancel_pairing();
        assert!(
            pairing_connect(&mac, &phone, &offer.token).is_err(),
            "closed pairing"
        );
    }

    #[test]
    fn a_pairing_phone_refuses_a_mac_other_than_the_one_on_the_code() {
        let mut mac = Device::generate().unwrap();
        let mut impostor = Device::generate().unwrap();
        let phone = Device::generate().unwrap();
        let offer = mac.start_pairing();
        let _ = impostor.start_pairing();
        let mut p = LanChannel::pairing_initiator(&phone, mac.relay_key(), &offer.token).unwrap();
        let hello = p.start();
        let mut fake = LanChannel::responder(&impostor).unwrap();
        let r = fake.receive(&hello).unwrap();
        assert!(
            matches!(p.receive(&r.send), Err(Error::WrongPeer)),
            "refused before the proof leaves"
        );
    }

    #[test]
    fn a_paired_phone_is_still_a_peer_while_a_pairing_is_open() {
        let (mut mac, phone, mac_id, phone_id) = pair();
        mac.confirm_pairing(&phone_id).unwrap();
        let _ = mac.start_pairing();
        let (_, m) = connect(&mac, &phone, &mac_id, usize::MAX).unwrap();
        assert_eq!(m.peer_id(), Some(phone_id.as_str()));
        assert!(!m.is_pairing());
    }

    #[test]
    fn an_expired_pairing_is_refused() {
        let mut mac = Device::generate().unwrap();
        let phone = Device::generate().unwrap();
        let offer = mac.start_pairing();
        mac.expire_pairing_for_test();
        assert!(pairing_connect(&mac, &phone, &offer.token).is_err());
    }
}
