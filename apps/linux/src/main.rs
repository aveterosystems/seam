// SPDX-License-Identifier: AGPL-3.0-only
//! Seam for Linux: one GTK4/libadwaita app on the shared Rust core (decisions 0013, 0014).

mod app;
#[cfg(debug_assertions)]
mod demo;
mod model;
mod os;
mod tray;
mod ui;

use std::rc::Rc;

use adw::prelude::*;
use gtk::{gio, glib};
use seam_core::proto::v1::notification_action::Action;
use seam_core::proto::v1::{NotificationApp, NotificationMode, NotificationSettingsChange};

pub const APP_ID: &str = "com.avetero.Seam";

/// The app's live objects, made at startup.
type Live = (Rc<app::Controller>, Rc<ui::window::Window>);

/// A poisoned lock is still usable: nothing behind a lock here has an invariant a panic mid-update
/// could break.
pub(crate) fn lock<T>(m: &std::sync::Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|p| p.into_inner())
}

fn main() -> glib::ExitCode {
    let app = adw::Application::builder()
        .application_id(APP_ID)
        .flags(gio::ApplicationFlags::HANDLES_COMMAND_LINE)
        .build();
    // Started at login: wait in the background, no window
    let background = std::env::args().any(|a| a == "--background");
    let state: Rc<std::cell::RefCell<Option<Live>>> = Rc::default();

    let s = state.clone();
    app.connect_startup(move |app| {
        ui::load_css();
        if let Some(display) = gtk::gdk::Display::default() {
            gtk::IconTheme::for_display(&display).add_resource_path("/com/avetero/Seam/icons");
            gtk::IconTheme::for_display(&display)
                .add_search_path(concat!(env!("CARGO_MANIFEST_DIR"), "/data/icons"));
        }
        std::mem::forget(app.hold()); // closing the window keeps Seam running, for the app's life
        let c = app::Controller::new(app);
        let w = ui::window::Window::new(&c);
        actions(app, &c, &w);
        c.start();
        tray::start(&c);
        // Debug screenshots: SEAM_OPEN=clipboard | prefs:<page> | setup | phone-setup opens that at launch
        #[cfg(debug_assertions)]
        if let Ok(what) = std::env::var("SEAM_OPEN") {
            let (app, w) = (app.clone(), w.clone());
            glib::timeout_add_local_once(std::time::Duration::from_millis(600), move || match what
                .split_once(':')
            {
                Some(("prefs", page)) => {
                    app.activate_action("preferences-page", Some(&page.to_variant()))
                }
                _ if what == "clipboard" => w.show_page("clipboard"),
                _ if what == "setup" => app.activate_action("clipboard-setup", None),
                _ if what == "phone-setup" => app.activate_action("phone-clipboard-setup", None),
                _ if what == "about" => app.activate_action("about", None),
                _ => {}
            });
        }
        #[cfg(debug_assertions)]
        if std::env::var_os("SEAM_DUMP").is_some() {
            let win = w.window.clone();
            glib::timeout_add_seconds_local_once(3, move || {
                let mut out = String::new();
                ui::dump(win.upcast_ref(), 0, &mut out);
                let _ = std::fs::write("/tmp/seam-dump.txt", out);
            });
        }
        *s.borrow_mut() = Some((c, w));
    });
    let s = state.clone();
    let first = std::cell::Cell::new(true);
    app.connect_command_line(move |app, cmd| {
        let quiet = first.replace(false)
            && background
            && cmd
                .arguments()
                .iter()
                .any(|a| a.to_str() == Some("--background"));
        if !quiet {
            app.activate();
        }
        let _ = &s;
        glib::ExitCode::SUCCESS
    });
    let s = state.clone();
    app.connect_activate(move |_| {
        if let Some((_, w)) = s.borrow().as_ref() {
            w.window.present();
        }
    });
    app.run_with_args(
        &std::env::args()
            .filter(|a| a != "--background")
            .collect::<Vec<_>>(),
    )
}

/// What an app action does with its target.
type Handler = Box<dyn Fn(Option<&glib::Variant>)>;

