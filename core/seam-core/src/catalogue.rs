// SPDX-License-Identifier: AGPL-3.0-only
//! Each feature's delivery rules, in one table both apps' outboxes ask: how
//! long a payload may wait, whether only the latest counts, whether it's worth a connection of its
//! own, and whether a pause stops it. A new feature adds its row here, nowhere else.

#[cfg(test)]
mod tests;

use crate::proto::v1::{Payload, control, notification_action, payload::Body};

/// How a payload is delivered.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Rules {
    /// How long it may wait to be delivered, in seconds; 0: the core's default (24 h).
    pub ttl_s: u32,
    /// A newer one of the same kind replaces a queued one (a stale clipboard is worse than none).
    pub latest_only: bool,
    /// Sent on the next connection, never worth one of its own (a battery change).
    pub lazy: bool,
    /// Not captured or sent while the phone has Seam paused. Only control messages go.
    pub paused: bool,
}

const DEFAULT: Rules = Rules {
    ttl_s: 0,
    latest_only: false,
    lazy: false,
    paused: true,
};

/// The rules for this payload.
pub fn rules(payload: &Payload) -> Rules {
    let Some(body) = &payload.body else {
        return DEFAULT;
    };
    match body {
        Body::Control(c) => Rules {
            // A Hello is sent again on every connection; anything else keeps the default
            ttl_s: match c.body {
                Some(control::Body::Hello(_)) => 600,
                _ => 0,
            },
            paused: false, // the pause itself, and Unpair, must always go
            ..DEFAULT
        },
        Body::Clipboard(_) => Rules {
            ttl_s: 600,
            latest_only: true,
            ..DEFAULT
        },
        Body::Status(_) => Rules {
            ttl_s: 120, // an old battery level is worse than none
            latest_only: true,
            lazy: true,
            ..DEFAULT
        },
        Body::Notification(_) => Rules {
            ttl_s: 3600, // a computer that was away gets the last hour, not a flood
            ..DEFAULT
        },
        Body::NotificationSettings(_) => Rules {
            ttl_s: 600, // sent again on every connection
            latest_only: true,
            ..DEFAULT
        },
        Body::NotificationSettingsChange(_) => Rules {
            ttl_s: 600,
            ..DEFAULT
        },
        Body::NotificationAction(a) => Rules {
            // A typed reply must not be lost and a late dismiss is harmless; a button tap is stale soon
            ttl_s: match a.action {
                Some(notification_action::Action::ButtonId(_)) => 600,
                _ => 0,
            },
            ..DEFAULT
        },
        _ => DEFAULT,
    }
}

/// The capabilities every build of this core supports. An app adds the ones that depend on its
/// platform or permissions.
pub const CAPABILITIES: &[&str] = &["clipboard.v1", "notifications.v1", "status.v1"];
