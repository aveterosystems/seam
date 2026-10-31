// SPDX-License-Identifier: AGPL-3.0-only
//! The Mac's private onion service. Only phones whose discovery keys it was given can discover it
//! (restricted discovery). That hides the service; it isn't access control: a removed phone that
//! kept the descriptor may still reach it until its introduction points change, so Seam's own login,
//! inside the stream, decides who may talk (SR-33-01).

use std::pin::Pin;
use std::sync::Arc;

use arti_client::config::onion_service::OnionServiceConfigBuilder;
use futures::{Stream, StreamExt};
use tor_cell::relaycell::msg::Connected;
use tor_hsservice::config::TokenBucketConfig;
use tor_hsservice::config::restricted_discovery::HsClientNickname;
use tor_hsservice::{
    HsNickname, RendRequest, RunningOnionService, StreamRequest, handle_rend_requests,
};
use tor_proto::stream::IncomingStreamRequest;

use arti_client::HasKind;

use crate::keys::{DiscoveryPublic, OnionAddress, ServiceKey};
use crate::{DataStream, Error, SEAM_PORT, TorNode};

const MAX_STREAMS_PER_CIRCUIT: u32 = 2;
/// Introductions per second, and in one burst.
const INTRO_RATE: u32 = 1;
const INTRO_BURST: u32 = 10;

/// A running onion service. Dropping it takes the service down.
pub struct HostedService {
    address: OnionAddress,
    // Kept alive while hosted: dropping it stops the service
    _running: Arc<RunningOnionService>,
    requests: Pin<Box<dyn Stream<Item = StreamRequest> + Send>>,
}

impl TorNode {
    /// Publishes an onion service for `key`, discoverable only by `clients` (one entry per paired
    /// phone). To add or remove a phone, drop the service and host it again: Arti reads the
    /// client list only at launch.
    pub fn host(
        &self,
        key: &ServiceKey,
        clients: &[DiscoveryPublic],
    ) -> Result<HostedService, Error> {
        // Restricted discovery with nobody allowed would publish an address no one can use
        if clients.is_empty() {
            return Err(Error::NoClients);
        }
        let mut builder = OnionServiceConfigBuilder::default();
        let expected = key.address();
        // One service per key: the Mac hosts the old address and the one it's pairing at once
        let address = expected.to_string();
        builder.nickname(
            HsNickname::new(format!("seam-{}", &address[..16])).map_err(|_| Error::Config)?,
        );
        // A phone needs one stream at a time and introduces itself rarely: anything more is a
        // flood, refused before it costs circuits (SR-33-02)
        builder.max_concurrent_streams_per_circuit(MAX_STREAMS_PER_CIRCUIT);
        builder.rate_limit_at_intro(Some(TokenBucketConfig::new(INTRO_RATE, INTRO_BURST)));
        let discovery = builder.restricted_discovery();
        discovery.enabled(true);
        for (index, client) in clients.iter().enumerate() {
            let nickname = format!("phone-{index}")
                .parse::<HsClientNickname>()
                .map_err(|_| Error::Config)?;
            discovery
                .static_keys()
                .access()
                .push((nickname, client.0.clone()));
        }
        let config = builder.build().map_err(|_| Error::Config)?;

        type Launched = (
            Arc<RunningOnionService>,
            Pin<Box<dyn Stream<Item = RendRequest> + Send>>,
        );
        let launched: Option<Launched> = {
            let mut loaded = self.service_keys.lock().map_err(|_| Error::Config)?;
            if loaded.insert(address) {
                // First launch of this key: load it into the in-memory keystore. It goes in before
                // the launch, so a retry after a failed launch takes the relaunch path (SR-33-05);
                // were the insert itself to fail, the address check below refuses what launched
                self.client
                    .launch_onion_service_with_hsid(config, key.keypair())
                    .map_err(|e| Error::Tor(e.kind()))?
                    .map(|(running, requests)| (running, Box::pin(requests) as _))
            } else {
                // Relaunch (the phone list changed): Arti still holds this key under this name
                self.client
                    .launch_onion_service(config)
                    .map_err(|e| Error::Tor(e.kind()))?
                    .map(|(running, requests)| (running, Box::pin(requests) as _))
            }
        };
        let (running, requests) = launched.ok_or(Error::Config)?;
        // Never serve under an address other than the one the phones were given
        if running.onion_address() != Some(expected.0) {
            return Err(Error::WrongServiceKey);
        }
        Ok(HostedService {
            address: expected,
            _running: running,
            requests: Box::pin(handle_rend_requests(requests)),
        })
    }
}

impl HostedService {
    pub fn address(&self) -> OnionAddress {
        self.address
    }

    /// The next incoming stream to Seam's port. Streams to any other port, or of any other
    /// kind, are refused. `None` once the service has stopped.
    pub async fn accept(&mut self) -> Option<DataStream> {
        while let Some(request) = self.requests.next().await {
            let wanted = matches!(request.request(), IncomingStreamRequest::Begin(begin) if begin.port() == SEAM_PORT);
            if !wanted {
                // Not Seam: close the whole circuit, not just this stream, as Tor advises for
                // unexpected requests. Best effort: a failure means it's already gone
                let _ = request.shutdown_circuit();
                continue;
            }
            if let Ok(stream) = request.accept(Connected::new_empty()).await {
                return Some(stream);
            }
        }
        None
    }
}
