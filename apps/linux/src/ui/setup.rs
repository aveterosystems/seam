// SPDX-License-Identifier: AGPL-3.0-only
//! Setting up automatic clipboard: this computer's side (the GNOME extension, or Klipper on KDE),
//! and the phone's side (the one-time adb grant, as on the Mac).

use std::cell::Cell;
use std::rc::Rc;

use adw::prelude::*;

use crate::app::Controller;
use crate::os::doors::{self, ClipboardDoor};

const EXTENSION_PAGE: &str =
    "https://github.com/aveterosystems/seam/tree/main/apps/linux/gnome-extension";

thread_local! {
    /// The phone's adb setup is running: one at a time (SR-4-11).
    static ADB_RUNNING: Cell<bool> = const { Cell::new(false) };
}

/// This computer to the phone, without clicking Send.
pub fn computer(c: &Rc<Controller>, parent: &impl IsA<gtk::Widget>) {
    let parent = parent.clone().upcast::<gtk::Widget>();
    let door = c.model().clipboard_door;
    match door {
        ClipboardDoor::Open => {}
        ClipboardDoor::ExtensionOff => {
            let (tx, rx) = async_channel::bounded(1);
            c.os().spawn(async move {
                let _ = tx.send(doors::extension_installed().await).await;
            });
            let c = c.clone();
            gtk::glib::spawn_future_local(async move {
                let installed = rx.recv().await.unwrap_or(false);
                let body = if installed {
                    "Turn on the Seam Clipboard extension so copies send without clicking Send Clipboard. Only Seam can use it, and nothing you copy is kept."
                } else {
                    "Install the Seam Clipboard extension, then turn it on in Extensions, so copies send without clicking Send Clipboard. Only Seam can use it, and nothing you copy is kept."
                };
                let d = adw::AlertDialog::new(Some("Set Up Automatic Clipboard"), Some(body));
                d.add_responses(&[
                    ("cancel", "Cancel"),
                    (
                        "go",
                        if installed {
                            "Turn On"
                        } else {
                            "Get the Extension"
                        },
                    ),
                ]);
                d.set_response_appearance("go", adw::ResponseAppearance::Suggested);
                d.set_default_response(Some("go"));
                d.set_close_response("cancel");
                let weak = Rc::downgrade(&c);
                let window = parent.root().and_downcast::<gtk::Window>();
                d.connect_response(Some("go"), move |_, _| {
                    let Some(c) = weak.upgrade() else { return };
                    if installed {
                        c.os().spawn(async move {
                            let _ = doors::enable_extension().await; // the door opens when it's on the bus
                        });
                        return;
                    }
                    // GNOME asks, installs and turns it on; the door opens when it's on the bus
                    let (tx, rx) = async_channel::bounded(1);
                    c.os().spawn(async move {
                        let _ = tx.send(doors::install_extension().await).await;
                    });
                    let window = window.clone();
                    gtk::glib::spawn_future_local(async move {
                        // Not on extensions.gnome.org, or offline: its page says how to install it
                        if rx.recv().await.ok().flatten().is_none() {
                            gtk::UriLauncher::new(EXTENSION_PAGE).launch(
                                window.as_ref(),
                                None::<&gtk::gio::Cancellable>,
                                |_| {},
                            );
                        }
                    });
                });
                d.present(Some(&parent));
            });
        }
        ClipboardDoor::ExtensionRefused => {
            let d = adw::AlertDialog::new(
                Some("Automatic Clipboard Needs the Seam Flatpak"),
                Some(
                    "The Seam Clipboard extension works only with Seam installed as a Flatpak, so no other app can use it. For a development build, create the file ~/.config/seam/allow-development-build, then quit and reopen Seam.\n\nUntil then, click Send Clipboard.",
                ),
            );
            d.add_response("ok", "Close");
            d.present(Some(&parent));
        }
        ClipboardDoor::KlipperOff => {
            let d = adw::AlertDialog::new(
                Some("Turn On the Clipboard in Plasma"),
                Some(
                    "Seam sees what you copy through Klipper, Plasma's clipboard. It's turned off on this computer. Add the Clipboard widget to your panel, or turn Klipper back on in System Settings, and Seam uses it straight away.\n\nUntil then, click Send Clipboard.",
                ),
            );
            d.add_response("ok", "Close");
            d.present(Some(&parent));
        }
        ClipboardDoor::Unavailable => {
            let d = adw::AlertDialog::new(
                Some("Automatic Clipboard Isn't Available"),
                Some(
                    "This desktop doesn't let apps see the clipboard in the background. Send a copy when you want one.",
                ),
            );
            // The way that works here, offered right where it's explained
            d.add_responses(&[("ok", "Close"), ("send", "Send Clipboard")]);
            d.set_response_appearance("send", adw::ResponseAppearance::Suggested);
            d.set_default_response(Some("send"));
            d.set_close_response("ok");
            d.connect_response(Some("send"), |d, _| {
                let _ = d.activate_action("app.send-clipboard", None);
            });
            d.present(Some(&parent));
        }
    }
}

/// The phone to this computer, without using Share: the one-time adb grant.
pub fn phone(c: &Rc<Controller>, parent: &impl IsA<gtk::Widget>) {
    let parent = parent.clone().upcast::<gtk::Widget>();
    let Some(adb) = crate::os::adb::find() else {
        let d = adw::AlertDialog::new(
            Some("Install Android Tools"),
            Some(
                "Seam needs Android's adb tool for this. Install your distribution's android-tools package, then try again.\n\nNo cable? Turn on “Automatic clipboard” in Seam on your phone instead.",
            ),
        );
        d.add_response("ok", "Close");
        return d.present(Some(&parent));
    };
    let body = format!(
        "Connect your phone by USB with USB debugging on, and Seam sets this up once. On the phone:\n{}\n\nNeeds Seam from GitHub. No cable? Turn on “Automatic clipboard” in Seam on your phone instead.",
        crate::os::adb::consent()
    );
    let d = adw::AlertDialog::new(Some("Receive Copies Automatically?"), Some(&body));
    d.add_responses(&[("cancel", "Cancel"), ("go", "Set Up")]);
    d.set_response_appearance("go", adw::ResponseAppearance::Suggested);
    d.set_close_response("cancel");
    let (weak, anchor) = (Rc::downgrade(c), parent.clone());
    d.connect_response(Some("go"), move |_, _| {
        let parent = anchor.clone();
        let Some(c) = weak.upgrade() else { return };
        if ADB_RUNNING.replace(true) {
            return c.toast("Setup is already running"); // one run at a time (SR-4-11)
        }
        let (tx, rx) = async_channel::bounded(1);
        let adb = adb.clone();
        std::thread::spawn(move || {
            let out = crate::os::adb::grant(|args| crate::os::adb::run(&adb, args));
            let _ = tx.send_blocking(out);
        });
        let parent = parent.clone();
        gtk::glib::spawn_future_local(async move {
            let out = rx.recv().await;
            ADB_RUNNING.set(false);
            let Ok(out) = out else { return };
            let d = adw::AlertDialog::new(Some(&out.title), Some(&out.detail));
            d.add_response("ok", "Close");
            d.present(Some(&parent));
        });
    });
    d.present(Some(&parent));
}
