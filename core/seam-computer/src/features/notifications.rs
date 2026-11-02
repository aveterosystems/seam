// SPDX-License-Identifier: AGPL-3.0-only
//! The phone's notifications as banners here, what's done to them sent back, and the phone's
//! notification settings (the phone owns them; the computer asks it to change them).

use std::collections::{HashMap, HashSet};
use std::sync::Arc;

use async_trait::async_trait;
use seam_core::proto::v1::notification_action::Action;
use seam_core::proto::v1::{
    Notification, NotificationAction, NotificationSettings, NotificationSettingsChange, Payload,
    payload::Body,
};

use super::Host;

/// Matches the phone's limit per message (NotificationsFeature.MAX_ICONS).
const ICON_BATCH: usize = 40;
/// From the phone, but still checked: a small PNG or nothing.
const MAX_ICON: usize = 256 * 1024;
const MAX_SHOWN: usize = 200;
const PNG: &[u8] = b"\x89PNG\r\n\x1a\n";

/// A PNG's signature, then its image header (IHDR, 13 bytes) as the first chunk: what every PNG
/// starts with. The app still draws only what decodes.
fn looks_like_png(b: &[u8]) -> bool {
    b.len() > 33 && b.starts_with(PNG) && b[8..16] == *b"\x00\x00\x00\x0dIHDR"
}

/// The computer's notification centre, as the notifications feature sees it.
#[async_trait]
pub trait NotificationCentre: Send + Sync {
    /// `icon`: the phone app's icon PNG.
    async fn show(&self, notification: Notification, icon: Option<Vec<u8>>);
    async fn dismiss(&self, key: String);
    /// Every banner from the phone.
    async fn remove_all(&self);
}

fn dismissed(a: &NotificationAction) -> bool {
    a.action == Some(Action::Dismissed(true))
}

#[derive(Debug, Clone, PartialEq)]
pub enum Command {
    /// Asks the phone to change its settings, shown here at once.
    Change(NotificationSettingsChange),
    /// A button, reply or dismiss on one of the phone's notifications, here.
    Act(NotificationAction),
    /// The main window's dismiss: the banner goes too.
    Dismiss(String),
}

#[derive(Debug, Clone, PartialEq)]
pub struct View {
    /// The phone's notifications that are still up, newest first.
    pub shown: Vec<Notification>,
    /// As the phone last sent them, without icons.
    pub settings: Option<NotificationSettings>,
    /// Goes up when the icons change, so the app copies them only then.
    pub icons_version: u64,
    /// Counts notifications arriving from the phone, so the bell rings once for each.
    pub arrivals: u64,
}

pub struct Notifications {
    centre: Arc<dyn NotificationCentre>,
    /// As the phone last sent them, without icons. Memory only.
    settings: Option<NotificationSettings>,
    /// Phone apps' icon PNGs, from the phone when asked for. Memory only.
    icons: HashMap<String, Vec<u8>>,
    /// The phone's notifications that are still up, newest first (memory only).
    shown: Vec<Notification>,
    /// Counts notifications arriving from the phone, so the bell rings once for each.
    arrivals: u64,
    /// Asked once per launch each, so an app whose icon the phone can't draw isn't asked forever.
    asked: HashSet<String>,
    /// Goes up whenever `icons` changes, so the app copies them only then.
    icons_version: u64,
}

impl Notifications {
    pub fn new(centre: Arc<dyn NotificationCentre>) -> Self {
        Self {
            centre,
            settings: None,
            icons: HashMap::new(),
            shown: Vec::new(),
            arrivals: 0,
            asked: HashSet::new(),
            icons_version: 0,
        }
    }

    pub fn settings(&self) -> Option<&NotificationSettings> {
        self.settings.as_ref()
    }

    pub fn icons(&self) -> &HashMap<String, Vec<u8>> {
        &self.icons
    }

    pub fn icons_version(&self) -> u64 {
        self.icons_version
    }

    pub fn shown(&self) -> &[Notification] {
        &self.shown
    }

    pub fn arrivals(&self) -> u64 {
        self.arrivals
    }

    pub async fn command(&mut self, command: Command, host: &mut dyn Host) {
        match command {
            Command::Change(c) => self.change(c, host),
            Command::Act(a) => self.act(a, host),
            Command::Dismiss(key) => self.dismiss(key, host).await,
        }
    }

