// SPDX-License-Identifier: AGPL-3.0-only
//! Preferences (board M03), as a libadwaita preferences dialog: General, the phone by name (with
//! the Mac's Privacy facts), Notifications and Clipboard. Every switch takes effect at once.

use std::cell::RefCell;
use std::rc::Rc;

use adw::prelude::*;
use seam_core::proto::v1::{
    NotificationApp, NotificationMode, NotificationSettings, NotificationSettingsChange,
};

use crate::app::Controller;
use crate::ui::phone::{PhoneSummary, Size};

pub fn present(c: &Rc<Controller>, parent: Option<&gtk::Window>, page: &str) {
    // Wide enough that the four page names fit the switcher in full
    let dialog = adw::PreferencesDialog::builder().content_width(720).build();
    let mut ids = Vec::new();
    dialog.add(&general(c, &mut ids));
    dialog.add(&phone(c, &mut ids));
    dialog.add(&notifications(c, &mut ids));
    dialog.add(&clipboard(c, &mut ids));
    // Closed: its screens stop listening, so they can go
    let weak = Rc::downgrade(c);
    dialog.connect_closed(move |_| {
        if let Some(c) = weak.upgrade() {
            for id in &ids {
                c.unwatch(*id);
            }
        }
    });
    dialog.set_visible_page_name(page);
    if page == "phone" {
        c.ask_phone_status();
    }
    dialog.present(parent);
}

/// A switch row that sets itself from the model and sends only the user's own changes.
fn switch(title: &str, subtitle: &str) -> (adw::SwitchRow, Rc<std::cell::Cell<bool>>) {
    let row = adw::SwitchRow::builder()
        .use_markup(false)
        .title(title)
        .subtitle(subtitle)
        .build();
    (row, Rc::new(std::cell::Cell::new(false)))
}

fn set_switch(row: &adw::SwitchRow, quiet: &std::cell::Cell<bool>, on: bool) {
    if row.is_active() != on {
        quiet.set(true);
        row.set_active(on);
        quiet.set(false);
    }
}

/// General: opening at login, then what keeps Seam private (facts, not settings).
fn general(c: &Rc<Controller>, ids: &mut Vec<crate::app::WatchId>) -> adw::PreferencesPage {
    let page = adw::PreferencesPage::builder()
        .name("general")
        .title("General")
        .icon_name("emblem-system-symbolic")
        .build();
    // What closing the window does, since nothing else on the desktop says so
    let group = adw::PreferencesGroup::builder()
        .title("Startup")
        .description(
            "Closing the window keeps Seam running, so your phone's notifications still arrive.",
        )
        .build();
    let (login, quiet) = switch(
        "Open at Login",
        "Seam waits in the background from the moment you log in",
    );
    login.set_active(crate::os::background::autostart());
    let weak = Rc::downgrade(c);
    login.connect_active_notify(move |row| {
        if quiet.get() {
            return;
        }
        let Some(c) = weak.upgrade() else { return };
        let row = row.clone();
        crate::os::background::set_autostart(c.os(), row.is_active(), move |ok, why| {
            if !ok {
                row.set_subtitle(why);
            }
        });
    });
    group.add(&login);
    page.add(&group);

    let _ = (c, ids);
    page
}

