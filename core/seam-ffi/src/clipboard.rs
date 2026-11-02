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

#[cfg(feature = "computer")]
pub use computer_side::*;

/// The computer side (seam-computer): the pasteboard the Mac supplies, and what the screens show.
#[cfg(feature = "computer")]
mod computer_side {
    use std::sync::Arc;

    use seam_computer::features::clipboard as c;

    #[derive(uniffi::Record)]
    pub struct PasteboardItem {
        pub types: Vec<String>,
        pub text: Option<String>,
    }

    /// The computer's pasteboard.
    #[uniffi::export(with_foreign)]
    #[async_trait::async_trait]
    pub trait Pasteboard: Send + Sync {
        async fn change_count(&self) -> i64;
        /// The first item's types and text, read together, if the change count is still `if_still`.
        async fn first_item(&self, if_still: i64) -> Option<PasteboardItem>;
        /// Replaces the contents with `text`; returns the new change count.
        async fn write(&self, text: String) -> i64;
    }

    /// One clip that crossed between the computer and the phone.
    #[derive(uniffi::Record)]
    pub struct ClipRecord {
        pub text: String,
        pub at_ms: i64,
        pub from_phone: bool,
    }

    #[derive(uniffi::Record)]
    pub struct ClipboardView {
        pub sync_on: bool,
        /// Newest first.
        pub clips: Vec<ClipRecord>,
    }

    impl From<c::View> for ClipboardView {
        fn from(v: c::View) -> Self {
            Self {
                sync_on: v.sync_on,
                clips: v
                    .clips
                    .into_iter()
                    .map(|c| ClipRecord {
                        text: c.text,
                        at_ms: c.at,
                        from_phone: c.from_phone,
                    })
                    .collect(),
            }
        }
    }

    /// The web address a clip may be opened as, only when the whole clip is one (SR-10-03).
    #[uniffi::export]
    pub fn clip_openable_url(text: String) -> Option<String> {
        c::openable_url(&text).map(str::to_owned)
    }

    pub(crate) struct AppPasteboard(pub(crate) Arc<dyn Pasteboard>);

    #[async_trait::async_trait]
    impl c::Pasteboard for AppPasteboard {
        async fn change_count(&self) -> i64 {
            self.0.change_count().await
        }
        async fn first_item(&self, if_still: i64) -> Option<c::PasteboardItem> {
            let i = self.0.first_item(if_still).await?;
            Some(c::PasteboardItem {
                types: i.types,
                text: i.text,
            })
        }
        async fn write(&self, text: String) -> i64 {
            self.0.write(text).await
        }
    }
}
