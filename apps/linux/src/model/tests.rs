// SPDX-License-Identifier: AGPL-3.0-only
use seam_computer::computer::{Health, Snapshot};
use seam_computer::features::{Views, clipboard, notifications, status};
use seam_computer::pairing::{PairingFailure, PairingState};
use seam_computer::session::{Phone, Reconnect, State, Warning};
use seam_computer::tor::TorState;
use seam_core::links::Path;
use seam_core::proto::v1::{Notification, NotificationSettings};

use super::*;

fn phone() -> Phone {
    Phone {
        id: "p".into(),
        name: "Pixel 9".into(),
        safety_code: "482 913".into(),
        paired_at: None,
    }
}

fn session() -> State {
    State {
        phone: Some(phone()),
        route: Some(Path::Lan),
        allowed: vec![Path::Lan, Path::Tor],
        waiting: 0,
        pairing: PairingState::Idle,
        warning: None,
        paused_until: None,
        tor_services: vec![],
        wants_links: true,
        reconnect: None,
        last_seen: None,
        wifi: true,
        tor: true,
    }
}

fn views(shown: Vec<Notification>, settings: Option<NotificationSettings>) -> Views {
    Views {
        clipboard: clipboard::View {
            sync_on: true,
            clips: vec![],
        },
        notifications: notifications::View {
            shown,
            settings,
            icons_version: 1,
            arrivals: 0,
        },
        status: status::View {
            status: None,
            at: None,
        },
    }
}

fn snapshot(health: Health, session: Option<State>, features: Option<Views>) -> Snapshot {
    Snapshot {
        health,
        opens: 1,
        pair_again: false,
        session,
        features,
        tor_state: TorState::Ready,
    }
}

fn note(key: &str) -> Notification {
    Notification {
        key: key.into(),
        app_name: "Chat".into(),
        title: "Ana".into(),
        ..Default::default()
    }
}

#[test]
fn a_starting_snapshot_changes_nothing() {
    let m = Model::default();
    let after = m.apply(&snapshot(Health::Starting, Some(session()), None), false);
    assert_eq!(after.phone, None);
    assert_eq!(after.health, Health::Starting);
}

#[test]
fn ready_with_a_phone_on_the_same_wifi_is_connected() {
    let m = Model::default().apply(
        &snapshot(Health::Ready, Some(session()), Some(views(vec![], None))),
        false,
    );
    assert_eq!(m.phone_name(true), "Pixel 9");
    let look = m.link(0);
    assert!(look.connected);
    assert_eq!(look.kind, seam_looks::LinkKind::SameWifi);
    assert_eq!(m.status_line(0), "Connected");
}

#[test]
fn startup_problems_are_in_words_and_a_reset_says_so() {
    let failed = snapshot(
        Health::Failed {
            detail: "your keyring is locked.".into(),
        },
        None,
        None,
    );
    assert_eq!(
        Model::default()
            .apply(&failed, false)
            .startup_error
            .unwrap(),
        "Seam couldn't start: your keyring is locked."
    );
    assert_eq!(
        Model::default().apply(&failed, true).startup_error.unwrap(),
        "Seam couldn't reset: your keyring is locked."
    );
    let unreadable = Model::default().apply(&snapshot(Health::Unreadable, None, None), false);
    assert!(unreadable.needs_reset());
    // Ready again clears it
    let ok = unreadable.apply(&snapshot(Health::Ready, Some(session()), None), false);
    assert_eq!(ok.startup_error, None);
}

#[test]
fn warnings_and_pairing_again_are_shown() {
    let mut s = session();
    s.warning = Some(Warning::UntrustedIdentity);
    let m = Model::default().apply(&snapshot(Health::Ready, Some(s), None), false);
    assert!(m.warning.unwrap().contains("keys that don't match"));
    let mut again = snapshot(Health::Ready, Some(session()), None);
    again.pair_again = true;
    assert_eq!(
        Model::default().apply(&again, false).warning.unwrap(),
        "Seam now connects through Tor. Pair your phone again."
    );
}

