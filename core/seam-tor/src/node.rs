// SPDX-License-Identifier: AGPL-3.0-only
//! One Arti client per app: started once, made dormant when Seam has nothing to do.

use std::path::Path;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use arti_client::config::TorClientConfigBuilder;
use arti_client::status::BlockageKind;
use arti_client::{DormantMode, HasKind, TorClient};
use tor_keymgr::config::ArtiKeystoreKind;
use tor_rtcompat::PreferredRuntime;

use crate::Error;
use crate::keys::OnionAddress;

/// How long Tor may take to bootstrap before [`TorNode::start`] gives up.
pub const START_TIMEOUT: Duration = Duration::from_secs(120);

/// `app_dir` must be a real directory (not a symlink) that neither its group nor others can write.
fn check_private(app_dir: &Path) -> Result<(), Error> {
    let meta = std::fs::symlink_metadata(app_dir).map_err(|_| Error::Config)?;
    if !meta.is_dir() {
        return Err(Error::Config);
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        if meta.permissions().mode() & 0o022 != 0 {
            return Err(Error::Config);
        }
    }
    Ok(())
}

/// Seam's Tor client. Holds no secrets on disk: keys live only in memory (Arti's ephemeral
/// keystore), handed in by the app from its sealed data. The state and cache directories hold
/// Tor's public directory and guard choices only.
pub struct TorNode {
    pub(crate) client: Arc<TorClient<PreferredRuntime>>,
    /// The service key loaded into Arti's keystore, once [`TorNode::host`] has run. Arti keeps it
    /// for the node's lifetime, so every later launch must use the same key.
    pub(crate) service_address: Mutex<Option<OnionAddress>>,
}

/// Where Tor is, for the app's status line.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum TorStatus {
    /// Bootstrapping; percent done.
    Starting(u8),
    Ready,
    Blocked(Blocked),
}

/// Why Tor can't connect, in the terms the app shows the user.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Blocked {
    /// No internet at all.
    Offline,
    /// The network blocks Tor (workplaces, some countries).
    Filtered,
    /// The device clock is too far off for Tor.
    ClockSkewed,
    Other,
}

impl TorNode {
    /// Starts Tor and waits until it can carry traffic. Call from inside a Tokio runtime.
    ///
    /// `app_dir` is the app's private data folder (Android `filesDir`, the Mac app's Application
    /// Support folder). Tor keeps its state and cache in `app_dir/tor`. This checks `app_dir`
    /// itself (a real directory nobody else can write), and Arti checks everything below it.
    /// Folders above `app_dir` aren't checked: on Android they're group-writable by the system, and
    /// the platform's sandbox, not file modes, is what protects them (SR-33-03).
    ///
    /// Fails with [`Error::Timeout`] if Tor can't bootstrap within [`START_TIMEOUT`]: on a network
    /// that blocks Tor it would otherwise never return (SR-33-06).
    pub async fn start(app_dir: &Path) -> Result<Self, Error> {
        check_private(app_dir)?;
        // Fails only if a provider is already installed, which is just as good
        let _ = rustls::crypto::ring::default_provider().install_default();

        let tor_dir = app_dir.join("tor");
        let mut builder =
            TorClientConfigBuilder::from_directories(tor_dir.join("state"), tor_dir.join("cache"));
        builder.storage().permissions().ignore_prefix(app_dir);
        // Keys never touch disk (docs/security/threat-model.md: copied files must be useless)
        builder
            .storage()
            .keystore()
            .primary()
            .kind(ArtiKeystoreKind::Ephemeral.into());
        builder.address_filter().allow_onion_addrs(true);
        let config = builder.build().map_err(|_| Error::Config)?;

        let runtime = PreferredRuntime::current().map_err(|_| Error::Config)?;
        let builder = TorClient::with_runtime(runtime).config(config);
        let client = tokio::time::timeout(START_TIMEOUT, builder.create_bootstrapped())
            .await
            .map_err(|_| Error::Timeout)?
            .map_err(|e| Error::Tor(e.kind()))?;
        Ok(Self {
            client,
            service_address: Mutex::new(None),
        })
    }

    /// Dormant: no background traffic until the next connection. Seam goes dormant whenever it
    /// has no Tor connection open (decision 0005: the phone holds nothing it doesn't need).
    pub fn set_dormant(&self, dormant: bool) {
        self.client.set_dormant(if dormant {
            DormantMode::Soft
        } else {
            DormantMode::Normal
        });
    }

    pub fn status(&self) -> TorStatus {
        let status = self.client.bootstrap_status();
        if let Some(blockage) = status.blocked() {
            return TorStatus::Blocked(match blockage.kind() {
                BlockageKind::Offline => Blocked::Offline,
                BlockageKind::Filtering | BlockageKind::CantReachTor => Blocked::Filtered,
                BlockageKind::ClockSkewed => Blocked::ClockSkewed,
                _ => Blocked::Other,
            });
        }
        if status.ready_for_traffic() {
            TorStatus::Ready
        } else {
            TorStatus::Starting((status.as_frac().clamp(0.0, 1.0) * 100.0) as u8)
        }
    }
}
