// SPDX-License-Identifier: AGPL-3.0-only
//! The clipboard tile.

/// "Syncing" while connected, "On" while not, or "Off".
pub fn subtitle(sync_on: bool, connected: bool) -> &'static str {
    match (sync_on, connected) {
        (true, true) => "Syncing",
        (true, false) => "On",
        (false, _) => "Off",
    }
}

#[cfg(test)]
mod tests {
    #[test]
    fn the_tile_says_whether_its_syncing() {
        assert_eq!(super::subtitle(true, true), "Syncing");
        assert_eq!(super::subtitle(true, false), "On");
        assert_eq!(super::subtitle(false, true), "Off");
    }
}
