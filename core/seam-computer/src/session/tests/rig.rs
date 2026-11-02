// SPDX-License-Identifier: AGPL-3.0-only
use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use async_trait::async_trait;
use prost::Message;
use seam_core::Device;
use seam_core::hello::Facts;
use seam_core::links::{AppData, Path};
use seam_core::proto::v1::{
    self, Envelope, Hello, NotificationSettingsChange, OpenLinks, Payload, ServerFrame, control,
    payload::Body, server_frame,
};
use seam_tor::{DiscoveryKey, ServiceKey};

use crate::durable::DurableFile;
use crate::features::Features;
use crate::features::clipboard::{Clipboard, Pasteboard, PasteboardItem};
use crate::features::notifications::{NotificationCentre, Notifications};
use crate::features::status::{Battery, Status};
use crate::features::{Command as FeatureCommand, clipboard, notifications, status};
use crate::lan::{LanEvent, LanSender};
use crate::outbox::OutboxFile;
use crate::session::{Core, Preferences, Session, Storage};
use crate::testing::paired_devices;
use crate::timers::{Due, TimerToken, Timers};

pub fn lock<T>(m: &Mutex<T>) -> MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|p| p.into_inner())
}

/// A clock that only moves when the test says, handing back what was due on the way, in order.
pub struct FakeTimers {
    inner: Mutex<TimersInner>,
}

struct TimersInner {
    now: i64,
    next: u64,
    jobs: Vec<(u64, i64, Due)>,
}

impl FakeTimers {
    /// Starts at the real time: the core stamps envelopes with it.
    fn new() -> Arc<Self> {
        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_millis() as i64;
        Arc::new(Self {
            inner: Mutex::new(TimersInner {
                now,
                next: 0,
                jobs: Vec::new(),
            }),
        })
    }

    /// The earliest job due by `target`, with the clock moved to it.
    fn next_due(&self, target: i64) -> Option<Due> {
        let mut t = lock(&self.inner);
        let (i, _) = t
            .jobs
            .iter()
            .enumerate()
            .filter(|(_, j)| j.1 <= target)
            .min_by_key(|(_, j)| (j.1, j.0))?;
        let (_, at, due) = t.jobs.remove(i);
        t.now = t.now.max(at);
        Some(due)
    }

    fn set(&self, now: i64) {
        lock(&self.inner).now = now;
    }
}

impl Timers for Arc<FakeTimers> {
    fn now_ms(&self) -> i64 {
        lock(&self.inner).now
    }

    fn after(&self, delay: Duration, due: Due) -> TimerToken {
        let mut t = lock(&self.inner);
        t.next += 1;
        let (id, at) = (t.next, t.now + delay.as_millis() as i64);
        t.jobs.push((id, at, due));
        let me = Arc::downgrade(self);
        TimerToken::new(move || {
            if let Some(me) = me.upgrade() {
                lock(&me.inner).jobs.retain(|j| j.0 != id);
            }
        })
    }
}

/// A carrier's streams as the session sees them: Same Wi‑Fi's, or the Tor service's.
#[derive(Default)]
pub struct FakeLan {
    pub frames: Mutex<Vec<ServerFrame>>,
    pub closed: Mutex<Vec<String>>,
    /// The stream dropped: sends fail.
    pub failing: AtomicBool,
}

impl FakeLan {
    pub fn frames(&self) -> Vec<ServerFrame> {
        lock(&self.frames).clone()
    }

    pub fn closed(&self) -> Vec<String> {
        lock(&self.closed).clone()
    }

    pub fn last(&self) -> Option<server_frame::Frame> {
        lock(&self.frames).last().and_then(|f| f.frame.clone())
    }

    pub fn delivered(&self) -> Vec<Envelope> {
        self.frames()
            .into_iter()
            .filter_map(|f| match f.frame {
                Some(server_frame::Frame::Deliver(e)) => Some(e),
                _ => None,
            })
            .collect()
    }
}

