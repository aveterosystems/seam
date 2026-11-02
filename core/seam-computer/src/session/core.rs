// SPDX-License-Identifier: AGPL-3.0-only
//! Delivery, pairing and the paths: everything in the session but the features, which it hosts.

use std::collections::{BTreeSet, HashMap, HashSet};
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::Duration;

use seam_core::Device;
use seam_core::links::{self, AppData, Path};
use seam_core::proto::v1::{Envelope, Payload, ServerFrame, control, payload::Body, server_frame};

use super::pairing::Pairing;
use super::storage::{Preferences, Storage};
use super::{Phone, Reconnect, State, TorServiceSpec, UNTIL_RESUMED, Warning, reconnect_shows};
use crate::lan::LanSender;
use crate::lock;
use crate::outbox::Outbox;
use crate::pairing::PairingFlow;
use crate::timers::{Due, TimerToken, Timers};
use crate::{Event, Problem};

/// Delivery, pairing and the paths: everything but the features, which it hosts.
pub struct Core {
    pub(super) device: Arc<Mutex<Device>>,
    pub(super) storage: Box<dyn Storage>,
    pub(super) timers: Arc<dyn Timers>,
    pub(super) app_version: String,
    /// "macos" | "linux": in the Hello.
    pub(super) platform: String,
    pub(super) wifi: bool,
    pub(super) tor_on: bool,
    /// The running carriers: Same Wi‑Fi's listener and the Tor service's streams.
    pub(super) links: HashMap<Path, Arc<dyn LanSender>>,
    /// Paths with the phone authenticated on them now.
    pub(super) up: BTreeSet<Path>,
    pub(super) phone: Option<Phone>,
    pub(super) warning: Option<Warning>,
    /// Sealed envelopes not yet delivered, saved before any is handed to a path.
    pub(super) outbox: Outbox,
    /// Hellos in the outbox: the only envelopes allowed the Hello route. Memory only: after a
    /// restart a waiting Hello takes the normal route, and a fresh one goes when a path comes up.
    pub(super) hellos: HashSet<Vec<u8>>,
    /// Each envelope sent and not yet acked, with the link it went on.
    pub(super) ack_waits: HashMap<Vec<u8>, (Path, TimerToken)>,
    /// The phone paused Seam until then (only the phone pauses).
    pub(super) paused_until: Option<i64>,
    pub(super) pause_timer: Option<TimerToken>,
    pub(super) clipboard_sync: bool,
    pub(super) paired_at: Option<i64>,
    /// A Reconnect under way, until the phone is back or it gives up.
    pub(super) reconnect: Option<Reconnect>,
    pub(super) reconnect_timer: Option<TimerToken>,
    /// When the link last went from connected to not (unix ms). This run only.
    pub(super) last_seen: Option<i64>,
    pub(super) was_connected: bool,
    /// Capabilities features have turned off now, left out of the Hello.
    pub(super) withheld: BTreeSet<String>,
    pub(super) flow: PairingFlow,
    pub(super) expiry: Option<TimerToken>,
    pub(super) pairing: Pairing,
    pub(super) events: Vec<Event>,
}

pub fn deliver(envelope: Envelope) -> ServerFrame {
    ServerFrame {
        frame: Some(server_frame::Frame::Deliver(envelope)),
    }
}

pub(super) fn delivered(msg_id: Vec<u8>) -> ServerFrame {
    ServerFrame {
        frame: Some(server_frame::Frame::Delivered(
            seam_core::proto::v1::Delivered { msg_id },
        )),
    }
}

pub(super) fn control(body: control::Body) -> Payload {
    Payload {
        body: Some(Body::Control(seam_core::proto::v1::Control {
            body: Some(body),
        })),
    }
}

