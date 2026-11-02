// SPDX-License-Identifier: AGPL-3.0-only
use std::collections::HashSet;
use std::sync::{Arc, Mutex};

use async_trait::async_trait;
use seam_core::links::Path;
use seam_core::proto::v1::ServerFrame;

use super::{LanRunner, Paths};
use crate::lan::{LanEvent, LanSender, Stream};
use crate::session::TorServiceSpec;
use crate::session::tests::rig::{MAC_SECRET, Options, Rig, lock, mac_data};
use crate::tor::{StateSink, StreamSink, TorHosted, TorRunner, TorState};
use crate::{Event, Problem};

/// What the fakes did, in order.
#[derive(Default, Clone)]
struct Log(Arc<Mutex<Vec<String>>>);

impl Log {
    fn add(&self, e: impl Into<String>) {
        lock(&self.0).push(e.into());
    }
    fn all(&self) -> Vec<String> {
        lock(&self.0).clone()
    }
    fn count(&self, e: &str) -> usize {
        self.all().iter().filter(|x| *x == e).count()
    }
}

struct FakeRunner {
    name: &'static str,
    log: Log,
    fails: bool,
}

#[async_trait]
impl LanRunner for FakeRunner {
    async fn start(&self) -> std::io::Result<u16> {
        self.log.add(format!("{} starts", self.name));
        if self.fails {
            return Err(std::io::Error::other("no port"));
        }
        Ok(1)
    }
    fn stop(&self) {
        self.log.add(format!("{} stops", self.name));
    }
    fn adopt(&self, _: Stream) {}
}

impl LanSender for FakeRunner {
    fn send(&self, _: &ServerFrame, _: &str) -> bool {
        false
    }
    fn close(&self, _: &str) {}
}

/// Tor that's ready when the test says, and remembers which services are up.
#[derive(Default)]
struct FakeTor {
    live: Arc<Mutex<HashSet<TorServiceSpec>>>,
    starts: Mutex<usize>,
    /// Every publish, a republish included.
    hosts: Mutex<usize>,
}

struct Hosted(Arc<Mutex<HashSet<TorServiceSpec>>>, TorServiceSpec);

impl TorHosted for Hosted {
    fn stop(&self) {
        lock(&self.0).remove(&self.1);
    }
}

impl TorRunner for FakeTor {
    fn start(&self, on_state: StateSink) {
        *lock(&self.starts) += 1;
        on_state(TorState::Starting(0));
    }
    fn host(&self, spec: &TorServiceSpec, _: StreamSink) -> Option<Box<dyn TorHosted>> {
        *lock(&self.hosts) += 1;
        lock(&self.live).insert(spec.clone());
        Some(Box::new(Hosted(self.live.clone(), spec.clone())))
    }
}

impl FakeTor {
    fn live(&self) -> HashSet<TorServiceSpec> {
        lock(&self.live).clone()
    }
}

fn spec(n: u8) -> TorServiceSpec {
    TorServiceSpec {
        secret: vec![n; 32],
        clients: vec![vec![n; 32]],
    }
}

struct Setup {
    rig: Rig,
    paths: Paths,
    log: Log,
    tor: Arc<FakeTor>,
    made: Arc<Mutex<Vec<Arc<dyn LanRunner>>>>,
}

async fn setup(wifi: bool, tor: bool, lan_fails: bool) -> Setup {
    let rig = Rig::with(Options {
        wifi,
        tor,
        ..Default::default()
    })
    .await;
    setup_rig(rig, lan_fails)
}

fn setup_rig(rig: Rig, lan_fails: bool) -> Setup {
    let log = Log::default();
    let tor = Arc::new(FakeTor::default());
    let made: Arc<Mutex<Vec<Arc<dyn LanRunner>>>> = Arc::default();
    let (l, m) = (log.clone(), made.clone());
    let make = Box::new(move |path: Path, _gen: u64| -> Arc<dyn LanRunner> {
        let r: Arc<dyn LanRunner> = Arc::new(FakeRunner {
            name: if path == Path::Lan { "lan" } else { "tor" },
            log: l.clone(),
            fails: lan_fails && path == Path::Lan,
        });
        lock(&m).push(r.clone());
        r
    });
    let states = log.clone();
    let paths = Paths::new(
        tor.clone(),
        make,
        Arc::new(move |s| states.add(format!("tor {s:?}"))),
    );
    Setup {
        rig,
        paths,
        log,
        tor,
        made,
    }
}

/// Wants links, and these services, as the session's state would say.
async fn want(s: &mut Setup, services: Vec<TorServiceSpec>) {
    s.paths.links_wanted = true;
    s.paths.wanted = services;
    let st = s.rig.session.state();
    s.paths
        .update_carriers(st.wifi, st.tor, &mut s.rig.session)
        .await;
    s.paths.reconcile(st.tor);
}

