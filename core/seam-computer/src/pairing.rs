// SPDX-License-Identifier: AGPL-3.0-only
//! The pairing window's state machine and the QR code it shows.

pub mod qr;

use seam_core::PairingRequest;

/// How long a pairing QR code is valid, matching the core's own 5-minute slot (SR-2-22).
pub const LIFETIME_MS: i64 = 300_000;

#[derive(Clone, PartialEq, Eq)]
pub enum PairingState {
    Idle,
    Waiting {
        qr: String,
        /// Unix ms.
        expires_at: i64,
    },
    /// A verified request: the user compares the safety code.
    Confirm(PairingRequest),
    /// Pair was clicked and PairAccept is on its way; nothing can cancel it now.
    Confirming(PairingRequest),
    Paired {
        name: String,
    },
    Expired,
    Failed(PairingFailure),
}

// The code carries the one-time token and the discovery secret: it never prints (rule 6)
impl std::fmt::Debug for PairingState {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            PairingState::Idle => f.write_str("Idle"),
            PairingState::Waiting { expires_at, .. } => f
                .debug_struct("Waiting")
                .field("expires_at", expires_at)
                .finish_non_exhaustive(),
            PairingState::Confirm(r) => f.debug_tuple("Confirm").field(&r.peer_id).finish(),
            PairingState::Confirming(r) => f.debug_tuple("Confirming").field(&r.peer_id).finish(),
            PairingState::Paired { .. } => f.write_str("Paired"),
            PairingState::Expired => f.write_str("Expired"),
            PairingState::Failed(why) => f.debug_tuple("Failed").field(why).finish(),
        }
    }
}

/// Why a pairing didn't finish (the app picks the words; each ends with "show a new code").
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PairingFailure {
    /// The phone left, or never acked the PairAccept.
    Unreachable,
    /// The pairing couldn't be saved on this computer.
    NotSaved,
    /// The core doesn't hold the phone as confirmed.
    DidntFinish,
}

/// Every transition that doesn't apply in the current state is refused, so double clicks and late
/// events can't skip a step (SR-2-21, 22, 23).
#[derive(Debug, Clone)]
pub struct PairingFlow {
    state: PairingState,
}

impl Default for PairingFlow {
    fn default() -> Self {
        Self {
            state: PairingState::Idle,
        }
    }
}

impl PairingFlow {
    pub fn state(&self) -> &PairingState {
        &self.state
    }

    pub fn is_confirming(&self) -> bool {
        matches!(self.state, PairingState::Confirming(_))
    }

    /// A code is showing or being answered: the links stay open for it.
    pub fn is_open(&self) -> bool {
        matches!(
            self.state,
            PairingState::Waiting { .. } | PairingState::Confirm(_) | PairingState::Confirming(_)
        )
    }

    /// A fresh QR code. Refused while a confirmation is in flight.
    pub fn start(&mut self, qr: String, now_ms: i64) -> bool {
        if self.is_confirming() {
            return false;
        }
        self.state = PairingState::Waiting {
            qr,
            expires_at: now_ms + LIFETIME_MS,
        };
        true
    }

    /// A request the core verified. Only accepted while a QR code is showing.
    pub fn received(&mut self, request: PairingRequest) -> bool {
        if !matches!(self.state, PairingState::Waiting { .. }) {
            return false;
        }
        self.state = PairingState::Confirm(request);
        true
    }

    /// The user clicked Pair. Returns the request once; a second click gets None.
    pub fn begin_confirming(&mut self) -> Option<PairingRequest> {
        let PairingState::Confirm(r) = &self.state else {
            return None;
        };
        let r = r.clone();
        self.state = PairingState::Confirming(r.clone());
        Some(r)
    }

    /// Cancel, or the window closed. Returns the state it left (to undo it), or None while
    /// confirming, when there's nothing safe to undo.
    pub fn cancel(&mut self) -> Option<PairingState> {
        if self.is_confirming() {
            return None;
        }
        Some(std::mem::replace(&mut self.state, PairingState::Idle))
    }

    /// The code's 5 minutes are up. Only a showing QR code expires.
    pub fn expire(&mut self) -> bool {
        if !matches!(self.state, PairingState::Waiting { .. }) {
            return false;
        }
        self.state = PairingState::Expired;
        true
    }

    pub fn finish(&mut self, name: String) {
        if self.is_confirming() {
            self.state = PairingState::Paired { name };
        }
    }

    pub fn fail(&mut self, reason: PairingFailure) {
        self.state = PairingState::Failed(reason);
    }
}

#[cfg(test)]
mod tests;
