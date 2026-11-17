// SPDX-License-Identifier: AGPL-3.0-only
//! The 32-byte key the core seals Seam's state with, in the user's keyring: the Secret portal
//! inside Flatpak, the Secret Service (GNOME Keyring, KWallet) outside it. `oo7` picks.

use std::collections::HashMap;

use seam_computer::store::KeyStore;

const LABEL: &str = "Seam storage key";

pub struct Keyring {
    /// Debug builds keep their own key, so they never touch the installed app's.
    account: &'static str,
}

impl Keyring {
    pub fn new() -> Self {
        Self {
            account: if cfg!(debug_assertions) { "state-key-debug" } else { "state-key" },
        }
    }
}

impl KeyStore for Keyring {
    /// Runs on its own thread: the core asks from inside its runtime, and the keyring is async.
    fn storage_key(&self, create: bool) -> Result<Option<Vec<u8>>, String> {
        let account = self.account;
        std::thread::spawn(move || {
            tokio::runtime::Builder::new_current_thread()
                .enable_all()
                .build()
                .map_err(|e| e.to_string())?
                .block_on(key(account, create))
        })
        .join()
        .map_err(|_| "the keyring thread stopped".to_string())?
    }
}

/// What the user reads when the keyring won't help; the details go to the log, never the screen.
const NO_KEYRING: &str = "it needs your keyring to keep its keys safe, and none is running. Install GNOME Keyring or KWallet, then open Seam again.";
const LOCKED: &str = "your keyring is locked. Unlock it when your desktop asks, then open Seam again.";

async fn key(account: &str, create: bool) -> Result<Option<Vec<u8>>, String> {
    let fail = |words: &'static str| {
        move |e: oo7::Error| {
            eprintln!("seam: keyring: {e}"); // the error names the service, never the key
            words.to_string()
        }
    };
    let keyring = oo7::Keyring::new().await.map_err(fail(NO_KEYRING))?;
    // A locked login keyring asks the user once, as every app's does
    keyring.unlock().await.map_err(fail(LOCKED))?;
    let attributes = HashMap::from([("application", "com.avetero.Seam"), ("account", account)]);
    let items = keyring.search_items(&attributes).await.map_err(fail(LOCKED))?;
    if let Some(item) = items.first() {
        let secret = item.secret().await.map_err(fail(LOCKED))?;
        let bytes: &[u8] = secret.as_ref();
        // Never replaced: a key that can't be read would orphan the saved state
        return if bytes.len() == 32 {
            Ok(Some(bytes.to_vec()))
        } else {
            Err("its key in your keyring is damaged. Reset Seam to start again.".into())
        };
    }
    if !create {
        return Ok(None);
    }
    let key: [u8; 32] = rand::random();
    keyring
        .create_item(LABEL, &attributes, key.to_vec(), true)
        .await
        .map_err(fail(LOCKED))?;
    Ok(Some(key.to_vec()))
}
