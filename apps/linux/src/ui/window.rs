// SPDX-License-Identifier: AGPL-3.0-only
//! The main window, Seam's home on both desktops (decision from "What shape does Seam take on
//! Linux?"): a sidebar with the phone's card and the pages (Notifications with its count,
//! Clipboard), and the page itself. With no phone paired, the whole window is pairing. One banner
//! slot at the top says what needs the user (a startup problem, a warning). Closing it keeps Seam
//! running in the background.

use std::rc::Rc;

use adw::prelude::*;

use crate::app::Controller;
use crate::ui::{clipboard, notifications, pairing, phone};

pub struct Window {
    pub window: adw::ApplicationWindow,
    split: adw::NavigationSplitView,
    content: adw::NavigationPage,
    pages: gtk::Stack,
    nav: gtk::ListBox,
    clear: gtk::Button,
    notifications: Rc<notifications::Page>,
    // Held here: each page listens through a weak link, so it lives as long as the window
    _clipboard: Rc<clipboard::Page>,
    /// Send Clipboard, on the Clipboard page while copies need one tap.
    send: gtk::Button,
    controller: std::rc::Weak<Controller>,
    _pairing: Rc<pairing::Page>,
}

impl Window {
    pub fn new(c: &Rc<Controller>) -> Rc<Self> {
        let window = adw::ApplicationWindow::builder()
            .application(&c.app)
            .title("Seam")
            .default_width(940)
            .default_height(640)
            .width_request(360)
            .height_request(420)
            .hide_on_close(true)
            .build();

        // Sidebar: the phone, then the pages
        let nav = gtk::ListBox::builder()
            .selection_mode(gtk::SelectionMode::Single)
            .build();
        nav.add_css_class("navigation-sidebar");
        let count = gtk::Label::new(None);
        count.add_css_class("sidebar-count");
        nav.append(&nav_row(
            "preferences-system-notifications-symbolic",
            "Notifications",
            Some(&count),
        ));
        nav.append(&nav_row("edit-paste-symbolic", "Clipboard", None));
        let card = phone::card(c);
        card.set_margin_start(8);
        card.set_margin_end(8);
        card.set_margin_top(6);
        card.set_margin_bottom(8);
        let side_box = gtk::Box::builder()
            .orientation(gtk::Orientation::Vertical)
            .build();
        side_box.append(&card);
        side_box.append(&nav);
        let menu = primary_menu();
        let side_header = adw::HeaderBar::new();
        side_header.pack_end(&menu);
        let side_view = adw::ToolbarView::new();
        side_view.add_top_bar(&side_header);
        side_view.set_content(Some(
            &gtk::ScrolledWindow::builder()
                .hscrollbar_policy(gtk::PolicyType::Never)
                .child(&side_box)
                .build(),
        ));
        let sidebar = adw::NavigationPage::builder()
            .title("Seam")
            .child(&side_view)
            .build();

        // The page
        let notifications = notifications::Page::new(c);
        let clipboard = clipboard::Page::new(c);
        // Pages switch at once, as GNOME's own sidebar apps do
        let pages = gtk::Stack::new();
        pages.add_named(&notifications.widget, Some("notifications"));
        pages.add_named(&clipboard.widget, Some("clipboard"));
        // Flat header buttons, as libadwaita's are; Preferences lives in the main menu
        let clear = gtk::Button::from_icon_name("edit-clear-all-symbolic");
        let send = gtk::Button::with_label("Send Clipboard");
        send.add_css_class("suggested-action");
        send.set_tooltip_text(Some(
            "Send what's on this computer's clipboard to your phone",
        ));
        let header = adw::HeaderBar::new();
        header.pack_start(&send);
        header.pack_end(&clear);
        // One banner slot, under the header: a startup problem (with Reset Seam), then a warning
        let banner = adw::Banner::builder().revealed(false).build();
        let content_view = adw::ToolbarView::new();
        content_view.add_top_bar(&header);
        content_view.add_top_bar(&banner);
        content_view.set_content(Some(&pages));
        let content = adw::NavigationPage::builder()
            .title("Notifications")
            .child(&content_view)
            .build();
        let split = adw::NavigationSplitView::builder()
            .sidebar(&sidebar)
            .content(&content)
            .min_sidebar_width(240.0)
            .max_sidebar_width(300.0)
            .build();

        // No phone: pairing fills the window
        let pairing = pairing::Page::new(c);
        let pairing_view = adw::ToolbarView::new();
        let pairing_header = adw::HeaderBar::builder().show_title(false).build();
        pairing_header.pack_end(&primary_menu());
        pairing_view.add_top_bar(&pairing_header);
        let pairing_banner = adw::Banner::builder().revealed(false).build();
        pairing_view.add_top_bar(&pairing_banner);
        pairing_view.set_content(Some(&pairing.widget));

        // Each view at its own size: the two-column pairing page mustn't widen the home view
        let root = gtk::Stack::builder()
            .hhomogeneous(false)
            .vhomogeneous(false)
            .transition_type(gtk::StackTransitionType::Crossfade)
            .transition_duration(crate::ui::motion::FADE_MS)
            .build();
        root.add_named(&split, Some("home"));
        root.add_named(&pairing_view, Some("pairing"));
        // Starting: a spinner, and after a moment the likely reason it's slow (the keyring asking)
        let starting_page = adw::StatusPage::builder()
            .paintable(&adw::SpinnerPaintable::new(None::<&gtk::Widget>))
            .title("Starting Seam")
            .build();
        starting_page.add_css_class("compact");
        let sp = starting_page.clone();
        if let Some(p) = sp.paintable().and_downcast::<adw::SpinnerPaintable>() {
            p.set_widget(Some(&sp));
        }
        gtk::glib::timeout_add_seconds_local_once(4, move || {
            sp.set_description(Some(
                "Unlock your keyring if your desktop asks. Seam keeps its keys there.",
            ));
        });
        let starting = adw::ToolbarView::new();
        let starting_header = adw::HeaderBar::builder().show_title(false).build();
        starting_header.pack_end(&primary_menu());
        starting.add_top_bar(&starting_header);
        starting.set_content(Some(&starting_page));
        root.add_named(&starting, Some("starting"));

        // Seam couldn't start, with no phone known: say so in the whole window, with the one
        // thing to do about it (Reset Seam only when the saved data can't be read)
        let problem_page = adw::StatusPage::builder()
            .icon_name("dialog-warning-symbolic")
            .vexpand(true)
            .build();
        let reset = gtk::Button::builder()
            .label("Reset Seam")
            .halign(gtk::Align::Center)
            .build();
        reset.add_css_class("pill");
        reset.add_css_class("destructive-action");
        problem_page.set_child(Some(&reset));
        let problem = adw::ToolbarView::new();
        let problem_header = adw::HeaderBar::builder().show_title(false).build();
        problem_header.pack_end(&primary_menu());
        problem.add_top_bar(&problem_header);
        problem.set_content(Some(&problem_page));
        root.add_named(&problem, Some("problem"));
        let weak_c = Rc::downgrade(c);
        reset.connect_clicked(move |b| {
            if let Some(c) = weak_c.upgrade() {
                confirm_reset(b, &c);
            }
        });

        let toasts = adw::ToastOverlay::new();
        toasts.set_child(Some(&root));
        let t = toasts.clone();
        c.set_toaster(move |text| {
            let toast = adw::Toast::new(text);
            toast.set_timeout(2);
            t.add_toast(toast);
        });
        window.set_content(Some(&toasts));

        // libadwaita applies one breakpoint at a time, the last that matches: so the narrowest
        // carries everything the wider one sets too
        let pairing_narrow = |b: &adw::Breakpoint| {
            b.add_setter(
                &pairing.row,
                "orientation",
                Some(&gtk::Orientation::Vertical.to_value()),
            );
            b.add_setter(&pairing.row, "spacing", Some(&32.to_value()));
        };
        // Pairing: steps above the code
        let medium = adw::Breakpoint::new(adw::BreakpointCondition::new_length(
            adw::BreakpointConditionLengthType::MaxWidth,
            700.0,
            adw::LengthUnit::Sp,
        ));
        pairing_narrow(&medium);
        window.add_breakpoint(medium);
        // Phone width: one column
        let narrow = adw::Breakpoint::new(adw::BreakpointCondition::new_length(
            adw::BreakpointConditionLengthType::MaxWidth,
            600.0,
            adw::LengthUnit::Sp,
        ));
        narrow.add_setter(&split, "collapsed", Some(&true.to_value()));
        pairing_narrow(&narrow);
        window.add_breakpoint(narrow);

        // Debug screenshots: SEAM_SIZE=360x700 opens the window at that size (phone width and the like)
        #[cfg(debug_assertions)]
        if let Some((w, h)) = std::env::var("SEAM_SIZE").ok().and_then(|v| {
            let (w, h) = v.split_once('x')?;
            Some((w.parse().ok()?, h.parse().ok()?))
        }) {
            window.set_default_size(w, h);
        }
        let w = Rc::new(Self {
            window,
            split,
            content,
            pages,
            nav,
            clear,
            notifications,
            _clipboard: clipboard,
            send: send.clone(),
            controller: Rc::downgrade(c),
            _pairing: pairing,
        });

        let weak_w = Rc::downgrade(&w);
        w.nav.connect_row_selected(move |_, row| {
            let (Some(w), Some(row)) = (weak_w.upgrade(), row) else {
                return;
            };
            w.show_page(if row.index() == 1 {
                "clipboard"
            } else {
                "notifications"
            });
        });
        w.nav.select_row(w.nav.row_at_index(0).as_ref());

        let (weak_w, weak_c) = (Rc::downgrade(&w), Rc::downgrade(c));
        w.clear.connect_clicked(move |_| {
            let (Some(w), Some(c)) = (weak_w.upgrade(), weak_c.upgrade()) else {
                return;
            };
            if w.pages.visible_child_name().as_deref() == Some("clipboard") {
                c.clear_clips();
                c.toast("History cleared");
            } else {
                w.confirm_clear(&c);
            }
        });
        let weak_c = Rc::downgrade(c);
        send.connect_clicked(move |_| {
            let Some(c) = weak_c.upgrade() else { return };
            let name = c.model().phone_name(false);
            let w = Rc::downgrade(&c);
            c.send_clipboard(move |ok| {
                if let Some(c) = w.upgrade() {
                    // A password or a copied file never goes, and it says so without saying what
                    c.toast(&if ok {
                        format!("Sent to {name}")
                    } else {
                        "Nothing to send: copy some text first".into()
                    });
                }
            });
        });
        for b in [&banner, &pairing_banner] {
            let weak_c = Rc::downgrade(c);
            b.connect_button_clicked(move |b| {
                let Some(c) = weak_c.upgrade() else { return };
                if c.model().needs_reset() {
                    confirm_reset(b, &c);
                }
            });
        }

        let weak_w = Rc::downgrade(&w);
        // "Paired with Pixel 9" shows for 2 s, then the window becomes home (board M02)
        let hold = Rc::new(std::cell::Cell::new(false));
        let was_paired = std::cell::Cell::new(false);
        let weak_c = Rc::downgrade(c);
        c.watch(move |c| {
            let Some(w) = weak_w.upgrade() else { return };
            let m = c.model();
            let started = m.health != seam_computer::computer::Health::Starting || c.is_demo();
            let paired = m.phone.is_some();
            let just_paired = matches!(
                m.pairing,
                seam_computer::pairing::PairingState::Paired { .. }
            );
            if just_paired && !was_paired.replace(true) {
                hold.set(true);
                let (hold, weak_c) = (hold.clone(), weak_c.clone());
                gtk::glib::timeout_add_local_once(std::time::Duration::from_secs(2), move || {
                    hold.set(false);
                    if let Some(c) = weak_c.upgrade() {
                        c.refresh();
                    }
                });
            } else if !just_paired {
                was_paired.set(false);
            }
            let view = match (started, paired && !hold.get()) {
                _ if m.startup_error.is_some() && !paired => "problem",
                (false, _) => "starting",
                (_, true) => "home",
                _ => "pairing",
            };
            root.set_visible_child_name(view);
            if view == "problem" {
                problem_page.set_title(if m.needs_reset() {
                    "Can't Read Saved Data"
                } else {
                    "Seam Couldn't Start"
                });
                // The title says it couldn't start; the words say why and what to do
                let words = m.startup_error.as_deref().unwrap_or_default();
                let words = words.strip_prefix("Seam couldn't start: ").unwrap_or(words);
                let mut chars = words.chars();
                let words: String = chars
                    .next()
                    .map(|c| c.to_uppercase().chain(chars).collect())
                    .unwrap_or_default();
                problem_page.set_description(Some(&words));
                reset.set_visible(m.needs_reset());
            }
            // Unpaired with nothing showing yet: a code, straight away
            if started
                && !paired
                && m.health == seam_computer::computer::Health::Ready
                && matches!(m.pairing, seam_computer::pairing::PairingState::Idle)
                && !c.is_demo()
            {
                drop(m);
                c.start_pairing();
                return;
            }
            let shown = m.shown.len();
            // Off says so in the sidebar too, in place of the count
            let off = m.settings.as_ref().is_some_and(|s| !s.enabled);
            count.set_label(&if off {
                "Off".to_string()
            } else {
                shown.to_string()
            });
            count.set_visible(off || shown > 0);
            count.update_property(&[gtk::accessible::Property::Label(&format!(
                "{shown} notifications"
            ))]);
            for banner in [&banner, &pairing_banner] {
                match (&m.startup_error, &m.warning) {
                    (Some(e), _) if paired => {
                        banner.set_title(e);
                        banner.set_button_label(if m.needs_reset() {
                            Some("Reset Seam")
                        } else {
                            None
                        });
                        banner.set_revealed(true);
                    }
                    (None, Some(warning)) => {
                        banner.set_title(warning);
                        banner.set_button_label(None);
                        banner.set_revealed(true);
                    }
                    _ => banner.set_revealed(false),
                }
            }
            drop(m);
            w.update_header(c);
        });
        w
    }

