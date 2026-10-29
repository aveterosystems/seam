// SPDX-License-Identifier: AGPL-3.0-only
use libsignal_protocol::SignalProtocolError;

pub type Result<T> = std::result::Result<T, Error>;

#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error("signal protocol: {0}")]
    Signal(#[from] SignalProtocolError),
    #[error("message too large to pad ({0} bytes)")]
    TooLarge(usize),
    #[error("malformed padding")]
    BadPadding,
    #[error("unknown message kind {0}")]
    UnknownKind(u8),
    #[error("malformed prekey bundle")]
    BadBundle,
    #[error("peer isn't paired: pin its identity key first")]
    NotPaired,
    #[error("file chunk failed to decrypt (tampered, reordered or wrong key)")]
    BadChunk,
    #[error("malformed protobuf: {0}")]
    Decode(#[from] prost::DecodeError),
    #[error("malformed message: {0}")]
    Malformed(&'static str),
    #[error("unsupported protocol version {0}")]
    UnsupportedVersion(u32),
    #[error("envelope isn't addressed to this device")]
    NotForMe,
    #[error("envelope doesn't match its encrypted contents")]
    EnvelopeMismatch,
    #[error("peer isn't confirmed yet: only pairing messages allowed")]
    NotConfirmed,
    #[error("already received")]
    Duplicate,
    #[error("pairing failed: {0}")]
    BadPairing(&'static str),
    #[error("saved state can't be opened (wrong key or damaged)")]
    BadState,
}
