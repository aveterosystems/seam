// SPDX-License-Identifier: AGPL-3.0-only
//! The phone's notifications that are still up, newest first (board M05): each with its app's
//! icon, who or what, the text, the app and when; its phone buttons and Reply, which unfolds a
//! field in place; and dismiss, which takes the time's place on hover or keyboard focus.

use std::cell::RefCell;
use std::collections::HashMap;
use std::rc::Rc;

use adw::prelude::*;
use gtk::glib;
use seam_core::proto::v1::Notification;
use seam_core::proto::v1::notification_action::Action;

use crate::app::Controller;
use crate::model::EmptyAction;
use crate::ui::widgets::{arrive, leave, status_page};

struct Row {
    notification: Notification,
    revealer: gtk::Revealer,
    reply: gtk::Revealer,
    entry: gtk::Entry,
    time: gtk::Label,
}

pub struct Page {
    /// The banner over the list or its empty state.
    pub widget: gtk::Box,
    stack: gtk::Stack,
    banner: adw::Banner,
    list: gtk::ListBox,
    rows: RefCell<HashMap<String, Rc<Row>>>,
    order: RefCell<Vec<String>>,
    note: gtk::Label,
    /// Away: what you do here waits for the phone (never a dead end).
    empty: adw::StatusPage,
    empty_button: gtk::Button,
    empty_action: RefCell<Option<EmptyAction>>,
    controller: std::rc::Weak<Controller>,
}

impl Page {
    pub fn new(c: &Rc<Controller>) -> Rc<Self> {
        let list = gtk::ListBox::builder()
            .selection_mode(gtk::SelectionMode::None)
            .build();
        list.add_css_class("boxed-list");
        let note = gtk::Label::builder()
            .xalign(0.0)
            .wrap(true)
            .margin_bottom(12)
            .margin_start(6)
            .build();
        note.add_css_class("secondary");
        note.add_css_class("list-note");
        let column = gtk::Box::builder()
            .orientation(gtk::Orientation::Vertical)
            .build();
        // The note above the list, where libadwaita puts a group's description
        column.append(&note);
        column.append(&list);
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
        let (empty, empty_button) = status_page();
        let stack = gtk::Stack::builder()
            .transition_type(gtk::StackTransitionType::Crossfade)
            .transition_duration(crate::ui::motion::FADE_MS)
            .build();
        stack.add_named(&scroll, Some("list"));
        stack.add_named(&empty, Some("empty"));
        stack.set_vexpand(true);
        // Paused or away: one banner at the top, the same slot as the Clipboard page's
        let banner = adw::Banner::builder()
            .use_markup(false)
            .revealed(false)
            .build();
        let widget = gtk::Box::builder()
            .orientation(gtk::Orientation::Vertical)
            .build();
        widget.append(&banner);
        widget.append(&stack);
        let page = Rc::new(Self {
            widget,
            list,
            rows: RefCell::default(),
            order: RefCell::default(),
            note,
            stack,
            banner,
            empty,
            empty_button,
            empty_action: RefCell::default(),
            controller: Rc::downgrade(c),
        });
        let p = Rc::downgrade(&page);
        page.empty_button.connect_clicked(move |b| {
            let Some(p) = p.upgrade() else { return };
            let Some(c) = p.controller.upgrade() else {
                return;
            };
            match *p.empty_action.borrow() {
                Some(EmptyAction::TurnOnNotifications) => {
                    c.change_notifications(seam_core::proto::v1::NotificationSettingsChange {
                        enabled: Some(true),
                        ..Default::default()
                    })
                }
                Some(EmptyAction::ResumeNotifications) => {
                    c.change_notifications(seam_core::proto::v1::NotificationSettingsChange {
                        paused_until: Some(0),
                        ..Default::default()
                    })
                }
                Some(EmptyAction::ChooseApps) => {
                    let _ = b.activate_action("app.preferences-notifications", None);
                }
                None => {}
            }
        });
        let p = Rc::downgrade(&page);
        c.watch(move |c| {
            if let Some(p) = p.upgrade() {
                p.render(c)
            }
        });
        let p = Rc::downgrade(&page);
        glib::timeout_add_seconds_local(30, move || match p.upgrade() {
            Some(p) => {
                p.tick();
                glib::ControlFlow::Continue
            }
            None => glib::ControlFlow::Break,
        });
        page
    }

