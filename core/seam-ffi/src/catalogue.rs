// SPDX-License-Identifier: AGPL-3.0-only
//! Each feature's delivery rules for the apps' outboxes (seam_core::catalogue).

use prost::Message;
use seam_core::catalogue;

use crate::{Result, invalid};

/// How a payload is delivered.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct PayloadRules {
    /// Seconds it may wait; 0: the core's default (24 h). Pass to `seal`.
    pub ttl_s: u32,
    /// A newer one of the same kind replaces a queued one.
    pub latest_only: bool,
    /// Waits for the next connection rather than making one.
    pub lazy: bool,
    /// Not captured or sent while the phone has Seam paused.
    pub paused: bool,
}

/// The rules for a `seam.v1.Payload`.
#[uniffi::export]
pub fn payload_rules(payload: Vec<u8>) -> Result<PayloadRules> {
    let p = seam_core::proto::v1::Payload::decode(payload.as_slice())
        .map_err(|_| invalid("not a Payload"))?;
    let r = catalogue::rules(&p);
    Ok(PayloadRules {
        ttl_s: r.ttl_s,
        latest_only: r.latest_only,
        lazy: r.lazy,
        paused: r.paused,
    })
}

/// The capabilities every build supports; an app adds its platform's own.
#[uniffi::export]
pub fn core_capabilities() -> Vec<String> {
    catalogue::CAPABILITIES
        .iter()
        .map(|c| c.to_string())
        .collect()
}
