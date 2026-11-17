// SPDX-License-Identifier: AGPL-3.0-only
//! Opens the automatic clipboard door this desktop has (decision 0012, revised on KDE): Seam's
//! GNOME Shell extension on GNOME, Klipper on KDE Plasma. None open means one tap.
//!
//! Each door is found on the session bus and used the moment it's there (the extension enabled
//! later, Klipper restarted), and closes when it leaves.

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
    Klipper,
    OneTap,
}

/// GNOME uses Seam's extension (its portal would show "screen is being shared" all the time); KDE
/// Plasma uses Klipper (its portal only gives the clipboard with keyboard or pointer control);
/// anything else, one tap.
pub fn plan(current_desktop: &str) -> Plan {
    let desktops: Vec<String> = current_desktop
        .split(':')
        .map(|d| d.to_ascii_uppercase())
        .collect();
    if desktops.iter().any(|d| d == "GNOME" || d == "UBUNTU") {
        Plan::Extension
    } else if desktops.iter().any(|d| d == "KDE") {
        Plan::Klipper
    } else {
        Plan::OneTap
    }
}

/// What Settings and the setup dialog say about this desktop's door.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Setup {
    /// GNOME: install or turn on Seam's extension.
    Extension,
    /// KDE: Klipper, Plasma's clipboard, has to be running.
    Klipper,
    /// Nothing here can watch the clipboard: one tap only.
    Unavailable,
}

pub fn setup(plan: Plan) -> Setup {
    match plan {
        Plan::Extension => Setup::Extension,
        Plan::Klipper => Setup::Klipper,
        Plan::OneTap => Setup::Unavailable,
    }
}

/// Finds this desktop's door at launch and keeps it current.
pub fn start(c: &Rc<Controller>) {
    let desktop = std::env::var("XDG_CURRENT_DESKTOP").unwrap_or_default();
    let (pasteboard, tx) = (c.pasteboard.clone(), c.tx.clone());
    c.os.spawn(async move {
        register().await;
        let p = plan(&desktop);
        let _ = tx.send(Msg::ClipboardPlan(p)).await;
        match p {
            Plan::Extension => watch_extension(pasteboard, tx).await,
            Plan::Klipper => watch_klipper(pasteboard, tx).await,
            Plan::OneTap => {}
        }
    });
}

/// A development build outside Flatpak tells the portals who it is, so they can remember its
/// answers (inside Flatpak they know already).
pub async fn register() {
    if std::path::Path::new("/.flatpak-info").exists() {
        return;
    }
    if let Ok(id) = ashpd::AppID::try_from(crate::APP_ID) {
        let _ = ashpd::register_host_app(id).await;
    }
}

// ---- The GNOME extension -------------------------------------------------------------------

struct ExtensionDoor {
    proxy: zbus::Proxy<'static>,
}

#[async_trait]
impl Door for ExtensionDoor {
    async fn change_count(&self) -> i64 {
        self.proxy
            .call::<_, _, i64>("ChangeCount", &())
            .await
            .unwrap_or(-1)
    }

    async fn first_item(&self, if_still: i64) -> Option<PasteboardItem> {
        let (ok, types, text): (bool, Vec<String>, String) =
            self.proxy.call("Read", &(if_still,)).await.ok()?;
        ok.then_some(PasteboardItem {
            types,
            text: Some(text),
        })
    }

    async fn write(&self, text: String) -> Option<i64> {
        self.proxy.call::<_, _, i64>("Write", &(text,)).await.ok()
    }
}

async fn extension_door(conn: &zbus::Connection) -> Option<Arc<dyn Door>> {
    let proxy = zbus::Proxy::new(conn, EXT_NAME, EXT_PATH, EXT_IFACE)
        .await
        .ok()?;
    let api: u32 = proxy.get_property("Version").await.ok()?;
    // Too old to speak to (or it refused us): one tap, and Settings says to update it
    (api >= EXT_MIN_API).then(|| Arc::new(ExtensionDoor { proxy }) as Arc<dyn Door>)
}

