// SPDX-License-Identifier: AGPL-3.0-only
//! The one task everything runs on: it opens the store, then hands each message to the session
//! and its paths in order, and reports a snapshot after each.

use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{SystemTime, UNIX_EPOCH};

use seam_core::links::Path;
use tokio::sync::mpsc;

use super::carriers::SameWifiCarrier;
use super::{Health, Os, Settings, Snapshot};
use crate::clock::Clock;
use crate::features::clipboard::Clipboard;
use crate::features::notifications::Notifications;
use crate::features::status::Status;
use crate::features::{Command as FeatureCommand, Features};
use crate::lan::{LanConfig, LanEvent, LanServer};
use crate::paths::{LanRunner, Paths};
use crate::session::{Core, Reconnect, Session, Storage};
use crate::store::{Store, StoreError};
use crate::timers::{Due, TokioTimers};
use crate::tor::{TorRunner, TorState};
use crate::{Event, Problem, lock};

pub(super) enum Msg {
    Lan {
        path: Path,
        generation: u64,
        event: LanEvent,
    },
    Due(Due),
    Tor(TorState),
    Command(Command),
}

pub(super) enum Command {
    SetWifi(bool),
    SetTor(bool),
    StartPairing(String),
    ConfirmPairing,
    CancelPairing,
    Unpair,
    Reconnect,
    Feature(FeatureCommand),
    Woke,
    Reset,
    Stop,
}

/// What the app's thread reads without going through the queue.
#[derive(Default)]
pub(super) struct Shared {
    pub(super) icons: Mutex<HashMap<String, Vec<u8>>>,
}

/// The session, its paths and its timers, once the store is open.
struct Live {
    session: Session,
    paths: Paths,
    timers: Arc<TokioTimers>,
}

impl Live {
    /// One message, then the paths follow what the session wants now.
    async fn step(&mut self, msg: Msg) {
        let (s, p) = (&mut self.session, &mut self.paths);
        match msg {
            Msg::Lan {
                path,
                generation,
                event,
            } => p.carrier_event(path, generation, event, s).await,
            Msg::Due(due) => s.due(due).await,
            Msg::Tor(state) => p.tor_changed(state, s),
            Msg::Command(c) => match c {
                Command::SetWifi(on) => {
                    p.set_wifi(on, s).await;
                }
                Command::SetTor(on) => {
                    p.set_tor(on, s).await;
                }
                Command::StartPairing(name) => s.start_pairing(&name).await,
                Command::ConfirmPairing => s.confirm_pairing().await,
                Command::CancelPairing => s.cancel_pairing(),
                Command::Unpair => {
                    s.unpair_phone().await;
                }
                Command::Reconnect => {
                    // Out of reach, only this computer's side starts again; never under a pairing
                    // in progress, whose channel would close with the carrier (SR-55-10)
                    if s.reconnect() == Some(Reconnect::PhoneOffline) && !s.state().pairing_open() {
                        p.restart(s).await;
                    }
                }
                Command::Feature(f) => s.feature(f).await,
                Command::Woke => {
                    self.timers.woke();
                    p.republish(s);
                }
                Command::Reset | Command::Stop => {}
            },
        }
        // A phone may have come or gone, or a code shows: the links and services follow
        self.paths.update(&mut self.session).await;
        self.session.settle();
    }

    fn end(mut self) {
        self.paths.stop(&mut self.session);
        self.session.stop();
    }
}

pub(super) struct Actor {
    settings: Settings,
    os: Os,
    tor: Arc<dyn TorRunner>,
    tx: mpsc::UnboundedSender<Msg>,
    sync_on: Arc<AtomicBool>,
    shared: Arc<Shared>,
    live: Option<Live>,
    health: Health,
    opens: u64,
    pair_again: bool,
    last: Option<Snapshot>,
    icons_version: Option<u64>,
}

fn now_secs() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |d| d.as_secs())
}

impl Actor {
    pub(super) fn new(
        settings: Settings,
        os: Os,
        tor: Arc<dyn TorRunner>,
        tx: mpsc::UnboundedSender<Msg>,
        sync_on: Arc<AtomicBool>,
        shared: Arc<Shared>,
    ) -> Self {
        Self {
            settings,
            os,
            tor,
            tx,
            sync_on,
            shared,
            live: None,
            health: Health::Starting,
            opens: 0,
            pair_again: false,
            last: None,
            icons_version: None,
        }
    }

