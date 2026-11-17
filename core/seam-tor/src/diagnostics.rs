// SPDX-License-Identifier: AGPL-3.0-only
//! Arti's own log, recorded in memory for the user's diagnostics export (specs/diagnostics.md).

use std::sync::OnceLock;

use tracing_subscriber::layer::SubscriberExt;

/// Held for the life of the process: while it lives, nothing can switch Arti's redaction off.
static SAFE_LOGGING: OnceLock<safelog::Guard> = OnceLock::new();

/// Starts recording Arti's log, once per process; call before Arti starts. Only with Arti's
/// safe logging locked on: if something already switched it off, nothing is recorded.
pub fn record_arti() {
    if SAFE_LOGGING.get().is_some() {
        return;
    }
    let Ok(guard) = safelog::enforce_safe_logging() else {
        return;
    };
    if SAFE_LOGGING.set(guard).is_err() {
        return; // another thread got there first; its guard holds the lock
    }
    let layer = seam_diagnostics::ArtiLayer::new(seam_diagnostics::recorder());
    // Fails only if the app already installed a subscriber: then that one decides
    let _ = tracing::subscriber::set_global_default(tracing_subscriber::registry().with(layer));
}