impl LanSender for FakeLan {
    fn send(&self, frame: &ServerFrame, _to: &str) -> bool {
        if self.failing.load(Ordering::SeqCst) {
            return false;
        }
        lock(&self.frames).push(frame.clone());
        true
    }

    fn close(&self, peer: &str) {
        lock(&self.closed).push(peer.to_owned());
    }
}

type DuringRead = Box<dyn FnOnce() + Send>;

#[derive(Default)]
pub struct MemoryPasteboard {
    state: Mutex<(i64, Option<PasteboardItem>)>,
    /// Runs while the next read is in progress, once: something the user does mid-read.
    pub while_reading: Mutex<Option<DuringRead>>,
}

impl MemoryPasteboard {
    pub fn text(&self) -> Option<String> {
        lock(&self.state).1.as_ref().and_then(|i| i.text.clone())
    }

    /// Someone copied something on this computer.
    pub fn copy(&self, text: &str) {
        self.copy_typed(text, &["public.utf8-plain-text"]);
    }

    pub fn copy_typed(&self, text: &str, types: &[&str]) {
        let mut s = lock(&self.state);
        s.0 += 1;
        s.1 = Some(PasteboardItem {
            types: types.iter().map(|t| t.to_string()).collect(),
            text: Some(text.to_owned()),
        });
    }
}

#[async_trait]
impl Pasteboard for MemoryPasteboard {
    async fn change_count(&self) -> i64 {
        lock(&self.state).0
    }

    async fn first_item(&self, if_still: i64) -> Option<PasteboardItem> {
        if let Some(during) = lock(&self.while_reading).take() {
            during();
        }
        let s = lock(&self.state);
        (s.0 == if_still).then(|| s.1.clone()).flatten()
    }

    async fn write(&self, text: String) -> i64 {
        let mut s = lock(&self.state);
        s.0 += 1;
        s.1 = Some(PasteboardItem {
            types: vec!["public.utf8-plain-text".into()],
            text: Some(text),
        });
        s.0
    }
}

#[derive(Default)]
pub struct MemoryNotifications {
    pub shown: Mutex<Vec<(v1::Notification, Option<Vec<u8>>)>>,
    pub dismissed: Mutex<Vec<String>>,
}

#[async_trait]
impl NotificationCentre for MemoryNotifications {
    async fn show(&self, n: v1::Notification, icon: Option<Vec<u8>>) {
        lock(&self.shown).push((n, icon));
    }

    async fn dismiss(&self, key: String) {
        lock(&self.dismissed).push(key);
    }

    async fn remove_all(&self) {
        lock(&self.shown).clear();
    }
}

/// The session's storage, in memory.
pub struct MemoryStorage {
    saved: Arc<Saved>,
    device: Arc<Mutex<Device>>,
    outbox: OutboxFile,
}

impl Storage for MemoryStorage {
    fn save_state(&self) -> std::io::Result<()> {
        self.saved.save(&self.device)
    }

    fn outbox(&self) -> &OutboxFile {
        &self.outbox
    }

    fn preferences(&self) -> Preferences {
        lock(&self.saved.prefs).clone()
    }

    fn save_preferences(&self, p: &Preferences) {
        *lock(&self.saved.prefs) = p.clone();
    }
}

/// The core state as last saved, in memory; a save can be made to fail.
#[derive(Default)]
pub struct Saved {
    pub blob: Mutex<Option<Vec<u8>>>,
    pub failing: AtomicBool,
    pub prefs: Mutex<Preferences>,
    /// This computer's battery, as the session reads it.
    pub own_status: Mutex<Option<v1::Status>>,
}

pub const KEY: [u8; 32] = [7; 32];

impl Saved {
    fn save(&self, device: &Mutex<Device>) -> std::io::Result<()> {
        if self.failing.load(Ordering::SeqCst) {
            return Err(std::io::Error::other("disk full"));
        }
        let blob = lock(device)
            .export_state(&KEY)
            .map_err(std::io::Error::other)?;
        *lock(&self.blob) = Some(blob);
        Ok(())
    }
}