#[tokio::test]
async fn the_last_switch_cant_go_off() {
    let mut s = setup(true, false, false).await;
    assert!(!s.paths.set_wifi(false, &mut s.rig.session).await);
    assert!(s.rig.session.state().wifi);
    assert!(s.paths.set_tor(true, &mut s.rig.session).await);
    assert!(s.paths.set_wifi(false, &mut s.rig.session).await);
    assert!(!s.paths.set_tor(false, &mut s.rig.session).await);
    assert!(s.rig.session.state().tor);
}

#[tokio::test]
async fn services_are_published_once_tor_is_ready() {
    let mut s = setup(true, true, false).await;
    want(&mut s, vec![spec(0xA)]).await;
    assert_eq!(*lock(&s.tor.starts), 1);
    assert!(s.tor.live().is_empty(), "not before Tor is ready");
    s.paths.tor_changed(TorState::Ready, &s.rig.session);
    assert_eq!(s.tor.live(), HashSet::from([spec(0xA)]));
    assert!(
        s.log.all().contains(&"tor Starting(0)".to_string()),
        "the app hears Tor's state"
    );
}

#[tokio::test]
async fn only_a_change_republishes() {
    let mut s = setup(true, true, false).await;
    want(&mut s, vec![spec(0xA)]).await;
    s.paths.tor_changed(TorState::Ready, &s.rig.session);
    want(&mut s, vec![spec(0xA)]).await; // any state update: nothing changes
    want(&mut s, vec![spec(0xA), spec(0xB)]).await;
    assert_eq!(s.tor.live(), HashSet::from([spec(0xA), spec(0xB)]));
    assert_eq!(*lock(&s.tor.hosts), 2, "a stays up; only b is new");
    want(&mut s, vec![spec(0xB)]).await;
    assert_eq!(s.tor.live(), HashSet::from([spec(0xB)]));
    s.paths.republish(&s.rig.session); // after wake
    assert_eq!(s.tor.live(), HashSet::from([spec(0xB)]));
    assert_eq!(*lock(&s.tor.hosts), 3);
}

#[tokio::test]
async fn switching_tor_off_tells_the_session_then_stops_its_services_but_never_tor() {
    let mut s = setup(true, true, false).await;
    want(&mut s, vec![spec(0xA)]).await;
    s.paths.tor_changed(TorState::Ready, &s.rig.session);
    s.paths.set_tor(false, &mut s.rig.session).await;
    // SR-29-07: nothing more is routed through Tor
    assert!(!s.rig.session.state().allowed.contains(&Path::Tor));
    assert!(s.tor.live().is_empty());
    assert!(
        s.log.all().contains(&"tor stops".to_string()),
        "the carrier closes"
    );
    s.paths.set_tor(true, &mut s.rig.session).await;
    assert_eq!(
        s.tor.live(),
        HashSet::from([spec(0xA)]),
        "back on: published again"
    );
    assert_eq!(
        *lock(&s.tor.starts),
        1,
        "Tor itself was started once and kept running"
    );
}

#[tokio::test]
async fn follows_the_session_through_pair_cancel_and_unpair() {
    let mut s = setup(true, true, false).await;
    s.paths.update(&mut s.rig.session).await;
    s.paths.tor_changed(TorState::Ready, &s.rig.session);
    let paired = TorServiceSpec {
        secret: MAC_SECRET.to_vec(),
        clients: vec![mac_data().peer_discovery],
    };
    assert_eq!(s.tor.live(), HashSet::from([paired.clone()]));
    s.rig.session.start_pairing("Mac").await;
    s.paths.update(&mut s.rig.session).await;
    assert_eq!(
        s.tor.live().len(),
        2,
        "the code's new service, beside the paired one"
    );
    s.rig.session.cancel_pairing();
    s.paths.update(&mut s.rig.session).await;
    assert_eq!(
        s.tor.live(),
        HashSet::from([paired]),
        "cancelled: the new one stops"
    );
    s.rig.session.unpair_phone().await;
    s.paths.update(&mut s.rig.session).await;
    assert!(s.tor.live().is_empty(), "unpaired: nothing hosted");
    assert_eq!(
        *lock(&s.tor.starts),
        1,
        "and Tor is never put to sleep on the computer"
    );
}

#[tokio::test]
async fn switches_reach_the_session_in_the_order_they_were_made() {
    let mut s = setup(true, true, false).await;
    want(&mut s, vec![]).await;
    s.paths.set_tor(false, &mut s.rig.session).await;
    s.paths.set_tor(true, &mut s.rig.session).await;
    s.paths.set_wifi(false, &mut s.rig.session).await;
    assert_eq!(s.rig.session.state().allowed, [Path::Tor]);
}

