// SPDX-License-Identifier: AGPL-3.0-only
//! The app's one live object. Seam itself runs in the core's `Computer` (seam-computer:
//! delivery, the pause, paths, pairing and the features' logic); this supplies Linux's side of
//! it, turns its snapshots into the model the screens draw, and passes what the user does back.

use std::cell::{Cell, RefCell};
use std::path::PathBuf;
use std::rc::Rc;
use std::sync::Arc;

use adw::prelude::*;
use gtk::{gdk, gio, glib};
use seam_computer::Event;
use seam_computer::computer::{Computer, Listener, Os, Settings, Snapshot};
use seam_computer::features::{Command, clipboard, notifications, status};
use seam_computer::store::Legacy;
use seam_core::proto::v1::{
    NotificationAction, NotificationSettingsChange, notification_action::Action,
};

use crate::model::{Icons, Model};
use crate::os::banners::{self, Banner, Banners};
use crate::os::clipboard::{LinuxPasteboard, OneTap, Reading, Sent};
use crate::os::doors::ClipboardDoor;

/// What reaches the main thread from Seam's own threads, in order.
pub enum Msg {
    Snapshot(Box<Snapshot>),
    Event(Event),
    Banner(Banner),
    /// One tap: a copy from the phone to put on the clipboard while Seam has focus.
    WriteClipboard(String),
    BatteryChanged,
    Woke,
    /// The tray's menu, or another instance, asked for something.
    Do(&'static str),
    /// This computer's side of automatic clipboard changed.
    ClipboardDoor(ClipboardDoor),
}

// A copy's text never prints (rule 6)
impl std::fmt::Debug for Msg {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Msg::Snapshot(_) => f.write_str("Snapshot"),
            Msg::Event(e) => f.debug_tuple("Event").field(e).finish(),
            Msg::Banner(b) => f.debug_tuple("Banner").field(b).finish(),
            Msg::WriteClipboard(_) => f.write_str("WriteClipboard(..)"),
            Msg::BatteryChanged => f.write_str("BatteryChanged"),
            Msg::Woke => f.write_str("Woke"),
            Msg::Do(a) => f.debug_tuple("Do").field(a).finish(),
            Msg::ClipboardDoor(d) => f.debug_tuple("ClipboardDoor").field(d).finish(),
        }
    }
}

type Watcher = Rc<dyn Fn(&Controller)>;
type Toaster = Box<dyn Fn(&str)>;

/// Undoes a `watch`, for screens that close (Preferences).
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub struct WatchId(u64);

pub struct Controller {
    pub app: adw::Application,
    computer: RefCell<Option<Computer>>,
    model: RefCell<Model>,
    icons: RefCell<Icons>,
    icons_version: Cell<Option<u64>>,
    resetting: Cell<bool>,
    watchers: RefCell<Vec<(u64, Watcher)>>,
    next_watch: Cell<u64>,
    pasteboard: Arc<LinuxPasteboard>,
    /// Banners now showing, so the phone's unpair can take them all back.
    banners: RefCell<std::collections::HashSet<String>>,
    /// Seam's own threads for the desktop's services (D-Bus, portals, the tray).
    os: tokio::runtime::Runtime,
    tx: async_channel::Sender<Msg>,
    /// Debug: made-up state for looking at every screen without a phone. Nothing starts.
    demo: Cell<bool>,
    /// Shows a toast in the window (GNOME's way to confirm an action).
    toaster: RefCell<Option<Toaster>>,
}

