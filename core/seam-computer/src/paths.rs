// SPDX-License-Identifier: AGPL-3.0-only
//! Runs the paths the connection switches allow and keeps the session told:
//! - a carrier switched off closes at once, so nothing more can go on it (SR-29-07);
//! - switch changes reach the session in order (SR-29-11): everything here runs one step at a
//!   time, in the session's actor;
//! - each carrier has a generation, and an event from one that's been replaced is dropped, so a
//!   late close can never take its replacement away (SR-55-06, SR-31-03);
//! - the onion services follow what the session asks for, republished only when that changes or
//!   after wake.

use std::collections::HashMap;
use std::sync::{Arc, Weak};

use async_trait::async_trait;
use seam_core::links::Path;

use crate::lan::{LanEvent, LanSender, Stream};
use crate::session::{Session, TorServiceSpec};
use crate::tor::{StateSink, TorHosted, TorRunner, TorState};
use crate::{Event, Problem};

/// A direct-link carrier: `LanServer` in the app (Same Wi‑Fi's listener, or the Tor service's
/// streams), a fake in tests.
#[async_trait]
pub trait LanRunner: LanSender {
    async fn start(&self) -> std::io::Result<u16>;
    /// Closes gracefully: what was already sent is written first.
    fn stop(&self);
    /// A stream from the onion service, as if it had connected (the Tor carrier).
    fn adopt(&self, stream: Stream);
}

/// Makes a carrier for `path`; its events must come back tagged with `generation`.
pub type MakeCarrier = Box<dyn Fn(Path, u64) -> Arc<dyn LanRunner> + Send + Sync>;

struct Carrier {
    runner: Arc<dyn LanRunner>,
    generation: u64,
}

pub struct Paths {
    tor_state: TorState,
    tor: Arc<dyn TorRunner>,
    make: MakeCarrier,
    /// Hears Tor's states (the actor's queue).
    on_tor_state: StateSink,
    links_wanted: bool,
    stopped: bool,
    tor_started: bool,
    wanted: Vec<TorServiceSpec>,
    hosted: HashMap<TorServiceSpec, Box<dyn TorHosted>>,
    carriers: HashMap<Path, Carrier>,
    generation: u64,
    events: Vec<Event>,
}

impl Paths {
    pub fn new(tor_runner: Arc<dyn TorRunner>, make: MakeCarrier, on_tor_state: StateSink) -> Self {
        Self {
            tor_state: TorState::Off,
            tor: tor_runner,
            make,
            on_tor_state,
            links_wanted: false,
            stopped: false,
            tor_started: false,
            wanted: Vec::new(),
            hosted: HashMap::new(),
            carriers: HashMap::new(),
            generation: 0,
            events: Vec::new(),
        }
    }

    pub fn tor_state(&self) -> TorState {
        self.tor_state
    }

    pub fn take_events(&mut self) -> Vec<Event> {
        std::mem::take(&mut self.events)
    }

    /// Closes everything, for a session being replaced.
    pub fn stop(&mut self, session: &mut Session) {
        self.stopped = true;
        for path in Path::ALL {
            self.close(path, session);
        }
        self.stop_services();
    }

    /// False, and nothing changes, if it would turn off the last switch: one always stays on.
    pub async fn set_wifi(&mut self, on: bool, session: &mut Session) -> bool {
        let tor = session.state().tor;
        self.switch(on, tor, session).await
    }

    pub async fn set_tor(&mut self, on: bool, session: &mut Session) -> bool {
        let wifi = session.state().wifi;
        self.switch(wifi, on, session).await
    }

    /// A carrier going off closes first, so nothing more goes on it (SR-29-07) and the Hello
    /// announcing the change goes on what stays.
    async fn switch(&mut self, wifi: bool, tor: bool, session: &mut Session) -> bool {
        if !wifi && !tor {
            return false;
        }
        self.update_carriers(wifi, tor, session).await;
        session.set_switches(wifi, tor);
        self.reconcile(tor);
        true
    }

    /// What the session wants now: links while a phone is paired or pairing, and these services.
    pub async fn update(&mut self, session: &mut Session) {
        let s = session.state();
        self.links_wanted = s.wants_links;
        self.wanted = s.tor_services;
        self.update_carriers(s.wifi, s.tor, session).await;
        self.reconcile(s.tor);
    }

