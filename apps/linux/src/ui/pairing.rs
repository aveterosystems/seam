// SPDX-License-Identifier: AGPL-3.0-only
//! Pairing (board M02), inside the main window while no phone is paired: what to do and why it's
//! safe on the left; the code to scan, then the code to compare, on the right.

use std::cell::{Cell, RefCell};
use std::rc::Rc;

use adw::prelude::*;
use gtk::{gdk, glib};
use seam_computer::pairing::PairingState;

use crate::app::Controller;

pub struct Page {
    pub widget: gtk::ScrolledWindow,
    /// The two columns; the window stacks them on a narrow screen.
    pub row: gtk::Box,
    right: gtk::Stack,
    qr: gtk::Picture,
    countdown: gtk::Label,
    tor_note: gtk::Label,
    outcome_text: gtk::Label,
    outcome_button: gtk::Button,
    confirm_title: gtk::Label,
    code: gtk::Label,
    pair: gtk::Button,
    cancel: gtk::Button,
    sending: adw::Spinner,
    shown: RefCell<Option<PairingState>>,
    expires_at: Cell<i64>,
}

impl Page {
    pub fn new(c: &Rc<Controller>) -> Rc<Self> {
        // Left: the icon, what to do, and why it's safe
        let icon = gtk::Image::from_icon_name("com.avetero.Seam");
        icon.set_pixel_size(64);
        icon.set_halign(gtk::Align::Start);
        icon.set_accessible_role(gtk::AccessibleRole::Presentation);
        let title = gtk::Label::builder()
            .label("Pair Your Phone")
            .xalign(0.0)
            .build();
        title.add_css_class("title-1");
        let lead = gtk::Label::builder()
            .label("Open Seam on your Android phone and scan this code. That's the whole setup.")
            .xalign(0.0)
            .wrap(true)
            .build();
        lead.add_css_class("secondary");
        let steps = gtk::Box::builder()
            .orientation(gtk::Orientation::Vertical)
            .spacing(14)
            .margin_top(8)
            .build();
        steps.append(&step(1, "Get <b>Seam</b> on your phone from <a href=\"https://github.com/aveterosystems/seam/releases/latest\">GitHub</a>"));
        steps.append(&step(2, "Tap <b>Scan code</b> and point the camera here"));
        steps.append(&step(3, "Check both screens match, then pair"));
        let safe = gtk::Box::builder().spacing(8).margin_top(24).build();
        let lock = gtk::Image::from_icon_name("security-high-symbolic");
        lock.add_css_class("dim-label");
        safe.append(&lock);
        let safe_text = gtk::Label::builder()
            .label("Keys are made on this computer and never leave it. No account, no password.")
            .xalign(0.0)
            .wrap(true)
            .build();
        safe_text.add_css_class("secondary");
        safe.append(&safe_text);
        let left = gtk::Box::builder()
            .orientation(gtk::Orientation::Vertical)
            .spacing(12)
            .hexpand(true)
            .valign(gtk::Align::Center)
            .build();
        left.append(&icon);
        left.append(&title);
        left.append(&lead);
        left.append(&steps);
        left.append(&safe);

        // Right: the code, and that Seam is waiting for the phone
        let qr = gtk::Picture::builder()
            .width_request(200)
            .height_request(200)
            .can_shrink(false)
            .build();
        qr.update_property(&[gtk::accessible::Property::Label("Pairing QR code")]);
        let frame = gtk::Box::builder().halign(gtk::Align::Center).build();
        frame.add_css_class("qr-frame");
        frame.append(&qr);

        let countdown = gtk::Label::new(None);
        countdown.add_css_class("secondary");
        countdown.add_css_class("caption");
        countdown.add_css_class("numeric");
        let tor_note = gtk::Label::builder()
            .wrap(true)
            .justify(gtk::Justification::Center)
            .build();
        tor_note.add_css_class("secondary");
        tor_note.add_css_class("caption");
        let waiting = gtk::Box::builder()
            .orientation(gtk::Orientation::Vertical)
            .spacing(10)
            .valign(gtk::Align::Center)
            .build();
        waiting.append(&frame);
        // Waiting, never wrong: a small spinner beside the words, never an amber warning
        let spinner = adw::Spinner::new();
        let wait_line = gtk::Box::builder()
            .spacing(8)
            .halign(gtk::Align::Center)
            .build();
        wait_line.append(&spinner);
        wait_line.append(&countdown);
        waiting.append(&wait_line);
        waiting.append(&tor_note);

        // The safety code check: Cancel is the default, and Pair takes a click only once the
        // screen has been up a moment, so a keypress meant for something else can't pair (SR-2-23)
        let confirm_title = gtk::Label::builder()
            .wrap(true)
            .justify(gtk::Justification::Center)
            .build();
        confirm_title.add_css_class("title-3");
        let code = gtk::Label::new(None);
        code.add_css_class("safety-code");
        let hint = gtk::Label::new(Some("Check your phone shows the same code."));
        hint.add_css_class("secondary");
        let cancel = gtk::Button::with_label("Cancel");
        // Cancel is the default (Return cancels, SR-10-02), styled plainly like Pair: neither
        // button looks like the obvious yes
        cancel.add_css_class("pill");
        let pair = gtk::Button::with_label("Pair");
        pair.add_css_class("pill");
        let sending = adw::Spinner::new();
        sending.set_visible(false);
        let buttons = gtk::Box::builder()
            .spacing(10)
            .halign(gtk::Align::Center)
            .margin_top(6)
            .build();
        buttons.append(&cancel);
        buttons.append(&pair);
        buttons.append(&sending);
        let confirm = gtk::Box::builder()
            .orientation(gtk::Orientation::Vertical)
            .spacing(10)
            .valign(gtk::Align::Center)
            .build();
        confirm.append(&confirm_title);
        confirm.append(&code);
        confirm.append(&hint);
        confirm.append(&buttons);

        let outcome_text = gtk::Label::builder()
            .wrap(true)
            .justify(gtk::Justification::Center)
            .build();
        outcome_text.add_css_class("secondary");
        let outcome_button = gtk::Button::new();
        outcome_button.add_css_class("pill");
        outcome_button.add_css_class("suggested-action");
        outcome_button.set_halign(gtk::Align::Center);
        let outcome = gtk::Box::builder()
            .orientation(gtk::Orientation::Vertical)
            .spacing(14)
            .valign(gtk::Align::Center)
            .build();
        outcome.append(&outcome_text);
        outcome.append(&outcome_button);

        let right = gtk::Stack::builder()
            .transition_type(gtk::StackTransitionType::Crossfade)
            .transition_duration(crate::ui::motion::FADE_MS)
            .width_request(280)
            .vhomogeneous(false)
            .build();
        right.add_named(&waiting, Some("waiting"));
        right.add_named(&confirm, Some("confirm"));
        right.add_named(&outcome, Some("outcome"));

        let row = gtk::Box::builder().spacing(48).build();
        row.append(&left);
        row.append(&right);
        let clamp = adw::Clamp::builder()
            .maximum_size(820)
            .child(&row)
            .margin_top(32)
            .margin_bottom(32)
            .margin_start(32)
            .margin_end(32)
            .valign(gtk::Align::Center)
            .build();
        let widget = gtk::ScrolledWindow::builder()
            .hscrollbar_policy(gtk::PolicyType::Never)
            .child(&clamp)
            .vexpand(true)
            .build();

        let page = Rc::new(Self {
            widget,
            row: row.clone(),
            right,
            qr,
            countdown,
            tor_note,
            outcome_text,
            outcome_button,
            confirm_title,
            code,
            pair,
            cancel,
            sending,
            shown: RefCell::default(),
            expires_at: Cell::new(0),
        });
        let weak = Rc::downgrade(c);
        page.pair.connect_clicked(move |_| {
            if let Some(c) = weak.upgrade() {
                c.confirm_pairing()
            }
        });
        let weak = Rc::downgrade(c);
        page.cancel.connect_clicked(move |_| {
            if let Some(c) = weak.upgrade() {
                // Cancel ends this attempt; a fresh code follows at once, as the window stays open
                c.cancel_pairing();
                c.start_pairing();
            }
        });
        let weak = Rc::downgrade(c);
        page.outcome_button.connect_clicked(move |_| {
            if let Some(c) = weak.upgrade() {
                c.start_pairing()
            }
        });
        let p = Rc::downgrade(&page);
        c.watch(move |c| {
            if let Some(p) = p.upgrade() {
                p.render(c)
            }
        });
        let p = Rc::downgrade(&page);
        glib::timeout_add_seconds_local(1, move || match p.upgrade() {
            Some(p) => {
                p.count_down();
                glib::ControlFlow::Continue
            }
            None => glib::ControlFlow::Break,
        });
        page
    }

