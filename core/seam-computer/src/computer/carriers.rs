// SPDX-License-Identifier: AGPL-3.0-only
//! The two carriers the paths run: Same Wi‑Fi's listener (announced while it runs) and the Tor
//! service's streams.

use std::sync::Arc;

use async_trait::async_trait;
use seam_core::proto::v1;

use super::SameWifi;
use crate::lan::{LanSender, LanServer, Stream};
use crate::paths::LanRunner;

/// A Same Wi‑Fi carrier: the listener, announced while it runs.
pub(super) struct SameWifiCarrier {
    pub(super) server: LanServer,
    pub(super) same_wifi: Arc<dyn SameWifi>,
}

#[async_trait]
impl LanRunner for SameWifiCarrier {
    async fn start(&self) -> std::io::Result<u16> {
        let port = self.server.start().await?;
        self.same_wifi.announce(port);
        Ok(port)
    }

    fn stop(&self) {
        self.same_wifi.stop_announcing();
        self.server.stop();
    }

    fn adopt(&self, stream: Stream) {
        self.server.adopt(stream);
    }
}

impl LanSender for SameWifiCarrier {
    fn send(&self, frame: &v1::ServerFrame, to: &str) -> bool {
        self.server.send(frame, to)
    }

    fn close(&self, peer: &str) {
        self.server.close(peer);
    }
}

#[async_trait]
impl LanRunner for LanServer {
    async fn start(&self) -> std::io::Result<u16> {
        LanServer::start(self).await
    }

    fn stop(&self) {
        LanServer::stop(self);
    }

    fn adopt(&self, stream: Stream) {
        LanServer::adopt(self, stream);
    }
}
