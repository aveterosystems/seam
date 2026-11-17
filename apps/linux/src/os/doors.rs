// SPDX-License-Identifier: AGPL-3.0-only
//! Opens the automatic clipboard door this desktop has (decision 0012): Seam's GNOME Shell
//! extension on GNOME, the Clipboard portal on KDE Plasma. None open means one tap.
//!
//! The extension is found on the session bus, and is used the moment it's there (enabled later,
//! updated, or back after a GNOME upgrade). The portal asks the user once, from Set Up; its
//! restore token is kept in Seam's data folder so later launches don't ask again.

use std::rc::Rc;
use std::sync::Arc;

use async_trait::async_trait;
use futures_util::StreamExt;
use seam_computer::features::clipboard::PasteboardItem;
use tokio::sync::Mutex as AsyncMutex;

use crate::app::{Controller, Msg};
use crate::os::clipboard::Door;

/// The extension's well-known name, path and interface (apps/linux/gnome-extension).
pub const EXT_NAME: &str = "com.avetero.Seam.Clipboard";
const EXT_PATH: &str = "/com/avetero/Seam/Clipboard";
const EXT_IFACE: &str = "com.avetero.Seam.Clipboard1";
/// The oldest extension API this app speaks. A newer extension keeps working (decision: a
/// minimum level, not an exact match, so an app release never waits on extension review).
pub const EXT_MIN_API: u32 = 1;

/// Which way this desktop gives a background app the clipboard.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Plan {
    Extension,
    Portal,
    OneTap,
}

/// GNOME uses the extension (its portal would show "screen is being shared" all the time); KDE
/// and the rest use the portal where it exists; otherwise one tap.
pub fn plan(current_desktop: &str, portal_has_clipboard: bool) -> Plan {
    let desktops: Vec<String> = current_desktop.split(':').map(|d| d.to_ascii_uppercase()).collect();
    if desktops.iter().any(|d| d == "GNOME" || d == "UBUNTU") {
        Plan::Extension
    } else if portal_has_clipboard {
        Plan::Portal
    } else {
        Plan::OneTap
    }
}

/// What Settings and the setup dialog say about this desktop's door.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Setup {
    /// GNOME: install or turn on Seam's extension.
    Extension,
    /// KDE and others: allow the portal once.
    Portal,
    /// Nothing here can watch the clipboard: one tap only.
    Unavailable,
}

pub fn setup(plan: Plan) -> Setup {
    match plan {
        Plan::Extension => Setup::Extension,
        Plan::Portal => Setup::Portal,
        Plan::OneTap => Setup::Unavailable,
    }
}

/// Finds this desktop's door at launch and keeps it current.
pub fn start(c: &Rc<Controller>) {
    let desktop = std::env::var("XDG_CURRENT_DESKTOP").unwrap_or_default();
    let (pasteboard, tx) = (c.pasteboard.clone(), c.tx.clone());
    let token_file = crate::app::data_folder().ok().map(|f| f.join("clipboard-portal"));
    c.os.spawn(async move {
        let has_portal = portal_available().await;
        let p = plan(&desktop, has_portal);
        let _ = tx.send(Msg::ClipboardPlan(p)).await;
        match p {
            Plan::Extension => watch_extension(pasteboard, tx).await,
            Plan::Portal => {
                // Only with a token from an earlier Set Up: never a surprise dialog at launch
                if let Some(token) = token_file.as_ref().and_then(|f| std::fs::read_to_string(f).ok()) {
                    open_portal(pasteboard, tx, token_file, Some(token)).await;
                }
            }
            Plan::OneTap => {}
        }
    });
}

/// Set Up on KDE: the portal asks the user once; yes opens the door and keeps the token.
pub fn set_up_portal(c: &Rc<Controller>) {
    let (pasteboard, tx) = (c.pasteboard.clone(), c.tx.clone());
    let token_file = crate::app::data_folder().ok().map(|f| f.join("clipboard-portal"));
    c.os.spawn(async move {
        open_portal(pasteboard, tx, token_file, None).await;
    });
}

async fn portal_available() -> bool {
    match ashpd::desktop::clipboard::Clipboard::new().await {
        Ok(c) => c.get_property::<u32>("version").await.is_ok_and(|v| v >= 1),
        Err(_) => false,
    }
}

// ---- The GNOME extension -------------------------------------------------------------------

struct ExtensionDoor {
    proxy: zbus::Proxy<'static>,
}

#[async_trait]
impl Door for ExtensionDoor {
    async fn change_count(&self) -> i64 {
        self.proxy.call::<_, _, i64>("ChangeCount", &()).await.unwrap_or(-1)
    }

