// SPDX-License-Identifier: AGPL-3.0-only
//! The two secrets Seam's Tor path needs, as plain 32-byte values the apps keep in their sealed
//! app data. Nothing here invents cryptography: the bytes become Tor's own key types through
//! Arti's `tor-llcrypto` (ed25519-dalek and x25519-dalek underneath).
//!
//! - [`ServiceKey`]: the Mac's onion service identity. Its public half *is* the onion address.
//! - [`DiscoveryKey`]: one per paired phone. The Mac publishes its address details encrypted to
//!   the public half (Tor's "restricted discovery"), so only that phone can locate the service.

use std::fmt;
use std::str::FromStr;

use tor_hscrypto::pk::{HsClientDescEncKey, HsClientDescEncSecretKey, HsId, HsIdKey, HsIdKeypair};
use tor_llcrypto::pk::{curve25519, ed25519};
use zeroize::Zeroizing;

use crate::Error;

const SECRET_LEN: usize = 32;

fn random_secret() -> Result<Zeroizing<[u8; SECRET_LEN]>, Error> {
    let mut bytes = Zeroizing::new([0u8; SECRET_LEN]);
    getrandom::fill(bytes.as_mut()).map_err(|_| Error::Random)?;
    Ok(bytes)
}

/// The array copy on the stack isn't wiped; accepted (SR-33-08): Arti's keystore holds the
/// expanded keys in memory for the node's lifetime anyway.
fn secret_from(bytes: &[u8]) -> Result<Zeroizing<[u8; SECRET_LEN]>, Error> {
    let array: [u8; SECRET_LEN] = bytes.try_into().map_err(|_| Error::BadKey)?;
    Ok(Zeroizing::new(array))
}

/// The Mac's onion service identity (an ed25519 seed). Secret: store it only sealed.
pub struct ServiceKey(Zeroizing<[u8; SECRET_LEN]>);

impl ServiceKey {
    pub fn generate() -> Result<Self, Error> {
        random_secret().map(Self)
    }

    /// Exactly 32 bytes, as returned by [`ServiceKey::to_bytes`].
    pub fn from_bytes(bytes: &[u8]) -> Result<Self, Error> {
        secret_from(bytes).map(Self)
    }

    pub fn to_bytes(&self) -> Zeroizing<[u8; SECRET_LEN]> {
        self.0.clone()
    }

    /// The address phones connect to. Deterministic: the same key always gives the same address.
    pub fn address(&self) -> OnionAddress {
        OnionAddress(HsIdKey::from(&self.keypair()).id())
    }

    pub(crate) fn keypair(&self) -> HsIdKeypair {
        let keypair = ed25519::Keypair::from_bytes(&self.0);
        HsIdKeypair::from(ed25519::ExpandedKeypair::from(&keypair))
    }
}

/// A phone's restricted-discovery key (an x25519 secret). Secret: store it only sealed.
pub struct DiscoveryKey(Zeroizing<[u8; SECRET_LEN]>);

impl DiscoveryKey {
    pub fn generate() -> Result<Self, Error> {
        random_secret().map(Self)
    }

    /// Exactly 32 bytes, as returned by [`DiscoveryKey::to_bytes`].
    pub fn from_bytes(bytes: &[u8]) -> Result<Self, Error> {
        secret_from(bytes).map(Self)
    }

    pub fn to_bytes(&self) -> Zeroizing<[u8; SECRET_LEN]> {
        self.0.clone()
    }

    /// What the Mac needs to let this phone discover it. Safe to share.
    pub fn public(&self) -> DiscoveryPublic {
        DiscoveryPublic(HsClientDescEncKey::from(&self.secret()))
    }

    pub(crate) fn secret(&self) -> HsClientDescEncSecretKey {
        HsClientDescEncSecretKey::from(curve25519::StaticSecret::from(*self.0))
    }
}

// Secrets never print, even in a panic message or a debug log.
impl fmt::Debug for ServiceKey {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("ServiceKey(..)")
    }
}

impl fmt::Debug for DiscoveryKey {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("DiscoveryKey(..)")
    }
}

/// The public half of a [`DiscoveryKey`].
#[derive(Clone, PartialEq, Eq)]
pub struct DiscoveryPublic(pub(crate) HsClientDescEncKey);

impl DiscoveryPublic {
    pub fn from_bytes(bytes: &[u8]) -> Result<Self, Error> {
        let array: [u8; SECRET_LEN] = bytes.try_into().map_err(|_| Error::BadKey)?;
        Ok(Self(HsClientDescEncKey::from(curve25519::PublicKey::from(
            array,
        ))))
    }

