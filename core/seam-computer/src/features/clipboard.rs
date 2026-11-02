// SPDX-License-Identifier: AGPL-3.0-only
//! Clipboard both ways: copies here go to the phone while sync is on, the phone's land here, and
//! neither bounces back (the core's `ClipRules`). Which pasteboard items may be read at all is the
//! platform's to say: the types below.

use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

use async_trait::async_trait;
use seam_core::features::clipboard::ClipRules;
use seam_core::proto::v1::{Payload, payload::Body};

use super::{Host, Reach};
use crate::timers::{Due, TimerToken};

mod rules;

pub use rules::{ClipQueue, HOLD_MS, is_private, openable_url, sendable};

pub const CAPABILITY: &str = "clipboard.v1";
/// macOS has no change notification for the pasteboard: looking at its counter twice a second is
/// the standard way, and cheap.
pub const POLL: Duration = Duration::from_millis(500);
const MAX_CLIPS: usize = 100;
const DAY_MS: i64 = 86_400_000;

/// The computer's pasteboard, as the clipboard feature sees it.
#[async_trait]
pub trait Pasteboard: Send + Sync {
    async fn change_count(&self) -> i64;
    /// The first item's types and text, read together, if the change count is still `if_still`.
    /// None when there's nothing, or it changed meanwhile (a password copied a moment later can't
    /// be read under the previous item's types, SR-4-05).
    async fn first_item(&self, if_still: i64) -> Option<PasteboardItem>;
    /// Replaces the contents with `text`; returns the new change count.
    async fn write(&self, text: String) -> i64;
}

#[derive(Clone, PartialEq, Eq)]
pub struct PasteboardItem {
    pub types: Vec<String>,
    pub text: Option<String>,
}

/// One clip that crossed between the computer and the phone.
#[derive(Clone, PartialEq, Eq)]
pub struct Clip {
    pub text: String,
    /// Unix ms.
    pub at: i64,
    pub from_phone: bool,
}

// What was copied never prints, even in a panic message (rule 6)
impl std::fmt::Debug for PasteboardItem {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("PasteboardItem")
            .field("types", &self.types)
            .finish_non_exhaustive()
    }
}