    pub(super) async fn run(mut self, mut rx: mpsc::UnboundedReceiver<Msg>) {
        let legacy = std::mem::take(&mut self.settings.legacy);
        let opened = Store::open(&self.settings.folder, self.os.keys.as_ref(), legacy);
        self.begin(opened).await;
        self.publish();
        while let Some(msg) = rx.recv().await {
            match msg {
                Msg::Command(Command::Stop) => break,
                Msg::Command(Command::Reset) => {
                    let reset =
                        Store::reset(&self.settings.folder, self.os.keys.as_ref(), now_secs());
                    self.begin(reset).await;
                }
                msg => {
                    if let Some(live) = &mut self.live {
                        live.step(msg).await;
                        if live.session.state().phone.is_some() {
                            self.pair_again = false;
                        }
                    }
                }
            }
            self.publish();
        }
        if let Some(live) = self.live.take() {
            live.end();
        }
    }

    async fn begin(&mut self, opened: Result<Store, StoreError>) {
        if let Some(live) = self.live.take() {
            live.end();
        }
        self.opens += 1;
        let store = match opened {
            Ok(s) => s,
            Err(StoreError::Unreadable) => {
                self.health = Health::Unreadable;
                self.os
                    .listener
                    .on_event(Event::Problem(Problem::StoreUnreadable));
                return;
            }
            Err(StoreError::Failed(detail)) => {
                self.health = Health::Failed { detail };
                return;
            }
        };
        self.pair_again = store.pair_again;
        self.sync_on
            .store(store.preferences().clipboard_sync, Ordering::SeqCst);
        let device = store.device.clone();
        let tx = self.tx.clone();
        let timers = Arc::new(TokioTimers::new(
            Clock::System,
            Arc::new(move |due| {
                let _ = tx.send(Msg::Due(due));
            }),
        ));
        let core = Core::new(
            device.clone(),
            Box::new(store),
            timers.clone(),
            self.settings.app_version.clone(),
            self.settings.platform.clone(),
        );
        let features = Features {
            clipboard: Clipboard::new(self.os.pasteboard.clone(), self.sync_on.clone()),
            notifications: Notifications::new(self.os.notifications.clone()),
            status: Status::new(self.os.battery.clone()),
        };
        let mut session = Session::new(core, features);
        let (same_wifi, tx) = (self.os.same_wifi.clone(), self.tx.clone());
        let make = Box::new(move |path: Path, generation: u64| -> Arc<dyn LanRunner> {
            let tx = tx.clone();
            let sink = Arc::new(move |event| {
                let _ = tx.send(Msg::Lan {
                    path,
                    generation,
                    event,
                });
            });
            match path {
                Path::Lan => {
                    let check = same_wifi.clone();
                    let server = LanServer::new(
                        device.clone(),
                        LanConfig::same_wifi(),
                        Some(Arc::new(move |ip| check.is_lan_address(ip))),
                        sink,
                    );
                    Arc::new(SameWifiCarrier {
                        server,
                        same_wifi: same_wifi.clone(),
                    })
                }
                Path::Tor => Arc::new(LanServer::new(device.clone(), LanConfig::tor(), None, sink)),
            }
        });
        let tx = self.tx.clone();
        let paths = Paths::new(
            self.tor.clone(),
            make,
            Arc::new(move |s| {
                let _ = tx.send(Msg::Tor(s));
            }),
        );
        session.start().await; // what waited from before, ahead of any path
        let mut live = Live {
            session,
            paths,
            timers,
        };
        live.paths.update(&mut live.session).await;
        live.session.settle();
        self.live = Some(live);
        self.health = Health::Ready;
    }

    fn publish(&mut self) {
        if let Some(live) = &mut self.live {
            let events: Vec<Event> = live
                .session
                .core
                .take_events()
                .into_iter()
                .chain(live.paths.take_events())
                .collect();
            for e in events {
                self.os.listener.on_event(e);
            }
        }
        let snapshot = self.snapshot();
        if self.last.as_ref() != Some(&snapshot) {
            self.last = Some(snapshot.clone());
            self.os.listener.on_snapshot(snapshot);
        }
    }

    fn snapshot(&mut self) -> Snapshot {
        let live = self.live.as_ref();
        let features = live.map(|l| l.session.features.view());
        // The icons are copied only when they change: they can run to megabytes
        if let (Some(live), Some(f)) = (live, &features)
            && self.icons_version != Some(f.notifications.icons_version)
        {
            self.icons_version = Some(f.notifications.icons_version);
            *lock(&self.shared.icons) = live.session.features.notifications.icons().clone();
        }
        Snapshot {
            health: self.health.clone(),
            opens: self.opens,
            pair_again: self.pair_again,
            session: live.map(|l| l.session.state()),
            features,
            tor_state: live.map_or(TorState::Off, |l| l.paths.tor_state()),
        }
    }
}
