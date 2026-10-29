//! Seam's crypto core. Everything that touches keys lives here, written once and shared by the
//! Mac and Android apps.
//!
//! - [`Device`]: one device's identity and Signal sessions (PQXDH setup, triple ratchet).
//! - [`padding`]: pads every message to a fixed bucket size before it's encrypted.
//! - [`file`]: seals file chunks under a per-file key.
//! - [`bundle`]: the byte format for prekey bundles stored on the relay.

pub mod bundle;
mod device;
mod error;
pub mod file;
pub mod padding;

pub use device::{Device, Sealed, peer_address};
pub use error::{Error, Result};
pub use libsignal_protocol::{PreKeyBundle, ProtocolAddress, SignalProtocolError};
