// SPDX-License-Identifier: AGPL-3.0-only
//! Seam's Tor path (planning notes: docs/tor): away from home the Mac hosts a
//! private onion service and the phone reaches it through Tor, using Arti, the Tor Project's Rust
//! Tor client.
//!
//! Tor is only a pipe. Everything sent over a stream is already sealed by `seam-core`, and the
//! apps log in over it exactly as on Same Wi‑Fi, so a fault here can leak metadata, never content.
//!
//! Privacy: nothing here logs, and no tracing subscriber is ever installed, so Arti's own
//! diagnostics (which can mention addresses) go nowhere.

mod client;
mod keys;
mod node;
mod service;

pub use arti_client::{DataStream, ErrorKind};
pub use keys::{DiscoveryKey, DiscoveryPublic, OnionAddress, ServiceKey};
pub use node::{Blocked, START_TIMEOUT, TorNode, TorStatus};
pub use service::HostedService;

/// The one onion-service port Seam answers on. Onion ports are virtual: nothing listens on a real
/// port of the Mac.
pub const SEAM_PORT: u16 = 1;

/// What can go wrong. Messages never include an address or key.
#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error("key must be exactly 32 bytes")]
    BadKey,
    #[error("not a valid onion address")]
    BadAddress,
    #[error("the system random number generator failed")]
    Random,
    #[error("Tor configuration was rejected")]
    Config,
    #[error("no paired phone may discover this service")]
    NoClients,
    #[error("this Tor node already hosts a service under another key")]
    WrongServiceKey,
    #[error("Tor timed out")]
    Timeout,
    #[error("Tor failed: {0}")]
    Tor(ErrorKind),
}
