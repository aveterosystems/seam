// SPDX-License-Identifier: AGPL-3.0-only
//! The Mac's private onion service. Only phones whose discovery keys it was given can discover it
//! (restricted discovery). That hides the service; it isn't access control: a removed phone that
//! kept the descriptor may still reach it until its introduction points change, so Seam's own login,
//! inside the stream, decides who may talk (SR-33-01).

use std::pin::Pin;
use std::sync::Arc;

use arti_client::config::onion_service::OnionServiceConfigBuilder;
use futures::{Stream, StreamExt};
use tor_cell::relaycell::msg::{Connected, End, EndReason};
use tor_hsservice::config::TokenBucketConfig;
use tor_hsservice::config::restricted_discovery::HsClientNickname;
use tor_hsservice::{
    HsNickname, RendRequest, RunningOnionService, StreamRequest, handle_rend_requests,
};
use tor_proto::stream::IncomingStreamRequest;

use arti_client::HasKind;

use crate::keys::{DiscoveryPublic, OnionAddress, ServiceKey};
use crate::{DataStream, Error, SEAM_PORT, TorNode};

const SERVICE_NICKNAME: &str = "seam";
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
    /// Publishes the Mac's onion service, discoverable only by `clients` (one entry per paired
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
        builder.nickname(HsNickname::new(SERVICE_NICKNAME.into()).map_err(|_| Error::Config)?);
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

        let expected = key.address();
        type Launched = (
            Arc<RunningOnionService>,
            Pin<Box<dyn Stream<Item = RendRequest> + Send>>,
        );
        let launched: Option<Launched> = {
            let mut loaded = self.service_address.lock().map_err(|_| Error::Config)?;
            match *loaded {
                // First launch on this node: load the key into the in-memory keystore
                None => {
                    let launched = self
                        .client
                        .launch_onion_service_with_hsid(config, key.keypair());
                    // The key goes in before the launch, so it stays even if the launch fails: a
                    // retry must take the relaunch path (SR-33-05). Were the insert itself to fail,
                    // the address check below refuses whatever launched instead
                    *loaded = Some(expected);
                    launched
                        .map_err(|e| Error::Tor(e.kind()))?
                        .map(|(running, requests)| (running, Box::pin(requests) as _))
                }
                // Relaunch (the phone list changed): Arti still holds the same key
                Some(address) if address == expected => self
                    .client
                    .launch_onion_service(config)
                    .map_err(|e| Error::Tor(e.kind()))?
                    .map(|(running, requests)| (running, Box::pin(requests) as _)),
                // A different key would need a fresh node; never launch under it here
                Some(_) => return Err(Error::WrongServiceKey),
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
                // The refusal is best effort: a failure only means the circuit is already gone
                let _ = request
                    .reject(End::new_with_reason(EndReason::EXITPOLICY))
                    .await;
                continue;
            }
            if let Ok(stream) = request.accept(Connected::new_empty()).await {
                return Some(stream);
            }
        }
        None
    }
}
