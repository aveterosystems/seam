// SPDX-License-Identifier: AGPL-3.0-only
//! The notifications feature's computer side (seam-computer): the notification centre the Mac
//! supplies, and what the screens show. Protobuf messages as their bytes.

use std::sync::Arc;

use prost::Message;
use seam_computer::features::notifications as n;
use seam_core::proto::v1;

/// The computer's notification centre. `notification`: a seam.v1.Notification.
#[uniffi::export(with_foreign)]
#[async_trait::async_trait]
pub trait NotificationCentre: Send + Sync {
    async fn show(&self, notification: Vec<u8>, icon: Option<Vec<u8>>);
    async fn dismiss(&self, key: String);
    async fn remove_all(&self);
}

#[derive(uniffi::Record)]
pub struct NotificationsView {
    /// seam.v1.Notification each, newest first.
    pub shown: Vec<Vec<u8>>,
    /// seam.v1.NotificationSettings.
    pub settings: Option<Vec<u8>>,
    /// Goes up when the icons change: read them with `Computer.icons`.
    pub icons_version: u64,
    pub arrivals: u64,
}

impl From<n::View> for NotificationsView {
    fn from(v: n::View) -> Self {
        Self {
            shown: v.shown.iter().map(Message::encode_to_vec).collect(),
            settings: v.settings.map(|s| s.encode_to_vec()),
            icons_version: v.icons_version,
            arrivals: v.arrivals,
        }
    }
}

pub(crate) struct AppNotificationCentre(pub(crate) Arc<dyn NotificationCentre>);

#[async_trait::async_trait]
impl n::NotificationCentre for AppNotificationCentre {
    async fn show(&self, notification: v1::Notification, icon: Option<Vec<u8>>) {
        self.0.show(notification.encode_to_vec(), icon).await;
    }
    async fn dismiss(&self, key: String) {
        self.0.dismiss(key).await;
    }
    async fn remove_all(&self) {
        self.0.remove_all().await;
    }
}
