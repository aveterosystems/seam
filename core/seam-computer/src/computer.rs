// SPDX-License-Identifier: AGPL-3.0-only
//! The computer app's one handle on Seam. It opens the store, runs the session and its paths in
//! one actor (commands, link events, timers and Tor's states, one at a time, in order), and tells
//! the app what to show ([`Snapshot`]) and what to log or announce ([`Event`]).

mod actor;
mod carriers;

use std::net::IpAddr;
use std::path::PathBuf;
use std::sync::Arc;
use std::sync::atomic::AtomicBool;
use std::time::Duration;

use tokio::sync::mpsc;

use crate::Event;
use crate::features::clipboard::{self, Pasteboard};
use crate::features::notifications::NotificationCentre;
use crate::features::status::Battery;
use crate::features::{Command as FeatureCommand, Views};
use crate::session::State;
use crate::store::{KeyStore, Legacy};
use crate::tor::{SeamTor, TorRunner, TorState};
use actor::{Actor, Command, Msg, Shared};

/// Same Wi‑Fi, as the OS provides it.
pub trait SameWifi: Send + Sync {
    /// Announces Seam on the local network (Bonjour, mDNS) on this port, under a fixed name that
    /// doesn't say whose computer it is.
    fn announce(&self, port: u16);
    fn stop_announcing(&self);
    /// Whether this local address is on a Wi‑Fi or wired Ethernet interface. A connection that
    /// arrives on anything else (a VPN tunnel, mobile data, loopback) is refused.
    fn is_lan_address(&self, ip: IpAddr) -> bool;
}

/// Hears what the app should show, log and announce. Called on Seam's own threads, in order.
pub trait Listener: Send + Sync {
    fn on_snapshot(&self, snapshot: Snapshot);
    fn on_event(&self, event: Event);
}

/// Everything the computer app supplies from its OS.
pub struct Os {
    pub keys: Arc<dyn KeyStore>,
    pub pasteboard: Arc<dyn Pasteboard>,
    pub notifications: Arc<dyn NotificationCentre>,
    pub battery: Arc<dyn Battery>,
    pub same_wifi: Arc<dyn SameWifi>,
    pub listener: Arc<dyn Listener>,
}

pub struct Settings {
    /// The app's private data folder.
    pub folder: PathBuf,
    pub app_version: String,
    /// "macos" | "linux": in the Hello.
    pub platform: String,
    /// What an earlier app kept in its own preferences, copied across on the first launch after.
    pub legacy: Legacy,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Health {
    Starting,
    Ready,
    /// Saved data exists but can't be opened: the app offers a reset instead of pairing.
    Unreadable,
    /// Seam couldn't start (a disk or key store error).
    Failed {
        detail: String,
    },
}

/// What the app shows.
#[derive(Debug, Clone, PartialEq)]
pub struct Snapshot {
    pub health: Health,
    /// Goes up each time the saved data is opened (launch, reset), so the app can tell a new
    /// opening from the same health as before.
    pub opens: u64,
    /// A pairing from before Tor was dropped on opening: shown until a phone pairs again.
    pub pair_again: bool,
    /// None until the saved data is open.
    pub session: Option<State>,
    pub features: Option<Views>,
    pub tor_state: TorState,
}

pub struct Computer {
    runtime: Option<tokio::runtime::Runtime>,
    tx: mpsc::UnboundedSender<Msg>,
    sync_on: Arc<AtomicBool>,
    shared: Arc<Shared>,
}

impl Computer {
    /// Starts Seam on its own threads. The first snapshot follows at once.
    pub fn start(settings: Settings, os: Os) -> Self {
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .thread_name("seam")
            .enable_all()
            .build()
            .expect("a Tokio runtime");
        let tor: Arc<dyn TorRunner> = Arc::new(SeamTor::new(settings.folder.clone()));
        let mut c = Self::start_on(runtime.handle(), settings, os, tor);
        c.runtime = Some(runtime);
        c
    }

    /// On a runtime the caller owns, with its own Tor (tests).
    pub fn start_on(
        handle: &tokio::runtime::Handle,
        settings: Settings,
        os: Os,
        tor: Arc<dyn TorRunner>,
    ) -> Self {
        let (tx, rx) = mpsc::unbounded_channel();
        let sync_on = Arc::new(AtomicBool::new(true));
        let shared = Arc::new(Shared::default());
        let actor = Actor::new(
            settings,
            os,
            tor,
            tx.clone(),
            sync_on.clone(),
            shared.clone(),
        );
        handle.spawn(actor.run(rx));
        Self {
            runtime: None,
            tx,
            sync_on,
            shared,
        }
    }

    fn send(&self, c: Command) {
        let _ = self.tx.send(Msg::Command(c));
    }

    /// One switch always stays on: turning off the last one is ignored.
    pub fn set_wifi(&self, on: bool) {
        self.send(Command::SetWifi(on));
    }

    pub fn set_tor(&self, on: bool) {
        self.send(Command::SetTor(on));
    }

    /// A fresh QR code, `name` being this computer's.
    pub fn start_pairing(&self, name: String) {
        self.send(Command::StartPairing(name));
    }

    /// Pair was clicked. Safe to call twice.
    pub fn confirm_pairing(&self) {
        self.send(Command::ConfirmPairing);
    }

    /// Cancel, or the window closed.
    pub fn cancel_pairing(&self) {
        self.send(Command::CancelPairing);
    }

    /// Tells the phone and forgets it, its onion service with it.
    pub fn unpair(&self) {
        self.send(Command::Unpair);
    }

    /// The phone is asked to dial again; out of reach, this computer's own side starts again.
    pub fn reconnect(&self) {
        self.send(Command::Reconnect);
    }

    /// What the user did to a feature. Turning clipboard sync off takes effect at once, even on
    /// a copy being read now (SR-29-05).
    pub fn feature(&self, command: FeatureCommand) {
        if let FeatureCommand::Clipboard(c) = &command {
            clipboard::apply_now(c, &self.sync_on);
        }
        self.send(Command::Feature(command));
    }

    /// The computer woke from sleep: timers catch up and the onion services are published again.
    pub fn woke(&self) {
        self.send(Command::Woke);
    }

    /// Only after the user agreed: a new identity, the old files kept aside.
    pub fn reset(&self) {
        self.send(Command::Reset);
    }

    /// The phone apps' icon PNGs, by app id (they change when `icons_version` goes up).
    pub fn icons(&self) -> std::collections::HashMap<String, Vec<u8>> {
        crate::lock(&self.shared.icons).clone()
    }
}

impl Drop for Computer {
    fn drop(&mut self) {
        self.send(Command::Stop);
        if let Some(rt) = self.runtime.take() {
            // Let the actor close the links before the threads go
            rt.shutdown_timeout(Duration::from_secs(2));
        }
    }
}

#[cfg(test)]
mod tests;