    pub fn to_bytes(&self) -> [u8; SECRET_LEN] {
        let key: &curve25519::PublicKey = self.0.as_ref();
        key.to_bytes()
    }
}

impl fmt::Debug for DiscoveryPublic {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "DiscoveryPublic({})", self.0)
    }
}

/// A v3 onion address, `<56 characters>.onion`.
#[derive(Clone, Copy, PartialEq, Eq)]
pub struct OnionAddress(pub(crate) HsId);

impl fmt::Display for OnionAddress {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        // HsId's Display is redacted by default (safelog); this is the full address on purpose
        write!(
            f,
            "{}",
            safelog::DisplayRedacted::display_unredacted(&self.0)
        )
    }
}

// The address identifies the Mac like an IP address does: never in a debug print (SR-33-07).
// The full address comes only from Display, on purpose
impl fmt::Debug for OnionAddress {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("OnionAddress(..)")
    }
}

impl FromStr for OnionAddress {
    type Err = Error;

    /// Accepts only a full v3 address (checksum and version checked by Arti).
    fn from_str(text: &str) -> Result<Self, Error> {
        HsId::from_str(text)
            .map(Self)
            .map_err(|_| Error::BadAddress)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn address_is_the_seed_public_key() {
        let key = ServiceKey::from_bytes(&[7u8; 32]).unwrap();
        let public = ed25519::Keypair::from_bytes(&[7u8; 32]).verifying_key();
        let address = key.address();
        let id: &[u8; 32] = address.0.as_ref();
        assert_eq!(
            id,
            public.as_bytes(),
            "the onion address must commit to the stored seed"
        );
    }

    #[test]
    fn address_is_stable_and_round_trips() {
        let key = ServiceKey::generate().unwrap();
        let text = key.address().to_string();
        assert!(text.ends_with(".onion") && text.len() == 62, "{text}");
        assert_eq!(
            ServiceKey::from_bytes(&*key.to_bytes()).unwrap().address(),
            key.address()
        );
        assert_eq!(text.parse::<OnionAddress>().unwrap(), key.address());
    }

    #[test]
    fn different_seeds_give_different_addresses() {
        assert_ne!(
            ServiceKey::generate().unwrap().address(),
            ServiceKey::generate().unwrap().address()
        );
    }

    #[test]
    fn bad_addresses_are_rejected() {
        let good = ServiceKey::generate().unwrap().address().to_string();
        let mut flipped = good.clone().into_bytes();
        flipped[0] = if flipped[0] == b'a' { b'b' } else { b'a' };
        for bad in [
            String::new(),
            "example.com".into(),
            String::from_utf8(flipped).unwrap(),
            good.replace(".onion", ""),
        ] {
            assert!(bad.parse::<OnionAddress>().is_err(), "{bad} accepted");
        }
    }

    #[test]
    fn keys_must_be_32_bytes() {
        for len in [0, 31, 33, 64] {
            assert!(ServiceKey::from_bytes(&vec![1; len]).is_err());
            assert!(DiscoveryKey::from_bytes(&vec![1; len]).is_err());
            assert!(DiscoveryPublic::from_bytes(&vec![1; len]).is_err());
        }
    }

    #[test]
    fn discovery_public_is_derived_and_round_trips() {
        let key = DiscoveryKey::generate().unwrap();
        let again = DiscoveryKey::from_bytes(&*key.to_bytes()).unwrap();
        assert_eq!(key.public(), again.public());
        assert_eq!(
            DiscoveryPublic::from_bytes(&key.public().to_bytes()).unwrap(),
            key.public()
        );
        assert_ne!(key.public(), DiscoveryKey::generate().unwrap().public());
        assert_ne!(
            &key.public().to_bytes(),
            &*key.to_bytes(),
            "public must not be the secret"
        );
    }

    #[test]
    fn an_address_never_debug_prints() {
        let address = ServiceKey::generate().unwrap().address();
        let text = address.to_string();
        let printed = format!("{address:?}");
        assert!(!printed.contains(&text[..10]), "{printed}");
    }

    #[test]
    fn secrets_never_print() {
        let service = ServiceKey::from_bytes(&[0xAB; 32]).unwrap();
        let discovery = DiscoveryKey::from_bytes(&[0xCD; 32]).unwrap();
        let printed = format!("{service:?} {discovery:?}");
        let lower = printed.to_lowercase();
        assert!(
            !lower.contains("abab") && !lower.contains("cdcd") && !lower.contains("171"),
            "{printed}"
        );
    }
}
