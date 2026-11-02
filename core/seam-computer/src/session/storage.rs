// SPDX-License-Identifier: AGPL-3.0-only
//! What the session keeps across a restart, and where.

use crate::outbox::OutboxFile;

/// Where the session keeps what must survive a restart: `Store` on disk, a fake in tests.
pub trait Storage: Send + Sync {
    /// Seals the core's state to disk, durable before it returns. Nothing a failed save would
    /// forget is ever sent (SR-2-11).
    fn save_state(&self) -> std::io::Result<()>;
    fn outbox(&self) -> &OutboxFile;
    fn preferences(&self) -> Preferences;
    /// Best effort: a preference that didn't save is only a setting to choose again.
    fn save_preferences(&self, preferences: &Preferences);
}

/// What the user chose, and when things happened: kept across a restart. Not secrets.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Preferences {
    /// The connection switches. At least one stays on.
    pub wifi: bool,
    pub tor: bool,
    pub clipboard_sync: bool,
    /// The phone's pause (unix ms; [`super::UNTIL_RESUMED`]: until it resumes).
    pub paused_until: Option<i64>,
    /// When the phone was paired (unix ms), if it was since Seam kept the date.
    pub paired_at: Option<i64>,
}

impl Default for Preferences {
    fn default() -> Self {
        Self {
            wifi: true,
            tor: true,
            clipboard_sync: true,
            paused_until: None,
            paired_at: None,
        }
    }
}
