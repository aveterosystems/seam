//! Byte format for prekey bundles, which the relay stores and hands out as opaque blobs.
//! libsignal has no wire format for bundles, so this is ours. It carries public keys and
//! signatures only; libsignal verifies the signatures against the pinned identity when a
//! session starts, so a tampered bundle fails there.
//!
//! Layout: version (1) | registration id (u32) | one-time prekey flag (u8) [id u32, key]
//! | signed prekey id u32, key, sig | kyber prekey id u32, key, sig | identity key.
//! Every variable-length field is a u16 big-endian length followed by the bytes.

use libsignal_protocol::{
    DeviceId, IdentityKey, PreKeyBundle, PublicKey, SignalProtocolError, kem,
};

use crate::{Error, Result};

const VERSION: u8 = 1;

pub fn encode(b: &PreKeyBundle) -> Result<Vec<u8>> {
    let mut out = vec![VERSION];
    out.extend_from_slice(&b.registration_id()?.to_be_bytes());
    match (b.pre_key_id()?, b.pre_key_public()?) {
        (Some(id), Some(key)) => {
            out.push(1);
            out.extend_from_slice(&u32::from(id).to_be_bytes());
            put(&mut out, &key.serialize());
        }
        _ => out.push(0),
    }
    out.extend_from_slice(&u32::from(b.signed_pre_key_id()?).to_be_bytes());
    put(&mut out, &b.signed_pre_key_public()?.serialize());
    put(&mut out, b.signed_pre_key_signature()?);
    out.extend_from_slice(&u32::from(b.kyber_pre_key_id()?).to_be_bytes());
    put(&mut out, &b.kyber_pre_key_public()?.serialize());
    put(&mut out, b.kyber_pre_key_signature()?);
    put(&mut out, &b.identity_key()?.serialize());
    Ok(out)
}

pub fn decode(bytes: &[u8]) -> Result<PreKeyBundle> {
    let mut r = Reader(bytes);
    if r.u8()? != VERSION {
        return Err(Error::BadBundle);
    }
    let registration_id = r.u32()?;
    let one_time = match r.u8()? {
        0 => None,
        1 => Some((r.u32()?.into(), curve(r.field()?)?)),
        _ => return Err(Error::BadBundle),
    };
    let signed_id = r.u32()?;
    let signed_key = curve(r.field()?)?;
    let signed_sig = r.field()?.to_vec();
    let kyber_id = r.u32()?;
    let kyber_key = kem::PublicKey::deserialize(r.field()?)?;
    let kyber_sig = r.field()?.to_vec();
    let identity = IdentityKey::decode(r.field()?)?;
    if !r.0.is_empty() {
        return Err(Error::BadBundle);
    }
    Ok(PreKeyBundle::new(
        registration_id,
        DeviceId::try_from(1u8).expect("1 is a valid device id"),
        one_time,
        signed_id.into(),
        signed_key,
        signed_sig,
        kyber_id.into(),
        kyber_key,
        kyber_sig,
        identity,
    )?)
}

fn put(out: &mut Vec<u8>, data: &[u8]) {
    out.extend_from_slice(&(data.len() as u16).to_be_bytes());
    out.extend_from_slice(data);
}

fn curve(bytes: &[u8]) -> Result<PublicKey> {
    PublicKey::deserialize(bytes).map_err(|e| Error::Signal(SignalProtocolError::from(e)))
}

struct Reader<'a>(&'a [u8]);

impl<'a> Reader<'a> {
    fn take(&mut self, n: usize) -> Result<&'a [u8]> {
        if self.0.len() < n {
            return Err(Error::BadBundle);
        }
        let (head, rest) = self.0.split_at(n);
        self.0 = rest;
        Ok(head)
    }
    fn u8(&mut self) -> Result<u8> {
        Ok(self.take(1)?[0])
    }
    fn u32(&mut self) -> Result<u32> {
        Ok(u32::from_be_bytes(self.take(4)?.try_into().unwrap()))
    }
    fn field(&mut self) -> Result<&'a [u8]> {
        let len = u16::from_be_bytes(self.take(2)?.try_into().unwrap()) as usize;
        self.take(len)
    }
}

#[cfg(test)]
mod tests {
    use crate::Device;

    #[test]
    fn a_session_starts_from_a_decoded_bundle() {
        let mut mac = Device::new("mac").unwrap();
        let mut phone = Device::new("phone").unwrap();
        let (m, p) = (mac.address().clone(), phone.address().clone());
        mac.pin_peer(&p, &phone.identity_key()).unwrap();
        phone.pin_peer(&m, &mac.identity_key()).unwrap();

        let bytes = super::encode(&phone.prekey_bundle().unwrap()).unwrap();
        let bundle = super::decode(&bytes).unwrap();
        mac.start_session(&p, &bundle).unwrap();
        let sealed = mac.encrypt(&p, b"over the relay").unwrap();
        assert_eq!(phone.decrypt(&m, &sealed).unwrap(), b"over the relay");
    }

    #[test]
    fn rejects_truncated_extended_and_wrong_version_bundles() {
        let mut phone = Device::new("phone").unwrap();
        let bytes = super::encode(&phone.prekey_bundle().unwrap()).unwrap();
        assert!(super::decode(&bytes[..bytes.len() - 1]).is_err());
        let mut longer = bytes.clone();
        longer.push(0);
        assert!(super::decode(&longer).is_err());
        let mut v2 = bytes;
        v2[0] = 2;
        assert!(super::decode(&v2).is_err());
    }

    #[test]
    fn tampered_signature_fails_when_the_session_starts() {
        let mut mac = Device::new("mac").unwrap();
        let mut phone = Device::new("phone").unwrap();
        let p = phone.address().clone();
        mac.pin_peer(&p, &phone.identity_key()).unwrap();
        let mut bytes = super::encode(&phone.prekey_bundle().unwrap()).unwrap();
        // The signed-prekey signature sits after: version, reg id, flag, id, key field, id, key field
        let key_len = |at: usize, b: &[u8]| u16::from_be_bytes([b[at], b[at + 1]]) as usize;
        let mut at = 1 + 4 + 1 + 4;
        at += 2 + key_len(at, &bytes); // one-time key
        at += 4;
        at += 2 + key_len(at, &bytes); // signed prekey
        bytes[at + 2] ^= 1; // first byte of the signature
        let bundle = super::decode(&bytes).unwrap();
        assert!(mac.start_session(&p, &bundle).is_err());
    }
}