impl Controller {
    pub fn new(app: &adw::Application) -> Rc<Self> {
        let (tx, rx) = async_channel::unbounded::<Msg>();
        let writer_tx = tx.clone();
        let one_tap = Arc::new(OneTap::new(move |text| {
            let _ = writer_tx.send_blocking(Msg::WriteClipboard(text));
        }));
        let os = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(1)
            .thread_name("seam-os")
            .enable_all()
            .build()
            .expect("a Tokio runtime");
        let c = Rc::new(Self {
            app: app.clone(),
            computer: RefCell::new(None),
            model: RefCell::new(Model::default()),
            icons: RefCell::default(),
            icons_version: Cell::new(None),
            resetting: Cell::new(false),
            watchers: RefCell::default(),
            next_watch: Cell::new(0),
            pasteboard: Arc::new(LinuxPasteboard::new(one_tap)),
            banners: RefCell::default(),
            os,
            tx,
            demo: Cell::new(false),
            toaster: RefCell::default(),
        });
        let weak = Rc::downgrade(&c);
        glib::spawn_future_local(async move {
            while let Ok(msg) = rx.recv().await {
                let Some(c) = weak.upgrade() else { break };
                c.handle(msg);
            }
        });
        c
    }

    /// Starts Seam. Debug builds with SEAM_DEMO show made-up state instead.
    pub fn start(self: &Rc<Self>) {
        #[cfg(debug_assertions)]
        if std::env::var_os("SEAM_DEMO").is_some() {
            self.demo.set(true);
            let m = crate::demo::model(std::env::var("SEAM_DEMO").unwrap_or_default().as_str());
            *self.icons.borrow_mut() = crate::demo::icons();
            *self.model.borrow_mut() = m;
            self.notify();
            return;
        }
        let folder = match data_folder() {
            Ok(f) => f,
            Err(e) => {
                self.model.borrow_mut().startup_error = Some(format!("Seam couldn't start: {e}"));
                return self.notify();
            }
        };
        let os = Os {
            keys: Arc::new(crate::os::keys::Keyring::new()),
            pasteboard: self.pasteboard.clone(),
            notifications: Arc::new(Banners::new({
                let tx = self.tx.clone();
                move |b| {
                    let _ = tx.send_blocking(Msg::Banner(b));
                }
            })),
            battery: Arc::new(crate::os::battery::LinuxBattery::new()),
            same_wifi: Arc::new(crate::os::same_wifi::LinuxSameWifi::new()),
            listener: Arc::new(ToMain(self.tx.clone())),
        };
        let settings = Settings {
            folder,
            app_version: env!("CARGO_PKG_VERSION").into(),
            platform: "linux".into(),
            legacy: Legacy::default(),
        };
        *self.computer.borrow_mut() = Some(Computer::start(settings, os));
        let tx = self.tx.clone();
        self.os.spawn(crate::os::battery::watch(move || {
            let _ = tx.send_blocking(Msg::BatteryChanged);
        }));
        self.os.spawn(crate::os::system::learn_name());
        let tx = self.tx.clone();
        self.os.spawn(crate::os::system::watch_wake(move || {
            let _ = tx.send_blocking(Msg::Woke);
        }));
        let tx = self.tx.clone();
        crate::os::doors::start(self.os.handle(), self.pasteboard.clone(), move |d| {
            let _ = tx.send_blocking(Msg::ClipboardDoor(d));
        });
    }

    /// Calls `f` now and after every change.
    pub fn watch(&self, f: impl Fn(&Controller) + 'static) -> WatchId {
        f(self);
        let id = self.next_watch.get();
        self.next_watch.set(id + 1);
        self.watchers.borrow_mut().push((id, Rc::new(f)));
        WatchId(id)
    }

    pub fn unwatch(&self, id: WatchId) {
        self.watchers.borrow_mut().retain(|(i, _)| *i != id.0);
    }

