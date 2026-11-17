// SPDX-License-Identifier: AGPL-3.0-only
//! The tray icon and its menu, where the desktop has a tray (KDE, or GNOME with the AppIndicator
//! extension), through StatusNotifierItem. Elsewhere it simply isn't there: the window is Seam's
//! home either way. Clicking it opens the window; the menu says how the phone is and offers the
//! everyday actions.

use std::rc::Rc;

use ksni::TrayMethods;
use ksni::menu::{MenuItem, StandardItem};

use crate::app::{Controller, Msg};

pub struct SeamTray {
    tx: async_channel::Sender<Msg>,
    /// "Pixel 9 · Connected", or what's going on.
    status: String,
    /// One tap: Send Clipboard is in the menu while copies need it.
    one_tap: bool,
    paired: bool,
}

impl SeamTray {
    fn ask(&self, action: &'static str) {
        let _ = self.tx.send_blocking(Msg::Do(action));
    }
}

impl ksni::Tray for SeamTray {
    fn id(&self) -> String {
        crate::APP_ID.into()
    }

    fn title(&self) -> String {
        "Seam".into()
    }

    fn icon_name(&self) -> String {
        "com.avetero.Seam-symbolic".into()
    }

    fn icon_theme_path(&self) -> String {
        // A development build run from its source tree finds the icon there; installed, the
        // icon theme has it
        let dev = concat!(env!("CARGO_MANIFEST_DIR"), "/data/icons");
        if std::path::Path::new(dev).exists() {
            dev.into()
        } else {
            String::new()
        }
    }

    fn tool_tip(&self) -> ksni::ToolTip {
        ksni::ToolTip {
            title: "Seam".into(),
            description: self.status.clone(),
            ..Default::default()
        }
    }

    fn category(&self) -> ksni::Category {
        ksni::Category::Communications
    }

    fn activate(&mut self, _x: i32, _y: i32) {
        self.ask("app.show-window");
    }

    fn menu(&self) -> Vec<MenuItem<Self>> {
        let item = |label: &str, action: &'static str| -> MenuItem<Self> {
            StandardItem {
                label: label.into(),
                activate: Box::new(move |t: &mut Self| t.ask(action)),
                ..Default::default()
            }
            .into()
        };
        let mut menu: Vec<MenuItem<Self>> = vec![
            StandardItem {
                label: self.status.clone(),
                enabled: false,
                ..Default::default()
            }
            .into(),
            MenuItem::Separator,
            item("Open Seam", "app.show-window"),
        ];
        if self.paired && self.one_tap {
            menu.push(item("Send Clipboard", "app.send-clipboard"));
        }
        menu.push(item("Preferences", "app.preferences"));
        menu.push(MenuItem::Separator);
        menu.push(item("Quit", "app.quit"));
        menu
    }
}

/// What the tray shows for the model: the phone's name and the status line, or pairing.
pub fn look(c: &Controller) -> (String, bool, bool) {
    let m = c.model();
    let one_tap = c.pasteboard.reach() == crate::os::clipboard::Reach::OneTap;
    match m.phone {
        Some(_) => (
            format!(
                "{} · {}",
                m.phone_name(true),
                m.status_line(crate::ui::now_ms())
            ),
            one_tap,
            true,
        ),
        None => ("No phone paired".into(), one_tap, false),
    }
}

pub fn start(c: &Rc<Controller>) {
    if c.is_demo() {
        return;
    }
    let (status, one_tap, paired) = look(c);
    let tray = SeamTray {
        tx: c.tx.clone(),
        status,
        one_tap,
        paired,
    };
    let (tx, rx) = async_channel::bounded::<ksni::Handle<SeamTray>>(1);
    c.os.spawn(async move {
        // Inside Flatpak the well-known name can't be owned; the item still shows (ksni's advice)
        let sandboxed = std::path::Path::new("/.flatpak-info").exists();
        let spawned = if sandboxed {
            tray.disable_dbus_name(true).spawn().await
        } else {
            tray.spawn().await
        };
        if let Ok(handle) = spawned {
            let _ = tx.send(handle).await;
        } // no tray on this desktop: the window is enough
    });
    let weak = Rc::downgrade(c);
    gtk::glib::spawn_future_local(async move {
        let Ok(handle) = rx.recv().await else { return };
        let Some(c) = weak.upgrade() else { return };
        let os = c.os.handle().clone();
        let last = std::cell::RefCell::new(None);
        c.watch(move |c| {
            let now = look(c);
            // Only a change goes over D-Bus: the desktop redraws the menu for each one
            if last.borrow().as_ref() == Some(&now) {
                return;
            }
            *last.borrow_mut() = Some(now.clone());
            let (status, one_tap, paired) = now;
            let handle = handle.clone();
            os.spawn(async move {
                handle
                    .update(|t| {
                        t.status = status;
                        t.one_tap = one_tap;
                        t.paired = paired;
                    })
                    .await;
            });
        });
    });
}