fn phone(c: &Rc<Controller>, ids: &mut Vec<crate::app::WatchId>) -> adw::PreferencesPage {
    let page = adw::PreferencesPage::builder()
        .name("phone")
        .title("Phone")
        .icon_name("phone-symbolic")
        .build();
    let header_group = adw::PreferencesGroup::new();
    // The phone itself heads its page, on the page rather than in a row, as a device page does
    let summary = PhoneSummary::new(Size::Header);
    summary.widget.set_margin_start(6);
    let header_row = summary.widget.clone();
    header_group.add(&header_row);
    let none = adw::ActionRow::builder()
        .use_markup(false)
        .title("No Phone Paired")
        .subtitle("Scan a code with Seam on your Android phone")
        .build();
    let show = gtk::Button::builder()
        .label("Show Pairing Code")
        .valign(gtk::Align::Center)
        .build();
    show.add_css_class("suggested-action");
    show.set_action_name(Some("app.show-window"));
    none.add_suffix(&show);
    // Its own group: a hidden row would still leave its list's frame showing
    let none_group = adw::PreferencesGroup::new();
    none_group.add(&none);
    page.add(&header_group);
    page.add(&none_group);

    // Which paths may reach the phone; the last one on can't be turned off, and the footnote says why
    let connections = adw::PreferencesGroup::builder()
        .title("Connections")
        .build();
    let (wifi, wq) = switch(
        "Same Wi‑Fi",
        "Straight to the phone on the same network, and nothing leaves it",
    );
    let (tor, tq) = switch(
        "Through Tor",
        "Reaches the phone anywhere and hides where you are",
    );
    // Stuck? A stock button row in the same group: Seam tries every allowed path again
    let reconnect = adw::ButtonRow::builder()
        .use_markup(false)
        .title("Reconnect")
        .build();
    connections.add(&wifi);
    connections.add(&tor);
    connections.add(&reconnect);
    page.add(&connections);
    let weak = Rc::downgrade(c);
    let q = wq.clone();
    wifi.connect_active_notify(move |r| {
        if !q.get()
            && let Some(c) = weak.upgrade()
        {
            c.set_wifi(r.is_active())
        }
    });
    let weak = Rc::downgrade(c);
    let q = tq.clone();
    tor.connect_active_notify(move |r| {
        if !q.get()
            && let Some(c) = weak.upgrade()
        {
            c.set_tor(r.is_active())
        }
    });
    let weak = Rc::downgrade(c);
    reconnect.connect_activated(move |_| {
        if let Some(c) = weak.upgrade() {
            c.reconnect()
        }
    });

    // Pairing: the code to re-check (hidden until Show), when, and Unpair
    let pairing = adw::PreferencesGroup::builder().title("Pairing").build();
    let code_row = adw::ActionRow::builder()
        .use_markup(false)
        .title("Verification Code")
        .subtitle("Should match the code on your phone")
        .build();
    let code = gtk::Label::new(Some("••• •••"));
    code.add_css_class("mono");
    let show_code = gtk::ToggleButton::builder()
        .icon_name("view-reveal-symbolic")
        .tooltip_text("Show Code")
        .valign(gtk::Align::Center)
        .build();
    show_code.add_css_class("flat");
    code_row.add_suffix(&code);
    code_row.add_suffix(&show_code);
    let real_code: Rc<RefCell<String>> = Rc::default();
    let (cl, rc) = (code.clone(), real_code.clone());
    show_code.connect_toggled(move |t| {
        let real = rc.borrow().clone();
        cl.set_label(if t.is_active() {
            &real
        } else {
            "••• •••"
        });
        t.set_icon_name(if t.is_active() {
            "view-conceal-symbolic"
        } else {
            "view-reveal-symbolic"
        });
        t.set_tooltip_text(Some(if t.is_active() {
            "Hide Code"
        } else {
            "Show Code"
        }));
    });
    let paired_row = adw::ActionRow::builder()
        .use_markup(false)
        .title("Paired")
        .build();
    let paired_value = gtk::Label::new(None);
    paired_value.add_css_class("dim-label");
    paired_row.add_suffix(&paired_value);
    // A stock destructive button row (it asks first): both devices forget each other's keys
    let unpair = adw::ButtonRow::builder()
        .use_markup(false)
        .title("Unpair")
        .build();
    unpair.add_css_class("destructive-action");
    pairing.add(&code_row);
    pairing.add(&paired_row);
    pairing.add(&unpair);
    page.add(&pairing);
    let weak = Rc::downgrade(c);
    unpair.connect_activated(move |b| {
        let Some(c) = weak.upgrade() else { return };
        let name = c.model().phone_name(false);
        let dialog = adw::AlertDialog::new(
            Some(&format!("Unpair {name}?")),
            Some("Both devices forget each other's keys. Nothing more syncs until you pair again."),
        );
        dialog.add_responses(&[("cancel", "Cancel"), ("unpair", "Unpair")]);
        dialog.set_response_appearance("unpair", adw::ResponseAppearance::Destructive);
        dialog.set_default_response(Some("cancel"));
        dialog.set_close_response("cancel");
        let weak = Rc::downgrade(&c);
        dialog.connect_response(Some("unpair"), move |_, _| {
            if let Some(c) = weak.upgrade() {
                c.unpair()
            }
        });
        dialog.present(Some(b));
    });

    let weak_page = page.downgrade();
    ids.push(c.watch(move |c| {
        let Some(page) = weak_page.upgrade() else {
            return;
        };
        let m = c.model();
        let now = crate::ui::now_ms();
        let paired = m.phone.is_some();
        page.set_title(&if paired {
            m.phone_name(true)
        } else {
            "Phone".into()
        });
        header_row.set_visible(paired);
        none_group.set_visible(!paired);
        summary.render(&m, now);
        set_switch(&wifi, &wq, m.wifi);
        set_switch(&tor, &tq, m.tor);
        wifi.set_sensitive(!(m.wifi && !m.tor));
        tor.set_sensitive(!(m.tor && !m.wifi));
        // Why the last one on can't go off, as the group's own description
        connections.set_description(Some(&seam_looks::link::connections_footnote(
            m.wifi,
            m.tor,
            &crate::model::names(),
        )));
        reconnect.set_visible(paired);
        reconnect.set_sensitive(m.reconnecting.is_none());
        reconnect.set_title(if m.reconnecting.is_none() {
            "Reconnect"
        } else {
            "Reconnecting"
        });
        pairing.set_visible(paired);
        let name = m.phone_name(false);
        wifi.set_subtitle(&format!(
            "Straight to {name} on the same network, and nothing leaves it"
        ));
        tor.set_subtitle(&format!("Reaches {name} anywhere and hides where you are"));
        code_row.set_subtitle(&format!("Should match the code on {name}"));
        if let Some(p) = &m.phone {
            *real_code.borrow_mut() = p.safety_code.clone();
            code.set_label(if show_code.is_active() {
                &p.safety_code
            } else {
                "••• •••"
            });
            paired_value.set_label(
                &p.paired_at
                    .map(|at| crate::model::SystemTime.date_long(at))
                    .unwrap_or("With this computer".into()),
            );
            unpair.set_title(&format!("Unpair {}", m.phone_name(true)));
            // Security in one short line, where it matters (design-system.md, "Copy and voice")
            pairing.set_description(Some(&format!(
                "End-to-end encrypted: only this computer and {} can read what passes between them",
                m.phone_name(false)
            )));
        }
    }));
    page
}

