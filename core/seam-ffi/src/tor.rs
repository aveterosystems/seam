// SPDX-License-Identifier: AGPL-3.0-only
//! Tor for the apps (seam-tor behind UniFFI): one `Tor` per app, which starts Arti, sleeps it,
//! hosts the Mac's private onion service and connects the phone to it. Every connection is a
//! `TorStream`: a byte pipe the apps run the same `LanChannel` over as on Same Wi-Fi, so Tor never
//! sees anything but sealed, authenticated bytes.
//!
//! Callbacks run on Tor's own threads: the apps hop to theirs. Nothing here logs.
//!
//! Starting Tor installs ring as the process's TLS provider (rustls), once; nothing else in Seam
//! uses TLS in process, so nothing else is affected (SR-33-12).

use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};

use seam_tor::{Blocked, DiscoveryKey, OnionAddress, ServiceKey, TorNode, TorStatus};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::runtime::Runtime;
use tokio::sync::{mpsc, watch};

/// How Tor is doing, for the apps' status line.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum TorState {
    Off,
    /// Bootstrapping, percent done.
    Starting {
        percent: u8,
    },
    Ready,
    /// The network or device stops Tor: say why (decision 0004: never a silent fallback).
    Blocked {
        reason: TorBlock,
    },
    Failed,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum TorBlock {
    Offline,
    /// The network blocks Tor (workplaces, some countries).
    Filtered,
    /// The device clock is too far off for Tor.
    ClockSkewed,
    Other,
}

/// Told when Tor's state changes. Called on Tor's threads.
#[uniffi::export(with_foreign)]
pub trait TorStateListener: Send + Sync {
    fn on_state(&self, state: TorState);
}

/// What a stream reports. Called on Tor's threads, in order, `on_closed` exactly once.
#[uniffi::export(with_foreign)]
pub trait TorStreamListener: Send + Sync {
    fn on_bytes(&self, bytes: Vec<u8>);
    fn on_closed(&self);
}

/// Told about a connection attempt (phone) or an incoming stream (Mac).
#[uniffi::export(with_foreign)]
pub trait TorConnectListener: Send + Sync {
    /// Connected: call `stream.listen` to start receiving, `stream.shutdown` to end it.
    fn on_open(&self, stream: Arc<TorStream>);
    /// Couldn't connect (or, for a service, it stopped). Never called after `on_open` for the
    /// same attempt.
    fn on_failed(&self);
}

/// Tor for one app. Create once; `start` before `host` or `connect`.
#[derive(uniffi::Object)]
pub struct Tor {
    runtime: Runtime,
    app_dir: PathBuf,
    node: Arc<Mutex<Option<Arc<TorNode>>>>,
    state: Arc<Mutex<TorState>>,
    started: AtomicBool,
}

#[uniffi::export]
impl Tor {
    /// `app_dir`: the app's private data folder (Tor keeps its state in `app_dir/tor`).
    #[uniffi::constructor]
    pub fn new(app_dir: String) -> Arc<Self> {
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .thread_name("seam-tor")
            .enable_all()
            .build()
            .expect("a Tokio runtime");
        Arc::new(Self {
            runtime,
            app_dir: PathBuf::from(app_dir),
            node: Arc::default(),
            state: Arc::new(Mutex::new(TorState::Off)),
            started: AtomicBool::new(false),
        })
    }

    /// Starts Tor in the background, once; `listener` hears every state change.
    pub fn start(&self, listener: Arc<dyn TorStateListener>) {
        if self.started.swap(true, Ordering::SeqCst) {
            return listener.on_state(self.state());
        }
        let (dir, node, state) = (self.app_dir.clone(), self.node.clone(), self.state.clone());
        let set = move |s: TorState| {
            *state.lock().unwrap_or_else(|p| p.into_inner()) = s.clone();
            listener.on_state(s);
        };
        self.runtime.spawn(async move {
            set(TorState::Starting { percent: 0 });
            match TorNode::start(&dir).await {
                Ok(started) => {
                    *node.lock().unwrap_or_else(|p| p.into_inner()) = Some(Arc::new(started));
                    set(TorState::Ready);
                }
                Err(seam_tor::Error::Timeout) => set(TorState::Blocked {
                    reason: TorBlock::Filtered,
                }),
                Err(_) => set(TorState::Failed),
            }
        });
    }

