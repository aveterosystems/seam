// SPDX-License-Identifier: AGPL-3.0-only
//! Seam's few shared pieces: the phone's disc, the live status dot, the value swap and the
//! arrive/leave rows. Screens compose these; none draws its own.

use std::cell::{Cell, RefCell};
use std::f64::consts::PI;
use std::rc::Rc;

use adw::prelude::*;
use gtk::glib;

/// The phone on a disc: teal while connected, grey when not (the colour eases, 500 ms).
pub fn phone_disc(size: i32) -> gtk::Box {
    let b = gtk::Box::builder()
        .width_request(size)
        .height_request(size)
        .valign(gtk::Align::Center)
        .halign(gtk::Align::Center)
        .homogeneous(true)
        .build();
    b.add_css_class("phone-disc");
    let icon = gtk::Image::from_icon_name("phone-symbolic");
    icon.set_pixel_size((size as f64 * 0.5) as i32);
    // Centred without expanding: an expanding child would stretch every box it sits in
    icon.set_halign(gtk::Align::Center);
    icon.set_valign(gtk::Align::Center);
    b.set_vexpand(false);
    icon.set_accessible_role(gtk::AccessibleRole::Presentation);
    b.append(&icon);
    b
}

pub fn set_disc(disc: &gtk::Box, connected: bool) {
    if connected {
        disc.add_css_class("connected")
    } else {
        disc.remove_css_class("connected")
    }
}

/// The live dot beside the status line. Connected, it sends out a soft ring every 2.4 s
/// (catalogue 10); otherwise it's a still grey dot.
#[derive(Clone)]
pub struct StatusDot {
    pub widget: gtk::DrawingArea,
    on: Rc<Cell<bool>>,
    tick: Rc<RefCell<Option<gtk::TickCallbackId>>>,
    t: Rc<Cell<f64>>,
}

impl StatusDot {
    pub fn new() -> Self {
        let widget = gtk::DrawingArea::builder()
            .content_width(14)
            .content_height(14)
            .valign(gtk::Align::Center)
            .build();
        widget.set_accessible_role(gtk::AccessibleRole::Presentation);
        let on = Rc::new(Cell::new(false));
        let t = Rc::new(Cell::new(0.0));
        let (o, tt) = (on.clone(), t.clone());
        widget.set_draw_func(move |_, cr, w, h| {
            let p = crate::ui::palette();
            let c = if o.get() { p.teal } else { p.grey };
            let (cx, cy) = (w as f64 / 2.0, h as f64 / 2.0);
            let r = 3.0;
            if o.get() && crate::ui::animations() {
                // The ring: scale 1 → 2.8 while fading, every 2.4 s
                let phase: f64 = (tt.get() % 2.4) / 2.4;
                let ease = 1.0 - (1.0 - phase).powi(3);
                cr.set_source_rgba(
                    c.red() as f64,
                    c.green() as f64,
                    c.blue() as f64,
                    0.45 * (1.0 - phase),
                );
                cr.arc(cx, cy, r * (1.0 + 1.8 * ease), 0.0, 2.0 * PI);
                let _ = cr.fill();
            }
            cr.set_source_rgba(c.red() as f64, c.green() as f64, c.blue() as f64, 1.0);
            cr.arc(cx, cy, r, 0.0, 2.0 * PI);
            let _ = cr.fill();
        });
        Self {
            widget,
            on,
            tick: Rc::default(),
            t,
        }
    }

    pub fn set(&self, on: bool) {
        if self.on.replace(on) == on {
            return;
        }
        self.widget.queue_draw();
        if on && self.tick.borrow().is_none() {
            let (t, on, tick) = (self.t.clone(), self.on.clone(), self.tick.clone());
            let id = self.widget.add_tick_callback(move |w, clock| {
                t.set(clock.frame_time() as f64 / 1_000_000.0);
                w.queue_draw();
                if on.get() && crate::ui::animations() {
                    glib::ControlFlow::Continue
                } else {
                    tick.borrow_mut().take();
                    glib::ControlFlow::Break
                }
            });
            *self.tick.borrow_mut() = Some(id);
        }
    }
}

impl Default for StatusDot {
    fn default() -> Self {
        Self::new()
    }
}

/// A label whose text changes in place (catalogue 3): the old words leave upward, the new rise
/// into the same fixed-height slot, so nothing around it moves.
#[derive(Clone)]
pub struct Swap {
    pub widget: gtk::Stack,
    text: Rc<RefCell<String>>,
    flip: Rc<Cell<bool>>,
    labels: [gtk::Label; 2],
}

