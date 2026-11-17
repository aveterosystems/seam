// SPDX-License-Identifier: AGPL-3.0-only
//! The clipboard feature's pasteboard on Linux (decision 0012). Wayland lets only the focused app
//! read or write the clipboard, so a background app needs a door: the GNOME Shell extension on
//! GNOME, the Clipboard portal on KDE. Until one is set up, the clipboard still works with one
//! tap: Send clipboard offers the copy here, and a copy from the phone waits for Copy.
//!
//! Every door looks the same to the core: a change counter, the current item, and a write.

use std::sync::{Arc, Mutex};

use async_trait::async_trait;
use seam_computer::features::clipboard::{Pasteboard, PasteboardItem};

/// One way to reach the computer's clipboard.
#[async_trait]
pub trait Door: Send + Sync {
    async fn change_count(&self) -> i64;
    async fn first_item(&self, if_still: i64) -> Option<PasteboardItem>;
    /// Puts `text` on the clipboard; None when this door couldn't (it's gone, or refused).
    async fn write(&self, text: String) -> Option<i64>;
}

/// Which door is open, as Settings and the window say it.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Reach {
    /// Copies cross by themselves.
    Automatic,
    /// Send clipboard, and Copy on what arrives.
    OneTap,
}

/// The pasteboard the core polls. Behind it, the automatic door when there is one, else one tap.
pub struct LinuxPasteboard {
    one_tap: Arc<OneTap>,
    door: Mutex<Option<Arc<dyn Door>>>,
    /// Counts across doors: a door opening or closing is a change of its own, so the core never
    /// compares one door's counter with another's.
    epoch: Mutex<(i64, i64)>,
}

impl LinuxPasteboard {
    pub fn new(one_tap: Arc<OneTap>) -> Self {
        Self {
            one_tap,
            door: Mutex::new(None),
            epoch: Mutex::new((0, 0)),
        }
    }

    /// An automatic door opened (Some) or closed (None).
    pub fn set_door(&self, door: Option<Arc<dyn Door>>) {
        *crate::lock(&self.door) = door;
        let mut e = crate::lock(&self.epoch);
        e.0 += 1;
    }

    pub fn reach(&self) -> Reach {
        if crate::lock(&self.door).is_some() {
            Reach::Automatic
        } else {
            Reach::OneTap
        }
    }

    fn current(&self) -> (Arc<dyn Door>, i64) {
        let door = crate::lock(&self.door)
            .clone()
            .unwrap_or_else(|| self.one_tap.clone());
        (door, crate::lock(&self.epoch).0)
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
        combine(epoch, door.change_count().await)
    }

    async fn first_item(&self, if_still: i64) -> Option<PasteboardItem> {
        let (door, epoch) = self.current();
        if if_still >> 40 != epoch {
            return None; // the door changed since the core looked
        }
        door.first_item(if_still & ((1 << 40) - 1)).await
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

    /// Send clipboard: what the window just read. Types matter: a password or a file is still
    /// never sent (the core's rules decide).
    pub fn offer(&self, types: Vec<String>, text: Option<String>) {
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

    fn item(text: &str) -> (Vec<String>, Option<String>) {
        (vec!["text/plain;charset=utf-8".into()], Some(text.into()))
    }

    #[tokio::test]
    async fn an_offer_is_one_new_copy() {
        let tap = Arc::new(OneTap::default());
        let pb = LinuxPasteboard::new(tap.clone());
        let before = pb.change_count().await;
        let (t, x) = item("hello");
        tap.offer(t, x);
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
        let pb = LinuxPasteboard::new(tap.clone());
        let (t, x) = item("mine");
        tap.offer(t, x);
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
        assert_eq!(pb.reach(), Reach::Automatic);
        let now = pb.change_count().await;
        assert_ne!(before, now, "same raw count, different door");
        assert!(pb.first_item(before).await.is_none());
        assert_eq!(
            pb.first_item(now).await.unwrap().text.as_deref(),
            Some("door")
        );
        pb.set_door(None);
        assert_eq!(pb.reach(), Reach::OneTap);
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