#[test]
fn the_link_input_follows_the_session() {
    let mut s = session();
    s.route = None;
    s.allowed = vec![Path::Tor];
    s.paused_until = Some(5_000);
    s.reconnect = Some(Reconnect::Asked);
    let input = link_input(&s, TorState::Starting(40));
    assert_eq!(input.route, None);
    assert!(!input.lan_allowed && input.tor_allowed);
    assert_eq!(input.tor, Tor::Starting);
    assert_eq!(
        input.pause,
        Some(Pause {
            until_ms: 5_000,
            here: false
        })
    );
    assert_eq!(input.reconnect, Some(link::Reconnect::Asked));
}

#[test]
fn no_features_means_nothing_listed() {
    // Sync off or the data closed: no copy or notification lingers on screen
    let mut m = Model::default().apply(
        &snapshot(
            Health::Ready,
            Some(session()),
            Some(views(vec![note("a")], None)),
        ),
        false,
    );
    assert_eq!(m.shown.len(), 1);
    m = m.apply(&snapshot(Health::Ready, Some(session()), None), false);
    assert!(m.shown.is_empty() && m.clips.is_empty());
}

#[test]
fn empty_notifications_say_why() {
    let on = NotificationSettings {
        enabled: true,
        dismiss_both: true,
        ..Default::default()
    };
    let m = Model::default().apply(
        &snapshot(
            Health::Ready,
            Some(session()),
            Some(views(vec![], Some(on.clone()))),
        ),
        false,
    );
    let e = m.notifications_empty(0).unwrap();
    assert_eq!(e.title, "No Notifications");
    assert_eq!(e.action.unwrap().1, EmptyAction::ChooseApps);

    let off = NotificationSettings {
        enabled: false,
        ..on.clone()
    };
    let m2 = m.apply(
        &snapshot(
            Health::Ready,
            Some(session()),
            Some(views(vec![note("a")], Some(off))),
        ),
        false,
    );
    // Off wins even with something still listed: nothing more is coming
    assert_eq!(
        m2.notifications_empty(0).unwrap().action.unwrap().1,
        EmptyAction::TurnOnNotifications
    );

    let paused = NotificationSettings {
        paused_until: 10_000_000,
        ..on.clone()
    };
    let m3 = m.apply(
        &snapshot(
            Health::Ready,
            Some(session()),
            Some(views(vec![], Some(paused))),
        ),
        false,
    );
    assert_eq!(
        m3.notifications_empty(0).unwrap().action.unwrap().1,
        EmptyAction::ResumeNotifications
    );

    let waiting = m.apply(
        &snapshot(Health::Ready, Some(session()), Some(views(vec![], None))),
        false,
    );
    assert_eq!(
        waiting.notifications_empty(0).unwrap().title,
        "Waiting for Pixel 9"
    );

    let listed = m.apply(
        &snapshot(
            Health::Ready,
            Some(session()),
            Some(views(vec![note("a")], Some(on))),
        ),
        false,
    );
    assert!(listed.notifications_empty(0).is_none());
}

#[test]
fn clearing_says_whether_the_phone_clears_too() {
    let both = NotificationSettings {
        enabled: true,
        dismiss_both: true,
        ..Default::default()
    };
    let m = Model::default().apply(
        &snapshot(
            Health::Ready,
            Some(session()),
            Some(views(vec![note("a"), note("b")], Some(both.clone()))),
        ),
        false,
    );
    assert_eq!(
        m.clear_all_title(),
        "Clear 2 notifications on this computer and Pixel 9?"
    );
    assert_eq!(
        m.clear_note(),
        "Notifications you clear here are also cleared on Pixel 9."
    );
    let here = NotificationSettings {
        dismiss_both: false,
        ..both
    };
    let m = m.apply(
        &snapshot(
            Health::Ready,
            Some(session()),
            Some(views(vec![note("a")], Some(here))),
        ),
        false,
    );
    assert_eq!(
        m.clear_all_title(),
        "Clear 1 notification on this computer?"
    );
    assert_eq!(
        m.clear_note(),
        "Notifications you clear here stay on Pixel 9."
    );
}

