// SPDX-License-Identifier: AGPL-3.0-only
//! Everything between this computer and its phone: delivery (sealed, saved, then sent on a path
//! the core's connection rules allow; resent if a path drops before the phone acknowledged it),
//! what arrives (to the features), forgetting the phone, and pairing. The features own their state
//! and actions; the session only hosts them. Same Wi‑Fi and Tor each reach it through a
//! [`LanSender`]; `Paths` starts and stops those.
//!
//! Plain methods, one call at a time: the app's actor feeds it link events, timers and commands
//! in order, and tests call it directly.

mod accept;
mod core;
mod delivery;
mod host;
mod links;
mod pairing;
mod receive;
mod storage;

use std::time::Duration;

use prost::Message;
use seam_core::links::Path;
use seam_core::proto::v1::{Envelope, control};

use crate::features::Features;
use crate::pairing::PairingState;
use crate::timers::Due;

pub use core::{Core, deliver};
pub use pairing::ACCEPT_DEADLINE;
pub use storage::{Preferences, Storage};

use core::control;

/// A link that leaves an envelope unacked this long, while another link is up, is taken for dead
/// (a network that went away without closing it): it's closed and the envelope goes on the other.
pub const ACK_TIMEOUT: Duration = Duration::from_secs(8);
/// A pause on the phone with no end time: until it resumes there.
pub const UNTIL_RESUMED: i64 = i64::MAX;

/// One onion service as the session wants it: its secret seed and the discovery keys of the only
/// clients that may find it. Never printed: the secret is the service's identity.
#[derive(Clone, PartialEq, Eq, Hash)]
pub struct TorServiceSpec {
    pub secret: Vec<u8>,
    pub clients: Vec<Vec<u8>>,
}

impl std::fmt::Debug for TorServiceSpec {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("TorServiceSpec(..)")
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Phone {
    pub id: String,
    pub name: String,
    /// The code both devices showed at pairing, for checking again in Settings.
    pub safety_code: String,
    /// When it was paired (unix ms), if it was since Seam kept the date.
    pub paired_at: Option<i64>,
}

/// A Reconnect under way, until the phone is back or it gives up.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Reconnect {
    /// The phone was asked to dial again.
    Asked,
    /// The phone was out of reach, so only this computer's side starts again.
    PhoneOffline,
}

/// How long a Reconnect shows: the usual time to connect.
pub fn reconnect_shows(tor: bool) -> Duration {
    Duration::from_secs(if tor { 60 } else { 15 })
}

/// Something the user should see (the app picks the words).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Warning {
    /// Saving to disk failed; messages wait until it works.
    SaveFailed,
    /// A device with keys that don't match the paired phone tried to talk to this computer.
    UntrustedIdentity,
}

/// What the app shows.
#[derive(Debug, Clone, PartialEq)]
pub struct State {
    pub phone: Option<Phone>,
    /// Where a message would go now; None: it waits.
    pub route: Option<Path>,
    /// Paths switched on at both ends.
    pub allowed: Vec<Path>,
    /// Messages not yet delivered.
    pub waiting: usize,
    pub pairing: PairingState,
    pub warning: Option<Warning>,
    /// Paused on the phone until then (unix ms; [`UNTIL_RESUMED`]: until it resumes); None: not paused.
    pub paused_until: Option<i64>,
    /// The onion services this computer should host now: the paired phone's, and a pairing's new one.
    pub tor_services: Vec<TorServiceSpec>,
    /// A phone is paired or pairing, so the links should be open.
    pub wants_links: bool,
    pub reconnect: Option<Reconnect>,
    /// When the link last went from connected to not (unix ms), for "Last seen". This run only.
    pub last_seen: Option<i64>,
    /// The connection switches.
    pub wifi: bool,
    pub tor: bool,
}

/// The session: the core and the features it hosts.
pub struct Session {
    pub core: Core,
    pub features: Features,
}

impl State {
    /// A code is showing or being answered.
    pub fn pairing_open(&self) -> bool {
        matches!(
            self.pairing,
            PairingState::Waiting { .. } | PairingState::Confirm(_) | PairingState::Confirming(_)
        )
    }
}

impl Session {
    pub fn new(core: Core, features: Features) -> Self {
        Self { core, features }
    }

    pub fn state(&self) -> State {
        self.core.state()
    }

    /// Loads what waited from before a restart and starts the features.
    pub async fn start(&mut self) {
        let c = &mut self.core;
        if c.phone.is_some() {
            c.outbox = c.storage.outbox().load(c.timers.now_ms());
            c.outbox.requeue_sent(); // sent directly before a restart and never acked: goes again
        } else {
            c.storage.outbox().delete(); // a reset, or the phone went while Seam was closed
        }
        self.features.start(&mut self.core).await;
        if let Some(until) = self.core.paused_until {
            self.core.set_pause(Some(until)); // its end, timed from now
        }
    }