impl std::fmt::Debug for Clip {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Clip")
            .field("at", &self.at)
            .field("from_phone", &self.from_phone)
            .finish_non_exhaustive()
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Command {
    /// Off, the phone hears it in the Hello and stops sending copies this computer would drop.
    SetSync(bool),
    /// Puts an earlier clip back on this computer's clipboard ("Copy"), without sending it back.
    CopyAgain(String),
    ClearClips,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct View {
    pub sync_on: bool,
    /// Clips that crossed either way in the last day, newest first.
    pub clips: Vec<Clip>,
}

pub struct Clipboard {
    pasteboard: Arc<dyn Pasteboard>,
    rules: ClipRules,
    last_change: Option<i64>,
    held: ClipQueue<Payload>,
    poll: Option<TimerToken>,
    stopped: bool,
    /// Shared with the app, so turning sync off takes effect at once, even mid-read (SR-29-05).
    sync_on: Arc<AtomicBool>,
    /// What the phone was last told, so a change is told once (the app flips `sync_on` first).
    told: bool,
    /// Clips that crossed either way in the last day, newest first (memory only).
    clips: Vec<Clip>,
}

impl Clipboard {
    pub fn new(pasteboard: Arc<dyn Pasteboard>, sync_on: Arc<AtomicBool>) -> Self {
        Self {
            pasteboard,
            rules: ClipRules::new(),
            last_change: None,
            held: ClipQueue::default(),
            poll: None,
            stopped: false,
            told: sync_on.load(Ordering::SeqCst),
            sync_on,
            clips: Vec::new(),
        }
    }

    pub fn clips(&self) -> &[Clip] {
        &self.clips
    }

    pub fn sync_on(&self) -> bool {
        self.sync_on.load(Ordering::SeqCst)
    }

    pub async fn command(&mut self, command: Command, host: &mut dyn Host) {
        match command {
            Command::SetSync(on) => self.set_sync(on, host).await,
            Command::CopyAgain(text) => self.copy_again(text).await,
            Command::ClearClips => self.clips.clear(),
        }
    }

    pub fn view(&self) -> View {
        View {
            sync_on: self.sync_on(),
            clips: self.clips.clone(),
        }
    }

    /// What's on the pasteboard at launch was copied before, not for the phone.
    pub async fn start(&mut self, host: &mut dyn Host) {
        self.last_change = Some(self.pasteboard.change_count().await);
        host.set_withheld(CAPABILITY, !self.sync_on());
        self.poll_later(host);
    }

    pub fn stop(&mut self) {
        self.stopped = true;
        if let Some(t) = self.poll.take() {
            t.cancel();
        }
    }

    async fn set_sync(&mut self, on: bool, host: &mut dyn Host) {
        self.sync_on.store(on, Ordering::SeqCst);
        let changed = std::mem::replace(&mut self.told, on) != on;
        // Turning it on never sends what was copied while off
        self.last_change = Some(self.pasteboard.change_count().await);
        if !on {
            self.held.clear(); // nor does turning it off let a held copy go later
            self.clips.clear(); // and it takes the clips off the screen (SR-10-04)
        }
        host.set_withheld(CAPABILITY, !on);
        if changed {
            host.send_hello();
        }
    }

    fn poll_later(&mut self, host: &dyn Host) {
        if !self.stopped {
            self.poll = Some(host.after(POLL, Due::ClipboardPoll));
        }
    }

    pub async fn poll(&mut self, host: &mut dyn Host) {
        let reach = host.reach();
        if let Some(payload) = self.capture(reach, host.now_ms()).await {
            host.send(payload);
        }
        self.poll_later(host);
    }

    /// A new copy here: what to send now. Held instead while the phone can't be reached; with no
    /// phone, or while paused, seen and let go, so it never goes later either.
    async fn capture(&mut self, reach: Reach, now_ms: i64) -> Option<Payload> {
        let change = self.pasteboard.change_count().await;
        if Some(change) == self.last_change {
            return None;
        }
        if !self.sync_on() || reach == Reach::Off {
            self.last_change = Some(change);
            return None;
        }
        let item = self.pasteboard.first_item(change).await?; // look again next time
        self.last_change = Some(change);
        if !self.sync_on() {
            return None; // turned off while the read was in progress (SR-29-05)
        }
        if !sendable(&item.types) {
            return None;
        }
        let text = self.rules.outgoing(item.text.as_deref(), now_ms)?;
        let payload = Payload {
            body: Some(Body::Clipboard(seam_core::proto::v1::Clipboard {
                text: text.clone(),
                copied_at: now_ms,
                ..Default::default()
            })),
        };
        self.remember(Clip {
            text,
            at: now_ms,
            from_phone: false,
        });
        if reach != Reach::Connected {
            self.held.hold(payload, now_ms);
            return None;
        }
        Some(payload)
    }

    async fn copy_again(&mut self, text: String) {
        self.last_change = Some(self.pasteboard.write(text).await);
    }

    pub async fn receive(&mut self, body: &Body, host: &mut dyn Host) {
        let Body::Clipboard(clip) = body else { return };
        if !self.sync_on() {
            return;
        }
        let Some(text) = self.rules.incoming(&clip.text) else {
            return;
        };
        // Our own write isn't a new copy
        self.last_change = Some(self.pasteboard.write(text.clone()).await);
        self.remember(Clip {
            text,
            at: host.now_ms(),
            from_phone: true,
        });
        host.event(crate::Event::ClipFromPhone);
    }

    fn remember(&mut self, clip: Clip) {
        let day_ago = clip.at - DAY_MS;
        self.clips.retain(|c| c.at > day_ago);
        self.clips.insert(0, clip);
        self.clips.truncate(MAX_CLIPS);
    }

    /// A copy held while the phone was away goes now, unless it's too old.
    pub fn connected(&mut self, host: &mut dyn Host) {
        if let Some(payload) = self.held.take(host.now_ms(), self.sync_on()) {
            host.send(payload);
        }
    }

    pub fn reset(&mut self) {
        self.held.clear();
        self.rules.reset(); // nothing the old phone sent counts as an echo
        self.clips.clear();
    }
}

#[cfg(test)]
mod tests;