    fn render(self: &Rc<Self>, c: &Controller) {
        let m = c.model();
        let now = crate::ui::now_ms();
        match m.notifications_empty(now) {
            Some(e) => {
                self.empty.set_icon_name(Some(e.icon));
                self.empty.set_title(&e.title);
                self.empty.set_description(Some(&e.detail));
                self.empty_button.set_visible(e.action.is_some());
                // Choose Apps is a pointer, not something to fix: a plain button for it
                if e.action.is_some_and(|(_, a)| a == EmptyAction::ChooseApps) {
                    self.empty_button.remove_css_class("suggested-action");
                } else {
                    self.empty_button.add_css_class("suggested-action");
                }
                if let Some((label, a)) = e.action {
                    self.empty_button.set_label(label);
                    *self.empty_action.borrow_mut() = Some(a);
                }
                self.stack.set_visible_child_name("empty");
            }
            None => self.stack.set_visible_child_name("list"),
        }
        self.note.set_label(&m.clear_note());
        // Paused: nothing goes either way, so the rows' actions are off (the banner says why)
        let paused = m.link(now).kind == seam_looks::LinkKind::Paused;
        self.list.set_sensitive(!paused);
        match m.page_status(now, crate::model::Page::Notifications) {
            Some(words) => {
                self.banner.set_title(&words);
                self.banner.set_revealed(true);
            }
            None => self.banner.set_revealed(false),
        }
        // Only the surprising case needs saying: clearing here clears the phone too
        self.note
            .set_visible(m.settings.as_ref().is_none_or(|s| s.dismiss_both));
        let animate = crate::ui::animations() && !self.order.borrow().is_empty();
        let keys: Vec<String> = m.shown.iter().map(|n| n.key.clone()).collect();
        // Leaving: slide out and collapse, then go (catalogue 6)
        let gone: Vec<String> = self
            .order
            .borrow()
            .iter()
            .filter(|k| !keys.contains(k))
            .cloned()
            .collect();
        for key in gone {
            if let Some(row) = self.rows.borrow_mut().remove(&key) {
                let (list, r) = (self.list.clone(), row.revealer.clone());
                leave(&row.revealer, move || {
                    if let Some(parent) = r.parent() {
                        list.remove(&parent);
                    }
                });
            }
        }
        // Arriving: at the top, growing in (catalogue 5); kept rows update in place
        for (i, n) in m.shown.iter().enumerate() {
            let existing = self.rows.borrow().get(&n.key).cloned();
            match existing {
                Some(row) if row.notification == *n => {}
                Some(row) => {
                    // Same key, new words (a chat's next message): redraw that row in place
                    let index = row
                        .revealer
                        .parent()
                        .and_then(|p| p.downcast::<gtk::ListBoxRow>().ok())
                        .map(|r| r.index());
                    if let Some(parent) = row.revealer.parent() {
                        self.list.remove(&parent);
                    }
                    let fresh = self.row(c, n, false);
                    self.list.insert(&fresh.revealer, index.unwrap_or(i as i32));
                    self.rows.borrow_mut().insert(n.key.clone(), fresh);
                }
                None => {
                    let row = self.row(c, n, animate);
                    self.list.insert(&row.revealer, i as i32);
                    self.rows.borrow_mut().insert(n.key.clone(), row);
                }
            }
        }
        *self.order.borrow_mut() = keys;
    }

    fn tick(&self) {
        let now = crate::ui::now_ms();
        for row in self.rows.borrow().values() {
            row.time.set_label(&when(&row.notification, now));
        }
    }

    /// Opens a notification's reply field (from a banner's Reply).
    pub fn open_reply(&self, key: &str) {
        if let Some(row) = self.rows.borrow().get(key) {
            row.reply.set_reveal_child(true);
            let e = row.entry.clone();
            glib::timeout_add_local_once(std::time::Duration::from_millis(250), move || {
                e.grab_focus();
            });
        }
    }

