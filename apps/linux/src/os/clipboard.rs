// SPDX-License-Identifier: AGPL-3.0-only
//! The clipboard feature's pasteboard on Linux (decision 0012). Wayland lets only the focused app
//! read or write the clipboard, so a background app needs a door: the GNOME Shell extension on
//! GNOME, Klipper on KDE (os/doors.rs). Until one is open, the clipboard still works with one
//! tap: Send Clipboard offers the copy here, and a copy from the phone waits for Copy.
//!
//! Every door looks the same to the core: a change counter, the current item, and a write.

use std::sync::{Arc, Mutex};

use async_trait::async_trait;
use seam_computer::features::clipboard::{Pasteboard, PasteboardItem, sendable};

/// One way to reach the computer's clipboard.
#[async_trait]
pub trait Door: Send + Sync {
    async fn change_count(&self) -> i64;
    async fn first_item(&self, if_still: i64) -> Option<PasteboardItem>;
    /// Puts `text` on the clipboard; None when this door couldn't (it's gone, or refused).
    async fn write(&self, text: String) -> Option<i64>;
}

/// The pasteboard the core polls. Behind it, the automatic door when there is one, else one tap.
pub struct LinuxPasteboard {
    one_tap: Arc<OneTap>,
    /// The door, how many times it changed, and its count when it was first looked at, under one
    /// lock so the three always belong together. The epoch makes a door opening or closing a
    /// change of its own (the core never compares one door's counter with another's); what was
    /// on the clipboard when the door was first looked at was copied before, and is never offered.
    state: Mutex<DoorState>,
}

#[derive(Default)]
struct DoorState {
    door: Option<Arc<dyn Door>>,
    epoch: i64,
    baseline: Option<i64>,
}

impl LinuxPasteboard {
    pub fn new(one_tap: Arc<OneTap>) -> Self {
        Self {
            one_tap,
            state: Mutex::default(),
        }
    }

    /// An automatic door opened (Some) or closed (None).
    pub fn set_door(&self, door: Option<Arc<dyn Door>>) {
        let mut s = crate::lock(&self.state);
        s.door = door;
        s.epoch += 1;
        s.baseline = None; // the next look sets what was already there
    }

    /// Send Clipboard, with what the window read while Seam had focus. True when it's text that
    /// can go: a password or a file never does, and anything doubtful offers nothing. The core's
    /// rules still decide. With an automatic door open the copy goes by itself, so nothing is
    /// offered twice.
    pub fn send_now(&self, read: Reading) -> bool {
        // The text and its types must be the same copy: a password copied meanwhile can't go out
        // under the earlier copy's types (SR-4-05)
        if read.changed || read.types_before != read.types_after {
            return false;
        }
        let ok = read.text.as_ref().is_some_and(|t| !t.is_empty()) && sendable(&read.types_before);
        if ok && crate::lock(&self.state).door.is_none() {
            self.one_tap.offer(read.types_before, read.text);
        }
        ok
    }

    fn current(&self) -> (Arc<dyn Door>, i64) {
        let s = crate::lock(&self.state);
        let door = s.door.clone().unwrap_or_else(|| self.one_tap.clone());
        (door, s.epoch)
    }
}

/// The door's own count, shifted by how many times the door changed, so the two never collide.
fn combine(epoch: i64, count: i64) -> i64 {
    (epoch << 40) | (count & ((1 << 40) - 1))
}

#[async_trait]
impl Pasteboard for LinuxPasteboard {
    async fn change_count(&self) -> i64 {
        let (door, epoch) = self.current();
        let raw = door.change_count().await;
        // Only if the door is still the one that was asked: one opening meanwhile sets its own
        let mut s = crate::lock(&self.state);
        if s.epoch == epoch && s.baseline.is_none() {
            s.baseline = Some(raw);
        }
        combine(epoch, raw)
    }

    async fn first_item(&self, if_still: i64) -> Option<PasteboardItem> {
        let (door, epoch) = self.current();
        if if_still >> 40 != epoch {
            return None; // the door changed since the core looked
        }
        let raw = if_still & ((1 << 40) - 1);
        // A door that just opened (the extension enabled, Klipper back): what it holds was copied
        // before, so the new count it brings is never read as a copy and sent
        if crate::lock(&self.state).baseline == Some(raw) {
            return None;
        }
        door.first_item(raw).await
    }

    async fn write(&self, text: String) -> i64 {
        let (door, epoch) = self.current();
        if let Some(n) = door.write(text.clone()).await {
            return combine(epoch, n);
        }
        // The automatic door failed: the copy still lands, one tap away. The count stays the
        // door's, so what was already on the clipboard never reads as a new copy and goes out
        self.one_tap.write(text).await;
        combine(epoch, door.change_count().await)
    }
}

type Writer = Box<dyn Fn(String) + Send + Sync>;

/// What Send Clipboard read: the types, then the text, then the types again, and whether the
/// clipboard changed meanwhile (GTK's `changed` signal, which also catches a change and change
/// back).
pub struct Reading {
    pub types_before: Vec<String>,
    pub text: Option<String>,
    pub types_after: Vec<String>,
    pub changed: bool,
}

