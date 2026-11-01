// SPDX-License-Identifier: AGPL-3.0-only
use super::*;
use crate::proto::v1::{
    Clipboard, Control, Hello, Notification, NotificationAction, NotificationSettings,
    NotificationSettingsChange, Status, Unpair,
};

fn of(body: Body) -> Rules {
    rules(&Payload { body: Some(body) })
}

fn control(body: control::Body) -> Body {
    Body::Control(Control { body: Some(body) })
}

fn action(a: notification_action::Action) -> Body {
    Body::NotificationAction(NotificationAction {
        key: "k".into(),
        action: Some(a),
    })
}

// The table both outboxes used to keep apart (Outbox.kt, the Mac features' TTLs)
#[test]
fn delivery_rules_table() {
    use notification_action::Action::{ButtonId, Dismissed, ReplyText};
    let cases: Vec<(&str, Body, u32, bool, bool)> = vec![
        // name, payload, ttl, latest only, lazy
        (
            "clipboard",
            Body::Clipboard(Clipboard::default()),
            600,
            true,
            false,
        ),
        ("status", Body::Status(Status::default()), 120, true, true),
        (
            "notification",
            Body::Notification(Notification::default()),
            3600,
            false,
            false,
        ),
        (
            "settings",
            Body::NotificationSettings(NotificationSettings::default()),
            600,
            true,
            false,
        ),
        (
            "settings change",
            Body::NotificationSettingsChange(NotificationSettingsChange::default()),
            600,
            false,
            false,
        ),
        ("reply", action(ReplyText("hi".into())), 0, false, false),
        ("dismiss", action(Dismissed(true)), 0, false, false),
        ("button", action(ButtonId("b".into())), 600, false, false),
        (
            "hello",
            control(control::Body::Hello(Hello::default())),
            600,
            false,
            false,
        ),
        (
            "unpair",
            control(control::Body::Unpair(Unpair {})),
            0,
            false,
            false,
        ),
    ];
    for (name, body, ttl, latest, lazy) in cases {
        let r = of(body);
        assert_eq!(
            (r.ttl_s, r.latest_only, r.lazy),
            (ttl, latest, lazy),
            "{name}"
        );
    }
}

#[test]
fn a_pause_stops_everything_but_control() {
    assert!(!of(control(control::Body::Hello(Hello::default()))).paused);
    assert!(!of(control(control::Body::Unpair(Unpair {}))).paused);
    for body in [
        Body::Clipboard(Clipboard::default()),
        Body::Status(Status::default()),
        Body::Notification(Notification::default()),
        action(notification_action::Action::ReplyText("hi".into())),
    ] {
        assert!(of(body).paused);
    }
}

#[test]
fn an_empty_payload_gets_the_defaults() {
    assert_eq!(rules(&Payload { body: None }), DEFAULT);
}