    fn row(self: &Rc<Self>, c: &Controller, n: &Notification, animate: bool) -> Rc<Row> {
        let frame = crate::ui::widgets::app_icon(c.icon(&n.app_id), 32);

        let title = gtk::Label::builder()
            .label(if n.title.trim().is_empty() {
                &n.app_name
            } else {
                &n.title
            })
            .xalign(0.0)
            .ellipsize(gtk::pango::EllipsizeMode::End)
            .hexpand(true)
            .build();
        title.add_css_class("heading");
        // The app and when, on their own line under the words: the title never gives way to them
        let time = gtk::Label::builder()
            .label(when(n, crate::ui::now_ms()))
            .xalign(0.0)
            .ellipsize(gtk::pango::EllipsizeMode::End)
            .margin_top(2)
            .build();
        time.add_css_class("secondary");
        time.add_css_class("caption");
        let dismiss = gtk::Button::from_icon_name("window-close-symbolic");
        dismiss.add_css_class("flat");
        dismiss.add_css_class("circular");
        dismiss.set_tooltip_text(Some("Dismiss"));
        dismiss.update_property(&[gtk::accessible::Property::Label("Dismiss")]);
        dismiss.set_valign(gtk::Align::Center);
        // Dismiss sits in the row's top corner, always there (as Clipboard's actions are); it never moves
        // or spaces out anything else
        title.set_margin_end(32);
        let top = gtk::Box::builder().spacing(8).build();
        top.append(&title);
        dismiss.set_halign(gtk::Align::End);
        dismiss.set_valign(gtk::Align::Start);
        dismiss.set_margin_top(6);
        dismiss.set_margin_end(6);

        let column = gtk::Box::builder()
            .orientation(gtk::Orientation::Vertical)
            .spacing(2)
            .hexpand(true)
            .build();
        column.append(&top);
        if !n.text.is_empty() {
            let text = gtk::Label::builder()
                .label(&n.text)
                .xalign(0.0)
                .wrap(true)
                .wrap_mode(gtk::pango::WrapMode::WordChar)
                .lines(4)
                .ellipsize(gtk::pango::EllipsizeMode::End)
                .build();
            // The message is the content: normal text; the app and time under it are the quiet part.
            // It keeps clear of the dismiss button, as the title does
            text.set_margin_end(32);
            column.append(&text);
        }
        column.append(&time);
        let entry = gtk::Entry::builder()
            .placeholder_text(format!(
                "Reply to {}",
                if n.title.is_empty() {
                    &n.app_name
                } else {
                    &n.title
                }
            ))
            .hexpand(true)
            .build();
        let send = gtk::Button::with_label("Send");
        send.add_css_class("suggested-action");
        let field = gtk::Box::builder().spacing(6).margin_top(8).build();
        field.append(&entry);
        field.append(&send);
        let reply = gtk::Revealer::builder()
            .transition_type(gtk::RevealerTransitionType::SlideDown)
            .transition_duration(300)
            .child(&field)
            .build();

        if n.can_reply || !n.buttons.is_empty() {
            // Always there: hidden actions would leave a gap, and the keyboard and screen readers need them
            let actions = gtk::Box::builder().spacing(6).margin_top(6).build();
            if n.can_reply {
                let b = gtk::Button::with_label("Reply");
                let (r, e, actions2) = (reply.clone(), entry.clone(), actions.clone());
                // The button says what it does now, whichever way the field closed (Escape, Send)
                let bb = b.clone();
                reply.connect_reveal_child_notify(move |r| {
                    bb.set_label(if r.reveals_child() { "Cancel" } else { "Reply" })
                });
                b.connect_clicked(move |_| {
                    let open = !r.reveals_child();
                    r.set_reveal_child(open);
                    if open {
                        actions2.add_css_class("shown")
                    } else {
                        actions2.remove_css_class("shown")
                    }
                    let e = e.clone();
                    if open {
                        glib::timeout_add_local_once(
                            std::time::Duration::from_millis(250),
                            move || {
                                e.grab_focus();
                            },
                        );
                    }
                });
                actions.append(&b);
            }
            for button in &n.buttons {
                let b = gtk::Button::with_label(&button.title);
                let (weak, key, id) = (self.controller.clone(), n.key.clone(), button.id.clone());
                b.connect_clicked(move |_| {
                    if let Some(c) = weak.upgrade() {
                        c.act(key.clone(), Action::ButtonId(id.clone()));
                    }
                });
                actions.append(&b);
            }
            column.append(&actions);
        }
        column.append(&reply);

        let body = gtk::Box::builder()
            .spacing(12)
            .margin_top(10)
            .margin_bottom(10)
            .margin_start(12)
            .margin_end(12)
            .build();
        body.append(&frame);
        body.append(&column);
        let corner = gtk::Overlay::builder().child(&body).build();
        corner.add_overlay(&dismiss);
        let revealer = arrive(&corner, animate);
        let row = Rc::new(Row {
            notification: n.clone(),
            revealer,
            reply: reply.clone(),
            entry: entry.clone(),
            time,
        });

        let (weak, key) = (self.controller.clone(), n.key.clone());
        dismiss.connect_clicked(move |_| {
            if let Some(c) = weak.upgrade() {
                c.dismiss(&key);
            }
        });
        // Return sends, Escape closes; "✓ Sent" shows on the button, then the field folds away
        let (weak, key, r, e) = (
            self.controller.clone(),
            n.key.clone(),
            reply.clone(),
            entry.clone(),
        );
        let send_now = Rc::new(move |b: &gtk::Button| {
            let text = e.text().trim().to_string();
            if text.is_empty() {
                return;
            }
            if let Some(c) = weak.upgrade() {
                c.act(key.clone(), Action::ReplyText(text));
                c.toast(&format!("Reply sent to {}", c.model().phone_name(false)));
            }
            e.set_text("");
            r.set_reveal_child(false);
            let _ = b;
        });
        let s = send_now.clone();
        send.connect_clicked(move |b| s(b));
        let (s, b) = (send_now, send.clone());
        entry.connect_activate(move |_| s(&b));
        let esc = gtk::EventControllerKey::new();
        let r = reply.clone();
        esc.connect_key_pressed(move |_, key, _, _| {
            if key == gtk::gdk::Key::Escape && r.reveals_child() {
                r.set_reveal_child(false);
                glib::Propagation::Stop
            } else {
                glib::Propagation::Proceed
            }
        });
        entry.add_controller(esc);
        row
    }
}

/// "Chat · 12 min ago": the app, and when.
fn when(n: &Notification, now: i64) -> String {
    if n.posted_at > 0 {
        format!(
            "{} · {}",
            n.app_name,
            seam_looks::words::ago(n.posted_at, now, &crate::model::SystemTime, "now")
        )
    } else {
        n.app_name.clone()
    }
}
