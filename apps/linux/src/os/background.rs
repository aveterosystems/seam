// SPDX-License-Identifier: AGPL-3.0-only
//! Running in the background and opening at login, through the Background portal (inside Flatpak
//! and out), so the desktop shows it the way it shows any app's.

use std::rc::Rc;

use crate::app::Controller;

/// Whether Seam is set to open at login: the portal writes an autostart entry, so look for it.
pub fn autostart() -> bool {
    let dir = gtk::glib::user_config_dir().join("autostart");
    [format!("{}.desktop", crate::APP_ID)].iter().any(|f| dir.join(f).exists())
}

/// Asks the desktop to open Seam at login (or not). `done` hears whether it agreed, and why not.
pub fn set_autostart(c: &Rc<Controller>, on: bool, done: impl Fn(bool, &str) + 'static) {
    let (tx, rx) = async_channel::bounded::<Result<bool, String>>(1);
    c.os.spawn(async move {
        let r = ashpd::desktop::background::Background::request()
            .reason("Seam brings your phone's notifications and copies to this computer")
            .auto_start(on)
            .command(["seam", "--background"])
            .dbus_activatable(false)
            .send()
            .await
            .and_then(|r| r.response())
            .map(|r| r.auto_start() == on)
            .map_err(|e| e.to_string());
        let _ = tx.send(r).await;
    });
    gtk::glib::spawn_future_local(async move {
        match rx.recv().await {
            Ok(Ok(true)) => done(true, ""),
            Ok(Ok(false)) => done(false, "The desktop didn't allow it. Check Seam in your system's background apps."),
            _ => done(false, "The desktop couldn't be asked. Add Seam to your startup apps in system settings."),
        }
    });
}