impl Swap {
    /// `lines`: how many lines the text may take before it's cut short (the slot keeps that height).
    pub fn new(classes: &[&str]) -> Self {
        Self::with_lines(classes, 1)
    }

    pub fn with_lines(classes: &[&str], lines: i32) -> Self {
        let widget = gtk::Stack::builder()
            .transition_type(gtk::StackTransitionType::SlideUp)
            .transition_duration(260)
            .vhomogeneous(true)
            .hhomogeneous(false)
            .interpolate_size(true)
            .build();
        let make = || {
            let l = gtk::Label::builder()
                .xalign(0.0)
                .ellipsize(gtk::pango::EllipsizeMode::End)
                .wrap(lines > 1)
                .wrap_mode(gtk::pango::WrapMode::WordChar)
                .lines(lines)
                .build();
            for c in classes {
                l.add_css_class(c);
            }
            l
        };
        let labels = [make(), make()];
        widget.add_named(&labels[0], Some("a"));
        widget.add_named(&labels[1], Some("b"));
        Self {
            widget,
            text: Rc::default(),
            flip: Rc::default(),
            labels,
        }
    }

    pub fn set(&self, text: &str) {
        if *self.text.borrow() == text {
            return;
        }
        let first = self.text.borrow().is_empty();
        *self.text.borrow_mut() = text.to_string();
        let next = !self.flip.get();
        self.flip.set(next);
        let (label, name) = if next {
            (&self.labels[1], "b")
        } else {
            (&self.labels[0], "a")
        };
        label.set_label(text);
        self.widget
            .update_property(&[gtk::accessible::Property::Label(text)]);
        if first || !crate::ui::animations() {
            self.widget
                .set_visible_child_full(name, gtk::StackTransitionType::None);
        } else {
            self.widget
                .set_visible_child_full(name, gtk::StackTransitionType::SlideUp);
        }
    }

    pub fn set_tooltip(&self, text: Option<&str>) {
        self.widget.set_tooltip_text(text);
    }
}

/// A row that arrives by growing from nothing, fading in and sliding down (catalogue 5), and
/// leaves by collapsing and fading (catalogue 6). Wrap a list row's content in one.
pub fn arrive(child: &impl IsA<gtk::Widget>, animate: bool) -> gtk::Revealer {
    let r = gtk::Revealer::builder()
        .transition_type(gtk::RevealerTransitionType::SlideDown)
        .transition_duration(320)
        .reveal_child(!animate)
        .child(child)
        .build();
    if animate {
        let r2 = r.clone();
        glib::idle_add_local_once(move || r2.set_reveal_child(true));
    }
    r
}

/// Collapses a revealer, then runs `done` (removing the row) once it's gone.
pub fn leave(r: &gtk::Revealer, done: impl FnOnce() + 'static) {
    if !crate::ui::animations() {
        return done();
    }
    r.set_transition_type(gtk::RevealerTransitionType::SlideUp);
    r.set_transition_duration(280);
    r.set_reveal_child(false);
    let done = RefCell::new(Some(done));
    r.connect_child_revealed_notify(move |r| {
        if !r.is_child_revealed()
            && let Some(f) = done.borrow_mut().take()
        {
            f()
        }
    });
}

/// A small icon that names the path while connected; hovering says it in words.
pub fn path_icon() -> gtk::Image {
    let i = gtk::Image::new();
    i.set_pixel_size(14);
    i.add_css_class("dim-label");
    i
}

pub fn set_path_icon(i: &gtk::Image, look: &seam_looks::LinkLook) {
    match (look.connected, look.icon) {
        (true, Some(icon)) => {
            i.set_icon_name(Some(&crate::model::icon_name(icon)));
            i.set_tooltip_text(Some(&look.label));
            i.set_visible(true);
        }
        _ => i.set_visible(false),
    }
}

/// An empty page: an icon, what's going on, and the one thing to do about it.
pub fn status_page() -> (adw::StatusPage, gtk::Button) {
    let button = gtk::Button::builder().halign(gtk::Align::Center).build();
    button.add_css_class("pill");
    button.add_css_class("suggested-action");
    let page = adw::StatusPage::builder()
        .child(&button)
        .vexpand(true)
        .build();
    page.add_css_class("compact");
    (page, button)
}