/// Opens the door whenever the extension is on the bus, and closes it when it leaves.
async fn watch_extension(
    pasteboard: Arc<crate::os::clipboard::LinuxPasteboard>,
    tx: async_channel::Sender<Msg>,
) {
    let Ok(conn) = zbus::Connection::session().await else {
        return;
    };
    let Ok(dbus) = zbus::fdo::DBusProxy::new(&conn).await else {
        return;
    };
    let Ok(mut owners) = dbus.receive_name_owner_changed().await else {
        return;
    };
    let check = async |pasteboard: &crate::os::clipboard::LinuxPasteboard| {
        let door = extension_door(&conn).await;
        let open = door.is_some();
        if open != (pasteboard.reach() == crate::os::clipboard::Reach::Automatic) {
            if cfg!(debug_assertions) {
                eprintln!(
                    "seam: clipboard extension {}",
                    if open { "open" } else { "closed" }
                );
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

// ---- Klipper (KDE Plasma) ------------------------------------------------------------------

/// Plasma's clipboard service, which KDE offers other apps on the session bus. Its portal won't
/// give the clipboard without remote control of the keyboard or pointer, which Seam won't ask for.
const KLIPPER: &str = "org.kde.klipper";
const KLIPPER_PATH: &str = "/klipper";
const KLIPPER_IFACE: &str = "org.kde.klipper.klipper";

struct KlipperDoor {
    proxy: zbus::Proxy<'static>,
    state: Arc<AsyncMutex<KlipperState>>,
}

#[derive(Default)]
struct KlipperState {
    count: i64,
    text: String,
    /// What Seam itself put there, so its own write never reads as the user's copy.
    written: Option<String>,
}

/// Klipper gives text only. A copied file arrives as its address; say so, so the core's rules
/// treat it as a file and never send it (SR-4-06).
pub fn klipper_types(text: &str) -> Vec<String> {
    let lines: Vec<&str> = text
        .lines()
        .map(str::trim)
        .filter(|l| !l.is_empty())
        .collect();
    if !lines.is_empty() && lines.iter().all(|l| l.starts_with("file://")) {
        vec!["text/uri-list".into(), "text/plain;charset=utf-8".into()]
    } else {
        vec!["text/plain;charset=utf-8".into()]
    }
}

#[async_trait]
impl Door for KlipperDoor {
    async fn change_count(&self) -> i64 {
        self.state.lock().await.count
    }

    async fn first_item(&self, if_still: i64) -> Option<PasteboardItem> {
        let s = self.state.lock().await;
        (s.count == if_still).then(|| PasteboardItem {
            types: klipper_types(&s.text),
            text: Some(s.text.clone()),
        })
    }

    async fn write(&self, text: String) -> Option<i64> {
        self.state.lock().await.written = Some(text.clone());
        self.proxy
            .call::<_, _, ()>("setClipboardContents", &(text,))
            .await
            .ok()?;
        Some(self.state.lock().await.count)
    }
}

/// Opens the door while Klipper runs, and counts each new copy it records.
async fn watch_klipper(
    pasteboard: Arc<crate::os::clipboard::LinuxPasteboard>,
    tx: async_channel::Sender<Msg>,
) {
    let Ok(conn) = zbus::Connection::session().await else {
        return;
    };
    loop {
        if let Ok(proxy) = zbus::Proxy::new(&conn, KLIPPER, KLIPPER_PATH, KLIPPER_IFACE).await
            && let Ok(current) = proxy
                .call::<_, _, String>("getClipboardContents", &())
                .await
        {
            // What's there at launch was copied before: known, not counted
            let state = Arc::new(AsyncMutex::new(KlipperState {
                text: current,
                ..Default::default()
            }));
            if let Ok(mut changes) = proxy.receive_signal("clipboardHistoryUpdated").await {
                if cfg!(debug_assertions) {
                    eprintln!("seam: clipboard klipper open");
                }
                pasteboard.set_door(Some(Arc::new(KlipperDoor {
                    proxy: proxy.clone(),
                    state: state.clone(),
                })));
                let _ = tx.send(Msg::ClipboardReach).await;
                while changes.next().await.is_some() {
                    let Ok(text) = proxy
                        .call::<_, _, String>("getClipboardContents", &())
                        .await
                    else {
                        break;
                    };
                    let mut s = state.lock().await;
                    if s.text == text {
                        continue; // history reordered, nothing new copied
                    }
                    let ours = s.written.as_deref() == Some(text.as_str());
                    s.written = None;
                    s.text = text;
                    if !ours {
                        s.count += 1;
                    }
                }
                pasteboard.set_door(None);
                let _ = tx.send(Msg::ClipboardReach).await;
            }
        }
        // Klipper isn't running (or stopped): try again in a while, one tap meanwhile
        tokio::time::sleep(std::time::Duration::from_secs(30)).await;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn gnome_uses_the_extension() {
        assert_eq!(plan("GNOME"), Plan::Extension);
        assert_eq!(plan("ubuntu:GNOME"), Plan::Extension);
        assert_eq!(plan("Unity:GNOME-Classic:gnome"), Plan::Extension);
    }

    #[test]
    fn kde_uses_klipper_and_the_rest_one_tap() {
        assert_eq!(plan("KDE"), Plan::Klipper);
        assert_eq!(plan("sway"), Plan::OneTap);
        assert_eq!(plan(""), Plan::OneTap);
    }

    #[test]
    fn setup_follows_the_plan() {
        assert_eq!(setup(Plan::Extension), Setup::Extension);
        assert_eq!(setup(Plan::Klipper), Setup::Klipper);
        assert_eq!(setup(Plan::OneTap), Setup::Unavailable);
    }

    /// SR-4-06 on KDE: Klipper hands over a copied file as its address, which is never sent.
    #[test]
    fn a_file_copied_in_dolphin_reads_as_a_file() {
        use seam_computer::features::clipboard::sendable;
        assert!(!sendable(&klipper_types("file:///home/ana/Report.pdf")));
        assert!(!sendable(&klipper_types("file:///a.txt\nfile:///b.txt\n")));
        assert!(sendable(&klipper_types(
            "See file:///etc/hosts for the list"
        )));
        assert!(sendable(&klipper_types("Table for four at 8")));
    }
}