    pub fn model(&self) -> std::cell::Ref<'_, Model> {
        self.model.borrow()
    }

    pub fn icon(&self, app_id: &str) -> Option<gdk::Texture> {
        let bytes = self.icons.borrow().get(app_id).cloned()?;
        gdk::Texture::from_bytes(&glib::Bytes::from_owned(bytes)).ok()
    }

    /// Seam's own threads, for the desktop's services (D-Bus, portals, the tray).
    pub fn os(&self) -> &tokio::runtime::Handle {
        self.os.handle()
    }

    /// Asks for an app action from any thread (the tray's menu, another instance).
    pub fn asker(&self) -> impl Fn(&'static str) + Send + Sync + 'static {
        let tx = self.tx.clone();
        move |action| {
            let _ = tx.send_blocking(Msg::Do(action));
        }
    }

    pub fn is_demo(&self) -> bool {
        self.demo.get()
    }

    pub fn set_toaster(&self, f: impl Fn(&str) + 'static) {
        *self.toaster.borrow_mut() = Some(Box::new(f));
    }

    /// Confirms an action in a toast, as GNOME apps do ("Copied", "Reply sent").
    pub fn toast(&self, text: &str) {
        if let Some(f) = self.toaster.borrow().as_ref() {
            f(text)
        }
    }

    /// Redraws every screen from the model as it is (a timer ran out).
    pub fn refresh(&self) {
        self.notify();
    }

    fn notify(&self) {
        // A watcher may add another (a window opening): run the ones there were
        let watchers: Vec<Watcher> = self
            .watchers
            .borrow()
            .iter()
            .map(|(_, w)| w.clone())
            .collect();
        for w in watchers {
            w(self);
        }
    }

    fn handle(&self, msg: Msg) {
        match msg {
            Msg::Snapshot(s) => self.show(&s),
            Msg::Event(e) => self.heard(&e),
            Msg::Banner(b) => self.banner(b),
            Msg::WriteClipboard(text) => {
                if let Some(display) = gdk::Display::default() {
                    display.clipboard().set_text(&text);
                }
            }
            Msg::BatteryChanged => self.feature(Command::Status(status::Command::BatteryChanged)),
            Msg::Woke => {
                if let Some(c) = self.computer.borrow().as_ref() {
                    c.woke()
                }
            }
            Msg::Do(action) => self.app.activate_action(action, None),
            Msg::ClipboardDoor(d) => {
                self.model.borrow_mut().clipboard_door = d;
                self.notify();
            }
        }
    }

    fn show(&self, s: &Snapshot) {
        let next = self.model.borrow().apply(s, self.resetting.get());
        if s.opens > 0 && next.health != seam_computer::computer::Health::Starting {
            self.resetting.set(false);
        }
        if self.icons_version.get() != Some(next.icons_version)
            && let Some(c) = self.computer.borrow().as_ref()
        {
            self.icons_version.set(Some(next.icons_version));
            *self.icons.borrow_mut() = c.icons();
        }
        if cfg!(debug_assertions) && next.tor_state != self.model.borrow().tor_state {
            eprintln!("seam: tor {:?}", next.tor_state);
        }
        let pairing_changed = next.pairing != self.model.borrow().pairing;
        if pairing_changed {
            hand_over(&next.pairing);
            if cfg!(debug_assertions) {
                eprintln!("seam: pairing {:?}", next.pairing); // its Debug never prints the code
            }
        }
        if *self.model.borrow() != next {
            *self.model.borrow_mut() = next;
            self.notify();
        }
    }

    fn heard(&self, e: &Event) {
        match e {
            Event::Problem(p) => eprintln!("seam: {p:?}"), // a code, never contents
            Event::ClipFromPhone if !self.model.borrow().clipboard_door.automatic() => {
                // Wayland won't let Seam write the clipboard from the background: one tap does
                let n = gio::Notification::new("Copied from your phone");
                n.set_body(Some("Click Copy to put it on this computer's clipboard."));
                n.add_button("Copy", "app.copy-from-phone");
                n.set_default_action("app.copy-from-phone");
                self.app.send_notification(Some("clip"), &n);
            }
            _ => {}
        }
        if let Some(words) = crate::model::announcement(e) {
            crate::ui::announce(&self.app, &words);
        }
    }

    fn banner(&self, b: Banner) {
        match b {
            Banner::Show(n, icon) => {
                let spec = banners::spec(&n);
                let g = gio::Notification::new(&spec.title);
                g.set_body(Some(&spec.body));
                g.set_category(Some("im.received"));
                if let Some(png) = icon {
                    g.set_icon(&gio::BytesIcon::new(&glib::Bytes::from_owned(png)));
                }
                g.set_default_action_and_target_value(
                    "app.open-notification",
                    Some(&n.key.to_variant()),
                );
                for (label, action, (key, id)) in &spec.buttons {
                    let target = if *action == "app.reply" {
                        key.to_variant()
                    } else {
                        (key, id).to_variant()
                    };
                    g.add_button_with_target_value(label, action, Some(&target));
                }
                self.app.send_notification(Some(&spec.id), &g);
                self.banners.borrow_mut().insert(spec.id);
            }
            Banner::Dismiss(key) => {
                let id = banners::id(&key);
                self.app.withdraw_notification(&id);
                self.banners.borrow_mut().remove(&id);
            }
            Banner::RemoveAll => {
                for id in self.banners.borrow_mut().drain() {
                    self.app.withdraw_notification(&id);
                }
            }
        }
    }

    /// Hands a command to Seam, once it's running (never in demo mode).
    fn with(&self, f: impl FnOnce(&Computer)) {
        if let Some(c) = self.computer.borrow().as_ref() {
            f(c)
        }
    }

    pub fn feature(&self, command: Command) {
        self.with(|c| c.feature(command))
    }

    pub fn set_wifi(&self, on: bool) {
        self.with(|c| c.set_wifi(on))
    }

    pub fn set_tor(&self, on: bool) {
        self.with(|c| c.set_tor(on))
    }

    pub fn reconnect(&self) {
        self.with(Computer::reconnect)
    }

    pub fn unpair(&self) {
        self.with(Computer::unpair)
    }

    pub fn start_pairing(&self) {
        self.with(|c| c.start_pairing(crate::os::system::computer_name()))
    }

    pub fn confirm_pairing(&self) {
        self.with(Computer::confirm_pairing)
    }

    pub fn cancel_pairing(&self) {
        self.with(Computer::cancel_pairing)
    }

    /// Only after the user agreed: a new identity, the old files kept aside.
    pub fn reset(&self) {
        self.resetting.set(true);
        self.with(Computer::reset)
    }

    /// Changes the screens at once, before Seam's next snapshot says the same.
    fn edit(&self, f: impl FnOnce(&mut Model)) {
        f(&mut self.model.borrow_mut());
        self.notify();
    }

    pub fn set_clipboard_sync(&self, on: bool) {
        if !on {
            self.edit(Model::sync_off);
        }
        self.feature(Command::Clipboard(clipboard::Command::SetSync(on)));
    }

    pub fn copy_again(&self, text: String) {
        self.feature(Command::Clipboard(clipboard::Command::CopyAgain(text)));
    }

    pub fn clear_clips(&self) {
        self.edit(Model::clear_clips);
        self.feature(Command::Clipboard(clipboard::Command::ClearClips));
    }

    /// Send Clipboard: what's on the clipboard now, read while Seam has focus and offered to the
    /// core, whose rules decide (a password or a file never goes). A toast says how it went.
    pub fn send_clipboard(self: &Rc<Self>) {
        let Some(display) = gdk::Display::default() else {
            return;
        };
        let clip = display.clipboard();
        let types = |c: &gdk::Clipboard| -> Vec<String> {
            c.formats()
                .mime_types()
                .iter()
                .map(|t| t.to_string())
                .collect()
        };
        let types_before = types(&clip);
        let changed = Rc::new(Cell::new(false));
        let flag = changed.clone();
        let watch = clip.connect_changed(move |_| flag.set(true));
        let (weak, again) = (Rc::downgrade(self), clip.clone());
        clip.read_text_async(None::<&gio::Cancellable>, move |r| {
            again.disconnect(watch);
            let Some(c) = weak.upgrade() else { return };
            let read = Reading {
                types_before,
                text: r.ok().flatten().map(|t| t.to_string()),
                types_after: types(&again),
                changed: changed.get(),
            };
            // A password or a copied file never goes, and it says so without saying what
            let name = c.model().phone_name(false);
            c.toast(&match c.pasteboard.send_now(read) {
                Sent::Offered => format!("Sent to {name}"),
                Sent::Automatic => format!("Copies now go to {name} by themselves: copy it again"),
                Sent::Nothing => "Nothing to send: copy some text first".into(),
            });
        });
    }

    /// The latest copy from the phone, on the clipboard now (one tap).
    pub fn copy_from_phone(&self) {
        let text = self
            .model
            .borrow()
            .clips
            .iter()
            .find(|c| c.from_phone)
            .map(|c| c.text.clone());
        if let (Some(text), Some(display)) = (text, gdk::Display::default()) {
            display.clipboard().set_text(&text);
        }
    }

    pub fn act(&self, key: String, action: Action) {
        self.feature(Command::Notifications(notifications::Command::Act(
            NotificationAction {
                key,
                action: Some(action),
            },
        )));
    }

    /// The window's dismiss: the banner goes too.
    pub fn dismiss(&self, key: &str) {
        self.edit(|m| m.dismiss(key));
        self.feature(Command::Notifications(notifications::Command::Dismiss(
            key.to_string(),
        )));
    }

    pub fn change_notifications(&self, change: NotificationSettingsChange) {
        self.feature(Command::Notifications(notifications::Command::Change(
            change,
        )));
    }

    pub fn ask_phone_status(&self) {
        self.feature(Command::Status(status::Command::AskPhone));
    }
}

