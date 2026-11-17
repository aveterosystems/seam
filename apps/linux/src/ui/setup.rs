// SPDX-License-Identifier: AGPL-3.0-only
//! Setting up automatic clipboard: this computer's side (the GNOME extension, or the portal's
//! one-time question on KDE), and the phone's side (the one-time adb grant, as on the Mac).

use std::rc::Rc;

use adw::prelude::*;

use crate::app::Controller;
use crate::os::doors::Setup;

const EXTENSION_UUID: &str = "seam-clipboard@avetero.com";
const EXTENSION_PAGE: &str = "https://github.com/aveterosystems/seam/tree/main/apps/linux/gnome-extension";

/// Whether GNOME has Seam's extension installed (and so only needs turning on).
async fn extension_installed() -> bool {
    let Ok(conn) = zbus::Connection::session().await else { return false };
    let reply = conn
        .call_method(Some("org.gnome.Shell.Extensions"), "/org/gnome/Shell/Extensions", Some("org.gnome.Shell.Extensions"), "GetExtensionInfo", &(EXTENSION_UUID,))
        .await;
    reply
        .ok()
        .and_then(|m| m.body().deserialize::<std::collections::HashMap<String, zbus::zvariant::OwnedValue>>().ok())
        .is_some_and(|info| !info.is_empty())
}

async fn enable_extension() -> bool {
    let Ok(conn) = zbus::Connection::session().await else { return false };
    conn.call_method(Some("org.gnome.Shell.Extensions"), "/org/gnome/Shell/Extensions", Some("org.gnome.Shell.Extensions"), "EnableExtension", &(EXTENSION_UUID,))
        .await
        .ok()
        .and_then(|m| m.body().deserialize::<bool>().ok())
        .unwrap_or(false)
}

/// This computer to the phone, without clicking Send.
pub fn computer(c: &Rc<Controller>, parent: &impl IsA<gtk::Widget>) {
    let parent = parent.clone().upcast::<gtk::Widget>();
    match crate::os::doors::setup(c.clipboard_plan()) {
        Setup::Extension => {
            let (tx, rx) = async_channel::bounded(1);
            c.os.spawn(async move {
                let _ = tx.send(extension_installed().await).await;
            });
            let c = c.clone();
            gtk::glib::spawn_future_local(async move {
                let installed = rx.recv().await.unwrap_or(false);
                let body = if installed {
                    "GNOME lets Seam see what you copy through the Seam Clipboard extension, which is installed but off. Turn it on to send copies without clicking Send.\n\nOnly Seam can use it, and nothing you copy is kept or logged."
                } else {
                    "GNOME lets Seam see what you copy through a small extension, Seam Clipboard. Install it, then turn it on in Extensions.\n\nOnly Seam can use it, and nothing you copy is kept or logged. Until then, click Send Clipboard."
                };
                let d = adw::AlertDialog::new(Some("Set Up Automatic Clipboard"), Some(body));
                d.add_responses(&[("cancel", "Cancel"), ("go", if installed { "Turn On" } else { "Get the Extension" })]);
                d.set_response_appearance("go", adw::ResponseAppearance::Suggested);
                d.set_default_response(Some("go"));
                d.set_close_response("cancel");
                let weak = Rc::downgrade(&c);
                d.connect_response(Some("go"), move |d, _| {
                    let Some(c) = weak.upgrade() else { return };
                    if installed {
                        c.os.spawn(async move {
                            let _ = enable_extension().await; // the door opens when it's on the bus
                        });
                    } else {
                        gtk::UriLauncher::new(EXTENSION_PAGE).launch(d.root().and_downcast_ref::<gtk::Window>(), None::<&gtk::gio::Cancellable>, |_| {});
                    }
                });
                d.present(Some(&parent));
            });
        }
        Setup::Klipper => {
            let d = adw::AlertDialog::new(
                Some("Turn On the Clipboard in Plasma"),
                Some("Seam sees what you copy through Klipper, Plasma's clipboard. It's turned off on this computer. Add the Clipboard widget to your panel, or turn Klipper back on in System Settings, and Seam uses it straight away.\n\nUntil then, click Send Clipboard."),
            );
            d.add_response("ok", "OK");
            d.present(Some(&parent));
        }
        Setup::Unavailable => {
            let d = adw::AlertDialog::new(
                Some("Automatic Clipboard Isn't Available"),
                Some("This desktop doesn't let apps see the clipboard in the background. Click Send Clipboard in Seam's window when you want to send a copy."),
            );
            d.add_response("ok", "OK");
            d.present(Some(&parent));
        }
    }
}

/// The phone to this computer, without using Share: the one-time adb grant.
pub fn phone(c: &Rc<Controller>, parent: &impl IsA<gtk::Widget>) {
    let parent = parent.clone().upcast::<gtk::Widget>();
    let Some(adb) = crate::os::adb::find() else {
        let d = adw::AlertDialog::new(
            Some("Seam Needs Android's adb Tool"),
            Some("Install it from your distribution (the android-tools package), then try again. Or turn on Automatic clipboard in Seam on your phone, which needs no cable."),
        );
        d.add_response("ok", "OK");
        return d.present(Some(&parent));
    };
    let body = format!(
        "The easy way needs no cable: in Seam on your phone, turn on Automatic clipboard.\n\nTo set it up without Android's Accessibility, connect the phone with wireless debugging or USB, with USB debugging on in Settings › Developer options. Then, once, on the phone:\n{}\n\nYou need the Seam build from GitHub: the Play Store build can't do this.",
        crate::os::adb::consent()
    );
    let d = adw::AlertDialog::new(Some("Turn On Automatic Clipboard From Your Phone?"), Some(&body));
    d.add_responses(&[("cancel", "Cancel"), ("go", "Continue")]);
    d.set_response_appearance("go", adw::ResponseAppearance::Suggested);
    d.set_close_response("cancel");
    let (weak, anchor) = (Rc::downgrade(c), parent.clone());
    d.connect_response(Some("go"), move |_, _| {
        let parent = anchor.clone();
        let Some(c) = weak.upgrade() else { return };
        if c.adb_running.replace(true) {
            return c.toast("Setup is already running"); // one run at a time (SR-4-11)
        }
        let (tx, rx) = async_channel::bounded(1);
        let adb = adb.clone();
        std::thread::spawn(move || {
            let out = crate::os::adb::grant(|args| crate::os::adb::run(&adb, args));
            let _ = tx.send_blocking(out);
        });
        let (parent, weak) = (parent.clone(), Rc::downgrade(&c));
        gtk::glib::spawn_future_local(async move {
            let Ok(out) = rx.recv().await else { return };
            if let Some(c) = weak.upgrade() {
                c.adb_running.set(false);
            }
            let d = adw::AlertDialog::new(Some(&out.title), Some(&out.detail));
            d.add_response("ok", "OK");
            d.present(Some(&parent));
        });
    });
    d.present(Some(&parent));
}