    pub fn state(&self) -> TorState {
        let node = self.node.lock().unwrap_or_else(|p| p.into_inner()).clone();
        match node.map(|n| n.status()) {
            Some(TorStatus::Ready) => TorState::Ready,
            Some(TorStatus::Starting(percent)) => TorState::Starting { percent },
            Some(TorStatus::Blocked(b)) => TorState::Blocked { reason: block(b) },
            None => self.state.lock().unwrap_or_else(|p| p.into_inner()).clone(),
        }
    }

    /// Dormant: no background traffic until the next connection (the phone between connections).
    pub fn set_dormant(&self, dormant: bool) {
        if let Some(node) = self.node() {
            node.set_dormant(dormant);
        }
    }

    /// Phone: connects to the Mac's onion service. `listener` hears `on_open` or `on_failed`.
    pub fn connect(
        &self,
        onion_address: String,
        discovery_secret: Vec<u8>,
        listener: Arc<dyn TorConnectListener>,
    ) {
        let (Some(node), Ok(address), Ok(key)) = (
            self.node(),
            onion_address.parse::<OnionAddress>(),
            DiscoveryKey::from_bytes(&discovery_secret),
        ) else {
            return listener.on_failed();
        };
        let handle = self.runtime.handle().clone();
        self.runtime.spawn(async move {
            match node.connect(address, &key).await {
                Ok(stream) => listener.on_open(TorStream::new(stream, handle)),
                Err(_) => listener.on_failed(),
            }
        });
    }

    /// Mac: publishes the onion service for `service_secret`, discoverable only by `clients`
    /// (discovery public keys). Each incoming stream goes to `listener.on_open`; `on_failed` if the
    /// service stops by itself. Stop it with `TorService.stop`.
    pub fn host(
        &self,
        service_secret: Vec<u8>,
        clients: Vec<Vec<u8>>,
        listener: Arc<dyn TorConnectListener>,
    ) -> Option<Arc<TorService>> {
        let node = self.node()?;
        let key = ServiceKey::from_bytes(&service_secret).ok()?;
        let clients = clients
            .iter()
            .map(|c| seam_tor::DiscoveryPublic::from_bytes(c))
            .collect::<Result<Vec<_>, _>>()
            .ok()?;
        let mut hosted = node.host(&key, &clients).ok()?;
        let address = hosted.address().to_string();
        let handle = self.runtime.handle().clone();
        let task = self.runtime.spawn(async move {
            while let Some(stream) = hosted.accept().await {
                listener.on_open(TorStream::new(stream, handle.clone()));
            }
            listener.on_failed();
        });
        Some(Arc::new(TorService { address, task }))
    }
}

impl Tor {
    fn node(&self) -> Option<Arc<TorNode>> {
        self.node.lock().unwrap_or_else(|p| p.into_inner()).clone()
    }
}

fn block(b: Blocked) -> TorBlock {
    match b {
        Blocked::Offline => TorBlock::Offline,
        Blocked::Filtered => TorBlock::Filtered,
        Blocked::ClockSkewed => TorBlock::ClockSkewed,
        Blocked::Other => TorBlock::Other,
    }
}

/// A running onion service.
#[derive(uniffi::Object)]
pub struct TorService {
    address: String,
    task: tokio::task::JoinHandle<()>,
}

#[uniffi::export]
impl TorService {
    pub fn address(&self) -> String {
        self.address.clone()
    }

    /// Takes the service down (streams already open stay until closed).
    pub fn stop(&self) {
        self.task.abort();
    }
}

impl Drop for TorService {
    fn drop(&mut self) {
        self.task.abort();
    }
}

/// A byte pipe over Tor. `send` queues bytes for writing in order; `listen` starts reading.
#[derive(uniffi::Object)]
pub struct TorStream {
    writes: mpsc::UnboundedSender<Vec<u8>>,
    reader: Mutex<Option<Box<dyn AsyncRead + Send + Unpin>>>,
    closed: Arc<AtomicBool>,
    /// Set once to stop both directions; it stays set, so a task that starts later sees it too.
    stop: Arc<watch::Sender<bool>>,
    handle: tokio::runtime::Handle,
}

