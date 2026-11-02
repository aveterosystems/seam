// SPDX-License-Identifier: AGPL-3.0-only
//! What every Seam screen shows for each state (decision 0015, `specs/shared-looks/`).
//!
//! A look is plain data: the words, the icon as a meaning, the line style. Each app draws it and
//! maps the icon to its own icon set (docs/design/design-system.md, "Icons"); no app decides a
//! state, a word or an icon itself. Pure: no I/O, no clock reads. `now`, the app's names for the
//! two devices, and its locale's time words come in.

pub mod clipboard;
pub mod device_status;
pub mod icon;
pub mod link;
pub mod names;
pub mod notifications;
pub mod time;
pub mod words;

pub use device_status::DeviceStatusLook;
pub use icon::Icon;
pub use link::{Line, LinkInput, LinkKind, LinkLook};
pub use names::{Names, Side};
pub use time::TimeWords;

#[cfg(test)]
pub(crate) mod testing;
