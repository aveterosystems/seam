// SPDX-License-Identifier: AGPL-3.0-only
//! Opens the automatic clipboard door this desktop has (decision 0012, revised on KDE): Seam's
//! GNOME Shell extension on GNOME, Klipper on KDE Plasma. None open means one tap.
//!
//! Each door is found on the session bus and used the moment it's there (the extension enabled
//! later, Klipper restarted), and closes when it leaves. This module is the only author of
//! [`ClipboardDoor`]: every screen reads it from the model.

use std::sync::Arc;

use async_trait::async_trait;
use futures_util::StreamExt;
use seam_computer::features::clipboard::PasteboardItem;
use tokio::sync::Mutex as AsyncMutex;

use crate::os::clipboard::{Door, LinuxPasteboard};

/// The extension's UUID, well-known name, path, interface and refusal (apps/linux/gnome-extension).
const EXT_UUID: &str = "seam-clipboard@avetero.com";
const EXT_NAME: &str = "com.avetero.Seam.Clipboard";
const EXT_PATH: &str = "/com/avetero/Seam/Clipboard";
const EXT_IFACE: &str = "com.avetero.Seam.Clipboard1";
const EXT_DENIED: &str = "com.avetero.Seam.Clipboard.Error.AccessDenied";
/// The oldest extension API this app speaks. A newer extension keeps working (decision: a
/// minimum level, not an exact match, so an app release never waits on extension review).
const EXT_MIN_API: u32 = 1;

/// This computer's side of automatic clipboard, as every screen shows it.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum ClipboardDoor {
    /// Copies cross by themselves.
    Open,
    /// GNOME: Seam's extension is missing or off. One tap until it's on.
    ExtensionOff,
    /// GNOME: the extension is on but won't serve this Seam (a development build without its
    /// override). One tap.
    ExtensionRefused,
    /// KDE: Klipper, Plasma's clipboard, isn't running. One tap until it is.
    KlipperOff,
    /// Nothing on this desktop can watch the clipboard: one tap only.
    #[default]
    Unavailable,
}

impl ClipboardDoor {
    pub fn automatic(self) -> bool {
        self == Self::Open
    }

    /// Not open, but this desktop has a way to open it.
    pub fn can_set_up(self) -> bool {
        !matches!(self, Self::Open | Self::Unavailable)
    }
}

/// Which way this desktop gives a background app the clipboard.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Plan {
    Extension,
    Klipper,
    OneTap,
}