#[tokio::test]
async fn the_carriers_run_only_while_a_phone_is_paired_or_pairing() {
    let mut s = setup_rig(Rig::unpaired().await, false);
    s.paths.update(&mut s.rig.session).await;
    assert!(
        !s.log.all().contains(&"lan starts".to_string()),
        "no phone: nothing to listen for"
    );
    s.rig.session.start_pairing("Mac").await;
    s.paths.update(&mut s.rig.session).await;
    let lan = |log: &Log| {
        log.all()
            .into_iter()
            .filter(|e| e.starts_with("lan"))
            .collect::<Vec<_>>()
    };
    assert_eq!(lan(&s.log), ["lan starts"]);
    assert!(s.log.all().contains(&"tor starts".to_string()));
    s.paths.set_wifi(false, &mut s.rig.session).await;
    assert_eq!(lan(&s.log), ["lan starts", "lan stops"]);
}

#[tokio::test]
async fn a_listener_that_cant_start_is_reported_and_tried_again_later() {
    let mut s = setup(true, true, true).await;
    want(&mut s, vec![]).await;
    assert_eq!(
        s.paths.take_events(),
        [Event::Problem(Problem::ListenerFailed)]
    );
    want(&mut s, vec![]).await; // the next state update
    assert_eq!(s.log.count("lan starts"), 2);
}

#[tokio::test]
async fn stop_closes_everything_and_starts_nothing_more() {
    let mut s = setup(true, true, false).await;
    want(&mut s, vec![spec(0xA)]).await;
    s.paths.tor_changed(TorState::Ready, &s.rig.session);
    s.paths.stop(&mut s.rig.session);
    want(&mut s, vec![spec(0xA)]).await;
    s.paths.republish(&s.rig.session);
    assert!(
        s.log.all().contains(&"lan stops".to_string())
            && s.log.all().contains(&"tor stops".to_string())
    );
    assert!(s.tor.live().is_empty());
    assert_eq!(s.log.count("lan starts"), 1);
}

#[tokio::test]
async fn restart_starts_the_listeners_again_and_republishes() {
    let mut s = setup(true, true, false).await;
    want(&mut s, vec![spec(0xA)]).await;
    s.paths.tor_changed(TorState::Ready, &s.rig.session);
    assert_eq!(*lock(&s.tor.hosts), 1);
    s.paths.restart(&mut s.rig.session).await; // Reconnect with the phone out of reach
    assert_eq!(s.log.count("lan starts"), 2);
    assert_eq!(s.log.count("tor starts"), 2);
    assert!(
        s.log.all().contains(&"lan stops".to_string())
            && s.log.all().contains(&"tor stops".to_string())
    );
    assert_eq!(s.tor.live(), HashSet::from([spec(0xA)]));
    assert_eq!(*lock(&s.tor.hosts), 2, "published again");
    assert!(
        s.rig.session.state().wifi && s.rig.session.state().tor,
        "never a switch"
    );
}

#[tokio::test]
async fn sr_55_06_a_late_event_never_takes_away_the_carrier_that_replaced_it() {
    let mut s = setup(true, false, false).await;
    want(&mut s, vec![]).await;
    let first_generation = s.paths.carriers[&Path::Lan].generation;
    for _ in 0..5 {
        s.paths.restart(&mut s.rig.session).await;
    }
    assert_eq!(lock(&s.made).len(), 6);
    let newest = lock(&s.made).last().unwrap().clone();
    let current = s.rig.session.link(Path::Lan).unwrap().clone();
    assert!(
        std::ptr::addr_eq(Arc::as_ptr(&current), Arc::as_ptr(&newest)),
        "the newest listener is the session's"
    );
    // The first listener's phone link closes late: it doesn't reach the session
    s.rig.lan_up().await; // up through the current carrier, as far as the session knows
    s.rig.session.set_link(Some(newest), Path::Lan);
    let phone = s.rig.phone_id();
    s.paths
        .carrier_event(
            Path::Lan,
            first_generation,
            LanEvent::Closed(phone),
            &mut s.rig.session,
        )
        .await;
    assert_eq!(s.rig.session.state().route, Some(Path::Lan), "still up");
}

/// SR-31-03: a listener closed while starting is never handed over. Everything runs one step at
/// a time here, so a start always finishes before the next switch change; what's left to prove is
/// that the stale one is stopped and the session keeps the newer one.
#[tokio::test]
async fn sr_31_03_a_listener_closed_and_replaced_is_stopped_and_the_newer_one_kept() {
    let mut s = setup(true, true, false).await;
    want(&mut s, vec![]).await;
    s.paths.set_wifi(false, &mut s.rig.session).await;
    s.paths.set_wifi(true, &mut s.rig.session).await;
    assert_eq!(s.log.count("lan stops"), 1, "the first one is stopped");
    let made = lock(&s.made).clone();
    let lans: Vec<_> = made.iter().collect();
    let current = s.rig.session.link(Path::Lan).unwrap().clone();
    let newest = lans.last().unwrap();
    assert!(
        std::ptr::addr_eq(Arc::as_ptr(&current), Arc::as_ptr(newest)),
        "the session keeps the newer listener"
    );
}