    async fn first_item(&self, if_still: i64) -> Option<PasteboardItem> {
        let (ok, types, text): (bool, Vec<String>, String) = self.proxy.call("Read", &(if_still,)).await.ok()?;
        ok.then_some(PasteboardItem { types, text: Some(text) })
    }

    async fn write(&self, text: String) -> Option<i64> {
        self.proxy.call::<_, _, i64>("Write", &(text,)).await.ok()
    }
}

async fn extension_door(conn: &zbus::Connection) -> Option<Arc<dyn Door>> {
    let proxy = zbus::Proxy::new(conn, EXT_NAME, EXT_PATH, EXT_IFACE).await.ok()?;
    let api: u32 = proxy.get_property("Version").await.ok()?;
    // Too old to speak to (or it refused us): one tap, and Settings says to update it
    (api >= EXT_MIN_API).then(|| Arc::new(ExtensionDoor { proxy }) as Arc<dyn Door>)
}

/// Opens the door whenever the extension is on the bus, and closes it when it leaves.
async fn watch_extension(pasteboard: Arc<crate::os::clipboard::LinuxPasteboard>, tx: async_channel::Sender<Msg>) {
    let Ok(conn) = zbus::Connection::session().await else { return };
    let Ok(dbus) = zbus::fdo::DBusProxy::new(&conn).await else { return };
    let Ok(mut owners) = dbus.receive_name_owner_changed().await else { return };
    let mut check = async |pasteboard: &crate::os::clipboard::LinuxPasteboard| {
        let door = extension_door(&conn).await;
        let open = door.is_some();
        if open != (pasteboard.reach() == crate::os::clipboard::Reach::Automatic) {
            if cfg!(debug_assertions) {
                eprintln!("seam: clipboard extension {}", if open { "open" } else { "closed" });
            }
            pasteboard.set_door(door);
            let _ = tx.send(Msg::ClipboardReach).await;
        }
    };
    check(&pasteboard).await;
    while let Some(signal) = owners.next().await {
        if signal.args().is_ok_and(|a| a.name().as_str() == EXT_NAME) {
            // The shell needs a moment to export the object after taking the name
            tokio::time::sleep(std::time::Duration::from_millis(300)).await;
            check(&pasteboard).await;
        }
    }
}

// ---- The Clipboard portal (KDE) ------------------------------------------------------------

type RdSession = ashpd::desktop::Session<ashpd::desktop::remote_desktop::RemoteDesktop>;

struct PortalDoor {
    clipboard: Arc<ashpd::desktop::clipboard::Clipboard>,
    session: Arc<RdSession>,
    state: Arc<AsyncMutex<PortalState>>,
}

#[derive(Default)]
struct PortalState {
    count: i64,
    types: Vec<String>,
    /// What we offered, served when another app pastes.
    offered: Option<String>,
}

const TEXT_TYPES: [&str; 2] = ["text/plain;charset=utf-8", "text/plain"];

#[async_trait]
impl Door for PortalDoor {
    async fn change_count(&self) -> i64 {
        self.state.lock().await.count
    }

    async fn first_item(&self, if_still: i64) -> Option<PasteboardItem> {
        let types = {
            let s = self.state.lock().await;
            if s.count != if_still {
                return None;
            }
            s.types.clone()
        };
        let mime = TEXT_TYPES.iter().find(|t| types.iter().any(|x| x == *t)).copied();
        let text = match mime {
            Some(m) => read_fd(self.clipboard.selection_read(&self.session, m).await.ok()?.into()).await,
            None => None, // not text: the core's rules see the types and say no
        };
        // Types and text from the same copy: a newer one since means look again (SR-4-05)
        if self.state.lock().await.count != if_still {
            return None;
        }
        Some(PasteboardItem { types, text })
    }

    async fn write(&self, text: String) -> Option<i64> {
        {
            let mut s = self.state.lock().await;
            s.offered = Some(text);
        }
        let options = ashpd::desktop::clipboard::SetSelectionOptions::default().set_mime_types(&TEXT_TYPES);
        self.clipboard.set_selection(&self.session, options).await.ok()?;
        let mut s = self.state.lock().await;
        s.count += 1; // our own copy: counted, so it never reads as the user's
        Some(s.count)
    }
}

async fn read_fd(fd: std::os::fd::OwnedFd) -> Option<String> {
    tokio::task::spawn_blocking(move || {
        use std::io::Read;
        let mut f = std::fs::File::from(fd);
        let mut buf = Vec::new();
        // At most 1 MB: a clip is text, and the core caps what it sends anyway
        f.by_ref().take(1 << 20).read_to_end(&mut buf).ok()?;
        String::from_utf8(buf).ok()
    })
    .await
    .ok()
    .flatten()
}

