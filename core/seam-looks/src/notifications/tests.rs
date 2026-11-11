// SPDX-License-Identifier: AGPL-3.0-only
use super::*;
use crate::testing::{HOUR, NOW, Utc, phone};

fn app(name: &str, mode: NotificationMode) -> NotificationApp {
    let mut a = NotificationApp {
        app_name: name.into(),
        ..Default::default()
    };
    a.set_mode(mode);
    a
}

fn apps() -> Vec<NotificationApp> {
    use NotificationMode::*;
    vec![
        app("WhatsApp", On),
        app("Monzo", TitleOnly),
        app("Uber", Off),
        app("Gmail", TitleOnly),
    ]
}

#[test]
fn modes_have_one_name_each() {
    assert_eq!(mode_word(NotificationMode::TitleOnly), "Sender only");
    assert_eq!(mode_word(NotificationMode::Unspecified), "Off");
    assert_eq!(
        mode_explanation(NotificationMode::On, true, &phone()),
        "Everything, and reply from your Mac"
    );
    assert_eq!(
        mode_explanation(NotificationMode::Off, false, &phone()),
        "Stays on this phone"
    );
}

#[test]
fn counts_treat_sender_only_as_on() {
    assert_eq!(apps_on(&apps()), "3 of 4 on");
    assert_eq!(phone_short(true, &apps()), "On · 3 apps");
    assert_eq!(phone_short(false, &apps()), "Off");
    assert_eq!(
        phone_long(true, &apps()),
        "3 of 4 apps · Sender only for Monzo and Gmail"
    );
    assert_eq!(
        phone_long(false, &apps()),
        "Off · 3 of 4 apps · Sender only for Monzo and Gmail"
    );
    assert_eq!(phone_long(true, &apps()[..1]), "1 of 1 apps");
    assert_eq!(phone_app_count(&apps()), "3 of 4 apps");
    let many: Vec<_> = (0..3)
        .map(|i| app(&format!("App {i}"), NotificationMode::TitleOnly))
        .collect();
    assert_eq!(
        phone_long(true, &many),
        "3 of 3 apps · Sender only for 3 apps"
    );
}

#[test]
fn the_tile_says_its_state_in_words() {
    let t = |s: Option<&NotificationSettings>, showing| tile(s, showing, NOW, &Utc);
    let waiting = t(None, 0);
    assert_eq!(
        (waiting.mode, waiting.subtitle.as_str()),
        (TileMode::Waiting, "Waiting for phone")
    );
    let on = NotificationSettings {
        enabled: true,
        ..Default::default()
    };
    assert_eq!(t(Some(&on), 2).subtitle, "On · 2 showing");
    assert_eq!(
        (t(Some(&on), 0).subtitle.as_str(), t(Some(&on), 0).icon),
        ("On", Icon::Notifications)
    );
    let off = NotificationSettings {
        enabled: false,
        ..Default::default()
    };
    assert_eq!(
        (t(Some(&off), 2).subtitle.as_str(), t(Some(&off), 2).icon),
        ("Off", Icon::NotificationsOff)
    );
    let in_an_hour = NotificationSettings {
        enabled: true,
        paused_until: NOW + HOUR,
        ..Default::default()
    };
    assert_eq!(
        (
            t(Some(&in_an_hour), 0).mode,
            t(Some(&in_an_hour), 0).subtitle.as_str()
        ),
        (TileMode::Paused, "Paused until 13:00")
    );
    let tomorrow = NotificationSettings {
        enabled: true,
        paused_until: NOW + 20 * HOUR,
        ..Default::default()
    };
    assert_eq!(t(Some(&tomorrow), 0).subtitle, "Paused until tomorrow");
}