/// Waits until `stop` is set (at once if it already is).
async fn stopped(mut stop: watch::Receiver<bool>) {
    let _ = stop.wait_for(|stop| *stop).await;
}

impl TorStream {
    fn new<S>(stream: S, handle: tokio::runtime::Handle) -> Arc<Self>
    where
        S: AsyncRead + AsyncWrite + Send + Unpin + 'static,
    {
        let (read, mut write) = tokio::io::split(stream);
        let (writes, mut queued) = mpsc::unbounded_channel::<Vec<u8>>();
        let (closed, stop) = (
            Arc::new(AtomicBool::new(false)),
            Arc::new(watch::Sender::new(false)),
        );
        let (done, halt) = (closed.clone(), stop.subscribe());
        handle.spawn(async move {
            loop {
                tokio::select! {
                    next = queued.recv() => match next {
                        Some(bytes) if write.write_all(&bytes).await.is_ok() && write.flush().await.is_ok() => {}
                        _ => break,
                    },
                    () = stopped(halt.clone()) => break,
                }
            }
            let _ = write.shutdown().await;
            done.store(true, Ordering::SeqCst);
        });
        Arc::new(Self {
            writes,
            reader: Mutex::new(Some(Box::new(read))),
            closed,
            stop,
            handle,
        })
    }
}

#[uniffi::export]
impl TorStream {
    /// False once the stream is closed (the bytes won't go).
    pub fn send(&self, bytes: Vec<u8>) -> bool {
        !self.closed.load(Ordering::SeqCst) && self.writes.send(bytes).is_ok()
    }

    /// Starts reading: every chunk to `listener.on_bytes`, then `on_closed` once. Only the first
    /// call does anything.
    pub fn listen(&self, listener: Arc<dyn TorStreamListener>) {
        let Some(mut read) = self.reader.lock().unwrap_or_else(|p| p.into_inner()).take() else {
            return;
        };
        let (closed, stop, halt) = (
            self.closed.clone(),
            self.stop.clone(),
            self.stop.subscribe(),
        );
        self.handle.spawn(async move {
            let mut buf = vec![0u8; 16 * 1024];
            loop {
                tokio::select! {
                    n = read.read(&mut buf) => match n {
                        Ok(n) if n > 0 => listener.on_bytes(buf[..n].to_vec()),
                        _ => break,
                    },
                    () = stopped(halt.clone()) => break,
                }
            }
            closed.store(true, Ordering::SeqCst);
            stop.send_replace(true); // the writer stops too
            listener.on_closed();
        });
    }

    /// Closes both directions; `on_closed` follows. (Not `close`: Kotlin's `AutoCloseable.close`
    /// on every UniFFI object frees it.)
    pub fn shutdown(&self) {
        self.closed.store(true, Ordering::SeqCst);
        self.stop.send_replace(true);
    }
}

/// A fresh 32-byte Tor secret: the Mac's onion service seed, or a phone's discovery secret.
#[uniffi::export]
pub fn tor_new_secret() -> Vec<u8> {
    ServiceKey::generate()
        .map(|k| k.to_bytes().to_vec())
        .unwrap_or_default()
}

/// The onion address for a service secret ("" for one that isn't 32 bytes).
#[uniffi::export]
pub fn tor_onion_address(service_secret: Vec<u8>) -> String {
    ServiceKey::from_bytes(&service_secret)
        .map(|k| k.address().to_string())
        .unwrap_or_default()
}

