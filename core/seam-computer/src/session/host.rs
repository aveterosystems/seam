// SPDX-License-Identifier: AGPL-3.0-only
//! The session core as the features' host.

use std::time::Duration;

use seam_core::proto::v1::Payload;

use super::core::Core;
use crate::Event;
use crate::features::{Host, Reach};
use crate::timers::{Due, TimerToken};

impl Host for Core {
    fn send(&mut self, payload: Payload) {
        self.send_to_phone(payload, false);
    }

    fn reach(&self) -> Reach {
        if self.phone.is_none() || self.paused() {
            return Reach::Off;
        }
        if self.route(false).is_none() {
            Reach::Away
        } else {
            Reach::Connected
        }
    }

    fn send_hello(&mut self) {
        Core::send_hello(self);
    }

    fn set_withheld(&mut self, capability: &str, off: bool) {
        if off {
            self.withheld.insert(capability.to_owned());
        } else {
            self.withheld.remove(capability);
        }
    }

    fn now_ms(&self) -> i64 {
        self.timers.now_ms()
    }

    fn after(&self, delay: Duration, due: Due) -> TimerToken {
        self.timers.after(delay, due)
    }

    fn event(&mut self, event: Event) {
        self.events.push(event);
    }
}