impl Core {
    pub fn new(
        device: Arc<Mutex<Device>>,
        storage: Box<dyn Storage>,
        timers: Arc<dyn Timers>,
        app_version: String,
        platform: String,
    ) -> Self {
        let prefs = storage.preferences();
        let phone = confirmed_phone(&lock(&device));
        // Nobody to pause or to have paired with, once the phone is gone
        let (paused_until, paired_at) = match phone {
            Some(_) => (prefs.paused_until, prefs.paired_at),
            None => (None, None),
        };
        Self {
            device,
            storage,
            timers,
            app_version,
            platform,
            wifi: prefs.wifi,
            tor_on: prefs.tor,
            links: HashMap::new(),
            up: BTreeSet::new(),
            phone,
            warning: None,
            outbox: Outbox::default(),
            hellos: HashSet::new(),
            ack_waits: HashMap::new(),
            paused_until,
            pause_timer: None,
            clipboard_sync: prefs.clipboard_sync,
            paired_at,
            reconnect: None,
            reconnect_timer: None,
            last_seen: None,
            was_connected: false,
            withheld: BTreeSet::new(),
            flow: PairingFlow::default(),
            expiry: None,
            pairing: Pairing::default(),
            events: Vec::new(),
        }
    }

    pub(super) fn dev(&self) -> MutexGuard<'_, Device> {
        lock(&self.device)
    }

    pub fn device(&self) -> &Arc<Mutex<Device>> {
        &self.device
    }

    /// What happened since the last call, for the app to log or announce.
    pub fn take_events(&mut self) -> Vec<Event> {
        std::mem::take(&mut self.events)
    }

    /// The switches as the core's path list: the one place they're translated.
    pub(super) fn enabled(&self) -> Vec<Path> {
        let mut out = Vec::new();
        if self.wifi {
            out.push(Path::Lan);
        }
        if self.tor_on {
            out.push(Path::Tor);
        }
        out
    }

    /// The links connected now, in the core's order.
    pub(super) fn up_paths(&self) -> Vec<Path> {
        Path::ALL
            .into_iter()
            .filter(|p| self.up.contains(p))
            .collect()
    }

    pub(super) fn route(&self, hello: bool) -> Option<Path> {
        let (enabled, up) = (self.enabled(), self.up_paths());
        match self.dev().app_data() {
            Some(d) => d.route(&enabled, &up, hello),
            None => links::route(&links::mine(&enabled), &up),
        }
    }

    pub(super) fn allowed(&self) -> Vec<Path> {
        let enabled = self.enabled();
        self.dev()
            .app_data()
            .map_or_else(|| links::mine(&enabled), |d| d.allowed(&enabled))
    }

    pub(super) fn paused(&self) -> bool {
        self.paused_until.is_some_and(|u| u > self.timers.now_ms())
    }

    pub fn state(&self) -> State {
        State {
            phone: self.phone.clone().map(|p| Phone {
                paired_at: self.paired_at,
                ..p
            }),
            route: self.route(false),
            allowed: self.allowed(),
            waiting: self.outbox.len(),
            pairing: self.flow.state().clone(),
            warning: self.warning,
            paused_until: self.paused().then_some(self.paused_until).flatten(),
            tor_services: self.tor_services(),
            wants_links: self.phone.is_some() || self.flow.is_open(),
            reconnect: self.reconnect,
            last_seen: self.last_seen,
            wifi: self.wifi,
            tor: self.tor_on,
        }
    }

    /// The phone can be reached now, on a path both ends allow, and isn't paused.
    fn connected(&self) -> bool {
        self.route(false).is_some() && !self.paused()
    }

    /// After anything happens: a link that went from connected to not is "last seen" now, and a
    /// Reconnect is over once the phone is back.
    pub fn settle(&mut self) {
        let connected = self.connected();
        if self.was_connected && !connected {
            self.last_seen = Some(self.timers.now_ms());
        }
        if connected {
            self.end_reconnect();
        }
        self.was_connected = connected;
    }

    pub(super) fn end_reconnect(&mut self) {
        self.reconnect = None;
        if let Some(t) = self.reconnect_timer.take() {
            t.cancel();
        }
    }

    /// "Reconnect" on this computer. None while one is under way: a second tap would restart
    /// this computer's side as the phone dials back (SR-55-03). `PhoneOffline`: only this
    /// computer's own side can start again.
    pub(super) fn start_reconnect(&mut self) -> Option<Reconnect> {
        if self.reconnect.is_some() {
            return None;
        }
        let r = if self.reconnect() {
            Reconnect::Asked
        } else {
            Reconnect::PhoneOffline
        };
        self.reconnect = Some(r);
        self.reconnect_timer = Some(
            self.timers
                .after(reconnect_shows(self.tor_on), Due::ReconnectEnded),
        );
        Some(r)
    }

    /// The paired phone's service (from app data), then a pairing's: only while Tor is on.
    pub(super) fn tor_services(&self) -> Vec<TorServiceSpec> {
        if !self.tor_on {
            return Vec::new();
        }
        let mut out = Vec::new();
        if self.phone.is_some()
            && let Some(d) = self.dev().app_data()
            && d.peer_discovery.len() == 32
        {
            out.push(TorServiceSpec {
                secret: d.tor_secret,
                clients: vec![d.peer_discovery],
            });
        }
        if let Some(s) = &self.pairing.service
            && !out.contains(s)
        {
            out.push(s.clone());
        }
        out
    }

    pub(super) fn save_failed(&mut self) {
        self.warning = Some(Warning::SaveFailed);
        self.events.push(Event::Problem(Problem::SaveFailed));
    }

    pub(super) fn save_state(&mut self) -> bool {
        if self.storage.save_state().is_ok() {
            return true;
        }
        self.save_failed();
        false
    }

    /// The phone paused (a time, or [`UNTIL_RESUMED`]) or resumed (None). A timed pause ends here
    /// at the same time as on the phone, with no message.
    pub(super) fn set_pause(&mut self, until: Option<i64>) {
        if let Some(t) = self.pause_timer.take() {
            t.cancel();
        }
        self.paused_until = until;
        self.save_preferences();
        if let Some(until) = until.filter(|&u| u != UNTIL_RESUMED) {
            let wait = until.saturating_sub(self.timers.now_ms()).max(0) as u64;
            self.pause_timer = Some(
                self.timers
                    .after(Duration::from_millis(wait), Due::PauseEnded),
            );
        }
    }

    pub(super) fn save_preferences(&self) {
        self.storage.save_preferences(&Preferences {
            wifi: self.wifi,
            tor: self.tor_on,
            clipboard_sync: self.clipboard_sync,
            paused_until: self.paused_until,
            paired_at: self.paired_at,
        });
    }

    pub(super) fn is_confirmed_phone(&self, peer_id: &str) -> bool {
        self.phone.as_ref().is_some_and(|p| p.id == peer_id)
            && self.dev().peers().get(peer_id).is_some_and(|p| p.confirmed)
    }
}