    fn update_header(&self, c: &Controller) {
        let m = c.model();
        let clipboard = self.pages.visible_child_name().as_deref() == Some("clipboard");
        // Nothing to clear: no button, rather than a greyed one
        let (what, visible) = if clipboard {
            ("Clear History", m.clipboard_sync && !m.clips.is_empty())
        } else {
            ("Clear All", !m.shown.is_empty())
        };
        self.clear.set_visible(visible);
        self.clear.set_tooltip_text(Some(what));
        self.clear
            .update_property(&[gtk::accessible::Property::Label(what)]);
        self.send.set_visible(
            clipboard
                && m.clipboard_sync
                && m.phone.is_some()
                && c.pasteboard.reach() == crate::os::clipboard::Reach::OneTap,
        );
    }

    pub fn show_page(&self, name: &str) {
        self.pages.set_visible_child_name(name);
        if let Some(c) = self.controller.upgrade() {
            self.update_header(&c);
        }
        self.content.set_title(if name == "clipboard" {
            "Clipboard"
        } else {
            "Notifications"
        });
        let index = if name == "clipboard" { 1 } else { 0 };
        if self.nav.selected_row().map(|r| r.index()) != Some(index) {
            self.nav.select_row(self.nav.row_at_index(index).as_ref());
        }
        self.split.set_show_content(true);
    }