    /// After wake: the services are published again (the descriptors may have lapsed meanwhile).
    pub fn republish(&mut self, session: &Session) {
        self.stop_services();
        self.reconcile(session.state().tor);
    }

    /// "Reconnect" with the phone out of reach: this computer's own side may be what's stuck, so
    /// its listeners start again and its services are published again. Never a switch or a pairing.
    pub async fn restart(&mut self, session: &mut Session) {
        if self.stopped {
            return;
        }
        for path in Path::ALL {
            self.close(path, session); // the Tor carrier takes its services with it
        }
        let s = session.state();
        self.update_carriers(s.wifi, s.tor, session).await;
        self.reconcile(s.tor);
    }

    pub fn tor_changed(&mut self, state: TorState, session: &Session) {
        self.tor_state = state;
        match state {
            TorState::Ready => self.reconcile(session.state().tor),
            // Bootstrap gave up: the next update or Reconnect starts it again
            TorState::Failed | TorState::Blocked(_) => self.tor_started = false,
            _ => {}
        }
    }

    /// An event from a carrier: only the current one's reach the session.
    pub async fn carrier_event(
        &mut self,
        path: Path,
        generation: u64,
        event: LanEvent,
        session: &mut Session,
    ) {
        if self
            .carriers
            .get(&path)
            .is_some_and(|c| c.generation == generation)
        {
            session.link_event(event, path).await;
        }
    }

    /// Hosts what's wanted and not yet up, stops what's no longer wanted. Tor starts on first need.
    fn reconcile(&mut self, tor_on: bool) {
        let want: Vec<TorServiceSpec> = if self.stopped || !tor_on {
            Vec::new()
        } else {
            self.wanted.clone()
        };
        self.hosted.retain(|spec, service| {
            let keep = want.contains(spec);
            if !keep {
                service.stop();
            }
            keep
        });
        if want.is_empty() {
            return;
        }
        if !self.tor_started {
            self.tor_started = true;
            self.tor.start(self.on_tor_state.clone());
        }
        let Some(carrier) = self.carriers.get(&Path::Tor) else {
            return;
        };
        if self.tor_state != TorState::Ready {
            return;
        }
        let weak: Weak<dyn LanRunner> = Arc::downgrade(&carrier.runner);
        for spec in want {
            if self.hosted.contains_key(&spec) {
                continue;
            }
            let carrier = weak.clone();
            let on_stream = Arc::new(move |stream: Stream| {
                if let Some(c) = carrier.upgrade() {
                    c.adopt(stream);
                } // else dropped: no carrier to take it
            });
            if let Some(service) = self.tor.host(&spec, on_stream) {
                self.hosted.insert(spec, service);
            }
        }
    }

    fn stop_services(&mut self) {
        for (_, service) in self.hosted.drain() {
            service.stop();
        }
    }

    async fn update_carriers(&mut self, wifi: bool, tor: bool, session: &mut Session) {
        for (path, on) in [(Path::Lan, wifi), (Path::Tor, tor)] {
            let want = !self.stopped && on && self.links_wanted;
            if want && !self.carriers.contains_key(&path) {
                self.open(path, session).await;
            } else if !want {
                self.close(path, session);
            }
        }
    }

    async fn open(&mut self, path: Path, session: &mut Session) {
        self.generation += 1;
        let runner = (self.make)(path, self.generation);
        if runner.start().await.is_err() {
            // Reported; the next update tries again
            self.events.push(Event::Problem(Problem::ListenerFailed));
            return;
        }
        self.carriers.insert(
            path,
            Carrier {
                runner: runner.clone(),
                generation: self.generation,
            },
        );
        session.set_link(Some(runner), path);
    }

    fn close(&mut self, path: Path, session: &mut Session) {
        let Some(carrier) = self.carriers.remove(&path) else {
            return;
        };
        carrier.runner.stop();
        if path == Path::Tor {
            self.stop_services(); // no carrier to take their streams
        }
        session.set_link(None, path);
    }
}

#[cfg(test)]
mod tests;
