// SPDX-License-Identifier: AGPL-3.0-only
//! Pairing over the direct channel (docs/tor/13-implementation-contract.md, "Pairing exchange"),
//! around the core's state machine and `PairingFlow`. A code names a new onion service, so a phone
//! removed later only knows a dead address (SR-33-01). The phone reaches this computer on Same
//! Wi‑Fi or through that service; over that pairing channel it gets the bundle, sends its request,
//! and hears the PairAccept.

use std::time::Duration;

use prost::Message;
use seam_core::PairingRequest;
use seam_core::links::{AppData, Path};
use seam_core::proto::v1::{BundleResult, Envelope, ServerFrame, server_frame};
use seam_tor::{DiscoveryKey, ServiceKey};

use super::TorServiceSpec;
use super::core::{Core, delivered};
use crate::lan::PAIRING;
use crate::pairing::qr::PairingQr;
use crate::pairing::{LIFETIME_MS, PairingState};
use crate::timers::Due;
use crate::{Event, Problem};

/// How long the PairAccept may take to be acked before the pairing is undone.
pub const ACCEPT_DEADLINE: Duration = Duration::from_secs(60);

/// The PairAccept on its way to the pairing phone.
pub(super) struct PendingAccept {
    pub(super) msg_id: Vec<u8>,
    pub(super) request: PairingRequest,
    /// The app data from before the pairing, to put back if it's undone.
    pub(super) previous: Option<AppData>,
}

#[derive(Default)]
pub(super) struct Pairing {
    /// The new onion service the code shows, until confirmed or abandoned.
    pub(super) service: Option<TorServiceSpec>,
    /// The link the pairing phone is on (the first to authenticate wins).
    pub(super) path: Option<Path>,
    /// The bundle this code's phone was given: asking again gets the same one, never more saved keys.
    bundle: Option<Vec<u8>>,
    pub(super) pending: Option<PendingAccept>,
}

impl Core {
    /// A fresh QR code naming a new onion service only the phone holding it can find.
    pub(super) fn start_pairing(&mut self, name: &str) {
        if self.flow.is_confirming() {
            return;
        }
        self.cancel_pairing(); // an earlier code's request, service and slot
        let (Ok(service), Ok(discovery)) = (ServiceKey::generate(), DiscoveryKey::generate())
        else {
            return;
        };
        let (offer, identity_key, relay_key) = {
            let mut d = self.dev();
            (d.start_pairing(), d.identity_key(), d.relay_key())
        };
        let qr = PairingQr {
            onion_address: service.address().to_string(),
            discovery_secret: discovery.to_bytes().to_vec(),
            token: offer.token.to_vec(),
            identity_key,
            relay_key: relay_key.to_vec(),
            name: name.to_owned(),
        };
        self.pairing.service = Some(TorServiceSpec {
            secret: service.to_bytes().to_vec(),
            clients: vec![discovery.public().to_bytes().to_vec()],
        });
        self.flow.start(qr.url(), self.timers.now_ms());
        self.expiry = Some(self.timers.after(
            Duration::from_millis(LIFETIME_MS as u64),
            Due::PairingExpired,
        ));
    }

    /// Cancel, or the window closed. Does nothing while PairAccept is already on its way.
    pub(super) fn cancel_pairing(&mut self) {
        let Some(previous) = self.flow.cancel() else {
            return;
        };
        if let PairingState::Confirm(request) = previous {
            self.dev().reject_pairing(&request.peer_id);
        }
        self.close_pairing();
    }

    pub(super) fn pairing_linked(&mut self, path: Path) {
        // Only while a code is showing, and only one pairing link: a second carrier's goes. Once a
        // request is held, nobody else's link can take over and receive its PairAccept
        let showing = matches!(self.flow.state(), PairingState::Waiting { .. });
        let held = self.pairing.path == Some(path);
        if !(held || (showing && self.pairing.path.is_none())) {
            if let Some(l) = self.links.get(&path) {
                l.close(PAIRING);
            }
            return;
        }
        self.pairing.path = Some(path);
    }