// A copy's text never prints (rule 6)
impl std::fmt::Debug for Reading {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("Reading(..)")
    }
}

/// One tap: Send clipboard offers what the window read from the clipboard (it has focus then),
/// and a copy from the phone waits to be put on the clipboard while Seam has focus.
#[derive(Default)]
pub struct OneTap {
    state: Mutex<OneTapState>,
    /// Puts text on the clipboard from the main thread; a no-op in tests.
    writer: Mutex<Option<Writer>>,
}

#[derive(Default)]
struct OneTapState {
    count: i64,
    offered: Option<PasteboardItem>,
}

impl OneTap {
    pub fn new(writer: impl Fn(String) + Send + Sync + 'static) -> Self {
        Self {
            state: Mutex::default(),
            writer: Mutex::new(Some(Box::new(writer))),
        }
    }

    /// What Send Clipboard read, as a new copy for the core to look at.
    fn offer(&self, types: Vec<String>, text: Option<String>) {
        let mut s = crate::lock(&self.state);
        s.count += 1;
        s.offered = Some(PasteboardItem { types, text });
    }
}

#[async_trait]
impl Door for OneTap {
    async fn change_count(&self) -> i64 {
        crate::lock(&self.state).count
    }

    async fn first_item(&self, if_still: i64) -> Option<PasteboardItem> {
        let s = crate::lock(&self.state);
        if s.count == if_still {
            s.offered.clone()
        } else {
            None
        }
    }

