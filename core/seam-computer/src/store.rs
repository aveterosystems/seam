// SPDX-License-Identifier: AGPL-3.0-only
//! Where Seam keeps things on this computer, in the app's private folder: the sealed core state
//! (keys, peers, and the onion service secret in its app data), sealed with a key only the OS's
//! secret store holds; the outbox; the phone's pause; and Tor's own state in `tor/`. The files are
//! the Swift app's, unchanged, so an update and a downgrade both read them.

use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};

use seam_core::Device;

use crate::durable::DurableFile;
use crate::outbox::OutboxFile;
use crate::session::{Preferences, Storage};

/// The OS's secret store (Keychain, Secret Service): it alone holds the key the state is sealed with.
pub trait KeyStore: Send + Sync {
    /// The 32-byte key. When there's none, makes one only if `create` (first launch, or a reset);
    /// otherwise None, since saved state without its key can't be opened. Never replaces a key it
    /// can't read: that would orphan the saved state.
    fn storage_key(&self, create: bool) -> Result<Option<Vec<u8>>, String>;
}

#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum StoreError {
    /// Saved data exists but can't be opened (damaged, or its key is gone). Only a reset, which
    /// the user chooses, gets past this; Seam never overwrites it by itself.
    #[error("saved data can't be opened")]
    Unreadable,
    /// Anything that may be temporary (a disk or key store error).
    #[error("{0}")]
    Failed(String),
}

pub struct Store {
    pub device: Arc<Mutex<Device>>,
    /// A pairing from the relay builds was dropped on opening: the phone has to pair again.
    pub pair_again: bool,
    key: [u8; 32],
    state: DurableFile,
    outbox: OutboxFile,
    folder: PathBuf,
    legacy: Legacy,
}

fn failed(e: impl std::fmt::Display) -> StoreError {
    StoreError::Failed(e.to_string())
}

fn key32(key: Option<Vec<u8>>) -> Result<[u8; 32], StoreError> {
    key.and_then(|k| k.try_into().ok())
        .ok_or(StoreError::Unreadable)
}

impl Store {
    /// Restores the saved device, or creates one on first launch.
    pub fn open(folder: &Path, keys: &dyn KeyStore, legacy: Legacy) -> Result<Self, StoreError> {
        let state = state_file(folder)?;
        let Some(blob) = state.read().map_err(failed)? else {
            return Self::create(folder, state, keys, legacy);
        };
        let key = key32(keys.storage_key(false).map_err(StoreError::Failed)?)?;
        let mut device = match Device::restore(&blob, &key) {
            Ok(d) => d,
            Err(seam_core::Error::BadState) => return Err(StoreError::Unreadable),
            Err(e) => return Err(failed(e)),
        };
        // A phone paired through the relay has app data the core no longer reads: there's no way
        // to reach it over Tor, so it's forgotten and pairs again. The identity stays
        let relay_era = device.app_data().is_none() && !device.peers().is_empty();
        if relay_era {
            let peers: Vec<String> = device.peers().keys().cloned().collect();
            for peer in peers {
                device.remove_peer(&peer);
            }
            device.clear_app_data();
        }
        let store = Self {
            device: Arc::new(Mutex::new(device)),
            pair_again: relay_era,
            key,
            state,
            outbox: outbox_at(folder),
            folder: folder.to_owned(),
            legacy,
        };
        if relay_era {
            store.save().map_err(failed)?;
            store.outbox.delete(); // sealed for the forgotten phone
        }
        Ok(store)
    }

    /// The user chose to start over: the unreadable files are kept aside, never deleted, and this
    /// computer gets a new identity. Any paired phone has to pair again.
    /// The switches and the clipboard setting stay; the pause and the pairing date go with the phone.
    pub fn reset(folder: &Path, keys: &dyn KeyStore, stamp: u64) -> Result<Self, StoreError> {
        let state = state_file(folder)?;
        state.move_aside(stamp).map_err(failed)?;
        outbox_at(folder).delete(); // sealed for the old identity
        let store = Self::create(folder, state, keys, Legacy::default())?;
        let kept = store.preferences();
        store.save_preferences(&Preferences {
            paused_until: None,
            paired_at: None,
            ..kept
        });
        Ok(store)
    }

    /// A new identity, with no app data until a phone pairs (pairing makes the onion service).
    fn create(
        folder: &Path,
        state: DurableFile,
        keys: &dyn KeyStore,
        legacy: Legacy,
    ) -> Result<Self, StoreError> {
        let key = key32(keys.storage_key(true).map_err(StoreError::Failed)?)?;
        let store = Self {
            device: Arc::new(Mutex::new(Device::generate().map_err(failed)?)),
            pair_again: false,
            key,
            state,
            outbox: outbox_at(folder),
            folder: folder.to_owned(),
            legacy,
        };
        store.save().map_err(failed)?;
        Ok(store)
    }