/// The confirmed peer, if any: a computer has one phone.
pub(super) fn confirmed_phone(device: &Device) -> Option<Phone> {
    let (id, peer) = device.peers().iter().find(|(_, p)| p.confirmed)?;
    Some(Phone {
        id: id.clone(),
        name: peer.name.clone(),
        safety_code: device.safety_code(id).unwrap_or_default(),
        paired_at: None,
    })
}

/// A Hello's `paused_until_ms`: 0 (or an older phone that doesn't send it) is not paused.
pub(super) fn pause_end(ms: i64) -> Option<i64> {
    (ms > 0).then_some(ms)
}

impl Core {
    /// The phone is gone: its keys, Hello state and anything held for it. False if that
    /// couldn't be saved.
    pub(super) fn forget(&mut self, peer_id: &str) -> bool {
        {
            let mut d = self.dev();
            d.edit_app_data(AppData::forget_peer);
            d.remove_peer(peer_id);
            d.clear_app_data(); // its onion service goes: a forgotten phone only knows a dead address
        }
        let saved = self.save_state();
        if self.phone.as_ref().is_some_and(|p| p.id == peer_id) {
            self.phone = None;
        }
        self.up.clear();
        self.paired_at = None;
        self.set_pause(None); // nobody left to resume it (and saves the date with it)
        self.outbox.clear();
        self.hellos.clear();
        self.storage.outbox().delete();
        saved
    }
}
