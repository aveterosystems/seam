// SPDX-License-Identifier: AGPL-3.0-only
//! The phone as Seam shows it everywhere: the disc, its name, the status line with its live dot
//! and path icon, the stitch, and its battery. The sidebar's card and the Phone page's header
//! are this one piece at two sizes.

use std::rc::Rc;

use adw::prelude::*;

use crate::app::Controller;
use crate::model::{Model, icon_name};
use crate::ui::stitch::Stitch;
use crate::ui::widgets::{StatusDot, Swap, path_icon, phone_disc, set_disc, set_path_icon};

#[derive(Clone, Copy, PartialEq)]
pub enum Size {
    Card,
    Header,
}

#[derive(Clone)]
pub struct PhoneSummary {
    pub widget: gtk::Box,
    disc: gtk::Box,
    name: gtk::Label,
    dot: StatusDot,
    line: Swap,
    path: gtk::Image,
    stitch: Stitch,
    battery: gtk::Box,
    battery_icon: gtk::Image,
    battery_text: Swap,
    stats: gtk::Box,
    size: Size,
}

impl PhoneSummary {
    pub fn new(size: Size) -> Self {
        let header = size == Size::Header;
        let disc = phone_disc(if header { 56 } else { 32 });
        let name = gtk::Label::builder()
            .xalign(0.0)
            .ellipsize(gtk::pango::EllipsizeMode::End)
            .hexpand(true)
            .build();
        name.add_css_class(if header { "title-2" } else { "heading" });
        let dot = StatusDot::new();
        // One fixed-height line; the whole sentence is in its tooltip and read out
        let line = Swap::new(&["secondary", "status-line"]);
        let path = path_icon();
        // The dot's drawing area leaves 6 px for its ring: the name, the stitch and the stats step
        // in by as much, so the dot itself lines up with them
        let status = gtk::Box::builder().spacing(6).build();
        // The ring's room already spaces the dot from its words: a tighter gap there
        let dotted = gtk::Box::builder().spacing(2).build();
        dotted.append(&dot.widget);
        dotted.append(&line.widget);
        status.append(&dotted);
        status.append(&path); // the path's icon right after its words, not across the card
        let stitch = Stitch::new();
        stitch.widget.set_margin_start(6);
        stitch.widget.set_margin_top(if header { 10 } else { 8 });
        stitch.widget.set_margin_bottom(if header { 10 } else { 0 });

        let battery_icon = gtk::Image::new();
        battery_icon.set_pixel_size(16);
        let battery_text = Swap::new(&["numeric"]);
        let battery = gtk::Box::builder()
            .spacing(4)
            .valign(gtk::Align::Center)
            .build();
        battery.append(&battery_icon);
        battery.append(&battery_text.widget);
        if !header {
            battery.add_css_class("secondary"); // in the header, the stats row is dim already
        }
        let stats = gtk::Box::builder().spacing(14).build();
        stats.add_css_class("dim-label");

        let text = gtk::Box::builder()
            .orientation(gtk::Orientation::Vertical)
            .hexpand(true)
            .build();
        let widget = gtk::Box::builder()
            .spacing(if header { 16 } else { 10 })
            .build();
        if header {
            let top = gtk::Box::builder()
                .orientation(gtk::Orientation::Vertical)
                .spacing(2)
                .build();
            name.set_margin_start(6);
            stats.set_margin_start(6);
            top.append(&name);
            top.append(&status);
            text.append(&top);
            text.append(&stitch.widget);
            stats.prepend(&battery);
            text.append(&stats);
            widget.append(&disc);
            widget.append(&text);
            widget.set_margin_top(6);
            widget.set_margin_bottom(6);
        } else {
            // The disc beside the name (with the battery) and the status, then the stitch the
            // whole width of the card under them, as on the Mac
            let head = gtk::Box::builder().spacing(8).margin_start(6).build();
            head.append(&name);
            head.append(&battery);
            status.set_margin_top(2);
            text.append(&head);
            text.append(&status);
            disc.set_valign(gtk::Align::Center);
            let top = gtk::Box::builder().spacing(10).build();
            top.append(&disc);
            top.append(&text);
            widget.set_orientation(gtk::Orientation::Vertical);
            widget.set_spacing(0);
            stitch.widget.set_margin_start(0);
            widget.append(&top);
            widget.append(&stitch.widget);
        }
        Self {
            widget,
            disc,
            name,
            dot,
            line,
            path,
            stitch,
            battery,
            battery_icon,
            battery_text,
            stats,
            size,
        }
    }