    /// Durable before it returns. Callers must not send anything a failed save would forget (SR-2-11).
    pub fn save(&self) -> std::io::Result<()> {
        let blob = crate::lock(&self.device)
            .export_state(&self.key)
            .map_err(std::io::Error::other)?;
        self.state.write(&blob)
    }

    /// Envelopes for the phone not yet delivered, next to the state file.
    pub fn outbox_file(&self) -> &OutboxFile {
        &self.outbox
    }

    pub fn folder(&self) -> &Path {
        &self.folder
    }
}

impl Storage for Store {
    fn save_state(&self) -> std::io::Result<()> {
        self.save()
    }

    fn outbox(&self) -> &OutboxFile {
        &self.outbox
    }

    /// From `preferences`. The first launch after the Swift app copies what it kept across
    /// (`legacy`) and saves it; a file that can't be read is never overwritten by that.
    fn preferences(&self) -> Preferences {
        let file = preferences_file(&self.folder);
        match file.read() {
            Ok(Some(bytes)) => parse_preferences(&bytes),
            Ok(None) => {
                let p = self.legacy.apply(Preferences::default());
                let _ = file.write(&format_preferences(&p));
                p
            }
            Err(_) => self.legacy.apply(Preferences::default()),
        }
    }

    fn save_preferences(&self, p: &Preferences) {
        let _ = preferences_file(&self.folder).write(&format_preferences(p));
    }
}

/// What the Swift app kept in its own preferences, copied across once.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Legacy {
    pub wifi: Option<bool>,
    pub tor: Option<bool>,
    pub clipboard_sync: Option<bool>,
    pub paused_until: Option<i64>,
    pub paired_at: Option<i64>,
}

impl Legacy {
    fn apply(&self, d: Preferences) -> Preferences {
        let (wifi, tor) = (self.wifi.unwrap_or(d.wifi), self.tor.unwrap_or(d.tor));
        Preferences {
            // One always stays on
            wifi: wifi || !tor,
            tor,
            clipboard_sync: self.clipboard_sync.unwrap_or(d.clipboard_sync),
            paused_until: self.paused_until.filter(|&ms| ms > 0),
            paired_at: self.paired_at.filter(|&ms| ms > 0),
        }
    }
}

/// `key=value` lines: `wifi`, `tor`, `clipboard_sync` (1 or 0), `paused_until`, `paired_at`
/// (unix ms, 0 for none). Anything unknown or missing keeps its default.
fn format_preferences(p: &Preferences) -> Vec<u8> {
    let flag = |b: bool| u8::from(b);
    format!(
        "wifi={}\ntor={}\nclipboard_sync={}\npaused_until={}\npaired_at={}\n",
        flag(p.wifi),
        flag(p.tor),
        flag(p.clipboard_sync),
        p.paused_until.unwrap_or(0),
        p.paired_at.unwrap_or(0)
    )
    .into_bytes()
}

fn parse_preferences(bytes: &[u8]) -> Preferences {
    let mut p = Preferences::default();
    for line in String::from_utf8_lossy(bytes).lines() {
        let Some((key, value)) = line.split_once('=') else {
            continue;
        };
        let (flag, ms) = (
            value.trim() == "1",
            value.trim().parse::<i64>().ok().filter(|&m| m > 0),
        );
        match key.trim() {
            "wifi" => p.wifi = flag,
            "tor" => p.tor = flag,
            "clipboard_sync" => p.clipboard_sync = flag,
            "paused_until" => p.paused_until = ms,
            "paired_at" => p.paired_at = ms,
            _ => {}
        }
    }
    if !p.wifi && !p.tor {
        p.wifi = true; // one always stays on
    }
    p
}

fn state_file(folder: &Path) -> Result<DurableFile, StoreError> {
    create_private_folder(folder).map_err(failed)?;
    Ok(DurableFile::new(folder.join("state.bin")))
}

fn outbox_at(folder: &Path) -> OutboxFile {
    OutboxFile::new(DurableFile::new(folder.join("outbox.bin")))
}

fn preferences_file(folder: &Path) -> DurableFile {
    DurableFile::new(folder.join("preferences"))
}

#[cfg(unix)]
fn create_private_folder(folder: &Path) -> std::io::Result<()> {
    use std::os::unix::fs::DirBuilderExt;
    std::fs::DirBuilder::new()
        .recursive(true)
        .mode(0o700)
        .create(folder)
}

#[cfg(not(unix))]
fn create_private_folder(folder: &Path) -> std::io::Result<()> {
    std::fs::create_dir_all(folder)
}

#[cfg(test)]
mod tests;
