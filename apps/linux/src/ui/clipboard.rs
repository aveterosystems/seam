// SPDX-License-Identifier: AGPL-3.0-only
//! What crossed either way in the last day (board M06's clipboard column), as a boxed list of
//! stock rows: the copy, where it came from and when, Open for a link, and Copy. While copies need
//! one tap (decision 0012), a banner offers the automatic setup and the header has Send Clipboard.

use std::cell::RefCell;
use std::rc::Rc;

use adw::prelude::*;
use seam_computer::features::clipboard::{Clip, openable_url};

use crate::app::Controller;
use crate::os::clipboard::Reach;

pub struct Page {
    /// The banner over the list or its empty states.
    pub widget: gtk::Box,
    stack: gtk::Stack,
    /// "Automatic clipboard isn't set up": shown while copies need one tap.
    banner: adw::Banner,
    waiting: adw::ActionRow,
    waiting_list: gtk::ListBox,
    list: gtk::ListBox,
    empty: adw::StatusPage,
    off: adw::StatusPage,
    shown: RefCell<Vec<Clip>>,
    controller: std::rc::Weak<Controller>,
}

impl Page {
    pub fn new(c: &Rc<Controller>) -> Rc<Self> {
        let banner = adw::Banner::builder()
            .title("Automatic clipboard isn't set up")
            .button_label("Set Up")
            .revealed(false)
            .build();
        banner.set_action_name(Some("app.clipboard-setup"));

        // Waiting for the phone: how many, and when they go
        let waiting = adw::ActionRow::builder().use_markup(false).build();
        let clock = gtk::Image::from_icon_name("document-open-recent-symbolic");
        clock.set_accessible_role(gtk::AccessibleRole::Presentation);
        waiting.add_prefix(&clock);
        let waiting_list = gtk::ListBox::builder()
            .selection_mode(gtk::SelectionMode::None)
            .margin_bottom(18)
            .build();
        waiting_list.add_css_class("boxed-list");
        waiting_list.append(&waiting);

        let list = gtk::ListBox::builder()
            .selection_mode(gtk::SelectionMode::None)
            .build();
        list.add_css_class("boxed-list");
        let note = gtk::Label::builder()
            .label("Kept in memory for a day, and gone when Seam quits. Copies that password managers mark as private, and copied files, are never sent.")
            .xalign(0.0)
            .wrap(true)
            .margin_top(12)
            .margin_start(6)
            .build();
        note.add_css_class("secondary");
        let column = gtk::Box::builder()
            .orientation(gtk::Orientation::Vertical)
            .build();
        column.append(&waiting_list);
        column.append(&list);
        column.append(&note);
        let clamp = adw::Clamp::builder()
            .maximum_size(720)
            .child(&column)
            .margin_top(18)
            .margin_bottom(24)
            .margin_start(12)
            .margin_end(12)
            .build();
        let scroll = gtk::ScrolledWindow::builder()
            .hscrollbar_policy(gtk::PolicyType::Never)
            .child(&clamp)
            .vexpand(true)
            .build();

        let empty = adw::StatusPage::builder()
            .icon_name("edit-paste-symbolic")
            .title("Nothing Copied Yet")
            .vexpand(true)
            .build();
        empty.add_css_class("compact");
        let off = adw::StatusPage::builder()
            .icon_name("edit-paste-symbolic")
            .title("Clipboard Sync Is Off")
            .vexpand(true)
            .build();
        off.add_css_class("compact");
        let turn_on = gtk::Button::builder()
            .label("Turn On")
            .halign(gtk::Align::Center)
            .build();
        turn_on.add_css_class("pill");
        turn_on.add_css_class("suggested-action");
        off.set_child(Some(&turn_on));
        let weak = Rc::downgrade(c);
        turn_on.connect_clicked(move |_| {
            if let Some(c) = weak.upgrade() {
                c.set_clipboard_sync(true)
            }
        });

        let stack = gtk::Stack::builder().vexpand(true).build();
        stack.add_named(&scroll, Some("list"));
        stack.add_named(&empty, Some("empty"));
        stack.add_named(&off, Some("off"));
        let widget = gtk::Box::builder()
            .orientation(gtk::Orientation::Vertical)
            .build();
        widget.append(&banner);
        widget.append(&stack);

        let page = Rc::new(Self {
            widget,
            stack,
            banner,
            waiting,
            waiting_list,
            list,
            empty,
            off,
            shown: RefCell::default(),
            controller: Rc::downgrade(c),
        });
        let p = Rc::downgrade(&page);
        c.watch(move |c| {
            if let Some(p) = p.upgrade() {
                p.render(c)
            }
        });
        page
    }