    pub fn view(&self) -> View {
        View {
            shown: self.shown.clone(),
            settings: self.settings.clone(),
            icons_version: self.icons_version,
            arrivals: self.arrivals,
        }
    }

    pub async fn receive(&mut self, body: &Body, host: &mut dyn Host) {
        match body {
            Body::Notification(n) => {
                self.centre
                    .show(n.clone(), self.icons.get(&n.app_id).cloned())
                    .await;
                // An update to one that's up (a new message in a chat) replaces it
                let new = !self
                    .shown
                    .iter()
                    .any(|s| s.key == n.key && s.posted_at == n.posted_at);
                self.shown.retain(|s| s.key != n.key);
                self.shown.insert(0, n.clone());
                self.shown.truncate(MAX_SHOWN);
                if new {
                    self.arrivals += 1;
                    host.event(crate::Event::NotificationFromPhone {
                        app_name: n.app_name.clone(),
                    });
                }
            }
            Body::NotificationAction(a) if dismissed(a) => {
                self.centre.dismiss(a.key.clone()).await;
                self.shown.retain(|s| s.key != a.key);
            }
            Body::NotificationSettings(s) => self.received(s.clone(), host),
            _ => {}
        }
    }

    /// Asks the phone to change its settings, showing the change here at once; the phone's
    /// answer confirms it.
    fn change(&mut self, change: NotificationSettingsChange, host: &mut dyn Host) {
        if let Some(s) = &mut self.settings {
            if let Some(v) = change.enabled {
                s.enabled = v;
            }
            if let Some(v) = change.dismiss_both {
                s.dismiss_both = v;
            }
            if let Some(v) = change.quiet_while_using_phone {
                s.quiet_while_using_phone = v;
            }
            if let Some(v) = change.paused_until {
                s.paused_until = v;
            }
            let modes: HashMap<_, _> = change.apps.iter().map(|a| (&a.app_id, a.mode)).collect();
            for app in &mut s.apps {
                if let Some(&mode) = modes.get(&app.app_id) {
                    app.mode = mode;
                }
            }
        }
        host.send(Payload {
            body: Some(Body::NotificationSettingsChange(change)),
        });
    }

    /// What the user did to a banner or the main window here, for the phone. A typed reply waits
    /// for the phone like any message; a stale dismiss or button press could hit a later
    /// notification that reuses the key, so it expires sooner (the core's catalogue, SR-19-03).
    fn act(&mut self, action: NotificationAction, host: &mut dyn Host) {
        if dismissed(&action) {
            self.shown.retain(|s| s.key != action.key);
            if self.settings.as_ref().is_some_and(|s| !s.dismiss_both) {
                return;
            }
        }
        host.send(Payload {
            body: Some(Body::NotificationAction(action)),
        });
    }

    /// The main window's dismiss: the banner goes too.
    async fn dismiss(&mut self, key: String, host: &mut dyn Host) {
        self.centre.dismiss(key.clone()).await;
        self.act(
            NotificationAction {
                key,
                action: Some(Action::Dismissed(true)),
            },
            host,
        );
    }

    pub async fn reset(&mut self) {
        self.centre.remove_all().await;
        self.shown.clear();
        self.settings = None;
        self.icons.clear();
        self.icons_version += 1;
        self.asked.clear();
    }

    fn received(&mut self, mut s: NotificationSettings, host: &mut dyn Host) {
        for app in &mut s.apps {
            let png = std::mem::take(&mut app.icon_png);
            if !png.is_empty()
                && png.len() <= MAX_ICON
                && looks_like_png(&png)
                && self.icons.get(&app.app_id) != Some(&png)
            {
                self.icons.insert(app.app_id.clone(), png);
                self.icons_version += 1;
            }
        }
        // In batches the phone answers in one message; each answer brings the next request
        let missing: Vec<String> = s
            .apps
            .iter()
            .map(|a| &a.app_id)
            .filter(|id| !self.icons.contains_key(*id) && !self.asked.contains(*id))
            .take(ICON_BATCH)
            .cloned()
            .collect();
        self.settings = Some(s);
        if missing.is_empty() {
            return;
        }
        self.asked.extend(missing.iter().cloned());
        host.send(Payload {
            body: Some(Body::NotificationSettingsChange(
                NotificationSettingsChange {
                    need_icons: missing,
                    ..Default::default()
                },
            )),
        });
    }
}

#[cfg(test)]
mod tests;