/// The public half of a discovery secret, which the Mac's service lets discover it (empty for
/// one that isn't 32 bytes).
#[uniffi::export]
pub fn tor_discovery_public(discovery_secret: Vec<u8>) -> Vec<u8> {
    DiscoveryKey::from_bytes(&discovery_secret)
        .map(|k| k.public().to_bytes().to_vec())
        .unwrap_or_default()
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::mpsc::{Receiver, channel};
    use std::time::Duration;

    struct Collect(std::sync::mpsc::Sender<Option<Vec<u8>>>);
    impl TorStreamListener for Collect {
        fn on_bytes(&self, bytes: Vec<u8>) {
            let _ = self.0.send(Some(bytes));
        }
        fn on_closed(&self) {
            let _ = self.0.send(None);
        }
    }

    fn stream_pair(rt: &Runtime) -> (Arc<TorStream>, Arc<TorStream>) {
        let (a, b) = tokio::io::duplex(64 * 1024);
        (
            TorStream::new(a, rt.handle().clone()),
            TorStream::new(b, rt.handle().clone()),
        )
    }

    fn collect(stream: &TorStream) -> Receiver<Option<Vec<u8>>> {
        let (tx, rx) = channel();
        stream.listen(Arc::new(Collect(tx)));
        rx
    }

    fn rt() -> Runtime {
        tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .enable_all()
            .build()
            .unwrap()
    }

    #[test]
    fn bytes_cross_in_order_and_close_is_reported_once() {
        let rt = rt();
        let (a, b) = stream_pair(&rt);
        let got = collect(&b);
        for chunk in [b"one".to_vec(), vec![7u8; 100_000], b"three".to_vec()] {
            assert!(a.send(chunk));
        }
        let mut all = Vec::new();
        while all.len() < 3 + 100_000 + 5 {
            all.extend(got.recv_timeout(Duration::from_secs(5)).unwrap().unwrap());
        }
        assert_eq!(&all[..3], b"one");
        assert_eq!(&all[all.len() - 5..], b"three");
        a.shutdown();
        assert_eq!(
            got.recv_timeout(Duration::from_secs(5)).unwrap(),
            None,
            "the far end sees the close"
        );
        assert!(
            got.recv_timeout(Duration::from_millis(200)).is_err(),
            "once"
        );
    }

    #[test]
    fn a_closed_stream_refuses_sends_and_reports_its_own_close() {
        let rt = rt();
        let (a, _b) = stream_pair(&rt);
        let mine = collect(&a);
        a.shutdown();
        assert_eq!(mine.recv_timeout(Duration::from_secs(5)).unwrap(), None);
        assert!(!a.send(b"late".to_vec()));
    }

    #[test]
    fn listen_starts_reading_only_once() {
        let rt = rt();
        let (a, b) = stream_pair(&rt);
        let first = collect(&b);
        let second = collect(&b);
        assert!(a.send(b"x".to_vec()));
        assert_eq!(
            first.recv_timeout(Duration::from_secs(5)).unwrap(),
            Some(b"x".to_vec())
        );
        assert!(second.recv_timeout(Duration::from_millis(200)).is_err());
    }

    #[test]
    fn key_helpers_derive_and_refuse_bad_lengths() {
        let secret = tor_new_secret();
        assert_eq!(secret.len(), 32);
        let address = tor_onion_address(secret.clone());
        assert!(address.ends_with(".onion") && address.len() == 62);
        assert_eq!(tor_onion_address(secret), address, "stable");
        assert_eq!(tor_discovery_public(tor_new_secret()).len(), 32);
        assert_eq!(tor_onion_address(vec![1; 31]), "");
        assert!(tor_discovery_public(vec![1; 33]).is_empty());
    }

    #[test]
    fn nothing_works_before_tor_has_started() {
        struct Failed(std::sync::mpsc::Sender<bool>);
        impl TorConnectListener for Failed {
            fn on_open(&self, _: Arc<TorStream>) {
                let _ = self.0.send(true);
            }
            fn on_failed(&self) {
                let _ = self.0.send(false);
            }
        }
        let tor = Tor::new(std::env::temp_dir().to_string_lossy().into_owned());
        assert_eq!(tor.state(), TorState::Off);
        let (tx, rx) = channel();
        tor.connect(
            tor_onion_address(tor_new_secret()),
            tor_new_secret(),
            Arc::new(Failed(tx.clone())),
        );
        assert!(
            !rx.recv_timeout(Duration::from_secs(5)).unwrap(),
            "connect fails before start"
        );
        assert!(
            tor.host(
                tor_new_secret(),
                vec![tor_discovery_public(tor_new_secret())],
                Arc::new(Failed(tx))
            )
            .is_none()
        );
    }
}
