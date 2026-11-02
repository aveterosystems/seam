// SPDX-License-Identifier: AGPL-3.0-only
//! The computer side of each feature (decision 0011): it drives the feature's shared rules in
//! `seam-core` and reaches the OS only through its interface. A feature never uses another's code.
//! Adding one means a module here (with its `Command` and `View`), a field in [`Features`], and
//! its line in each hook below.

pub mod clipboard;
pub mod notifications;
pub mod status;
#[cfg(test)]
pub(crate) mod testing;

use std::time::Duration;

use seam_core::proto::v1::{Payload, payload::Body};

use crate::timers::{Due, TimerToken};

/// Whether what happens here now can go to the phone.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Reach {
    /// No paired phone, or it has Seam paused: nothing is captured.
    Off,
    /// Paired, but no path to it now.
    Away,
    Connected,
}

/// What a feature can ask of the session hosting it.
pub trait Host: Send {
    /// Sealed, saved, then sent; dropped with no phone, or while it has Seam paused (except control).
    fn send(&mut self, payload: Payload);
    fn reach(&self) -> Reach;
    /// Something a feature says in the Hello changed: the phone hears now.
    fn send_hello(&mut self);
    /// A capability this feature has turned off (`off`) or back on, left out of the Hello.
    fn set_withheld(&mut self, capability: &str, off: bool);
    fn now_ms(&self) -> i64;
    fn after(&self, delay: Duration, due: Due) -> TimerToken;
    fn event(&mut self, event: crate::Event);
}

/// What the user does to a feature, from the app's screens.
#[derive(Debug, Clone, PartialEq)]
pub enum Command {
    Clipboard(clipboard::Command),
    Notifications(notifications::Command),
    Status(status::Command),
}

/// What each feature shows.
#[derive(Debug, Clone, PartialEq)]
pub struct Views {
    pub clipboard: clipboard::View,
    pub notifications: notifications::View,
    pub status: status::View,
}

/// Every feature, in the order each hears of things.
pub struct Features {
    pub clipboard: clipboard::Clipboard,
    pub notifications: notifications::Notifications,
    pub status: status::Status,
}

impl Features {
    pub async fn start(&mut self, host: &mut dyn Host) {
        self.clipboard.start(host).await;
    }

    /// Each feature picks out the payloads it handles and ignores the rest.
    pub async fn receive(&mut self, body: &Body, host: &mut dyn Host) {
        self.clipboard.receive(body, host).await;
        self.notifications.receive(body, host).await;
        self.status.receive(body, host);
    }

    /// A path to the phone came up.
    pub fn connected(&mut self, host: &mut dyn Host) {
        self.clipboard.connected(host);
        self.status.connected(host);
    }

    /// The phone was forgotten (or replaced): nothing from it may stay.
    pub async fn reset(&mut self) {
        self.clipboard.reset();
        self.notifications.reset().await;
        self.status.reset();
    }

    pub async fn command(&mut self, command: Command, host: &mut dyn Host) {
        match command {
            Command::Clipboard(c) => self.clipboard.command(c, host).await,
            Command::Notifications(c) => self.notifications.command(c, host).await,
            Command::Status(c) => self.status.command(c, host),
        }
    }

    pub fn view(&self) -> Views {
        Views {
            clipboard: self.clipboard.view(),
            notifications: self.notifications.view(),
            status: self.status.view(),
        }
    }

    /// The session is being replaced: timers off.
    pub fn stop(&mut self) {
        self.clipboard.stop();
    }
}