async fn open_portal(
    pasteboard: Arc<crate::os::clipboard::LinuxPasteboard>,
    tx: async_channel::Sender<Msg>,
    token_file: Option<std::path::PathBuf>,
    token: Option<String>,
) {
    use ashpd::desktop::remote_desktop::{DeviceType, RemoteDesktop, SelectDevicesOptions};
    let result: Result<(), String> = async {
        let rd = RemoteDesktop::new().await.map_err(|e| e.to_string())?;
        let clipboard = Arc::new(ashpd::desktop::clipboard::Clipboard::new().await.map_err(|e| e.to_string())?);
        let session = Arc::new(rd.create_session(Default::default()).await.map_err(|e| e.to_string())?);
        clipboard.request(&session, Default::default()).await.map_err(|e| e.to_string())?;
        // No keyboard or pointer: only the clipboard, which needs a remote desktop session
        let devices = SelectDevicesOptions::default()
            .set_devices(DeviceType::empty())
            .set_persist_mode(ashpd::desktop::PersistMode::ExplicitlyRevoked)
            .set_restore_token(token.as_deref());
        rd.select_devices(&session, devices).await.map_err(|e| e.to_string())?;
        let started = rd.start(&session, None, Default::default()).await.map_err(|e| e.to_string())?;
        let selected = started.response().map_err(|e| e.to_string())?;
        if !selected.is_clipboard_enabled() {
            return Err("clipboard not allowed".into());
        }
        if let (Some(file), Some(t)) = (&token_file, selected.restore_token()) {
            crate::app::write_private(file, t.as_bytes());
        }
        let state = Arc::new(AsyncMutex::new(PortalState::default()));
        let door = PortalDoor { clipboard: clipboard.clone(), session, state: state.clone() };
        let owners = clipboard.receive_selection_owner_changed::<RemoteDesktop>().await.map_err(|e| e.to_string())?;
        let transfers = clipboard.receive_selection_transfer::<RemoteDesktop>().await.map_err(|e| e.to_string())?;
        let session2 = door.session.clone();
        pasteboard.set_door(Some(Arc::new(door)));
        let _ = tx.send(Msg::ClipboardReach).await;
        // One task for both streams (they borrow the portal): a copy anywhere is counted with its
        // types, unless it's our own offer; another app pasting our offer is handed the text
        let mut owners = std::pin::pin!(owners);
        let mut transfers = std::pin::pin!(transfers);
        loop {
            tokio::select! {
                Some((_, change)) = owners.next() => {
                    if change.session_is_owner() == Some(true) {
                        continue;
                    }
                    let mut st = state.lock().await;
                    st.count += 1;
                    st.types = change.mime_types().to_vec();
                    st.offered = None;
                }
                Some((_, _mime, serial)) = transfers.next() => {
                    let text = state.lock().await.offered.clone();
                    let ok = match (text, clipboard.selection_write(&session2, serial).await) {
                        (Some(t), Ok(fd)) => tokio::task::spawn_blocking(move || {
                            use std::io::Write;
                            std::fs::File::from(std::os::fd::OwnedFd::from(fd)).write_all(t.as_bytes()).is_ok()
                        })
                        .await
                        .unwrap_or(false),
                        _ => false,
                    };
                    let _ = clipboard.selection_write_done(&session2, serial, ok).await;
                }
                else => break,
            }
        }
        Ok(())
    }
    .await;
    if let Err(e) = result {
        eprintln!("seam: clipboard portal: {e}"); // never contents
        if token.is_some()
            && let Some(f) = token_file
        {
            let _ = std::fs::remove_file(f); // a stale token: Set Up asks again
        }
    }
    pasteboard.set_door(None);
    let _ = tx.send(Msg::ClipboardReach).await;
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn gnome_uses_the_extension_even_with_a_portal() {
        assert_eq!(plan("GNOME", true), Plan::Extension);
        assert_eq!(plan("ubuntu:GNOME", true), Plan::Extension);
        assert_eq!(plan("Unity:GNOME-Classic:gnome", false), Plan::Extension);
    }

    #[test]
    fn kde_and_others_use_the_portal_where_there_is_one() {
        assert_eq!(plan("KDE", true), Plan::Portal);
        assert_eq!(plan("sway", true), Plan::Portal);
        assert_eq!(plan("KDE", false), Plan::OneTap);
        assert_eq!(plan("", false), Plan::OneTap);
    }

    #[test]
    fn setup_follows_the_plan() {
        assert_eq!(setup(Plan::Extension), Setup::Extension);
        assert_eq!(setup(Plan::Portal), Setup::Portal);
        assert_eq!(setup(Plan::OneTap), Setup::Unavailable);
    }
}
