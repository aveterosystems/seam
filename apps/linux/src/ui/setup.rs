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
                d.connect_response(Some("go"), move |d, _| {
                    let Some(c) = weak.upgrade() else { return };
                    if installed {
                        c.os().spawn(async move {
                            let _ = doors::enable_extension().await; // the door opens when it's on the bus
                        });
                    } else {
                        gtk::UriLauncher::new(EXTENSION_PAGE).launch(
                            d.root().and_downcast_ref::<gtk::Window>(),
                            None::<&gtk::gio::Cancellable>,
                            |_| {},
                        );
                    }
                });
                d.present(Some(&parent));
            });
        }
        ClipboardDoor::ExtensionRefused => {
            let d = adw::AlertDialog::new(
                Some("Automatic Clipboard Needs Seam From Flathub"),
                Some(
                    "The Seam Clipboard extension works only with Seam installed from Flathub, so no other app can use it. For a development build, create the file ~/.config/seam/allow-development-build, then quit and reopen Seam.\n\nUntil then, click Send Clipboard.",
                ),
            );
            d.add_response("ok", "OK");
            d.present(Some(&parent));
        }
        ClipboardDoor::KlipperOff => {
            let d = adw::AlertDialog::new(
                Some("Turn On the Clipboard in Plasma"),
                Some(
                    "Seam sees what you copy through Klipper, Plasma's clipboard. It's turned off on this computer. Add the Clipboard widget to your panel, or turn Klipper back on in System Settings, and Seam uses it straight away.\n\nUntil then, click Send Clipboard.",
                ),
            );
            d.add_response("ok", "OK");
            d.present(Some(&parent));
        }
        ClipboardDoor::Unavailable => {
            let d = adw::AlertDialog::new(
                Some("Automatic Clipboard Isn't Available"),
                Some(
                    "This desktop doesn't let apps see the clipboard in the background. Click Send Clipboard in Seam's window when you want to send a copy.",
                ),
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
            Some(
                "Install it from your distribution (the android-tools package), then try again. Or turn on Automatic clipboard in Seam on your phone, which needs no cable.",
            ),
        );
        d.add_response("ok", "OK");
        return d.present(Some(&parent));
    };
    let body = format!(
        "The easy way needs no cable: in Seam on your phone, turn on Automatic clipboard.\n\nTo set it up without Android's Accessibility, connect the phone with wireless debugging or USB, with USB debugging on in Settings › Developer options. Then, once, on the phone:\n{}\n\nYou need the Seam build from GitHub: the Play Store build can't do this.",
        crate::os::adb::consent()
    );
    let d = adw::AlertDialog::new(
        Some("Turn On Automatic Clipboard From Your Phone?"),
        Some(&body),
    );
    d.add_responses(&[("cancel", "Cancel"), ("go", "Continue")]);
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
            d.add_response("ok", "OK");
            d.present(Some(&parent));
        });
    });
    d.present(Some(&parent));
}