    /// Timers off, for a session being replaced.
    pub fn stop(&mut self) {
        self.features.stop();
        if let Some(t) = self.core.expiry.take() {
            t.cancel();
        }
        if let Some(t) = self.core.pause_timer.take() {
            t.cancel();
        }
        for (_, (_, t)) in self.core.ack_waits.drain() {
            t.cancel();
        }
    }

    /// The connection switches changed (at least one stays on): the phone is told, and what
    /// waited goes if it now can.
    /// The connection switches changed: the phone is told, and what waited goes if it now can.
    /// False, and nothing changes, if both would be off: one always stays on.
    pub fn set_switches(&mut self, wifi: bool, tor: bool) -> bool {
        if !wifi && !tor {
            return false;
        }
        self.core.wifi = wifi;
        self.core.tor_on = tor;
        self.core.save_preferences();
        self.core.send_hello();
        self.core.flush();
        true
    }

    /// A timer is due.
    pub async fn due(&mut self, due: Due) {
        match due {
            Due::AckOverdue { msg_id, path } => self.core.ack_overdue(&msg_id, path),
            Due::PauseEnded => {} // `state` reads the clock: the pause shows as over now
            Due::PairingExpired => self.core.pairing_expired(),
            Due::AcceptOverdue(msg_id) => self.core.accept_overdue(&msg_id),
            Due::ClipboardPoll => self.features.clipboard.poll(&mut self.core).await,
            Due::ReconnectEnded => self.core.end_reconnect(),
        }
    }

    /// "Unpair": tells the phone (sealed) on every link it's on, then forgets it here, its onion
    /// service with it. A phone that's away finds its computer's address gone, and pairs again.
    /// False if forgetting it couldn't be saved (then nothing is sent).
    pub async fn unpair_phone(&mut self) -> bool {
        let Some(phone) = self.core.phone.clone() else {
            return true; // nothing to forget (SR-29-06: the caller's copy may lag)
        };
        // Not saved before sending, unlike other messages (SR-2-11): the phone's keys are deleted
        // right after, so a crash can't reuse them
        let unpair = control(control::Body::Unpair(Default::default())).encode_to_vec();
        let goodbye = self
            .core
            .dev()
            .seal(&phone.id, &unpair, 0)
            .ok()
            .and_then(|s| Envelope::decode(s.as_slice()).ok());
        // Every up path this computer's own switches allow, whatever the phone's last list said
        let enabled = self.core.enabled();
        let via: Vec<Path> = self
            .core
            .up_paths()
            .into_iter()
            .filter(|p| enabled.contains(p))
            .collect();
        // Only once forgetting it is saved: otherwise a restart would reload the phone with the
        // ratchet from before the goodbye was sealed, and could reuse its key (SR-7-04)
        if !self.forget(&phone.id).await {
            return false;
        }
        for path in via {
            if let Some(l) = self.core.links.get(&path) {
                if let Some(g) = &goodbye {
                    l.send(&deliver(g.clone()), &phone.id);
                }
                l.close(&phone.id); // gracefully: the goodbye is written first
            }
        }
        true
    }

    /// The phone is gone (unpaired here, or it unpaired us): its keys, Hello state, every
    /// feature's data and anything held for it. False if that couldn't be saved.
    async fn forget(&mut self, peer_id: &str) -> bool {
        let saved = self.core.forget(peer_id);
        self.features.reset().await;
        saved
    }

    /// "Reconnect" on this computer. With a link to the phone up, the phone is asked (sealed, on
    /// every open link, never queued: a late one would cut a fresh link) and this computer closes
    /// its end, so the phone dials again at once. False when no link is up: only this computer's
    /// own side can start again (`Paths::restart`).
    pub fn reconnect(&mut self) -> Option<Reconnect> {
        self.core.start_reconnect()
    }

    /// See [`Core::settle`]: call after anything happens.
    pub fn settle(&mut self) {
        self.core.settle();
    }

    /// What the user did to a feature, from the app's screens.
    pub async fn feature(&mut self, command: crate::features::Command) {
        use crate::features::{Command, clipboard};
        if let Command::Clipboard(clipboard::Command::SetSync(on)) = command {
            self.core.clipboard_sync = on;
            self.core.save_preferences();
        }
        self.features.command(command, &mut self.core).await;
    }

    pub async fn start_pairing(&mut self, name: &str) {
        self.core.start_pairing(name);
    }

    pub async fn confirm_pairing(&mut self) {
        self.confirm_pairing_now().await;
    }

    pub fn cancel_pairing(&mut self) {
        self.core.cancel_pairing();
    }
}

#[cfg(test)]
pub(crate) mod tests;
