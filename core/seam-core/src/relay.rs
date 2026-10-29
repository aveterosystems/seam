// SPDX-License-Identifier: AGPL-3.0-only
//! Relay login. Each device has an Ed25519 key used only to answer the relay's challenge, so the
//! relay can check group membership without ever seeing a Signal key.

use ed25519_dalek::Signer;

use crate::Device;

pub const LOGIN_CONTEXT: &[u8] = b"seam-relay-login-v1";

impl Device {
    /// The public half, which the relay knows this device by.
    pub fn relay_key(&self) -> [u8; 32] {
        self.relay_key.verifying_key().to_bytes()
    }

    /// Signs "seam-relay-login-v1" || nonce || group_id (see protocol/seam/v1/relay.proto).
    pub fn sign_relay_login(&self, nonce: &[u8], group_id: &[u8]) -> [u8; 64] {
        let mut msg = LOGIN_CONTEXT.to_vec();
        msg.extend_from_slice(nonce);
        msg.extend_from_slice(group_id);
        self.relay_key.sign(&msg).to_bytes()
    }
}

#[cfg(test)]
mod tests {
    use ed25519_dalek::{Signature, Verifier, VerifyingKey};

    use crate::Device;

    #[test]
    fn login_signature_verifies_only_for_that_nonce_and_group() {
        let d = Device::generate().unwrap();
        let key = VerifyingKey::from_bytes(&d.relay_key()).unwrap();
        let sig = Signature::from_bytes(&d.sign_relay_login(&[1; 32], &[2; 16]));
        let msg = |nonce: &[u8], group: &[u8]| [super::LOGIN_CONTEXT, nonce, group].concat();
        assert!(key.verify(&msg(&[1; 32], &[2; 16]), &sig).is_ok());
        assert!(
            key.verify(&msg(&[9; 32], &[2; 16]), &sig).is_err(),
            "other nonce"
        );
        assert!(
            key.verify(&msg(&[1; 32], &[9; 16]), &sig).is_err(),
            "other group"
        );
    }
}
