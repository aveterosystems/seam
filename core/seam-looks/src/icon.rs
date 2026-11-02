// SPDX-License-Identifier: AGPL-3.0-only
//! Icons as meanings: one variant per row of the design system's icon table. Each app maps a
//! meaning to its own icon (SF Symbol, Android drawable, Adwaita symbolic name).

#[derive(Debug, Clone, Copy, PartialEq)]
pub enum Icon {
    /// The path: Same Wi‑Fi.
    SameWifi,
    /// The path: through Tor (a neutral globe, never the onion).
    ThroughTor,
    Paused,
    /// Fills to `level` (0, 25, 50, 75 or 100); a bolt while charging.
    Battery {
        level: u8,
        charging: bool,
    },
    /// The other device is on Wi‑Fi (its network, not the path).
    Wifi,
    /// Bars filled to `fraction` (0 to 1).
    MobileSignal {
        fraction: f32,
    },
    Storage,
    DoNotDisturb,
    Notifications,
    NotificationsOff,
}

impl Icon {
    /// The battery icon for a level: rounded down to a quarter, full while charging.
    pub fn battery(percent: u32, charging: bool) -> Self {
        let level = if charging {
            100
        } else {
            (percent.min(100) / 25 * 25) as u8
        };
        Icon::Battery { level, charging }
    }
}
