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

pub fn load_css() {
    let provider = gtk::CssProvider::new();
    provider.load_from_string(CSS);
    if let Some(display) = gdk::Display::default() {
        gtk::style_context_add_provider_for_display(&display, &provider, gtk::STYLE_PROVIDER_PRIORITY_APPLICATION);
    }
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
        Palette { teal: rgba("#3DD6BE"), grey: rgba("#8E9194"), paused: rgba("#5B8FC4") }
    } else {
        Palette { teal: rgba("#0B7F6F"), grey: rgba("#9A9DA1"), paused: rgba("#32628D") }
    }
}

/// The timing tokens (motion.md). `spring` is the prototype's: 7.7% overshoot, settled by about
/// 45% of 420 ms. As a damped spring: damping ratio 0.632, response 0.388 × d seconds.
pub mod motion {
    pub fn spring(duration: f64) -> adw::SpringParams {
        let response = 0.388 * duration;
        let stiffness = (2.0 * std::f64::consts::PI / response).powi(2);
        adw::SpringParams::new(0.632, 1.0, stiffness)
    }
    /// Default spatial: open, expand, insert, press release.
    pub fn default() -> adw::SpringParams {
        spring(0.42)
    }
    /// Presses, chevrons, small icons.
    pub fn fast() -> adw::SpringParams {
        spring(0.3)
    }
    pub const FADE_MS: u32 = 350;
    pub const EXIT_MS: u32 = 180;
    pub const SETTLE_MS: u32 = 700;
    /// Sections arriving when a surface opens: 60 ms, then 35 ms each.
    pub fn stagger(i: u32) -> u32 {
        60 + 35 * i
    }
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

/// Debug: prints the window's widget tree with each widget's size and place (SEAM_DUMP=1), for
/// layout bugs seen in a screenshot.
#[cfg(debug_assertions)]
pub fn dump(w: &gtk::Widget, depth: usize, out: &mut String) {
    let b = w.compute_bounds(&w.root().map(|r| r.upcast::<gtk::Widget>()).unwrap_or_else(|| w.clone()));
    let (x, y, wd, ht) = b.map(|b| (b.x(), b.y(), b.width(), b.height())).unwrap_or_default();
    let extra = w.downcast_ref::<gtk::Label>().map(|l| format!(" {:?}", l.label().chars().take(24).collect::<String>())).unwrap_or_default();
    out.push_str(&format!("{}{} {:.0},{:.0} {:.0}x{:.0}{}\n", "  ".repeat(depth), w.type_().name(), x, y, wd, ht, extra));
    let mut c = w.first_child();
    while let Some(child) = c {
        if child.is_visible() {
            dump(&child, depth + 1, out);
        }
        c = child.next_sibling();
    }
}
