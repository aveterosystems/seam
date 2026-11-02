// SPDX-License-Identifier: AGPL-3.0-only
//! Confirming a pairing: the PairAccept, saved before it goes, and undone if it never arrives.

use prost::Message;
use seam_core::PairingRequest;
use seam_core::links::{AppData, Path};
use seam_core::proto::v1::{self, Envelope};

use super::core::{Core, control, deliver};
use super::pairing::{ACCEPT_DEADLINE, PendingAccept};
use super::{Phone, Session};
use crate::lan::PAIRING;
use crate::pairing::PairingFailure;
use crate::timers::Due;
use crate::{Event, Problem};

impl Core {
    pub(super) fn fail_accept(&mut self, pending: PendingAccept) {
        self.events.push(Event::Problem(Problem::PairingFailed));
        self.undo_pairing(&pending.request.peer_id, pending.previous);
        self.flow.fail(PairingFailure::Unreachable);
    }

    pub(super) fn finish_pairing(&mut self, request: PairingRequest) {
        // Only a phone the core really holds as confirmed counts as paired
        let peer = {
            let d = self.dev();
            d.peers()
                .get(&request.peer_id)
                .filter(|p| p.confirmed)
                .map(|p| Phone {
                    id: request.peer_id.clone(),
                    name: p.name.clone(),
                    safety_code: d.safety_code(&request.peer_id).unwrap_or_default(),
                    paired_at: None,
                })
        };
        let Some(peer) = peer else {
            self.flow.fail(PairingFailure::DidntFinish);
            return;
        };
        if self.phone.as_ref().is_some_and(|old| old.id != peer.id) {
            self.up.clear(); // the old phone's links no longer count
        }
        self.phone = Some(peer);
        self.paired_at = Some(self.timers.now_ms());
        self.save_preferences();
        self.flow.finish(request.device_name);
        self.close_pairing(); // the phone comes back as itself, on the same service
        self.send_hello();
    }

    /// A phone confirmed here whose PairAccept never got out: forgotten, the app data put back.
    /// It never became `phone`, so no feature holds anything of it.
    pub(super) fn undo_pairing(&mut self, peer_id: &str, previous: Option<AppData>) {
        {
            let mut d = self.dev();
            d.remove_peer(peer_id);
            match previous {
                Some(p) => {
                    let _ = d.set_app_data(&p);
                }
                None => d.clear_app_data(),
            }
        }
        self.save_state();
        self.close_pairing();
    }

    pub(super) fn pairing_expired(&mut self) {
        if self.flow.expire() {
            self.close_pairing();
        }
    }

    /// Saved before the phone hears it's paired: never tell it so unless this computer will
    /// remember. Returns the PairAccept, or None when the pairing was given up.
    pub(super) fn seal_accept(
        &mut self,
        request: &PairingRequest,
    ) -> Option<(Envelope, Path, Option<AppData>)> {
        let previous = self.dev().app_data();
        // The phone left while the user compared codes: nothing to tell
        let reachable = self.pairing.path.filter(|p| self.links.contains_key(p));
        let (Some(service), Some(path)) = (self.pairing.service.clone(), reachable) else {
            self.dev().reject_pairing(&request.peer_id);
            self.close_pairing();
            self.flow.fail(PairingFailure::Unreachable);
            return None;
        };
        let accept = control(v1::control::Body::PairAccept(Default::default())).encode_to_vec();
        let sealed = (|| {
            let mut d = self.dev();
            d.confirm_pairing(&request.peer_id).ok()?;
            let sealed = d.seal(&request.peer_id, &accept, 0).ok()?;
            d.edit_app_data(AppData::forget_peer); // a new pairing starts the Hello counts again
            d.set_app_data(&AppData::new(
                service.secret.clone(),
                String::new(),
                service.clients[0].clone(),
            ))
            .ok()?;
            drop(d);
            self.storage.save_state().ok()?;
            Envelope::decode(sealed.as_slice()).ok()
        })();
        let Some(envelope) = sealed else {
            self.events.push(Event::Problem(Problem::PairingFailed));
            self.undo_pairing(&request.peer_id, previous);
            self.flow.fail(PairingFailure::NotSaved);
            return None;
        };
        Some((envelope, path, previous))
    }
}

impl Session {
    /// Pair was clicked. Safe to call twice: only the first call does anything.
    pub(super) async fn confirm_pairing_now(&mut self) {
        let Some(request) = self.core.flow.begin_confirming() else {
            return;
        };
        let Some((envelope, path, previous)) = self.core.seal_accept(&request) else {
            return;
        };
        // Anything held for a previous phone goes before the new one can send (SR-29-10)
        self.features.reset().await;
        let c = &mut self.core;
        let msg_id = envelope.msg_id.clone();
        let sent = c
            .links
            .get(&path)
            .is_some_and(|l| l.send(&deliver(envelope), PAIRING));
        let pending = PendingAccept {
            msg_id: msg_id.clone(),
            request,
            previous,
        };
        if !sent {
            return c.fail_accept(pending);
        }
        c.pairing.pending = Some(pending);
        if let Some(t) = c.expiry.take() {
            t.cancel();
        }
        c.expiry = Some(c.timers.after(ACCEPT_DEADLINE, Due::AcceptOverdue(msg_id)));
    }

    pub(super) async fn pairing_unlinked(&mut self, path: Path) {
        self.core.pairing_unlinked(path);
    }

    pub(super) fn pairing_acked(&mut self, msg_id: Vec<u8>, path: Path) {
        self.core.pairing_acked(msg_id, path);
    }
}
