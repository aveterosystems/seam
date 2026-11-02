// SPDX-License-Identifier: AGPL-3.0-only
//! The session through its interface only, with fake paths and system adapters and a real phone
//! (a second core device) to open what the computer sends and seal what it receives.

mod delivery;
mod failover;
mod features;
mod pairing;
mod pause;
mod reconnect;
pub(crate) mod rig;
mod settings;