    fn render(&self, c: &Controller) {
        let m = c.model();
        if m.startup_error.is_some() {
            // Nothing can pair until Seam starts: say so, with no button that can't work
            *self.shown.borrow_mut() = None;
            return self.outcome(
                "Seam can show a code once it starts. The message above says what to do.",
                "",
            );
        }
        if self.shown.borrow().as_ref() == Some(&m.pairing) {
            return;
        }
        *self.shown.borrow_mut() = Some(m.pairing.clone());
        // While Tor isn't ready the phone can still pair on the same Wi‑Fi: say why Tor waits
        let tor_note = (m.tor && m.tor_state != seam_computer::tor::TorState::Ready).then(|| {
            let mut input = seam_looks::LinkInput::new();
            input.lan_allowed = false;
            input.tor = crate::model::tor_look(m.tor_state);
            seam_looks::link::look(
                &input,
                &crate::model::names(),
                crate::ui::now_ms(),
                &crate::model::SystemTime,
            )
            .label
        });
        self.tor_note.set_visible(tor_note.is_some());
        self.tor_note
            .set_label(tor_note.as_deref().unwrap_or_default());
        match &m.pairing {
            PairingState::Idle => {
                self.outcome("Show a code to scan with Seam on your phone.", "Show Code")
            }
            PairingState::Waiting { qr, expires_at } => {
                self.qr.set_paintable(qr_texture(qr).as_ref());
                self.expires_at.set(*expires_at);
                self.count_down();
                self.right.set_visible_child_name("waiting");
            }
            PairingState::Confirm(r) | PairingState::Confirming(r) => {
                let sending = matches!(m.pairing, PairingState::Confirming(_));
                self.confirm_title
                    .set_label(&format!("Pair With {}?", r.device_name));
                self.code.set_label(&r.safety_code);
                self.code
                    .update_property(&[gtk::accessible::Property::Label(&format!(
                        "Code {}",
                        r.safety_code
                    ))]);
                self.cancel.set_sensitive(!sending);
                self.sending.set_visible(sending);
                if self.right.visible_child_name().as_deref() != Some("confirm") {
                    self.pair.set_sensitive(false);
                    let pair = self.pair.clone();
                    glib::timeout_add_local_once(
                        std::time::Duration::from_millis(1500),
                        move || pair.set_sensitive(true),
                    );
                    // Cancel is the default: Return cancels
                    if let Some(w) = self.cancel.root().and_downcast::<gtk::Window>() {
                        w.set_default_widget(Some(&self.cancel));
                        self.cancel.grab_focus();
                    }
                    if let Some(w) = self.widget.root().and_downcast::<gtk::Window>() {
                        w.present(); // the safety code needs the user's eyes now
                    }
                } else if sending {
                    self.pair.set_sensitive(false);
                }
                self.right.set_visible_child_name("confirm");
            }
            PairingState::Paired { name } => self.outcome(&format!("Paired with {name}."), ""),
            PairingState::Expired => self.outcome(
                "Codes work for 5 minutes. Show a new one to pair.",
                "Show New Code",
            ),
            PairingState::Failed(why) => {
                self.outcome(crate::model::pairing_failure_words(*why), "Show New Code")
            }
        }
    }