    async fn write(&self, text: String) -> Option<i64> {
        if let Some(w) = crate::lock(&self.writer).as_ref() {
            w(text);
        }
        let mut s = crate::lock(&self.state);
        s.count += 1;
        s.offered = None; // our own write is never offered back
        Some(s.count)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn reading(types: &[&str], text: &str) -> Reading {
        let types: Vec<String> = types.iter().map(|t| t.to_string()).collect();
        Reading {
            types_before: types.clone(),
            text: Some(text.into()),
            types_after: types,
            changed: false,
        }
    }

    /// Send Clipboard offers one copy the core can read, and only text that may go.
    #[tokio::test]
    async fn send_now_offers_only_text_that_can_go() {
        let pb = LinuxPasteboard::new(Arc::new(OneTap::default()));
        let before = pb.change_count().await;
        assert!(pb.send_now(reading(&["text/plain"], "hi")));
        let now = pb.change_count().await;
        assert_ne!(before, now);
        assert_eq!(
            pb.first_item(now).await.unwrap().text.as_deref(),
            Some("hi")
        );
        // Nothing doubtful is offered at all: the core never even sees it
        let secret = &["text/plain", "x-kde-passwordManagerHint"];
        for no in [
            reading(secret, "hunter2"),
            reading(&["text/uri-list", "text/plain"], "file:///a.pdf"),
            reading(&["text/plain"], ""),
        ] {
            assert!(!pb.send_now(no));
        }
        assert_eq!(pb.change_count().await, now);
    }

    /// The text and its types must be one copy: a copy that changed during the read, even to a
    /// password and back, is never offered (SR-4-05).
    #[tokio::test]
    async fn a_copy_that_changed_during_the_read_is_not_offered() {
        let pb = LinuxPasteboard::new(Arc::new(OneTap::default()));
        let before = pb.change_count().await;
        let mut swapped = reading(&["text/plain"], "hunter2");
        swapped.types_after = vec!["text/plain".into(), "x-kde-passwordManagerHint".into()];
        assert!(!pb.send_now(swapped));
        let mut changed_back = reading(&["text/plain"], "hunter2");
        changed_back.changed = true;
        assert!(!pb.send_now(changed_back));
        assert_eq!(pb.change_count().await, before);
        assert_eq!(format!("{:?}", reading(&[], "hunter2")), "Reading(..)");
    }

    /// With an automatic door open the copy goes by itself: Send Clipboard says so, and offers
    /// nothing a second time.
    #[tokio::test]
    async fn send_now_with_a_door_open_offers_nothing_twice() {
        let tap = Arc::new(OneTap::default());
        let pb = LinuxPasteboard::new(tap.clone());
        pb.set_door(Some(Arc::new(Fixed(3, Some(3)))));
        assert!(pb.send_now(reading(&["text/plain"], "hi")));
        assert_eq!(tap.change_count().await, 0);
    }

    #[tokio::test]
    async fn an_offer_is_one_new_copy() {
        let pb = LinuxPasteboard::new(Arc::new(OneTap::default()));
        let before = pb.change_count().await;
        assert!(pb.send_now(reading(&["text/plain"], "hello")));
        let now = pb.change_count().await;
        assert_ne!(before, now);
        assert_eq!(
            pb.first_item(now).await.unwrap().text.as_deref(),
            Some("hello")
        );
        // Asked about an older count, nothing: a later copy can't be read under it (SR-4-05)
        assert!(pb.first_item(before).await.is_none());
    }

    #[tokio::test]
    async fn a_write_from_the_phone_is_never_offered_back() {
        let written = Arc::new(Mutex::new(Vec::new()));
        let w = written.clone();
        let tap = Arc::new(OneTap::new(move |t| crate::lock(&w).push(t)));
        let pb = LinuxPasteboard::new(tap);
        assert!(pb.send_now(reading(&["text/plain"], "mine")));
        let after_write = pb.write("from phone".into()).await;
        assert_eq!(pb.change_count().await, after_write);
        assert!(pb.first_item(after_write).await.is_none());
        assert_eq!(*crate::lock(&written), vec!["from phone".to_string()]);
    }

    struct Fixed(i64, Option<i64>);
    #[async_trait]
    impl Door for Fixed {
        async fn change_count(&self) -> i64 {
            self.0
        }
        async fn first_item(&self, if_still: i64) -> Option<PasteboardItem> {
            (if_still == self.0).then(|| PasteboardItem {
                types: vec![],
                text: Some("door".into()),
            })
        }
        async fn write(&self, _: String) -> Option<i64> {
            self.1
        }
    }

    #[tokio::test]
    async fn a_door_opening_is_a_change_and_old_counts_dont_read_through_it() {
        let tap = Arc::new(OneTap::default());
        let pb = LinuxPasteboard::new(tap.clone());
        let before = pb.change_count().await;
        pb.set_door(Some(Arc::new(Fixed(0, Some(1)))));
        let now = pb.change_count().await;
        assert_ne!(before, now, "same raw count, different door");
        assert!(pb.first_item(before).await.is_none());
    }

    /// A door that opens after launch (the extension enabled later, Klipper restarted) holds what
    /// was copied before: the core sees the count change, but there's nothing to send.
    #[tokio::test]
    async fn what_a_door_holds_when_it_opens_is_never_sent() {
        let pb = LinuxPasteboard::new(Arc::new(OneTap::default()));
        pb.change_count().await; // the core's look at launch
        let door = Arc::new(Counting::default());
        pb.set_door(Some(door.clone()));
        let opened = pb.change_count().await;
        assert!(
            pb.first_item(opened).await.is_none(),
            "copied before the door opened"
        );
        // A real copy afterwards is read
        door.copy("new");
        let copied = pb.change_count().await;
        assert_ne!(copied, opened);
        assert_eq!(
            pb.first_item(copied).await.unwrap().text.as_deref(),
            Some("new")
        );
    }

    /// A door that opens while the old one is being asked: the old door's count never becomes
    /// the new door's baseline, so what the new door holds is still never sent.
    #[tokio::test]
    async fn a_door_opening_mid_look_keeps_its_own_baseline() {
        struct Swapping {
            pb: Mutex<Option<Arc<LinuxPasteboard>>>,
            next: Arc<Counting>,
        }
        #[async_trait]
        impl Door for Swapping {
            async fn change_count(&self) -> i64 {
                // The new door opens while this (old) door is answering
                if let Some(pb) = crate::lock(&self.pb).take() {
                    pb.set_door(Some(self.next.clone()));
                }
                7
            }
            async fn first_item(&self, _: i64) -> Option<PasteboardItem> {
                None
            }
            async fn write(&self, _: String) -> Option<i64> {
                None
            }
        }
        let pb = Arc::new(LinuxPasteboard::new(Arc::new(OneTap::default())));
        let next = Arc::new(Counting::default());
        next.copy("copied before the new door opened");
        let old = Arc::new(Swapping {
            pb: Mutex::new(Some(pb.clone())),
            next: next.clone(),
        });
        pb.set_door(Some(old));
        pb.change_count().await; // asks the old door; the new one opens meanwhile
        let now = pb.change_count().await;
        assert!(
            pb.first_item(now).await.is_none(),
            "the new door's earlier copy is never sent"
        );
    }

    #[derive(Default)]
    struct Counting(Mutex<(i64, String)>);
    impl Counting {
        fn copy(&self, t: &str) {
            let mut s = crate::lock(&self.0);
            s.0 += 1;
            s.1 = t.into();
        }
    }
    #[async_trait]
    impl Door for Counting {
        async fn change_count(&self) -> i64 {
            crate::lock(&self.0).0
        }
        async fn first_item(&self, if_still: i64) -> Option<PasteboardItem> {
            let s = crate::lock(&self.0);
            (s.0 == if_still).then(|| PasteboardItem {
                types: vec![],
                text: Some(s.1.clone()),
            })
        }
        async fn write(&self, _: String) -> Option<i64> {
            None
        }
    }

    #[tokio::test]
    async fn a_failed_automatic_write_still_lands_one_tap_away() {
        let written = Arc::new(Mutex::new(Vec::new()));
        let w = written.clone();
        let tap = Arc::new(OneTap::new(move |t| crate::lock(&w).push(t)));
        let pb = LinuxPasteboard::new(tap);
        pb.set_door(Some(Arc::new(Fixed(5, None))));
        let seen = pb.change_count().await;
        let after = pb.write("x".into()).await;
        assert_eq!(*crate::lock(&written), vec!["x".to_string()]);
        assert_eq!(
            after, seen,
            "what was already there never reads as a new copy"
        );
    }
}
