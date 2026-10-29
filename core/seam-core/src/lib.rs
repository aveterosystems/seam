// SPDX-License-Identifier: AGPL-3.0-only
//! Seam's crypto core. Everything that touches keys lives here, written once and shared by the
//! Mac and Android apps.
//!
//! - [`Device`]: identity, peers and Signal sessions (PQXDH setup, triple ratchet).
//! - [`envelope`]: sealing and opening what travels between devices, with the binding checks.
//! - [`pairing`]: both sides of QR pairing and the 6-digit safety code.
//! - [`state`]: saving and restoring a device, sealed under the app's storage key.
//! - [`relay`]: the Ed25519 key a device logs in to the relay with.
//! - [`padding`], [`bundle`], [`file`]: size buckets, prekey bundle bytes, file chunks.

pub mod bundle;
mod device;
pub mod envelope;
mod error;
pub mod file;
pub mod padding;
pub mod pairing;
pub mod proto;
pub mod relay;
pub mod state;

pub use device::{Device, Peer, Sealed, device_id_for, peer_address};
pub use envelope::Opened;
pub use error::{Error, Result};
pub use libsignal_protocol::{PreKeyBundle, ProtocolAddress, SignalProtocolError};
pub use pairing::{PairingOffer, PairingRequest};