fn actions(app: &adw::Application, c: &Rc<app::Controller>, w: &Rc<ui::window::Window>) {
    let add = |name: &str, f: Handler, ty: Option<&glib::VariantTy>| {
        let a = gio::SimpleAction::new(name, ty);
        a.connect_activate(move |_, v| f(v));
        app.add_action(&a);
    };
    let window = w.window.clone();
    let prefs = {
        let (c, window) = (c.clone(), window.clone());
        move |page: &str| {
            window.present();
            ui::preferences::present(&c, Some(window.upcast_ref()), page);
        }
    };
    let p = prefs.clone();
    add("preferences", Box::new(move |_| p("phone")), None);
    let p = prefs.clone();
    add("preferences-phone", Box::new(move |_| p("phone")), None);
    let p = prefs.clone();
    add(
        "preferences-notifications",
        Box::new(move |_| p("notifications")),
        None,
    );
    let p = prefs.clone();
    add(
        "preferences-page",
        Box::new(move |v| p(v.and_then(|v| v.str()).unwrap_or("phone"))),
        Some(glib::VariantTy::STRING),
    );
    let (cc, win) = (c.clone(), window.clone());
    add(
        "clipboard-setup",
        Box::new(move |_| ui::setup::computer(&cc, &win)),
        None,
    );
    let (cc, win) = (c.clone(), window.clone());
    add(
        "phone-clipboard-setup",
        Box::new(move |_| ui::setup::phone(&cc, &win)),
        None,
    );
    let _ = prefs;
    let win = window.clone();
    add("show-window", Box::new(move |_| win.present()), None);
    let win = window.clone();
    add(
        "about",
        Box::new(move |_| {
            let about = adw::AboutDialog::builder()
                .application_name("Seam")
                .application_icon(APP_ID)
                .developer_name("Avetero")
                .version(env!("CARGO_PKG_VERSION"))
                .website("https://github.com/aveterosystems/seam")
                .issue_url("https://github.com/aveterosystems/seam/issues")
                .license_type(gtk::License::Agpl30Only)
                .copyright("© 2026 Avetero")
                .comments("Your Android phone's notifications and clipboard on this computer, end-to-end encrypted.")
                .build();
            // The Tor Project's trademark guidelines ask for this wording
            about.add_legal_section(
                "Tor",
                None,
                gtk::License::Custom,
                Some("Seam uses Tor through Arti. It is produced independently from the Tor® anonymity software and carries no guarantee from The Tor Project about quality, suitability or anything else. It isn't endorsed by The Tor Project."),
            );
            about.present(Some(&win));
        }),
        None,
    );
    let a = app.clone();
    add("quit", Box::new(move |_| a.quit()), None);
    let (cc, ww) = (c.clone(), w.clone());
    add(
        "reply",
        Box::new(move |v| {
            if let Some(key) = v.and_then(|v| v.str()) {
                let _ = &cc;
                ww.reply_to(key);
            }
        }),
        Some(glib::VariantTy::STRING),
    );
    let ww = w.clone();
    add(
        "open-notification",
        Box::new(move |_| {
            ww.show_page("notifications");
            ww.window.present();
        }),
        Some(glib::VariantTy::STRING),
    );
    let cc = c.clone();
    add(
        "phone-button",
        Box::new(move |v| {
            if let Some((key, id)) = v.and_then(|v| v.get::<(String, String)>()) {
                cc.act(key, Action::ButtonId(id));
            }
        }),
        Some(glib::VariantTy::new("(ss)").expect("a tuple of two strings")),
    );
    let (cc, ww) = (c.clone(), w.clone());
    add(
        "copy-from-phone",
        Box::new(move |_| {
            ww.show_page("clipboard");
            ww.window.present();
            // Seam has focus once presented: the clipboard takes the copy
            let cc = cc.clone();
            glib::timeout_add_local_once(std::time::Duration::from_millis(150), move || {
                cc.copy_from_phone()
            });
        }),
        None,
    );
    let (cc, ww) = (c.clone(), w.clone());
    add(
        "send-clipboard",
        Box::new(move |_| {
            if ww.window.is_active() {
                return cc.send_clipboard();
            }
            // Wayland lets only the focused app read the clipboard: from the tray, Seam takes
            // focus first, as Copy does
            ww.show_page("clipboard");
            ww.window.present();
            let cc = cc.clone();
            glib::timeout_add_local_once(std::time::Duration::from_millis(150), move || {
                cc.send_clipboard()
            });
        }),
        None,
    );
    let cc = c.clone();
    add(
        "notification-mode-all",
        Box::new(move |v| {
            let i: usize = v
                .and_then(|v| v.str())
                .and_then(|s| s.parse().ok())
                .unwrap_or(0);
            let mode = [
                NotificationMode::On,
                NotificationMode::TitleOnly,
                NotificationMode::Off,
            ][i.min(2)];
            let apps = cc
                .model()
                .settings
                .as_ref()
                .map(|s| s.apps.clone())
                .unwrap_or_default();
            cc.change_notifications(NotificationSettingsChange {
                apps: apps
                    .into_iter()
                    .map(|a| NotificationApp {
                        app_id: a.app_id,
                        mode: mode as i32,
                        ..Default::default()
                    })
                    .collect(),
                ..Default::default()
            });
        }),
        Some(glib::VariantTy::STRING),
    );
    app.set_accels_for_action("app.preferences", &["<Control>comma"]);
    app.set_accels_for_action("app.quit", &["<Control>q"]);
    app.set_accels_for_action("window.close", &["<Control>w"]);
}