/// GNOME uses Seam's extension (its portal would show "screen is being shared" all the time); KDE
/// Plasma uses Klipper (its portal only gives the clipboard with keyboard or pointer control);
/// anything else, one tap.
fn plan(current_desktop: &str) -> Plan {
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

/// The door before any has opened: what this desktop could set up, if anything.
pub fn at_launch(current_desktop: &str) -> ClipboardDoor {
    closed(plan(current_desktop))
}

fn closed(plan: Plan) -> ClipboardDoor {
    match plan {
        Plan::Extension => ClipboardDoor::ExtensionOff,
        Plan::Klipper => ClipboardDoor::KlipperOff,
        Plan::OneTap => ClipboardDoor::Unavailable,
    }
}

/// Finds this desktop's door at launch and keeps it current; `tell` hears each change.
pub fn start(
    os: &tokio::runtime::Handle,
    pasteboard: Arc<LinuxPasteboard>,
    tell: impl Fn(ClipboardDoor) + Send + Sync + 'static,
) {
    let desktop = std::env::var("XDG_CURRENT_DESKTOP").unwrap_or_default();
    os.spawn(async move {
        register().await;
        let p = plan(&desktop);
        tell(closed(p));
        match p {
            Plan::Extension => watch_extension(&pasteboard, &tell).await,
            Plan::Klipper => watch_klipper(&pasteboard, &tell).await,
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

/// Open only once a checked call is answered: anyone may read the Version property, so an
/// extension that refuses this Seam still has one.
fn extension_status(api: Option<u32>, probe: Result<(), Option<String>>) -> ClipboardDoor {
    match (api, probe) {
        (Some(v), Ok(())) if v >= EXT_MIN_API => ClipboardDoor::Open,
        (_, Err(Some(name))) if name == EXT_DENIED => ClipboardDoor::ExtensionRefused,
        // Not there, too old to speak to, or not ready: Settings offers to set it up
        _ => ClipboardDoor::ExtensionOff,
    }
}

async fn probe_extension(conn: &zbus::Connection) -> (Option<Arc<dyn Door>>, ClipboardDoor) {
    let Ok(proxy) = zbus::Proxy::new(conn, EXT_NAME, EXT_PATH, EXT_IFACE).await else {
        return (None, ClipboardDoor::ExtensionOff);
    };
    let api = proxy.get_property::<u32>("Version").await.ok();
    let probe = match proxy.call::<_, _, i64>("ChangeCount", &()).await {
        Ok(_) => Ok(()),
        Err(zbus::Error::MethodError(name, ..)) => Err(Some(name.to_string())),
        Err(_) => Err(None),
    };
    let status = extension_status(api, probe);
    let door = status
        .automatic()
        .then(|| Arc::new(ExtensionDoor { proxy }) as Arc<dyn Door>);
    (door, status)
}

/// Opens the door whenever the extension is on the bus and answers Seam, and closes it when it
/// leaves.
async fn watch_extension(pasteboard: &LinuxPasteboard, tell: &(dyn Fn(ClipboardDoor) + Sync)) {
    let Ok(conn) = zbus::Connection::session().await else {
        return;
    };
    let Ok(dbus) = zbus::fdo::DBusProxy::new(&conn).await else {
        return;
    };
    let Ok(mut owners) = dbus.receive_name_owner_changed().await else {
        return;
    };
    let mut last = None;
    loop {
        let (door, status) = probe_extension(&conn).await;
        if last != Some(status) {
            if cfg!(debug_assertions) {
                eprintln!("seam: clipboard extension {status:?}");
            }
            pasteboard.set_door(door);
            tell(status);
            last = Some(status);
        }
        // Until the extension's name changes hands (enabled, disabled, the shell restarted)
        loop {
            match owners.next().await {
                None => return,
                Some(s) if s.args().is_ok_and(|a| a.name().as_str() == EXT_NAME) => break,
                Some(_) => {}
            }
        }
        // The shell needs a moment to export the object after taking the name
        tokio::time::sleep(std::time::Duration::from_millis(300)).await;
    }
}

/// Whether GNOME has Seam's extension installed (and so only needs turning on).
pub async fn extension_installed() -> bool {
    shell_extensions("GetExtensionInfo")
        .await
        .and_then(|m| {
            m.body()
                .deserialize::<std::collections::HashMap<String, zbus::zvariant::OwnedValue>>()
                .ok()
        })
        .is_some_and(|info| !info.is_empty())
}

/// Turns Seam's extension on; the door opens when it's on the bus.
pub async fn enable_extension() -> bool {
    shell_extensions("EnableExtension")
        .await
        .and_then(|m| m.body().deserialize::<bool>().ok())
        .unwrap_or(false)
}

async fn shell_extensions(method: &str) -> Option<zbus::Message> {
    let conn = zbus::Connection::session().await.ok()?;
    conn.call_method(
        Some("org.gnome.Shell.Extensions"),
        "/org/gnome/Shell/Extensions",
        Some("org.gnome.Shell.Extensions"),
        method,
        &(EXT_UUID,),
    )
    .await
    .ok()
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

impl KlipperState {
    /// What Klipper holds now. A new copy by someone else counts; history reordering and Seam's
    /// own write don't, so neither is ever read back as the user's copy.
    fn saw(&mut self, text: String) {
        if self.text == text {
            return;
        }
        let ours = self.written.as_deref() == Some(text.as_str());
        self.written = None;
        self.text = text;
        if !ours {
            self.count += 1;
        }
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
async fn watch_klipper(pasteboard: &LinuxPasteboard, tell: &(dyn Fn(ClipboardDoor) + Sync)) {
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
                tell(ClipboardDoor::Open);
                while changes.next().await.is_some() {
                    let Ok(text) = proxy
                        .call::<_, _, String>("getClipboardContents", &())
                        .await
                    else {
                        break;
                    };
                    state.lock().await.saw(text);
                }
                pasteboard.set_door(None);
                tell(ClipboardDoor::KlipperOff);
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
    fn before_a_door_opens_only_gnome_and_kde_can_set_one_up() {
        assert_eq!(at_launch("GNOME"), ClipboardDoor::ExtensionOff);
        assert_eq!(at_launch("KDE"), ClipboardDoor::KlipperOff);
        assert_eq!(at_launch("sway"), ClipboardDoor::Unavailable);
        assert!(at_launch("GNOME").can_set_up() && !at_launch("GNOME").automatic());
        assert!(!ClipboardDoor::Unavailable.can_set_up());
        assert!(ClipboardDoor::Open.automatic() && !ClipboardDoor::Open.can_set_up());
    }

    /// A refused Seam is never told the door is open: the Version property answers anyone, so
    /// only a checked call opens it.
    #[test]
    fn the_extension_opens_only_on_a_checked_answer() {
        let denied = || Err(Some(EXT_DENIED.to_string()));
        assert_eq!(extension_status(Some(1), Ok(())), ClipboardDoor::Open);
        assert_eq!(
            extension_status(Some(1), denied()),
            ClipboardDoor::ExtensionRefused
        );
        assert_eq!(
            extension_status(None, denied()),
            ClipboardDoor::ExtensionRefused
        );
        // Gone, not exported yet, too old, or another error: off, never open
        assert_eq!(
            extension_status(None, Err(None)),
            ClipboardDoor::ExtensionOff
        );
        assert_eq!(
            extension_status(
                Some(1),
                Err(Some("org.freedesktop.DBus.Error.UnknownObject".into()))
            ),
            ClipboardDoor::ExtensionOff
        );
        assert_eq!(
            extension_status(Some(0), Ok(())),
            ClipboardDoor::ExtensionOff
        );
    }

    #[test]
    fn klipper_counts_only_the_users_new_copies() {
        let mut s = KlipperState {
            text: "before launch".into(),
            ..Default::default()
        };
        s.saw("before launch".into()); // a reorder: nothing new
        assert_eq!(s.count, 0);
        s.saw("mine".into());
        assert_eq!((s.count, s.text.as_str()), (1, "mine"));
        // Seam writes a copy from the phone: it lands, but isn't counted as the user's
        s.written = Some("from phone".into());
        s.saw("from phone".into());
        assert_eq!(s.count, 1);
        assert!(s.written.is_none(), "only the one write is excused");
        // The same words copied again by the user later do count
        s.saw("other".into());
        s.saw("from phone".into());
        assert_eq!(s.count, 3);
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
