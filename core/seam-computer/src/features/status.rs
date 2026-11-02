// SPDX-License-Identifier: AGPL-3.0-only
//! The phone's battery and the like, for the computer's screens; and the computer's battery, for
//! the phone.

use std::sync::Arc;

use seam_core::proto::v1::{self, Payload, payload::Body};

use super::{Host, Reach};

/// The computer's battery: None on a computer without one, which sends nothing.
pub trait Battery: Send + Sync {
    fn read(&self) -> Option<v1::Status>;
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Command {
    /// This computer's battery changed.
    BatteryChanged,
}

#[derive(Debug, Clone, PartialEq)]
pub struct View {
    /// The phone's battery and the like, as it last sent them.
    pub status: Option<v1::Status>,
    /// When it sent them (unix ms), so a stale level can say how old it is.
    pub at: Option<i64>,
}

pub struct Status {
    battery: Arc<dyn Battery>,
    status: Option<v1::Status>,
    /// When the phone last sent it (unix ms), so a stale level can say how old it is.
    status_at: Option<i64>,
}

impl Status {
    pub fn new(battery: Arc<dyn Battery>) -> Self {
        Self {
            battery,
            status: None,
            status_at: None,
        }
    }

    pub fn status(&self) -> Option<&v1::Status> {
        self.status.as_ref()
    }

    pub fn status_at(&self) -> Option<i64> {
        self.status_at
    }

    pub fn command(&mut self, command: Command, host: &mut dyn Host) {
        match command {
            Command::BatteryChanged => self.own_changed(host),
        }
    }

    pub fn view(&self) -> View {
        View {
            status: self.status.clone(),
            at: self.status_at,
        }
    }

    pub fn receive(&mut self, body: &Body, host: &mut dyn Host) {
        if let Body::Status(s) = body {
            let mut s = s.clone();
            s.battery_pct = s.battery_pct.min(100); // never nonsense on screen
            self.status = Some(s);
            self.status_at = Some(host.now_ms());
        }
    }

    /// This computer's battery changed: the phone hears it if it's connected (and not paused).
    /// Otherwise it waits for the next connection, which sends it fresh.
    fn own_changed(&mut self, host: &mut dyn Host) {
        if host.reach() != Reach::Connected {
            return;
        }
        let Some(mut s) = self.battery.read() else {
            return;
        };
        s.battery_pct = s.battery_pct.min(100);
        host.send(Payload {
            body: Some(Body::Status(s)),
        });
    }

    pub fn connected(&mut self, host: &mut dyn Host) {
        self.own_changed(host);
    }

    pub fn reset(&mut self) {
        self.status = None;
        self.status_at = None;
    }
}

#[cfg(test)]
mod tests;