impl Battery for Saved {
    fn read(&self) -> Option<v1::Status> {
        lock(&self.own_status).clone()
    }
}

pub struct Rig {
    pub mac: Arc<Mutex<Device>>,
    pub phone: Device,
    pub lan: Arc<FakeLan>,
    /// The Tor service's streams.
    pub tor: Arc<FakeLan>,
    pub pasteboard: Arc<MemoryPasteboard>,
    pub centre: Arc<MemoryNotifications>,
    pub timers: Arc<FakeTimers>,
    pub saved: Arc<Saved>,
    pub outbox: OutboxFile,
    pub sync_on: Arc<AtomicBool>,
    pub session: Session,
    opened: HashMap<Vec<u8>, Payload>,
    _dir: tempfile::TempDir,
}

/// The computer's service secret, and the phone's discovery secret, in a paired rig.
pub const MAC_SECRET: [u8; 32] = [1; 32];
pub const PHONE_DISCOVERY: [u8; 32] = [2; 32];

/// A paired computer's app data: its service, findable only by the phone's discovery key.
pub fn mac_data() -> AppData {
    let public = DiscoveryKey::from_bytes(&PHONE_DISCOVERY).unwrap().public();
    AppData::new(
        MAC_SECRET.to_vec(),
        String::new(),
        public.to_bytes().to_vec(),
    )
}

pub fn onion_address(secret: &[u8]) -> String {
    ServiceKey::from_bytes(secret)
        .unwrap()
        .address()
        .to_string()
}

pub struct Options {
    pub paired: bool,
    pub wifi: bool,
    pub tor: bool,
}

impl Default for Options {
    fn default() -> Self {
        Self {
            paired: true,
            wifi: true,
            tor: true,
        }
    }
}

impl Rig {
    pub async fn new() -> Self {
        Self::with(Options::default()).await
    }

    pub async fn unpaired() -> Self {
        Self::with(Options {
            paired: false,
            ..Default::default()
        })
        .await
    }

    pub async fn with(o: Options) -> Self {
        let (mut mac, mut phone) = if o.paired {
            paired_devices()
        } else {
            (Device::generate().unwrap(), Device::generate().unwrap())
        };
        if o.paired {
            mac.set_app_data(&mac_data()).unwrap();
            let data = AppData::new(
                PHONE_DISCOVERY.to_vec(),
                onion_address(&MAC_SECRET),
                Vec::new(),
            );
            phone.set_app_data(&data).unwrap();
        }
        let dir = tempfile::tempdir().unwrap();
        let mut rig = Self {
            mac: Arc::new(Mutex::new(mac)),
            phone,
            lan: Arc::default(),
            tor: Arc::default(),
            pasteboard: Arc::default(),
            centre: Arc::default(),
            timers: FakeTimers::new(),
            saved: Arc::default(),
            outbox: OutboxFile::new(DurableFile::new(dir.path().join("outbox.bin"))),
            sync_on: Arc::new(AtomicBool::new(true)),
            session: placeholder(),
            opened: HashMap::new(),
            _dir: dir,
        };
        rig.session = rig.make_session(o.wifi, o.tor);
        rig.saved.save(&rig.mac).unwrap();
        rig.session.start().await;
        rig
    }

    fn make_session(&self, wifi: bool, tor: bool) -> Session {
        let storage = Box::new(MemoryStorage {
            saved: self.saved.clone(),
            device: self.mac.clone(),
            outbox: self.outbox.clone(),
        });
        let timers: Arc<dyn Timers> = Arc::new(self.timers.clone());
        {
            let mut p = lock(&self.saved.prefs);
            p.wifi = wifi;
            p.tor = tor;
        }
        let core = Core::new(
            self.mac.clone(),
            storage,
            timers,
            "test".into(),
            "macos".into(),
        );
        let features = Features {
            clipboard: Clipboard::new(self.pasteboard.clone(), self.sync_on.clone()),
            notifications: Notifications::new(self.centre.clone()),
            status: Status::new(self.saved.clone()),
        };
        Session::new(core, features)
    }