    fn outcome(&self, text: &str, button: &str) {
        self.outcome_text.set_label(text);
        self.outcome_button.set_visible(!button.is_empty());
        self.outcome_button.set_label(button);
        self.right.set_visible_child_name("outcome");
    }

    fn count_down(&self) {
        let left = ((self.expires_at.get() - crate::ui::now_ms()) / 1000).max(0);
        self.countdown.set_label(&format!(
            "Waiting for your phone · code expires in {}:{:02}",
            left / 60,
            left % 60
        ));
    }
}

fn step(n: u32, markup: &str) -> gtk::Box {
    let number = gtk::Label::new(Some(&n.to_string()));
    number.add_css_class("step-number");
    number.set_valign(gtk::Align::Start);
    let text = gtk::Label::builder()
        .use_markup(true)
        .label(markup)
        .xalign(0.0)
        .wrap(true)
        .max_width_chars(40)
        .hexpand(true)
        .build();
    let b = gtk::Box::builder().spacing(12).build();
    b.append(&number);
    b.append(&text);
    b
}

/// The QR code as crisp pixels, 4 px a module with a quiet zone, black on white.
pub fn qr_texture(data: &str) -> Option<gdk::MemoryTexture> {
    let code =
        qrcode::QrCode::with_error_correction_level(data.as_bytes(), qrcode::EcLevel::M).ok()?;
    let width = code.width();
    let quiet = 2;
    let scale = 4;
    let size = (width + quiet * 2) * scale;
    let mut pixels = vec![255u8; size * size];
    for y in 0..width {
        for x in 0..width {
            if code[(x, y)] == qrcode::Color::Dark {
                for dy in 0..scale {
                    let row = ((y + quiet) * scale + dy) * size;
                    for dx in 0..scale {
                        pixels[row + (x + quiet) * scale + dx] = 0;
                    }
                }
            }
        }
    }
    Some(gdk::MemoryTexture::new(
        size as i32,
        size as i32,
        gdk::MemoryFormat::G8,
        &glib::Bytes::from_owned(pixels),
        size,
    ))
}
