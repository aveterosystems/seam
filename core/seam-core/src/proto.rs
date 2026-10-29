// SPDX-License-Identifier: AGPL-3.0-only
//! Generated Protobuf types: the shared protocol and the core's own saved state.

/// `protocol/seam/v1`, the same schema the apps and the relay use.
pub mod v1 {
    include!(concat!(env!("OUT_DIR"), "/seam.v1.rs"));
}

/// The sealed state blob (proto/state.proto). Never leaves the device.
pub(crate) mod state {
    include!(concat!(env!("OUT_DIR"), "/seam.state.v1.rs"));
}
