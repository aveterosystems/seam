// SPDX-License-Identifier: AGPL-3.0-only
//! The phone's side: one stream to the paired Mac's onion service.

use std::time::Duration;

use tor_keymgr::KeystoreSelector;

use arti_client::{HasKind, IsolationToken};

use crate::keys::{DiscoveryKey, OnionAddress};
use crate::{DataStream, Error, SEAM_PORT, TorNode};

/// Long enough for a slow mobile network and a few fresh rendezvous; short enough that the app
/// can say "can't reach your Mac" instead of hanging.
const CONNECT_TIMEOUT: Duration = Duration::from_secs(60);
/// While an attempt hasn't connected, a fresh one starts this often beside it.
const FRESH_ATTEMPT_EVERY: Duration = Duration::from_secs(10);

impl TorNode {
    /// Opens a stream to the Mac's onion service, proving this phone may discover it with
    /// `discovery`. What flows over the stream is still Seam's login and sealed envelopes.
    ///
    /// Arti keeps one connection attempt per service and isolation, and a later `connect` joins it
    /// even when it is stuck: a fresh client waits minutes for a rendezvous the service already gave
    /// up on (its own circuit-build timeouts start at 60 s). So an attempt still hanging after
    /// [`FRESH_ATTEMPT_EVERY`] gets a fresh one beside it, in its own isolation group, which makes
    /// Arti pick a new rendezvous and introduction point; the first stream wins. An attempt that
    /// fails outright (the Mac isn't published, or is offline) ends the call when nothing else is
    /// still trying: the app paces those retries. The group that connected is kept for the next
    /// call, so a reconnect can reuse its circuit; after a failure the next call starts in a new one.
    pub async fn connect(
        &self,
        address: OnionAddress,
        discovery: &DiscoveryKey,
    ) -> Result<DataStream, Error> {
        self.set_discovery_key(address, discovery)?;
        let current = *self.isolation.lock().map_err(|_| Error::Config)?;
        let result = self.race(address.to_string(), current).await;
        let keep = result
            .as_ref()
            .map_or_else(|_| IsolationToken::new(), |(_, group)| *group);
        *self.isolation.lock().map_err(|_| Error::Config)? = keep;
        result.map(|(stream, _)| stream)
    }

    /// Overlapping attempts, the first in `current`'s isolation group and each later one in a new
    /// group; see [`TorNode::connect`].
    async fn race(
        &self,
        target: String,
        current: IsolationToken,
    ) -> Result<(DataStream, IsolationToken), Error> {
        use futures::StreamExt;
        use futures::stream::FuturesUnordered;
        use tokio::time::{Instant, sleep_until};

        let started = Instant::now();
        let deadline = started + CONNECT_TIMEOUT;
        let mut tries = 0u32;
        let record = |event: String| seam_diagnostics::record("tor", &event);
        let mut attempts = FuturesUnordered::new();
        let mut next = Instant::now();
        let mut group = Some(current);
        loop {
            tokio::select! {
                Some((n, result)) = attempts.next() => match result {
                    Ok(won) => {
                        record(format!("dial {n} connected after {:.1} s", started.elapsed().as_secs_f32()));
                        return Ok(won);
                    }
                    // Not this phone's Mac any more: no attempt will do better
                    Err(e) => {
                        record(format!("dial {n} failed after {:.1} s: {e}", started.elapsed().as_secs_f32()));
                        match e {
                            Error::Tor(arti_client::ErrorKind::OnionServiceWrongClientAuth
                                | arti_client::ErrorKind::OnionServiceMissingClientAuth) => return Err(e),
                            _ if attempts.is_empty() => return Err(e),
                            _ => {} // another attempt is still under way
                        }
                    }
                },
                () = sleep_until(next) => {
                    tries += 1;
                    let n = tries;
                    let reused = group.is_some();
                    record(format!("dial {n} started ({})", if reused { "same circuits as last time" } else { "fresh circuits" }));
                    let token = group.take().unwrap_or_else(IsolationToken::new);
                    let mut prefs = arti_client::StreamPrefs::new();
                    prefs.set_isolation(token);
                    let client = self.client.clone();
                    let target = target.clone();
                    attempts.push(async move {
                        let result = client
                            .connect_with_prefs((target.as_str(), SEAM_PORT), &prefs)
                            .await
                            .map(|stream| (stream, token))
                            .map_err(|e| Error::Tor(e.kind()));
                        (n, result)
                    });
                    next = Instant::now() + FRESH_ATTEMPT_EVERY;
                }
                () = sleep_until(deadline) => {
                    record(format!("dial gave up after {} s, {tries} attempt(s)", CONNECT_TIMEOUT.as_secs()));
                    return Err(Error::Timeout);
                }
            }
        }
    }

    /// Makes sure the in-memory keystore holds exactly this phone's key for this service.
    fn set_discovery_key(
        &self,
        address: OnionAddress,
        discovery: &DiscoveryKey,
    ) -> Result<(), Error> {
        let wanted = discovery.public();
        let current = self
            .client
            .get_service_discovery_key(address.0)
            .map_err(|e| Error::Tor(e.kind()))?;
        if current.as_ref() == Some(&wanted.0) {
            return Ok(());
        }
        if current.is_some() {
            self.client
                .remove_service_discovery_key(KeystoreSelector::Primary, address.0)
                .map_err(|e| Error::Tor(e.kind()))?;
        }
        self.client
            .insert_service_discovery_key(KeystoreSelector::Primary, address.0, discovery.secret())
            .map_err(|e| Error::Tor(e.kind()))?;
        Ok(())
    }
}