const MODES: [(NotificationMode, &str); 3] = [
    (NotificationMode::On, "On"),
    (NotificationMode::TitleOnly, "Sender only"),
    (NotificationMode::Off, "Off"),
];

fn mode_index(app: &NotificationApp) -> u32 {
    match NotificationMode::try_from(app.mode).unwrap_or(NotificationMode::Off) {
        NotificationMode::On => 0,
        NotificationMode::TitleOnly => 1,
        _ => 2,
    }
}

fn notifications(c: &Rc<Controller>, ids: &mut Vec<crate::app::WatchId>) -> adw::PreferencesPage {
    let page = adw::PreferencesPage::builder()
        .name("notifications")
        .title("Notifications")
        .icon_name("preferences-system-notifications-symbolic")
        .build();
    let waiting = adw::PreferencesGroup::new();
    let waiting_row = adw::ActionRow::builder().use_markup(false)
        .title("Waiting for Your Phone")
        .subtitle("Its notification settings show here the next time it connects. Turn on its screen to connect now.")
        .build();
    waiting_row.add_suffix(&adw::Spinner::new());
    waiting.add(&waiting_row);
    page.add(&waiting);

    let switches = adw::PreferencesGroup::builder().title("Banners").build();
    let (enabled, eq) = switch(
        "Show on This Computer",
        "Your phone's notifications appear here as banners",
    );
    let (both, bq) = switch(
        "Dismiss on Both Devices",
        "Clear one here and it's cleared on your phone",
    );
    let (quiet, qq) = switch(
        "Quiet While Your Phone Is Unlocked",
        "Nothing comes here while it's in your hand",
    );
    switches.add(&enabled);
    switches.add(&both);
    switches.add(&quiet);
    // Not a setting here: the desktop's own, said as the group's note
    switches.set_description(Some(
        "Banners, sounds and previews are set by your desktop, like any app's.",
    ));
    page.add(&switches);
    for (row, q, field) in [
        (&enabled, eq.clone(), 0),
        (&both, bq.clone(), 1),
        (&quiet, qq.clone(), 2),
    ] {
        let weak = Rc::downgrade(c);
        row.connect_active_notify(move |r| {
            if q.get() {
                return;
            }
            let Some(c) = weak.upgrade() else { return };
            let mut change = NotificationSettingsChange::default();
            match field {
                0 => change.enabled = Some(r.is_active()),
                1 => change.dismiss_both = Some(r.is_active()),
                _ => change.quiet_while_using_phone = Some(r.is_active()),
            }
            c.change_notifications(change);
        });
    }

    // Apps: each On, Sender only or Off; a filter past 8 apps; Set all
    let apps = adw::PreferencesGroup::builder().title("Apps").build();
    let set_all = gtk::MenuButton::builder()
        .label("Set All")
        .valign(gtk::Align::Center)
        .build();
    set_all.add_css_class("flat");
    let menu = gtk::gio::Menu::new();
    for (i, (_, word)) in MODES.iter().enumerate() {
        menu.append(Some(word), Some(&format!("app.notification-mode-all::{i}")));
    }
    set_all.set_menu_model(Some(&menu));
    apps.set_header_suffix(Some(&set_all));
    let filter = gtk::SearchEntry::builder()
        .placeholder_text("Filter apps")
        .margin_bottom(12)
        .build();
    apps.add(&filter);
    let list = gtk::ListBox::builder()
        .selection_mode(gtk::SelectionMode::None)
        .build();
    list.add_css_class("boxed-list");
    apps.add(&list);
    let footnote = gtk::Label::builder()
        .label("Apps that are off are filtered on your phone, before anything leaves it. Sender only shows who, not what.")
        .xalign(0.0)
        .wrap(true)
        .margin_top(12)
        .build();
    footnote.add_css_class("dim-label");
    footnote.add_css_class("caption");
    apps.add(&footnote);
    page.add(&apps);

    let shown_apps: Rc<RefCell<Vec<NotificationApp>>> = Rc::default();
    let filter_text: Rc<RefCell<String>> = Rc::default();
    let rebuild = {
        let (list, shown_apps, filter_text, weak) = (
            list.clone(),
            shown_apps.clone(),
            filter_text.clone(),
            Rc::downgrade(c),
        );
        Rc::new(move || {
            let Some(c) = weak.upgrade() else { return };
            while let Some(r) = list.first_child() {
                list.remove(&r);
            }
            let f = filter_text.borrow().to_lowercase();
            let all = shown_apps.borrow();
            let shown: Vec<&NotificationApp> = all
                .iter()
                .filter(|a| f.is_empty() || a.app_name.to_lowercase().contains(&f))
                .collect();
            if all.is_empty() {
                list.append(&adw::ActionRow::builder().use_markup(false).title("No Apps Yet").subtitle("Apps that can notify show here once your phone allows notification access").build());
            } else if shown.is_empty() {
                list.append(
                    &adw::ActionRow::builder()
                        .use_markup(false)
                        .title(format!("No apps match “{}”", filter_text.borrow()))
                        .build(),
                );
            }
            for app in shown {
                let row = adw::ComboRow::builder()
                    .use_markup(false)
                    .title(&app.app_name)
                    .model(&gtk::StringList::new(&MODES.map(|m| m.1)))
                    .selected(mode_index(app))
                    .build();
                // The same icon as the notification list's, in the same slot for every app
                let icon = crate::ui::widgets::app_icon(c.icon(&app.app_id), 24);
                icon.set_valign(gtk::Align::Center); // centred in its row, as row prefixes are
                row.add_prefix(&icon);
                let explain = |r: &adw::ComboRow, can_reply: bool| {
                    r.set_subtitle(match r.selected() {
                        0 if can_reply => "You can reply from this computer",
                        1 => "Shows who, not what",
                        _ => "",
                    });
                };
                explain(&row, app.can_reply);
                let can_reply = app.can_reply;
                row.connect_selected_notify(move |r| explain(r, can_reply));
                let (weak, id) = (Rc::downgrade(&c), app.app_id.clone());
                row.connect_selected_notify(move |r| {
                    let Some(c) = weak.upgrade() else { return };
                    let mode = MODES[(r.selected() as usize).min(2)].0;
                    c.change_notifications(NotificationSettingsChange {
                        apps: vec![NotificationApp {
                            app_id: id.clone(),
                            mode: mode as i32,
                            ..Default::default()
                        }],
                        ..Default::default()
                    });
                });
                list.append(&row);
            }
        })
    };
    let (r, ft) = (rebuild.clone(), filter_text.clone());
    filter.connect_search_changed(move |e| {
        *ft.borrow_mut() = e.text().to_string();
        r();
    });

    let weak_page = page.downgrade();
    ids.push(c.watch(move |c| {
        if weak_page.upgrade().is_none() {
            return;
        }
        let m = c.model();
        let s: Option<&NotificationSettings> = m.settings.as_ref();
        let paired = m.phone.is_some();
        waiting.set_visible(paired && s.is_none());
        if !paired {
            waiting_row.set_title("No Phone Paired");
            waiting_row.set_subtitle("Pair a phone to choose which of its notifications show here");
            waiting.set_visible(true);
        }
        switches.set_visible(s.is_some());
        apps.set_visible(s.is_some());
        let Some(s) = s else { return };
        let (name, title_name) = (m.phone_name(false), m.phone_name(true));
        enabled.set_subtitle(&format!(
            "{title_name}'s notifications appear here as banners"
        ));
        both.set_subtitle(&format!("Clear one here and it's cleared on {name}"));
        quiet.set_title(&format!("Quiet While {title_name} Is Unlocked"));
        set_switch(&enabled, &eq, s.enabled);
        set_switch(&both, &bq, s.dismiss_both);
        set_switch(&quiet, &qq, s.quiet_while_using_phone);
        apps.set_description(Some(&seam_looks::notifications::apps_on(&s.apps)));
        filter.set_visible(s.apps.len() > 8);
        if *shown_apps.borrow() != s.apps {
            *shown_apps.borrow_mut() = s.apps.clone();
            rebuild();
        }
    }));
    page
}

