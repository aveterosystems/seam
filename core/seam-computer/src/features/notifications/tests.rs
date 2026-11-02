// SPDX-License-Identifier: AGPL-3.0-only
use std::sync::Arc;

use seam_core::proto::v1::{
    Notification, NotificationAction, NotificationApp, NotificationSettings,
    NotificationSettingsChange, notification_action::Action, payload::Body,
};

use super::{Command, Notifications};
use crate::Event;
use crate::features::testing::FakeHost;
use crate::session::tests::rig::{MemoryNotifications, lock};

/// A 1×1 PNG.
const PNG: &[u8] = &[
    0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
    0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4,
    0x89, 0x00, 0x00, 0x00, 0x0D, 0x49, 0x44, 0x41, 0x54, 0x78, 0x9C, 0x63, 0x00, 0x01, 0x00, 0x00,
    0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, 0xB4, 0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE,
    0x42, 0x60, 0x82,
];

fn setup() -> (Notifications, Arc<MemoryNotifications>, FakeHost) {
    let centre = Arc::new(MemoryNotifications::default());
    (
        Notifications::new(centre.clone()),
        centre,
        FakeHost::default(),
    )
}

fn note(key: &str) -> Notification {
    Notification {
        key: key.into(),
        ..Default::default()
    }
}

fn dismissed(key: &str) -> NotificationAction {
    NotificationAction {
        key: key.into(),
        action: Some(Action::Dismissed(true)),
    }
}

fn app(id: &str, icon: &[u8]) -> NotificationApp {
    NotificationApp {
        app_id: id.into(),
        icon_png: icon.to_vec(),
        ..Default::default()
    }
}

fn settings(apps: Vec<NotificationApp>) -> Body {
    Body::NotificationSettings(NotificationSettings {
        apps,
        ..Default::default()
    })
}

#[tokio::test]
async fn notifications_show_with_their_apps_icon() {
    let (mut n, centre, mut host) = setup();
    n.receive(&settings(vec![app("chat", PNG)]), &mut host)
        .await;
    let chat = Notification {
        app_id: "chat".into(),
        ..note("k")
    };
    n.receive(&Body::Notification(chat), &mut host).await;
    let shown = lock(&centre.shown).clone();
    assert_eq!(shown.len(), 1);
    assert_eq!(shown[0].1.as_deref(), Some(PNG));
    assert_eq!(n.icons().get("chat").map(Vec::as_slice), Some(PNG));
    n.receive(&Body::NotificationAction(dismissed("k")), &mut host)
        .await;
    assert_eq!(
        *lock(&centre.dismissed),
        ["k"],
        "dismissed on the phone, dismissed here"
    );
}

#[tokio::test]
async fn an_icon_that_isnt_a_png_is_dropped() {
    let (mut n, _, mut host) = setup();
    n.receive(
        &settings(vec![
            app("chat", b"<svg onload=...>"),
            app("big", &[PNG, &vec![0; 300_000]].concat()),
        ]),
        &mut host,
    )
    .await;
    assert!(n.icons().is_empty());
    assert!(
        n.settings()
            .unwrap()
            .apps
            .iter()
            .all(|a| a.icon_png.is_empty()),
        "never kept in the settings either"
    );
}

#[tokio::test]
async fn icons_are_asked_for_forty_at_a_time_and_only_once() {
    let (mut n, _, mut host) = setup();
    let apps: Vec<_> = (0..45).map(|i| app(&format!("app{i}"), &[])).collect();
    let asked = |host: &FakeHost| -> Vec<usize> {
        host.bodies()
            .into_iter()
            .filter_map(|b| match b {
                Body::NotificationSettingsChange(c) => Some(c.need_icons.len()),
                _ => None,
            })
            .collect()
    };
    n.receive(&settings(apps.clone()), &mut host).await;
    assert_eq!(asked(&host), [40]);
    n.receive(&settings(apps.clone()), &mut host).await;
    assert_eq!(asked(&host), [40, 5]);
    n.receive(&settings(apps), &mut host).await;
    assert_eq!(asked(&host), [40, 5], "not asked again");
}

#[tokio::test]
async fn dismissing_here_dismisses_on_the_phone_only_with_dismiss_both_on() {
    let (mut n, _, mut host) = setup();
    let actions = |host: &FakeHost| {
        host.bodies()
            .into_iter()
            .filter(|b| matches!(b, Body::NotificationAction(_)))
            .count()
    };
    let off = Body::NotificationSettings(NotificationSettings {
        dismiss_both: false,
        ..Default::default()
    });
    n.receive(&off, &mut host).await;
    n.command(Command::Act(dismissed("k")), &mut host).await;
    assert_eq!(actions(&host), 0);
    let on = NotificationSettingsChange {
        dismiss_both: Some(true),
        ..Default::default()
    };
    n.command(Command::Change(on), &mut host).await;
    assert!(n.settings().unwrap().dismiss_both, "shown at once");
    n.command(Command::Act(dismissed("k")), &mut host).await;
    assert_eq!(actions(&host), 1);
}

#[tokio::test]
async fn the_main_window_lists_the_phones_notifications_until_dismissed() {
    let (mut n, centre, mut host) = setup();
    let titled = |key: &str, title: &str| {
        Body::Notification(Notification {
            title: title.into(),
            ..note(key)
        })
    };
    n.receive(&titled("a", "first"), &mut host).await;
    n.receive(&titled("b", "second"), &mut host).await;
    n.receive(&titled("a", "first, updated"), &mut host).await;
    let titles: Vec<_> = n.shown().iter().map(|x| x.title.clone()).collect();
    assert_eq!(
        titles,
        ["first, updated", "second"],
        "an update replaces it, newest first"
    );
    n.command(Command::Dismiss("a".into()), &mut host).await;
    assert_eq!(
        n.shown().iter().map(|x| x.key.as_str()).collect::<Vec<_>>(),
        ["b"]
    );
    assert_eq!(*lock(&centre.dismissed), ["a"], "its banner goes too");
    n.receive(&Body::NotificationAction(dismissed("b")), &mut host)
        .await;
    assert!(n.shown().is_empty(), "dismissed on the phone");
}

#[tokio::test]
async fn a_new_or_updated_notification_rings_but_a_repeat_or_dismissal_doesnt() {
    let (mut n, _, mut host) = setup();
    let posted = |at| {
        Body::Notification(Notification {
            posted_at: at,
            app_name: "Chat".into(),
            ..note("a")
        })
    };
    n.receive(&posted(1), &mut host).await;
    n.receive(&posted(1), &mut host).await; // the same one again
    n.receive(&posted(5), &mut host).await; // a new message in the same chat
    n.receive(&Body::NotificationAction(dismissed("a")), &mut host)
        .await;
    assert_eq!(n.arrivals(), 2);
    let announced = Event::NotificationFromPhone {
        app_name: "Chat".into(),
    };
    assert_eq!(host.events, [announced.clone(), announced]);
}

#[tokio::test]
async fn forgetting_the_phone_clears_it_all() {
    let (mut n, centre, mut host) = setup();
    n.receive(&settings(vec![app("chat", PNG)]), &mut host)
        .await;
    n.receive(&Body::Notification(note("k")), &mut host).await;
    let before = n.icons_version();
    n.reset().await;
    assert!(n.shown().is_empty() && n.settings().is_none() && n.icons().is_empty());
    assert!(lock(&centre.shown).is_empty(), "the banners too");
    assert!(n.icons_version() > before, "the app hears the icons went");
}