    /// Quit and relaunch: the computer comes back from what it saved, with a new session.
    pub async fn restart(&mut self) {
        self.session.stop();
        let blob = lock(&self.saved.blob).clone().unwrap();
        self.mac = Arc::new(Mutex::new(Device::restore(&blob, &KEY).unwrap()));
        self.sync_on.store(true, Ordering::SeqCst);
        let p = lock(&self.saved.prefs).clone();
        self.session = self.make_session(p.wifi, p.tor);
        self.session.start().await;
    }

    pub fn now(&self) -> i64 {
        self.timers.now_ms()
    }

    /// Moves time on, handing the session what falls due on the way, in order.
    pub async fn advance(&mut self, seconds: f64) {
        let target = self.now() + (seconds * 1000.0) as i64;
        while let Some(due) = self.timers.next_due(target) {
            self.session.due(due).await;
        }
        self.timers.set(target);
    }

    pub fn phone_id(&self) -> String {
        self.phone.id().to_owned()
    }

    pub fn mac_id(&self) -> String {
        lock(&self.mac).id().to_owned()
    }

    pub fn mac(&self) -> MutexGuard<'_, Device> {
        lock(&self.mac)
    }

    /// The phone connected through the computer's onion service.
    pub async fn tor_up(&mut self) {
        self.session.set_link(Some(self.tor.clone()), Path::Tor);
        let id = self.phone_id();
        self.session
            .link_event(LanEvent::Authenticated(id), Path::Tor)
            .await;
    }

    pub async fn lan_up(&mut self) {
        self.session.set_link(Some(self.lan.clone()), Path::Lan);
        let id = self.phone_id();
        self.session
            .link_event(LanEvent::Authenticated(id), Path::Lan)
            .await;
    }

    pub async fn event(&mut self, event: LanEvent, path: Path) {
        self.session.link_event(event, path).await;
    }

    /// Something for the phone that isn't a Hello, told apart by `n`.
    pub async fn send_something(&mut self, n: i64) {
        self.notifications(notifications::Command::Change(NotificationSettingsChange {
            paused_until: Some(n),
            ..Default::default()
        }))
        .await;
    }

    pub async fn clipboard(&mut self, c: clipboard::Command) {
        // As the app does: the shared switch flips first, then the session hears (SR-29-05)
        if let clipboard::Command::SetSync(on) = c {
            self.sync_on.store(on, Ordering::SeqCst);
        }
        self.session.feature(FeatureCommand::Clipboard(c)).await;
    }

    pub async fn notifications(&mut self, c: notifications::Command) {
        self.session.feature(FeatureCommand::Notifications(c)).await;
    }

    pub async fn battery_changed(&mut self) {
        self.session
            .feature(FeatureCommand::Status(status::Command::BatteryChanged))
            .await;
    }

    /// The phone seals `payload` for the computer.
    pub fn sealed_by_phone(&mut self, payload: &Payload) -> Envelope {
        let sealed = self
            .phone
            .seal(&self.mac_id(), &payload.encode_to_vec(), 0)
            .unwrap();
        Envelope::decode(sealed.as_slice()).unwrap()
    }

    pub async fn receive(&mut self, payload: Payload) -> bool {
        let e = self.sealed_by_phone(&payload);
        self.session.received(e).await
    }

    /// The phone's Hello, as its switches say. `open_links`: the links the phone says it has open
    /// (None: an older phone that doesn't say).
    pub fn phone_hello(
        &mut self,
        wifi: bool,
        tor: bool,
        paused_until_ms: i64,
        open_links: Option<Vec<Path>>,
    ) -> Envelope {
        let mut enabled = Vec::new();
        if wifi {
            enabled.push(Path::Lan);
        }
        if tor {
            enabled.push(Path::Tor);
        }
        let facts = Facts {
            app_version: "test".into(),
            platform: "android".into(),
            enabled,
            open: Vec::new(),
            paused_until_ms,
            capabilities: seam_core::catalogue::CAPABILITIES
                .iter()
                .map(|c| c.to_string())
                .collect(),
        };
        let mut payload = self.phone.edit_app_data(|d| d.hello(&facts)).unwrap();
        if let Some(Body::Control(c)) = &mut payload.body
            && let Some(control::Body::Hello(h)) = &mut c.body
        {
            h.open_links = open_links.map(|l| OpenLinks {
                transports: l.into_iter().map(Path::transport).collect(),
            });
        }
        self.sealed_by_phone(&payload)
    }

    /// What reached the phone on a carrier, opened by the phone, in order. The same envelope can
    /// go twice (requeued after a drop); the phone opens it once.
    pub fn opened(&mut self, lan: &FakeLan) -> Vec<Payload> {
        lan.delivered()
            .into_iter()
            .map(|e| {
                if let Some(p) = self.opened.get(&e.msg_id) {
                    return p.clone();
                }
                let o = self.phone.open(&e.encode_to_vec()).unwrap();
                let p = Payload::decode(o.payload.as_slice()).unwrap();
                self.opened.insert(e.msg_id.clone(), p.clone());
                p
            })
            .collect()
    }

    pub fn on_tor(&mut self) -> Vec<Payload> {
        let tor = self.tor.clone();
        self.opened(&tor)
    }

    pub fn on_lan(&mut self) -> Vec<Payload> {
        let lan = self.lan.clone();
        self.opened(&lan)
    }
}

