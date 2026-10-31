// SPDX-License-Identifier: AGPL-3.0-only
//! The phone's side: one stream to the paired Mac's onion service.

use std::time::Duration;

use tor_keymgr::KeystoreSelector;

use arti_client::HasKind;

use crate::keys::{DiscoveryKey, OnionAddress};
use crate::{DataStream, Error, SEAM_PORT, TorNode};

/// Long enough for a slow mobile network and a fresh rendezvous; short enough that the app can
/// say "can't reach your Mac" instead of hanging.
const CONNECT_TIMEOUT: Duration = Duration::from_secs(60);

impl TorNode {
    /// Opens a stream to the Mac's onion service, proving this phone may discover it with
    /// `discovery`. What flows over the stream is still Seam's login and sealed envelopes.
    pub async fn connect(
        &self,
        address: OnionAddress,
        discovery: &DiscoveryKey,
    ) -> Result<DataStream, Error> {
        self.set_discovery_key(address, discovery)?;
        let target = (address.to_string(), SEAM_PORT);
        match tokio::time::timeout(CONNECT_TIMEOUT, self.client.connect(target)).await {
            Ok(Ok(stream)) => Ok(stream),
            Ok(Err(e)) => Err(Error::Tor(e.kind())),
            Err(_) => Err(Error::Timeout),
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
