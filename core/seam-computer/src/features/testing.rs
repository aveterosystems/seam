// SPDX-License-Identifier: AGPL-3.0-only
//! A host for one feature on its own: it records what the feature sends and says, and its reach
//! and clock are whatever the test sets.

use std::collections::BTreeSet;
use std::time::Duration;

use seam_core::proto::v1::{Payload, payload::Body};

use super::{Host, Reach};
use crate::Event;
use crate::timers::{Due, TimerToken};

pub struct FakeHost {
    pub sent: Vec<Payload>,
    pub reach: Reach,
    pub now: i64,
    pub hellos: usize,
    pub withheld: BTreeSet<String>,
    pub events: Vec<Event>,
}

impl Default for FakeHost {
    fn default() -> Self {
        Self {
            sent: Vec::new(),
            reach: Reach::Connected,
            now: 1_000_000,
            hellos: 0,
            withheld: BTreeSet::new(),
            events: Vec::new(),
        }
    }
}

impl FakeHost {
    /// The bodies sent, in order.
    pub fn bodies(&self) -> Vec<&Body> {
        self.sent.iter().filter_map(|p| p.body.as_ref()).collect()
    }
}

impl Host for FakeHost {
    fn send(&mut self, payload: Payload) {
        self.sent.push(payload);
    }
    fn reach(&self) -> Reach {
        self.reach
    }
    fn send_hello(&mut self) {
        self.hellos += 1;
    }
    fn set_withheld(&mut self, capability: &str, off: bool) {
        if off {
            self.withheld.insert(capability.to_owned());
        } else {
            self.withheld.remove(capability);
        }
    }
    fn now_ms(&self) -> i64 {
        self.now
    }
    fn after(&self, _: Duration, _: Due) -> TimerToken {
        TimerToken::new(|| {})
    }
    fn event(&mut self, event: Event) {
        self.events.push(event);
    }
}
