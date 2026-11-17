// SPDX-License-Identifier: AGPL-3.0-only
//! What crossed either way in the last day (board M06's clipboard column), with Send clipboard
//! at the top while copies need one tap (decision 0012), and Copy or Open on each.

use std::cell::RefCell;
use std::rc::Rc;

use adw::prelude::*;
use seam_computer::features::clipboard::{Clip, openable_url};

use crate::app::Controller;
use crate::os::clipboard::Reach;
use crate::ui::widgets::arrive;

pub struct Page {
    pub widget: gtk::ScrolledWindow,
    off: adw::ActionRow,
    one_tap: adw::ActionRow,
    waiting: gtk::Label,
    list: gtk::ListBox,
    empty: gtk::Box,
    empty_detail: gtk::Label,
    shown: RefCell<Vec<Clip>>,
    controller: std::rc::Weak<Controller>,
}

impl Page {
    pub fn new(c: &Rc<Controller>) -> Rc<Self> {
        // Sync off: say so, with the switch to turn it back on
        let off = adw::ActionRow::builder().title("Clipboard Sync Is Off").build();
        let on = gtk::Button::builder().label("Turn On").valign(gtk::Align::Center).build();
        off.add_suffix(&on);
        off.add_prefix(&gtk::Image::from_icon_name("edit-paste-symbolic"));
        let weak = Rc::downgrade(c);
        on.connect_clicked(move |_| {
            if let Some(c) = weak.upgrade() {
                c.set_clipboard_sync(true)
            }
        });
        // One tap: this desktop doesn't let Seam see copies by itself until it's set up
        let one_tap = adw::ActionRow::builder()
            .title("Send What You Copied")
            .subtitle("Copies go to your phone when you click Send, until automatic clipboard is set up.")
            .build();
        let send = gtk::Button::builder().label("Send Clipboard").valign(gtk::Align::Center).build();
        send.add_css_class("suggested-action");
        one_tap.add_suffix(&send);
        let set_up = gtk::Button::builder().label("Set Up").valign(gtk::Align::Center).build();
        set_up.set_action_name(Some("app.clipboard-setup"));
        one_tap.add_suffix(&set_up);
        let weak = Rc::downgrade(c);
        send.connect_clicked(move |_| {
            let Some(c) = weak.upgrade() else { return };
            let name = c.model().phone_name(false);
            let w = Rc::downgrade(&c);
            c.send_clipboard(move |ok| {
                if let Some(c) = w.upgrade() {
                    // A password or a copied file never goes, and says so without saying what it was
                    c.toast(&if ok { format!("Sent to {name}") } else { "Nothing to send: copy some text first".into() });
                }
            });
        });
        let top = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).margin_bottom(18).build();
        top.add_css_class("boxed-list");
        top.append(&off);
        top.append(&one_tap);

        let waiting = gtk::Label::builder().xalign(0.0).margin_bottom(12).build();
        waiting.add_css_class("waiting-bar");
        waiting.add_css_class("dim-label");

