// SPDX-License-Identifier: AGPL-3.0-only
//! Delivery to the phone: sealed, saved, then sent; kept until the phone's Delivered.

use prost::Message;
use seam_core::catalogue;
use seam_core::hello::Facts;
use seam_core::links::Path;
use seam_core::proto::v1::{self, Envelope, Payload};

use super::ACK_TIMEOUT;
use super::core::{Core, control, deliver};
use crate::timers::Due;
use crate::{Event, Problem};

impl Core {
    /// Sealed, saved, then sent: never sends what a failed save would forget (SR-2-11). How long
    /// it may wait, and whether it waits out the phone's pause, is the core's catalogue.
    pub(super) fn send_to_phone(&mut self, payload: Payload, hello: bool) {
        let Some(phone) = self.phone.clone() else {
            return;
        };
        let rules = catalogue::rules(&payload);
        // Paused on the phone: nothing but control goes, and nothing is kept for later
        if self.paused() && rules.paused {
            return;
        }
        let sealed = self
            .dev()
            .seal(&phone.id, &payload.encode_to_vec(), rules.ttl_s);
        let Ok(sealed) = sealed else {
            self.events.push(Event::Problem(Problem::SealFailed));
            return;
        };
        if !self.save_state() {
            return;
        }
        let Ok(envelope) = Envelope::decode(sealed.as_slice()) else {
            self.events.push(Event::Problem(Problem::SealFailed));
            return;
        };
        if hello {
            self.hellos.insert(envelope.msg_id.clone()); // may take any path this computer allows
        }
        // Saved to the outbox first, then onto the best path; otherwise it waits there
        self.outbox.add(envelope.clone());
        if self.save_outbox() {
            self.attempt(&envelope);
        } // else it stays in memory; the next flush tries again
    }

    /// Never a disallowed path, and never a path before it's on disk. Kept in the outbox until the
    /// phone's Delivered; resent if the link drops first.
    fn attempt(&mut self, envelope: &Envelope) {
        let hello = self.hellos.contains(&envelope.msg_id);
        let (Some(phone), Some(path)) = (self.phone.as_ref(), self.route(hello)) else {
            return;
        };
        let Some(link) = self.links.get(&path) else {
            return;
        };
        if !link.send(&deliver(envelope.clone()), &phone.id) {
            return;
        }
        self.outbox.mark_sent(&envelope.msg_id);
        self.save_outbox();
        let id = envelope.msg_id.clone();
        if let Some((_, t)) = self.ack_waits.remove(&id) {
            t.cancel();
        }
        let timer = self.timers.after(
            ACK_TIMEOUT,
            Due::AckOverdue {
                msg_id: id.clone(),
                path,
            },
        );
        self.ack_waits.insert(id, (path, timer));
    }

    /// No ack for this long on `path` while another link is up: that link is dead without having
    /// said so. Closed, so what it never confirmed goes on the other one (and the phone is told).
    pub(super) fn ack_overdue(&mut self, msg_id: &[u8], path: Path) {
        // Acked, or sent again since on another link
        if self.ack_waits.get(msg_id).map(|w| w.0) != Some(path) {
            return;
        }
        self.ack_waits.remove(msg_id);
        let unacked = self.outbox.sent().iter().any(|e| e.msg_id == msg_id);
        let Some(phone) = self.phone.clone() else {
            return;
        };
        if !unacked || !self.up.contains(&path) || self.up.len() < 2 {
            return;
        }
        if let Some(l) = self.links.get(&path) {
            l.close(&phone.id);
        }
        self.path_closed(path);
    }

    /// Everything waiting, oldest first, now that a path may have opened.
    pub(super) fn flush(&mut self) {
        self.outbox.drop_expired(self.timers.now_ms());
        // Nothing goes out that isn't on disk (an earlier save may have failed)
        if !self.save_outbox() {
            return;
        }
        for envelope in self.outbox.waiting().to_vec() {
            self.attempt(&envelope);
        }
    }

    pub(super) fn save_outbox(&mut self) -> bool {
        if self.storage.outbox().save(&self.outbox).is_ok() {
            return true;
        }
        self.save_failed();
        false
    }

    /// Anything sent but not acked goes again, on whatever path is allowed now.
    pub(super) fn path_closed(&mut self, path: Path) {
        if !self.up.remove(&path) {
            return;
        }
        if !self.outbox.sent().is_empty() {
            self.outbox.requeue_sent();
            self.save_outbox();
            self.flush();
        }
        // The phone may not know this link is gone: the Hello lists the ones still open
        if !self.up.is_empty() {
            self.send_hello();
        }
    }

    /// The phone's Hello lists the links it has open. One of ours that it doesn't list is dead on
    /// its side (it left the Wi‑Fi): close it, so nothing more is sent into it.
    pub(super) fn close_links(&mut self, open: &[Path]) {
        let Some(phone) = self.phone.clone() else {
            return;
        };
        for path in self.up_paths() {
            if !open.contains(&path) {
                if let Some(l) = self.links.get(&path) {
                    l.close(&phone.id);
                }
                self.path_closed(path);
            }
        }
    }

    /// Its seq is counted in the core's app data and saved with the seal, before it leaves: a
    /// Hello never goes with a seq a restart could hand out again.
    pub(super) fn send_hello(&mut self) {
        if self.phone.is_none() {
            return;
        }
        let facts = Facts {
            app_version: self.app_version.clone(),
            platform: self.platform.clone(),
            enabled: self.enabled(),
            open: self.up_paths(),
            paused_until_ms: 0, // a computer never pauses: only the phone does
            capabilities: catalogue::CAPABILITIES
                .iter()
                .map(|c| c.to_string())
                .filter(|c| !self.withheld.contains(c))
                .collect(),
        };
        let hello = self.dev().edit_app_data(|d| d.hello(&facts));
        if let Some(hello) = hello {
            self.send_to_phone(hello, true);
        }
    }

    pub(super) fn reconnect(&mut self) -> bool {
        // Never a path this computer's switches have off, like Unpair (SR-55-09)
        let enabled = self.enabled();
        let via: Vec<Path> = self
            .up_paths()
            .into_iter()
            .filter(|p| enabled.contains(p))
            .collect();
        let Some(phone) = self.phone.clone() else {
            return false;
        };
        if via.is_empty() {
            return false;
        }
        let ask = control(v1::control::Body::Reconnect(Default::default())).encode_to_vec();
        let sealed = self.dev().seal(&phone.id, &ask, 0);
        if let Some(envelope) = sealed
            .ok()
            .and_then(|s| Envelope::decode(s.as_slice()).ok())
        {
            // Saved before it leaves: a restart must never seal again with the same key (SR-2-11)
            if self.save_state() {
                for path in &via {
                    if let Some(l) = self.links.get(path) {
                        l.send(&deliver(envelope.clone()), &phone.id);
                    }
                }
            }
        }
        for path in via {
            if let Some(l) = self.links.get(&path) {
                l.close(&phone.id); // gracefully: the ask is written first
            }
            self.path_closed(path);
        }
        true
    }
}
