// SPDX-License-Identifier: AGPL-3.0-only
//! What arrives from the phone: opened by the core, the Hello applied, then to the features.

use prost::Message;
use seam_core::Error as CoreError;
use seam_core::proto::v1::{Envelope, Payload, control, payload::Body};

use super::core::pause_end;
use super::{Session, Warning};
use crate::{Event, Problem};

impl Session {
    /// An envelope from the phone. False when it must not be acked (a failed save).
    pub async fn received(&mut self, envelope: Envelope) -> bool {
        let bytes = envelope.encode_to_vec();
        let opened = self.core.dev().open(&bytes);
        let message = match opened {
            Ok(m) => m,
            Err(CoreError::Duplicate) => return true, // already handled before a reconnect
            Err(CoreError::Signal(seam_core::SignalProtocolError::UntrustedIdentity(_)))
            | Err(CoreError::WrongPeer) => {
                self.core.warning = Some(Warning::UntrustedIdentity);
                self.core
                    .events
                    .push(Event::Problem(Problem::UntrustedIdentity));
                return true;
            }
            Err(_) => {
                self.core
                    .events
                    .push(Event::Problem(Problem::DroppedEnvelope));
                return true; // dropped for good: the phone can stop resending it
            }
        };
        let payload = Payload::decode(message.payload.as_slice()).ok();
        // Only a newer Hello counts (the core checks): a resent old one must not undo a newer
        // one. Applied before the save just below, with the ratchet
        if let Some(Body::Control(c)) = payload.as_ref().and_then(|p| p.body.as_ref())
            && let Some(control::Body::Hello(h)) = &c.body
            && self.core.is_confirmed_phone(&message.peer_id)
        {
            let peer = self.core.dev().edit_app_data(|d| d.take_hello(h)).flatten();
            if let Some(peer) = peer {
                // The phone's pause rides on the same counter, so a late or replayed Hello can't
                // re-pause a resumed computer, and every new connection brings the current state (SR-32-01)
                self.core.set_pause(pause_end(peer.paused_until_ms));
                if let Some(open) = peer.open_links {
                    self.core.close_links(&open);
                }
            }
        }
        let saved = self.core.storage.save_state().is_ok();
        // Handled even if the save failed: the ratchet has moved, so a redelivery in this run
        // opens as a duplicate and would be lost (SR-2-59). Not acked, so after a restart the
        // saved state opens it again.
        if let Some(payload) = payload {
            self.handle(payload, &message.peer_id).await;
        }
        if !saved {
            self.core.save_failed();
        }
        saved
    }

    async fn handle(&mut self, payload: Payload, from: &str) {
        // Features only from the paired, confirmed phone
        if !self.core.is_confirmed_phone(from) {
            return;
        }
        let Some(body) = payload.body else { return };
        let Body::Control(c) = &body else {
            self.features.receive(&body, &mut self.core).await;
            return;
        };
        match c.body {
            Some(control::Body::Hello(_)) => self.core.flush(), // a path may have opened
            Some(control::Body::Unpair(_)) => {
                self.forget(from).await;
                for link in self.core.links.values() {
                    link.close(from);
                }
            }
            _ => {}
        }
    }
}