#[test]
fn an_unnamed_phone_is_your_phone() {
    let p = Phone {
        name: "  ".into(),
        ..phone()
    };
    assert_eq!(phone_name(Some(&p), true), "Your phone");
    assert_eq!(phone_name(Some(&p), false), "your phone");
    assert_eq!(phone_name(None, false), "your phone");
}

#[test]
fn every_failure_ends_with_what_to_do() {
    for f in [
        PairingFailure::Unreachable,
        PairingFailure::NotSaved,
        PairingFailure::DidntFinish,
    ] {
        assert!(pairing_failure_words(f).ends_with("Show a new code and scan it again."));
    }
}

#[test]
fn announcements_never_carry_contents() {
    assert_eq!(
        announcement(&seam_computer::Event::ClipFromPhone).unwrap(),
        "Copied from your phone"
    );
    assert_eq!(
        announcement(&seam_computer::Event::NotificationFromPhone {
            app_name: "Chat".into()
        })
        .unwrap(),
        "Notification from Chat"
    );
    assert!(
        announcement(&seam_computer::Event::Problem(
            seam_computer::Problem::SaveFailed
        ))
        .is_none()
    );
}

#[test]
fn icons_are_adwaitas() {
    assert_eq!(battery_icon(82, false), "battery-level-80-symbolic");
    assert_eq!(battery_icon(82, true), "battery-level-80-charging-symbolic");
    assert_eq!(
        battery_icon(100, true),
        "battery-level-100-charged-symbolic"
    );
    assert_eq!(battery_icon(4, false), "battery-level-0-symbolic");
    assert_eq!(battery_icon(250, false), "battery-level-100-symbolic");
    assert_eq!(icon_name(Icon::ThroughTor), "seam-globe-symbolic");
    assert_eq!(
        icon_name(Icon::MobileSignal { fraction: 0.5 }),
        "network-cellular-signal-ok-symbolic"
    );
}

#[test]
fn times_drop_their_seconds() {
    assert_eq!(trim_seconds("14:02:09"), "14:02");
    assert_eq!(trim_seconds("2:02:09 PM"), "2:02 PM");
    assert_eq!(trim_seconds("14h02"), "14h02");
}

#[test]
fn the_card_says_paused_briefly() {
    let mut s = session();
    s.route = None;
    s.paused_until = Some(i64::MAX);
    let m = Model::default().apply(&snapshot(Health::Ready, Some(s), None), false);
    assert_eq!(m.card_line(0), "Paused");
    assert!(m.status_line(0).len() > m.card_line(0).len());
    // Not paused: the card says what the status line says
    let c = Model::default().apply(&snapshot(Health::Ready, Some(session()), None), false);
    assert_eq!(c.card_line(0), c.status_line(0));
}

#[test]
fn a_page_says_paused_or_away_never_both() {
    let mut paused = session();
    paused.route = None;
    paused.paused_until = Some(i64::MAX);
    let m = Model::default().apply(&snapshot(Health::Ready, Some(paused), None), false);
    let words = m.page_status(0).unwrap();
    assert!(words.starts_with("Pixel 9 is paused"), "{words}");
    assert!(!words.contains("away"));

    let mut away = session();
    away.route = None;
    away.waiting = 3;
    let m = Model::default().apply(&snapshot(Health::Ready, Some(away), None), false);
    assert_eq!(
        m.page_status(0).unwrap(),
        "Pixel 9 is away: 3 waiting send when it's back."
    );

    let connected = Model::default().apply(&snapshot(Health::Ready, Some(session()), None), false);
    assert!(connected.page_status(0).is_none());
}