/// Filled in by `Rig::with` once the rig's parts exist.
fn placeholder() -> Session {
    let device = Arc::new(Mutex::new(Device::generate().unwrap()));
    let dir = std::env::temp_dir().join("seam-placeholder-outbox");
    let storage = Box::new(MemoryStorage {
        saved: Arc::default(),
        device: device.clone(),
        outbox: OutboxFile::new(DurableFile::new(dir)),
    });
    let timers: Arc<dyn Timers> = Arc::new(FakeTimers::new());
    let core = Core::new(device, storage, timers, String::new(), String::new());
    struct No;
    impl Battery for No {
        fn read(&self) -> Option<v1::Status> {
            None
        }
    }
    Session::new(
        core,
        Features {
            clipboard: Clipboard::new(Arc::new(MemoryPasteboard::default()), Arc::default()),
            notifications: Notifications::new(Arc::new(MemoryNotifications::default())),
            status: Status::new(Arc::new(No)),
        },
    )
}

pub fn hello_of(p: &Payload) -> Option<&Hello> {
    match &p.body {
        Some(Body::Control(c)) => match &c.body {
            Some(control::Body::Hello(h)) => Some(h),
            _ => None,
        },
        _ => None,
    }
}

pub fn is_hello(p: &Payload) -> bool {
    hello_of(p).is_some()
}

/// Without Hellos, which go whenever a path comes up.
pub fn messages(ps: &[Payload]) -> Vec<Payload> {
    ps.iter().filter(|p| !is_hello(p)).cloned().collect()
}

/// The `send_something` numbers, in order.
pub fn numbers(ps: &[Payload]) -> Vec<i64> {
    ps.iter()
        .filter_map(|p| match &p.body {
            Some(Body::NotificationSettingsChange(c)) => c.paused_until,
            _ => None,
        })
        .collect()
}

pub fn clips(ps: &[Payload]) -> Vec<String> {
    ps.iter()
        .filter_map(|p| match &p.body {
            Some(Body::Clipboard(c)) => Some(c.text.clone()),
            _ => None,
        })
        .collect()
}

pub fn clip(text: &str) -> Payload {
    Payload {
        body: Some(Body::Clipboard(v1::Clipboard {
            text: text.into(),
            ..Default::default()
        })),
    }
}

pub fn body(b: Body) -> Payload {
    Payload { body: Some(b) }
}

pub fn control_payload(b: control::Body) -> Payload {
    body(Body::Control(v1::Control { body: Some(b) }))
}