    /// From a banner's Reply: the notification's field, open and focused.
    pub fn reply_to(&self, key: &str) {
        self.show_page("notifications");
        self.window.present();
        self.notifications.open_reply(key);
    }

    /// Clearing here can clear the phone too, so it asks first and says which.
    fn confirm_clear(&self, c: &Rc<Controller>) {
        let title = c.model().clear_all_title();
        let dialog = adw::AlertDialog::new(Some(&title), None);
        dialog.add_responses(&[("cancel", "Cancel"), ("clear", "Clear All")]);
        dialog.set_response_appearance("clear", adw::ResponseAppearance::Destructive);
        dialog.set_default_response(Some("cancel"));
        dialog.set_close_response("cancel");
        let weak = Rc::downgrade(c);
        dialog.connect_response(Some("clear"), move |_, _| {
            let Some(c) = weak.upgrade() else { return };
            let keys: Vec<String> = c.model().shown.iter().map(|n| n.key.clone()).collect();
            for key in keys {
                c.dismiss(&key);
            }
        });
        dialog.present(Some(&self.window));
    }
}

fn nav_row(icon: &str, title: &str, suffix: Option<&gtk::Label>) -> gtk::ListBoxRow {
    let b = gtk::Box::builder().spacing(12).build();
    b.append(&gtk::Image::from_icon_name(icon));
    let l = gtk::Label::builder()
        .label(title)
        .xalign(0.0)
        .hexpand(true)
        .build();
    b.append(&l);
    if let Some(s) = suffix {
        b.append(s);
    }
    gtk::ListBoxRow::builder().child(&b).build()
}

