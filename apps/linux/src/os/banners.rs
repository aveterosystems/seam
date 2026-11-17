// SPDX-License-Identifier: AGPL-3.0-only
//! The phone's notifications as desktop banners, through GNotification (the Notification portal
//! inside Flatpak). Its buttons and Reply come back as the app's actions; a dismiss from the
//! phone withdraws the banner here.
//!
//! GNotification can't take typed replies or say when a banner was cleared: Reply opens the
//! window at that notification's reply field, and clearing one here is done from the window.

use async_trait::async_trait;
use seam_computer::features::notifications::NotificationCentre;
use seam_core::proto::v1::Notification;

use crate::app::Msg;

/// What the core asks of the banners, carried to the main thread.
#[derive(Clone)]
pub enum Banner {
    Show(Notification, Option<Vec<u8>>),
    Dismiss(String),
    RemoveAll,
}

// A notification's words and icon never print (rule 6)
impl std::fmt::Debug for Banner {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(match self {
            Banner::Show(..) => "Show(..)",
            Banner::Dismiss(_) => "Dismiss(..)",
            Banner::RemoveAll => "RemoveAll",
        })
    }
}

pub struct Banners {
    tx: async_channel::Sender<Msg>,
}

impl Banners {
    pub fn new(tx: async_channel::Sender<Msg>) -> Self {
        Self { tx }
    }
}

#[async_trait]
impl NotificationCentre for Banners {
    async fn show(&self, notification: Notification, icon: Option<Vec<u8>>) {
        let _ = self.tx.send(Msg::Banner(Banner::Show(notification, icon))).await;
    }

    async fn dismiss(&self, key: String) {
        let _ = self.tx.send(Msg::Banner(Banner::Dismiss(key))).await;
    }

    async fn remove_all(&self) {
        let _ = self.tx.send(Msg::Banner(Banner::RemoveAll)).await;
    }
}

/// A banner as the desktop draws it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Spec {
    pub id: String,
    pub title: String,
    pub body: String,
    /// Label, action, and the notification key with the button's id.
    pub buttons: Vec<(String, &'static str, (String, String))>,
}

const PREFIX: &str = "phone:";

pub fn id(key: &str) -> String {
    format!("{PREFIX}{key}")
}

/// Title and body as the Mac shows them: who or what, then the text; the app's name when the
/// title is empty. Buttons: Reply first (opens the window), then the phone's own, at most three
/// in all, as most desktops show no more.
pub fn spec(n: &Notification) -> Spec {
    let title = if n.title.trim().is_empty() { n.app_name.clone() } else { format!("{} · {}", n.title, n.app_name) };
    let mut buttons = Vec::new();
    if n.can_reply {
        buttons.push(("Reply".to_string(), "app.reply", (n.key.clone(), String::new())));
    }
    for b in &n.buttons {
        buttons.push((b.title.clone(), "app.phone-button", (n.key.clone(), b.id.clone())));
    }
    buttons.truncate(3);
    Spec {
        id: id(&n.key),
        title,
        body: n.text.clone(),
        buttons,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use seam_core::proto::v1::NotificationButton;

    fn n() -> Notification {
        Notification {
            key: "0|chat|1".into(),
            app_name: "Chat".into(),
            title: "Ana".into(),
            text: "On my way".into(),
            can_reply: true,
            buttons: vec![
                NotificationButton { id: "b1".into(), title: "Mark read".into() },
                NotificationButton { id: "b2".into(), title: "Mute".into() },
                NotificationButton { id: "b3".into(), title: "Archive".into() },
            ],
            ..Default::default()
        }
    }

    #[test]
    fn reply_first_then_the_phones_buttons_at_most_three() {
        let s = spec(&n());
        assert_eq!(s.id, "phone:0|chat|1");
        assert_eq!(s.title, "Ana · Chat");
        assert_eq!(s.body, "On my way");
        let labels: Vec<_> = s.buttons.iter().map(|b| b.0.as_str()).collect();
        assert_eq!(labels, ["Reply", "Mark read", "Mute"]);
        assert_eq!(s.buttons[1].2, ("0|chat|1".to_string(), "b1".to_string()));
    }

    #[test]
    fn an_empty_title_shows_the_apps_name() {
        let mut x = n();
        x.title = "  ".into();
        x.can_reply = false;
        assert_eq!(spec(&x).title, "Chat");
        assert_eq!(spec(&x).buttons[0].1, "app.phone-button");
    }

    #[test]
    fn debug_never_prints_contents() {
        let shown = format!("{:?}", Banner::Show(n(), None));
        assert!(!shown.contains("On my way") && !shown.contains("Ana"));
    }
}