    pub fn render(&self, m: &Model, now: i64) {
        let look = m.link(now);
        set_disc(&self.disc, look.connected);
        self.name.set_label(&m.phone_name(true));
        let paused = look.kind == seam_looks::LinkKind::Paused;
        crate::ui::widgets::set_disc_paused(&self.disc, paused);
        // Paused is the user's own choice: the dot and the words take the calm paused blue too
        self.dot.set_state(look.connected, paused);
        if paused {
            self.line.widget.add_css_class("paused-label");
        } else {
            self.line.widget.remove_css_class("paused-label");
        }
        let words = if self.size == Size::Header {
            m.hero_line(now)
        } else {
            m.card_line(now)
        };
        self.line.set(&words);
        // The full sentence and why, on hover; the line itself is short
        let full = m.status_line(now);
        let tip = match &look.detail {
            Some(d) => format!("{full}\n{d}"),
            None => full,
        };
        self.line.set_tooltip(Some(&tip));
        set_path_icon(&self.path, &look);
        let connecting = matches!(
            look.kind,
            seam_looks::LinkKind::ConnectingTor | seam_looks::LinkKind::Reconnecting
        );
        self.stitch.set(
            look.line,
            connecting,
            look.kind == seam_looks::LinkKind::Paused,
        );

        let status = m.phone_status(now);
        self.battery
            .set_visible(status.as_ref().is_some_and(|s| s.battery.is_some()));
        if let Some(s) = &status {
            // The real level, not the shared look's quarter steps: Adwaita draws tens
            self.battery_icon
                .set_icon_name(Some(&crate::model::battery_icon(s.percent, s.charging)));
            let text = match (&s.battery, s.stale, &s.meta) {
                (Some(b), true, Some(meta)) if self.size == Size::Header => format!("{b} · {meta}"),
                (Some(b), ..) => b.clone(),
                _ => String::new(),
            };
            self.battery_text.set(&text);
            // 15% or below and not charging: the warning colour (catalogue 11); an old reading is dimmed too
            if s.low {
                self.battery.add_css_class("warn-label")
            } else {
                self.battery.remove_css_class("warn-label")
            }
            // An old reading is dimmed, but never a low one: the warning must stay readable (4.5:1)
            self.battery
                .set_opacity(if s.stale && !s.low { 0.8 } else { 1.0 });
            self.battery.set_tooltip_text(s.battery_detail.as_deref());
            self.battery
                .update_property(&[gtk::accessible::Property::Label(&s.spoken)]);
        }
        if self.size == Size::Header {
            // Network, free storage and Do Not Disturb after the battery, as on the Mac
            while let Some(c) = self.stats.last_child() {
                if c == self.battery {
                    break;
                }
                self.stats.remove(&c);
            }
            if let Some(s) = &status {
                if let (Some(icon), Some(net)) = (s.network_icon, &s.network) {
                    self.stats.append(&stat(&icon_name(icon), net, net));
                }
                if let (Some(free), Some(detail)) = (&s.storage, &s.storage_detail) {
                    self.stats.append(&stat(
                        "drive-harddisk-symbolic",
                        &format!("{free} free"),
                        detail,
                    ));
                }
                if s.dnd {
                    self.stats.append(&stat(
                        "weather-clear-night-symbolic",
                        "Do Not Disturb",
                        "Do Not Disturb",
                    ));
                }
            }
        }
        self.widget
            .update_property(&[gtk::accessible::Property::Label(&format!(
                "{}, {}",
                m.phone_name(true),
                look.sentence()
            ))]);
    }
}

/// An icon and its short value; hovering shows it in full, and screen readers read it in full.
fn stat(icon: &str, value: &str, detail: &str) -> gtk::Box {
    let b = gtk::Box::builder().spacing(5).build();
    let i = gtk::Image::from_icon_name(icon);
    i.set_pixel_size(16);
    b.append(&i);
    let l = gtk::Label::new(Some(value));
    l.add_css_class("numeric");
    b.append(&l);
    b.set_tooltip_text(Some(detail));
    b.update_property(&[gtk::accessible::Property::Label(detail)]);
    b
}

/// The sidebar's card: the summary as a button that opens the phone's preferences.
pub fn card(c: &Rc<Controller>) -> gtk::Button {
    let summary = PhoneSummary::new(Size::Card);
    let button = gtk::Button::builder().child(&summary.widget).build();
    button.add_css_class("flat");
    button.add_css_class("phone-card");
    button.set_tooltip_text(Some("Phone settings"));
    button.set_action_name(Some("app.preferences-phone"));
    let s = summary.clone();
    c.watch(move |c| s.render(&c.model(), crate::ui::now_ms()));
    // "Last seen 12 min ago" moves on with the clock
    let (s, weak) = (summary, Rc::downgrade(c));
    gtk::glib::timeout_add_seconds_local(30, move || match weak.upgrade() {
        Some(c) => {
            s.render(&c.model(), crate::ui::now_ms());
            gtk::glib::ControlFlow::Continue
        }
        None => gtk::glib::ControlFlow::Break,
    });
    button
}