    pub(super) fn pairing_unlinked(&mut self, path: Path) {
        if self.pairing.path != Some(path) {
            return;
        }
        self.pairing.path = None;
        // The PairAccept may not have arrived: never leave a half-paired phone (the phone acks
        // before it closes, so a finished pairing is never here)
        if let Some(pending) = self.pairing.pending.take() {
            self.fail_accept(pending);
        }
    }

    /// Steps 1–2: this computer's bundle, fresh, saved as used before it goes.
    pub(super) fn send_bundle(&mut self, path: Path) {
        if self.pairing.path != Some(path)
            || !matches!(self.flow.state(), PairingState::Waiting { .. })
        {
            return;
        }
        let bundle = match self.pairing.bundle.clone() {
            Some(sent) => sent,
            None => {
                let fresh = self
                    .dev()
                    .prekey_bundle()
                    .and_then(|b| seam_core::bundle::encode(&b));
                let Ok(fresh) = fresh else {
                    self.events.push(Event::Problem(Problem::PairingFailed));
                    return;
                };
                if self.storage.save_state().is_err() {
                    self.events.push(Event::Problem(Problem::PairingFailed));
                    return;
                }
                self.pairing.bundle = Some(fresh.clone());
                fresh
            }
        };
        let device_key = self.dev().relay_key().to_vec();
        if let Some(l) = self.links.get(&path) {
            let frame = ServerFrame {
                frame: Some(server_frame::Frame::Bundle(BundleResult {
                    device_key,
                    bundle,
                })),
            };
            l.send(&frame, PAIRING);
        }
    }

    /// Steps 3–4: the phone's request, checked by the core, then the safety code for the user.
    pub(super) fn pairing_envelope(&mut self, envelope: Envelope, path: Path) {
        if self.pairing.path != Some(path)
            || !matches!(self.flow.state(), PairingState::Waiting { .. })
        {
            return;
        }
        let accepted = self.dev().accept_pairing(&envelope.encode_to_vec());
        match accepted {
            Ok(request) => {
                if !self.flow.received(request.clone()) {
                    self.dev().reject_pairing(&request.peer_id);
                    return;
                }
                if let Some(l) = self.links.get(&path) {
                    l.send(&delivered(envelope.msg_id), PAIRING);
                }
            }
            Err(_) => {
                self.events.push(Event::Problem(Problem::PairingRefused));
                if let Some(l) = self.links.get(&path) {
                    l.close(PAIRING);
                }
            }
        }
    }

    /// Step 5: the phone has the PairAccept.
    pub(super) fn pairing_acked(&mut self, msg_id: Vec<u8>, path: Path) {
        if self.pairing.path != Some(path)
            || self
                .pairing
                .pending
                .as_ref()
                .is_none_or(|p| p.msg_id != msg_id)
        {
            return;
        }
        if let Some(pending) = self.pairing.pending.take() {
            self.finish_pairing(pending.request);
        }
    }

    pub(super) fn accept_overdue(&mut self, msg_id: &[u8]) {
        if self
            .pairing
            .pending
            .as_ref()
            .is_some_and(|p| p.msg_id == msg_id)
            && let Some(pending) = self.pairing.pending.take()
        {
            self.fail_accept(pending);
        }
    }

    /// The code can't be used any more: the core's slot, the new service (unless it became the
    /// pairing's) and the pairing link all go.
    pub(super) fn close_pairing(&mut self) {
        if let Some(t) = self.expiry.take() {
            t.cancel();
        }
        self.dev().cancel_pairing();
        self.pairing.service = None;
        if let Some(path) = self.pairing.path.take()
            && let Some(l) = self.links.get(&path)
        {
            l.close(PAIRING);
        }
        self.pairing.bundle = None;
    }
}
