// SPDX-License-Identifier: AGPL-3.0-only
//! The computer side of Seam (decision 0014): everything between a computer and its phone that
//! isn't a screen or an OS call, shared by every computer app. The Mac reaches it through
//! `seam-ffi`; Linux links it directly. All crypto stays in `seam-core`. Nothing here logs: what an
//! app should log arrives as events.

pub mod backoff;
pub mod clock;
pub mod computer;
pub mod durable;
pub mod features;
pub mod lan;
pub mod outbox;
pub mod pairing;
pub mod paths;
pub mod session;
pub mod store;
pub mod timers;
pub mod tor;

/// A poisoned lock is still usable: nothing behind a lock here has an invariant a panic mid-update
/// could break.
pub(crate) fn lock<T>(m: &std::sync::Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|p| p.into_inner())
}

/// Something the app should log (a problem) or announce (an arrival). Never carries contents,
/// keys or addresses.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Event {
    Problem(Problem),
    /// A clip from the phone landed on this computer's clipboard.
    ClipFromPhone,
    /// A new notification from the phone.
    NotificationFromPhone {
        app_name: String,
    },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Problem {
    SaveFailed,
    SealFailed,
    UntrustedIdentity,
    /// An envelope from the phone that failed its checks.
    DroppedEnvelope,
    /// A pairing step failed here (bundle, confirm, or the PairAccept never acked).
    PairingFailed,
    /// A pairing request the core refused.
    PairingRefused,
    /// The Same Wi‑Fi listener couldn't start.
    ListenerFailed,
    /// The saved data exists but can't be opened.
    StoreUnreadable,
}

#[cfg(test)]
mod testing;
