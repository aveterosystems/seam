// SPDX-License-Identifier: AGPL-3.0-only
//! Seam's screens on Linux: stock libadwaita (headers, split view, boxed lists, preferences),
//! with Seam's own pieces only where the design system has them (the stitch, the phone's disc,
//! the status dot). Motion follows docs/design/motion.md, on libadwaita's springs.

pub mod clipboard;
pub mod notifications;
pub mod pairing;
pub mod phone;
pub mod preferences;
pub mod setup;
pub mod stitch;
pub mod widgets;
pub mod window;

use adw::prelude::*;
use gtk::{gdk, glib};

/// The design system's tokens (design-system.md, "Mac tokens", which Linux shares) and the few
/// styles stock widgets don't have. Controls keep the desktop's own accent colour; the stitch,
/// the phone's disc and the status dot are Seam teal.
const CSS: &str = include_str!("style.css");
const DARK_CSS: &str = include_str!("style-dark.css");

pub fn load_css() {
    let Some(display) = gdk::Display::default() else {
        return;
    };
    let provider = gtk::CssProvider::new();
    provider.load_from_string(CSS);
    gtk::style_context_add_provider_for_display(
        &display,
        &provider,
        gtk::STYLE_PROVIDER_PRIORITY_APPLICATION,
    );
    // Dark tokens on top while libadwaita is dark (the desktop's choice), off again when it isn't
    let dark = gtk::CssProvider::new();
    dark.load_from_string(DARK_CSS);
    let apply = move |m: &adw::StyleManager| {
        if m.is_dark() {
            gtk::style_context_add_provider_for_display(
                &display,
                &dark,
                gtk::STYLE_PROVIDER_PRIORITY_APPLICATION + 1,
            );
        } else {
            gtk::style_context_remove_provider_for_display(&display, &dark);
        }
    };
    let manager = adw::StyleManager::default();
    apply(&manager);
    manager.connect_dark_notify(apply);
}

/// Seam teal and the status colours, light or dark, for what's drawn by hand.
pub struct Palette {
    pub teal: gdk::RGBA,
    pub grey: gdk::RGBA,
    pub paused: gdk::RGBA,
}

pub fn palette() -> Palette {
    let dark = adw::StyleManager::default().is_dark();
    let rgba = |s: &str| gdk::RGBA::parse(s).unwrap_or(gdk::RGBA::BLACK);
    if dark {
        Palette {
            teal: rgba("#3DD6BE"),
            grey: rgba("#8E9194"),
            paused: rgba("#5B8FC4"),
        }
    } else {
        Palette {
            teal: rgba("#0B7F6F"),
            grey: rgba("#9A9DA1"),
            paused: rgba("#32628D"),
        }
    }
}

/// Motion on Linux is libadwaita's own (GNOME HIG): stock widgets keep their transitions. Seam's
/// own pieces (the stitch, the status dot) follow motion.md's catalogue 10.
pub mod motion {
    /// Colour and opacity changes (motion.md's `fade`).
    pub const FADE_MS: u32 = 350;
}

/// Whether the desktop asks for less motion (GNOME's and KDE's "Animations" switch).
pub fn animations() -> bool {
    gtk::Settings::default().is_some_and(|s| s.is_gtk_enable_animations())
}

/// Says something to screen readers (an arrival), politely, through the active window.
pub fn announce(app: &adw::Application, words: &str) {
    if let Some(w) = app.active_window() {
        w.announce(words, gtk::AccessibleAnnouncementPriority::Medium);
    }
}

/// Unix ms now.
pub fn now_ms() -> i64 {
    glib::real_time() / 1000
}

/// One widget in the debug dump: its type and its size and place. There's no way to pass a
/// label's words (a notification, a copy), so none can reach the file.
#[cfg(debug_assertions)]
fn dump_line(depth: usize, kind: &str, min: i32, (x, y, w, h): (f32, f32, f32, f32)) -> String {
    format!(
        "{}{kind} min{min} {x:.0},{y:.0} {w:.0}x{h:.0}\n",
        "  ".repeat(depth)
    )
}

/// Debug: prints the window's widget tree with each widget's size and place (SEAM_DUMP=1), for
/// layout bugs seen in a screenshot.
#[cfg(debug_assertions)]
pub fn dump(w: &gtk::Widget, depth: usize, out: &mut String) {
    let b = w.compute_bounds(
        &w.root()
            .map(|r| r.upcast::<gtk::Widget>())
            .unwrap_or_else(|| w.clone()),
    );
    let (x, y, wd, ht) = b
        .map(|b| (b.x(), b.y(), b.width(), b.height()))
        .unwrap_or_default();
    let (min, _, _, _) = w.measure(gtk::Orientation::Horizontal, -1);
    out.push_str(&dump_line(depth, w.type_().name(), min, (x, y, wd, ht)));
    let mut c = w.first_child();
    while let Some(child) = c {
        if child.is_visible() {
            dump(&child, depth + 1, out);
        }
        c = child.next_sibling();
    }
}

#[cfg(all(test, debug_assertions))]
mod tests {
    /// The dump says what a widget is and where, never what it says.
    #[test]
    fn the_debug_dump_carries_no_words() {
        let line = super::dump_line(2, "GtkLabel", 15, (86.0, 102.0, 374.0, 18.0));
        assert_eq!(line, "    GtkLabel min15 86,102 374x18\n");
    }
}