        let heading = gtk::Label::builder().label("Recent Copies").xalign(0.0).margin_bottom(8).margin_start(6).build();
        heading.add_css_class("heading");
        let list = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).build();
        list.add_css_class("boxed-list");

        let empty_detail = gtk::Label::builder().wrap(true).justify(gtk::Justification::Center).build();
        empty_detail.add_css_class("dim-label");
        let empty_title = gtk::Label::new(Some("Nothing Copied Yet"));
        empty_title.add_css_class("title-4");
        let empty_icon = gtk::Image::from_icon_name("edit-paste-symbolic");
        empty_icon.set_pixel_size(32);
        empty_icon.add_css_class("dim-label");
        let empty = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(6).margin_top(28).margin_bottom(28).build();
        empty.append(&empty_icon);
        empty.append(&empty_title);
        empty.append(&empty_detail);

        let note = gtk::Label::builder()
            .label("Kept in memory for a day, and gone when Seam quits. Copies that password managers mark as private, and copied files, are never sent.")
            .xalign(0.0)
            .wrap(true)
            .margin_top(12)
            .margin_start(6)
            .build();
        note.add_css_class("dim-label");
        note.add_css_class("list-note");

        let column = gtk::Box::builder().orientation(gtk::Orientation::Vertical).build();
        column.append(&top);
        column.append(&waiting);
        column.append(&heading);
        column.append(&empty);
        column.append(&list);
        column.append(&note);
        let clamp = adw::Clamp::builder().maximum_size(720).child(&column).margin_top(18).margin_bottom(24).margin_start(12).margin_end(12).build();
        let widget = gtk::ScrolledWindow::builder().hscrollbar_policy(gtk::PolicyType::Never).child(&clamp).vexpand(true).build();
        let page = Rc::new(Self {
            widget,
            off,
            one_tap,
            waiting,
            list,
            empty,
            empty_detail,
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
        self.off.set_visible(!m.clipboard_sync);
        self.one_tap.set_visible(m.clipboard_sync && c.pasteboard.reach() == Reach::OneTap);
        let waiting = m.waiting > 0 && !connected;
        self.waiting.set_visible(waiting);
        if waiting {
            self.waiting.set_label(&format!("{} · sends when {name} is back", seam_looks::words::waiting(m.waiting as u32)));
        }
        self.empty.set_visible(m.clips.is_empty());
        self.empty_detail.set_label(&if m.clipboard_sync {
            format!("Copies to and from {name} show here for a day.")
        } else {
            format!("Turn on sync to share copies with {name}.")
        });
        if *self.shown.borrow() == m.clips {
            return;
        }
        let animate = crate::ui::animations() && !self.shown.borrow().is_empty();
        let old = self.shown.borrow().clone();
        // Clips only ever arrive at the top or all go at once (cleared, sync off)
        let fresh = m.clips.len().saturating_sub(old.len());
        let only_new = m.clips[fresh..] == old[..];
        if !only_new {
            while let Some(row) = self.list.first_child() {
                self.list.remove(&row);
            }
        }
        let (start, animate) = if only_new { (0, animate) } else { (0, false) };
        let new_ones = if only_new { &m.clips[..fresh] } else { &m.clips[..] };
        for (i, clip) in new_ones.iter().enumerate() {
            self.list.insert(&arrive(&self.row(clip, &name), animate), (start + i) as i32);
        }
        *self.shown.borrow_mut() = m.clips.clone();
    }

    fn row(&self, clip: &Clip, phone: &str) -> gtk::Box {
        let url = openable_url(&clip.text).map(str::to_string);
        let kind = gtk::Image::from_icon_name(if url.is_some() { "insert-link-symbolic" } else { "text-x-generic-symbolic" });
        kind.set_pixel_size(16);
        kind.set_size_request(32, 32);
        kind.add_css_class("row-icon");
        kind.add_css_class("kind");
        kind.set_valign(gtk::Align::Center);
        kind.set_accessible_role(gtk::AccessibleRole::Presentation);
        let preview: String = clip.text.chars().take(400).collect();
        let text = gtk::Label::builder().label(preview.trim()).xalign(0.0).wrap(true).wrap_mode(gtk::pango::WrapMode::WordChar).lines(2).ellipsize(gtk::pango::EllipsizeMode::End).build();
        let from = if clip.from_phone { format!("From {phone}") } else { "From this computer".into() };
        let meta = gtk::Label::builder()
            .label(format!("{from} · {}", seam_looks::words::ago(clip.at, crate::ui::now_ms(), &crate::model::SystemTime, "just now")))
            .xalign(0.0)
            .build();
        meta.add_css_class("dim-label");
        meta.add_css_class("caption");
        let column = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(2).hexpand(true).valign(gtk::Align::Center).build();
        column.append(&text);
        column.append(&meta);
        let actions = gtk::Box::builder().spacing(4).valign(gtk::Align::Center).build();
        actions.add_css_class("row-actions");
        if let Some(url) = url {
            let open = gtk::Button::with_label("Open");
            open.add_css_class("flat");
            open.connect_clicked(move |b| {
                let launcher = gtk::UriLauncher::new(&url);
                launcher.launch(b.root().and_downcast_ref::<gtk::Window>(), None::<&gtk::gio::Cancellable>, |_| {});
            });
            actions.append(&open);
        }
        let copy = gtk::Button::with_label("Copy");
        copy.add_css_class("flat");
        let (weak, text_) = (self.controller.clone(), clip.text.clone());
        copy.connect_clicked(move |b| {
            // Seam has focus: the clipboard takes it straight away; the core marks it as ours
            if let Some(display) = gtk::gdk::Display::default() {
                display.clipboard().set_text(&text_);
            }
            if let Some(c) = weak.upgrade() {
                c.copy_again(text_.clone());
                c.toast("Copied");
            }
            let _ = b;
        });
        actions.append(&copy);
        let body = gtk::Box::builder().spacing(12).margin_top(10).margin_bottom(10).margin_start(12).margin_end(12).build();
        body.append(&kind);
        body.append(&column);
        body.append(&actions);
        body
    }
}
