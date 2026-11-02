// SPDX-License-Identifier: AGPL-3.0-only
//! Tor as `Paths` runs it: the computer's onion services on `seam-tor`. Tor is started once and
//! never made dormant here: the computer is the one the phone reaches.

use std::path::PathBuf;
use std::sync::{Arc, Mutex};

use seam_tor::{DiscoveryPublic, ServiceKey, TorNode};

use crate::lan::Stream;
use crate::lock;
use crate::session::TorServiceSpec;

/// How Tor is doing, for the link's words (blocked, clock).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TorState {
    Off,
    /// Bootstrapping, percent done.
    Starting(u8),
    Ready,
    /// The network or device stops Tor: say why (never a silent fallback).
    Blocked(TorBlock),
    Failed,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TorBlock {
    Offline,
    /// The network blocks Tor (workplaces, some countries).
    Filtered,
    /// The clock is too far off for Tor.
    ClockSkewed,
    Other,
}

pub type StateSink = Arc<dyn Fn(TorState) + Send + Sync>;
pub type StreamSink = Arc<dyn Fn(Stream) + Send + Sync>;

pub trait TorRunner: Send + Sync {
    /// Starts Tor once; `on_state` hears every change (on Tor's threads).
    fn start(&self, on_state: StateSink);
    /// Publishes a service; its streams go to `on_stream`. None while Tor isn't ready.
    fn host(&self, spec: &TorServiceSpec, on_stream: StreamSink) -> Option<Box<dyn TorHosted>>;
}

/// A published onion service.
pub trait TorHosted: Send {
    fn stop(&self);
}

/// The real one. Tor keeps its state in `<app_dir>/tor`.
pub struct SeamTor {
    app_dir: PathBuf,
    node: Arc<Mutex<Option<Arc<TorNode>>>>,
}

impl SeamTor {
    pub fn new(app_dir: PathBuf) -> Self {
        Self {
            app_dir,
            node: Arc::default(),
        }
    }
}

impl TorRunner for SeamTor {
    fn start(&self, on_state: StateSink) {
        let (dir, node) = (self.app_dir.clone(), self.node.clone());
        tokio::spawn(async move {
            on_state(TorState::Starting(0));
            match TorNode::start(&dir).await {
                Ok(started) => {
                    *lock(&node) = Some(Arc::new(started));
                    on_state(TorState::Ready);
                }
                Err(seam_tor::Error::Timeout) => on_state(TorState::Blocked(TorBlock::Filtered)),
                Err(_) => on_state(TorState::Failed),
            }
        });
    }

    fn host(&self, spec: &TorServiceSpec, on_stream: StreamSink) -> Option<Box<dyn TorHosted>> {
        let node = lock(&self.node).clone()?;
        let key = ServiceKey::from_bytes(&spec.secret).ok()?;
        let clients = spec
            .clients
            .iter()
            .map(|c| DiscoveryPublic::from_bytes(c))
            .collect::<Result<Vec<_>, _>>()
            .ok()?;
        let mut hosted = node.host(&key, &clients).ok()?;
        let task = tokio::spawn(async move {
            while let Some(stream) = hosted.accept().await {
                on_stream(Box::new(stream));
            }
            // The service stopped by itself; Paths republishes after wake or the next change
        });
        Some(Box::new(Hosted(task)))
    }
}

struct Hosted(tokio::task::JoinHandle<()>);

impl TorHosted for Hosted {
    fn stop(&self) {
        self.0.abort();
    }
}

impl Drop for Hosted {
    fn drop(&mut self) {
        self.0.abort();
    }
}
