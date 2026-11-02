// SPDX-License-Identifier: AGPL-3.0-only
//! The status feature's computer side (seam-computer): the battery the Mac supplies, and the
//! phone's status for the screens. Protobuf messages as their bytes.

use std::sync::Arc;

use prost::Message;
use seam_computer::features::status as s;
use seam_core::proto::v1;

/// The computer's battery, as a seam.v1.Status; None without one.
#[uniffi::export(with_foreign)]
pub trait Battery: Send + Sync {
    fn read(&self) -> Option<Vec<u8>>;
}

#[derive(uniffi::Record)]
pub struct StatusView {
    /// seam.v1.Status, as the phone last sent it.
    pub status: Option<Vec<u8>>,
    /// When it sent it (unix ms).
    pub at_ms: Option<i64>,
}

impl From<s::View> for StatusView {
    fn from(v: s::View) -> Self {
        Self {
            status: v.status.map(|s| s.encode_to_vec()),
            at_ms: v.at,
        }
    }
}

pub(crate) struct AppBattery(pub(crate) Arc<dyn Battery>);

impl s::Battery for AppBattery {
    fn read(&self) -> Option<v1::Status> {
        v1::Status::decode(self.0.read()?.as_slice()).ok()
    }
}
