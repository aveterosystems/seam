// SPDX-License-Identifier: AGPL-3.0-only
//! The clipboard rules for the apps (seam_core::features::clipboard). Times are unix ms.

use std::sync::Mutex;

use seam_core::features::clipboard;

/// What crosses between the two clipboards: nothing empty or oversized, no quick repeats, no echoes.
#[derive(uniffi::Object, Default)]
pub struct ClipRules {
    inner: Mutex<clipboard::ClipRules>,
}

#[uniffi::export]
impl ClipRules {
    #[uniffi::constructor]
    pub fn new() -> Self {
        Self::default()
    }

    /// The text to send for something copied here, or None to skip it.
    pub fn outgoing(&self, text: Option<String>, now_ms: i64) -> Option<String> {
        self.lock().outgoing(text.as_deref(), now_ms)
    }

    /// Something the user explicitly sent: only the size rules apply.
    pub fn forced(&self, text: Option<String>, now_ms: i64) -> Option<String> {
        self.lock().forced(text.as_deref(), now_ms)
    }

    /// Text from the other side to put on this clipboard, or None to ignore it.
    pub fn incoming(&self, text: String) -> Option<String> {
        self.lock().incoming(&text)
    }

    /// The peer was forgotten.
    pub fn reset(&self) {
        self.lock().reset();
    }
}

impl ClipRules {
    fn lock(&self) -> std::sync::MutexGuard<'_, clipboard::ClipRules> {
        // Plain data with no invariant a panic could break: a poisoned lock is still usable
        self.inner.lock().unwrap_or_else(|e| e.into_inner())
    }
}

/// The largest clip, in UTF-8 bytes.
#[uniffi::export]
pub fn clip_max_bytes() -> u32 {
    clipboard::MAX_BYTES as u32
}