/// The core's listener: called on its threads, handed to the main thread in order.
struct ToMain(async_channel::Sender<Msg>);

impl Listener for ToMain {
    fn on_snapshot(&self, snapshot: Snapshot) {
        let _ = self.0.send_blocking(Msg::Snapshot(Box::new(snapshot)));
    }

    fn on_event(&self, event: Event) {
        let _ = self.0.send_blocking(Msg::Event(event));
    }
}

/// $XDG_DATA_HOME/seam (inside Flatpak, the app's own). Debug builds keep their own, so they run
/// next to the installed app without touching it.
pub fn data_folder() -> std::io::Result<PathBuf> {
    let name = if cfg!(debug_assertions) {
        // SEAM_DATA_FOLDER picks another folder, to run a second debug computer (device tests)
        std::env::var("SEAM_DATA_FOLDER")
            .ok()
            .filter(|n| !n.contains('/') && !n.is_empty())
            .unwrap_or("seam-debug".into())
    } else {
        "seam".into()
    };
    let folder = glib::user_data_dir().join(name);
    std::fs::create_dir_all(&folder)?;
    use std::os::unix::fs::PermissionsExt;
    std::fs::set_permissions(&folder, std::fs::Permissions::from_mode(0o700))?;
    Ok(folder)
}

/// Writes a file only this user can read (a token, a code), replacing what was there.
pub fn write_private(path: &std::path::Path, bytes: &[u8]) {
    use std::io::Write;
    use std::os::unix::fs::OpenOptionsExt;
    if let Ok(mut f) = std::fs::OpenOptions::new()
        .write(true)
        .create(true)
        .truncate(true)
        .mode(0o600)
        .open(path)
    {
        let _ = f.write_all(bytes);
    }
}

/// Debug device tests: hand the code to an emulator, which has no camera to scan with.
fn hand_over(pairing: &seam_computer::pairing::PairingState) {
    if !cfg!(debug_assertions) {
        return;
    }
    if let (seam_computer::pairing::PairingState::Waiting { qr, .. }, Ok(path)) =
        (pairing, std::env::var("SEAM_PAIRING_CODE_FILE"))
    {
        write_private(std::path::Path::new(&path), qr.as_bytes());
    }
}
