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
}