fn clipboard(c: &Rc<Controller>, ids: &mut Vec<crate::app::WatchId>) -> adw::PreferencesPage {
    let page = adw::PreferencesPage::builder()
        .name("clipboard")
        .title("Clipboard")
        .icon_name("edit-paste-symbolic")
        .build();
    // The switch, with what never goes and how long a copy waits as the group's description
    let group = adw::PreferencesGroup::builder().title("Sync").build();
    let (sync, sq) = switch("Sync Clipboard", "");
    group.add(&sync);
    page.add(&group);
    let weak = Rc::downgrade(c);
    let q = sq.clone();
    sync.connect_active_notify(move |r| {
        if !q.get()
            && let Some(c) = weak.upgrade()
        {
            c.set_clipboard_sync(r.is_active())
        }
    });

    // Automatic, both ways (decision 0012): set up, or one tap until it is
    let auto = adw::PreferencesGroup::builder()
        .title("Automatic Sync")
        .build();
    let auto_row = adw::ActionRow::builder()
        .use_markup(false)
        .title("Send Automatically")
        .build();
    let set_up = gtk::Button::builder()
        .label("Set Up")
        .valign(gtk::Align::Center)
        .build();
    set_up.set_action_name(Some("app.clipboard-setup"));
    let on = gtk::Label::new(Some("On"));
    on.add_css_class("dim-label");
    auto_row.add_suffix(&on);
    // Neither on nor something to set up: say so where the control would be
    let unavailable = gtk::Label::new(Some("Not available"));
    unavailable.add_css_class("dim-label");
    auto_row.add_suffix(&unavailable);
    auto_row.add_suffix(&set_up);
    auto.add(&auto_row);
    let phone_row = adw::ActionRow::builder()
        .use_markup(false)
        .title("Receive Automatically")
        .build();
    let phone_set_up = gtk::Button::builder()
        .label("Set Up")
        .valign(gtk::Align::Center)
        .build();
    phone_set_up.set_action_name(Some("app.phone-clipboard-setup"));
    phone_row.add_suffix(&phone_set_up);
    auto.add(&phone_row);
    page.add(&auto);

    ids.push(c.watch(move |c| {
        let m = c.model();
        let name = m.phone_name(false);
        set_switch(&sync, &sq, m.clipboard_sync);
        sync.set_subtitle(&format!(
            "What you copy here or on {name} reaches the other"
        ));
        group.set_description(Some(&format!(
            "Passwords and copied files are never sent. A copy made while {name} is offline is sent if it's back within 10 minutes."
        )));
        let automatic = m.clipboard_door.automatic();
        auto_row.set_subtitle(&m.automatic_clipboard_line());
        phone_row.set_subtitle(&format!(
            "Copies from {name} arrive here without using Share"
        ));
        on.set_visible(automatic);
        set_up.set_visible(m.clipboard_door.can_set_up());
        unavailable.set_visible(!automatic && !m.clipboard_door.can_set_up());
        auto.set_sensitive(m.clipboard_sync);
    }));
    page
}