    fn render(&self, c: &Controller) {
        let m = c.model();
        let name = m.phone_name(false);
        let connected = m.link(crate::ui::now_ms()).connected;
        self.banner.set_revealed(
            m.clipboard_sync && m.phone.is_some() && c.pasteboard.reach() == Reach::OneTap,
        );
        let waiting = m.waiting > 0 && !connected;
        self.waiting_list.set_visible(waiting);
        if waiting {
            self.waiting
                .set_title(&seam_looks::words::waiting(m.waiting as u32));
            self.waiting
                .set_subtitle(&format!("Sends when {name} is back"));
        }
        self.off
            .set_description(Some(&format!("Turn it on to share copies with {name}.")));
        self.empty.set_description(Some(&format!(
            "Copies to and from {name} show here for a day."
        )));
        self.stack
            .set_visible_child_name(match (m.clipboard_sync, m.clips.is_empty()) {
                (false, _) => "off",
                (true, true) if !waiting => "empty",
                _ => "list",
            });
        if *self.shown.borrow() == m.clips {
            return;
        }
        let old = self.shown.borrow().clone();
        // Clips only ever arrive at the top, or all go at once (cleared, sync off)
        let fresh = m.clips.len().saturating_sub(old.len());
        let only_new = m.clips[fresh..] == old[..];
        if !only_new {
            while let Some(row) = self.list.first_child() {
                self.list.remove(&row);
            }
        }
        let new_ones = if only_new {
            &m.clips[..fresh]
        } else {
            &m.clips[..]
        };
        for (i, clip) in new_ones.iter().enumerate() {
            // Stock rows go straight in, as GNOME's own lists do
            self.list.insert(&self.row(clip, &name), i as i32);
        }
        *self.shown.borrow_mut() = m.clips.clone();
    }

    fn row(&self, clip: &Clip, phone: &str) -> adw::ActionRow {
        let url = openable_url(&clip.text).map(str::to_string);
        let preview: String = clip.text.chars().take(400).collect();
        let from = if clip.from_phone {
            format!("From {phone}")
        } else {
            "From this computer".into()
        };
        // A copy is plain text, never markup: whatever was copied shows as it is
        let row = adw::ActionRow::builder()
            .use_markup(false)
            .title(preview.trim())
            .title_lines(2)
            .subtitle(format!(
                "{from} · {}",
                seam_looks::words::ago(
                    clip.at,
                    crate::ui::now_ms(),
                    &crate::model::SystemTime,
                    "just now"
                )
            ))
            .build();
        let kind = gtk::Image::from_icon_name(if url.is_some() {
            "insert-link-symbolic"
        } else {
            "format-justify-left-symbolic"
        });
        kind.set_accessible_role(gtk::AccessibleRole::Presentation);
        row.add_prefix(&kind);
        if let Some(url) = url {
            let open = gtk::Button::from_icon_name("adw-external-link-symbolic");
            open.add_css_class("flat");
            open.set_valign(gtk::Align::Center);
            open.set_tooltip_text(Some("Open in Browser"));
            open.update_property(&[gtk::accessible::Property::Label("Open in Browser")]);
            open.connect_clicked(move |b| {
                gtk::UriLauncher::new(&url).launch(
                    b.root().and_downcast_ref::<gtk::Window>(),
                    None::<&gtk::gio::Cancellable>,
                    |_| {},
                );
            });
            row.add_suffix(&open);
        }
        let copy = gtk::Button::from_icon_name("edit-copy-symbolic");
        copy.add_css_class("flat");
        copy.set_valign(gtk::Align::Center);
        copy.set_tooltip_text(Some("Copy"));
        copy.update_property(&[gtk::accessible::Property::Label("Copy")]);
        let (weak, text) = (self.controller.clone(), clip.text.clone());
        copy.connect_clicked(move |_| {
            // Seam has focus: the clipboard takes it straight away; the core marks it as ours
            if let Some(display) = gtk::gdk::Display::default() {
                display.clipboard().set_text(&text);
            }
            if let Some(c) = weak.upgrade() {
                c.copy_again(text.clone());
                c.toast("Copied");
            }
        });
        row.add_suffix(&copy);
        row
    }
}