/// The window's menu, as every GNOME app has one: Preferences, About, Quit.
fn primary_menu() -> gtk::MenuButton {
    let menu = gtk::gio::Menu::new();
    let section = gtk::gio::Menu::new();
    section.append(Some("_Preferences"), Some("app.preferences"));
    section.append(Some("_About Seam"), Some("app.about"));
    menu.append_section(None, &section);
    let quit = gtk::gio::Menu::new();
    quit.append(Some("_Quit"), Some("app.quit"));
    menu.append_section(None, &quit);
    let b = gtk::MenuButton::builder()
        .icon_name("open-menu-symbolic")
        .menu_model(&menu)
        .primary(true)
        .build();
    b.set_tooltip_text(Some("Main menu"));
    b
}

/// Only after the user agreed: a new identity, the old files kept aside.
fn confirm_reset(anchor: &impl IsA<gtk::Widget>, c: &Rc<Controller>) {
    let dialog = adw::AlertDialog::new(
        Some("Reset Seam?"),
        Some(
            "Seam starts over with a new identity and you pair your phone again. The old data is kept aside, not deleted.",
        ),
    );
    dialog.add_responses(&[("cancel", "Cancel"), ("reset", "Reset Seam")]);
    dialog.set_response_appearance("reset", adw::ResponseAppearance::Destructive);
    dialog.set_default_response(Some("cancel"));
    dialog.set_close_response("cancel");
    let weak = Rc::downgrade(c);
    dialog.connect_response(Some("reset"), move |_, _| {
        if let Some(c) = weak.upgrade() {
            c.reset()
        }
    });
    dialog.present(anchor.root().and_downcast_ref::<gtk::Window>());
}
