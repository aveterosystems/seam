// SPDX-License-Identifier: AGPL-3.0-only
//! The links to the phone, as the carriers report them.

use std::sync::Arc;

use seam_core::links::Path;

use super::Session;
use super::core::delivered;
use crate::lan::{LanEvent, LanSender, PAIRING};

impl Session {
    /// A carrier started (or None: stopped).
    pub fn set_link(&mut self, link: Option<Arc<dyn LanSender>>, path: Path) {
        match link {
            Some(l) => {
                self.core.links.insert(path, l);
            }
            None => {
                self.core.links.remove(&path);
                if self.core.pairing.path == Some(path) {
                    self.core.pairing.path = None;
                }
                self.core.path_closed(path);
            }
        }
    }

    /// Only the carrier currently set for `path` (tests and `Paths` compare it).
    pub fn link(&self, path: Path) -> Option<&Arc<dyn LanSender>> {
        self.core.links.get(&path)
    }

    pub async fn link_event(&mut self, event: LanEvent, path: Path) {
        let phone = self.core.phone.as_ref().map(|p| p.id.clone());
        let is_phone = |peer: &str| phone.as_deref() == Some(peer);
        match event {
            LanEvent::Authenticated(peer) if peer == PAIRING => self.core.pairing_linked(path),
            LanEvent::Authenticated(peer) => {
                if !is_phone(&peer) {
                    // No longer the phone
                    if let Some(l) = self.core.links.get(&path) {
                        l.close(&peer);
                    }
                    return;
                }
                self.core.up.insert(path);
                // A new link can replace the old one without a close event: what the old one
                // never got acked goes again
                if !self.core.outbox.sent().is_empty() {
                    self.core.outbox.requeue_sent();
                    self.core.save_outbox();
                }
                self.path_came_up();
            }
            LanEvent::Closed(peer) if peer == PAIRING => self.pairing_unlinked(path).await,
            LanEvent::Closed(peer) => {
                if is_phone(&peer) {
                    self.core.path_closed(path);
                }
            }
            LanEvent::Envelope(peer, e) if peer == PAIRING => self.core.pairing_envelope(e, path),
            LanEvent::Envelope(peer, e) => {
                if !is_phone(&peer) {
                    return;
                }
                // Acked on the same link once handled and saved (duplicates too); after a failed
                // save it isn't, so the phone sends it again after this computer restarts
                let msg_id = e.msg_id.clone();
                if self.received(e).await
                    && let Some(l) = self.core.links.get(&path)
                {
                    l.send(&delivered(msg_id), &peer);
                }
            }
            LanEvent::Delivered(peer, msg_id) if peer == PAIRING => {
                self.pairing_acked(msg_id, path)
            }
            LanEvent::Delivered(peer, msg_id) => {
                if !is_phone(&peer) {
                    return;
                }
                let c = &mut self.core;
                c.outbox.remove(&msg_id);
                c.hellos.remove(&msg_id);
                if let Some((_, t)) = c.ack_waits.remove(&msg_id) {
                    t.cancel();
                }
                c.save_outbox();
            }
            LanEvent::BundleRequest(peer) => {
                if peer == PAIRING {
                    self.core.send_bundle(path);
                }
            }
        }
    }

    /// A path to the phone came up: what waited, then this computer's switches, then the features'.
    pub(super) fn path_came_up(&mut self) {
        if self.core.phone.is_none() {
            return;
        }
        self.core.flush();
        self.core.send_hello();
        self.features.connected(&mut self.core);
    }
}
