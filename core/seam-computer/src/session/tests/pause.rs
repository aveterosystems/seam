// SPDX-License-Identifier: AGPL-3.0-only
//! Pausing happens on the phone only. The computer is told, shows it, sends nothing new and
//! captures nothing (so a copy made while paused never goes later), and keeps listening for the
//! resume.

use seam_core::proto::v1::{self, NotificationAction, notification_action::Action, payload::Body};

use super::rig::*;
#[allow(unused_imports)]
use crate::features::{clipboard, notifications};
use crate::session::UNTIL_RESUMED;

async fn pause(rig: &mut Rig, until_ms: i64) -> bool {
    // SR-32-01: the pause rides on the Hello's counter
    let hello = rig.phone_hello(true, true, until_ms, None);
    rig.session.received(hello).await
}

#[tokio::test]
async fn a_pause_from_the_phone_stops_copies_and_says_until_when() {
    let mut rig = Rig::new().await;
    rig.tor_up().await;
    let until = rig.now() + 3_600_000;
    assert!(pause(&mut rig, until).await);
    assert_eq!(rig.session.state().paused_until, Some(until));
    rig.pasteboard.copy("copied while paused");
    rig.advance(1.0).await;
    assert!(clips(&rig.on_tor()).is_empty());
}

#[tokio::test]
async fn this_computers_battery_stays_here_while_paused() {
    let mut rig = Rig::new().await;
    rig.tor_up().await;
    pause(&mut rig, UNTIL_RESUMED).await;
    *lock(&rig.saved.own_status) = Some(v1::Status {
        battery_pct: 50,
        ..Default::default()
    });
    rig.battery_changed().await;
    assert!(
        !rig.on_tor()
            .iter()
            .any(|p| matches!(p.body, Some(Body::Status(_))))
    );
}

/// Enforced once, in delivery: nothing but control goes while paused, and it isn't kept for later.
#[tokio::test]
async fn notification_actions_and_settings_changes_are_dropped_while_paused() {
    let mut rig = Rig::new().await;
    rig.tor_up().await;
    pause(&mut rig, UNTIL_RESUMED).await;
    rig.notifications(notifications::Command::Act(NotificationAction {
        key: "k".into(),
        action: Some(Action::ReplyText("on my way".into())),
    }))
    .await;
    rig.send_something(1).await;
    pause(&mut rig, 0).await;
    rig.advance(1.0).await;
    assert!(messages(&rig.on_tor()).is_empty());
    rig.send_something(2).await;
    assert_eq!(numbers(&rig.on_tor()), [2], "after the resume they go");
}

#[tokio::test]
async fn until_resumed_has_no_end_time() {
    let mut rig = Rig::new().await;
    rig.tor_up().await;
    pause(&mut rig, UNTIL_RESUMED).await;
    rig.advance(3600.0).await;
    assert_eq!(rig.session.state().paused_until, Some(UNTIL_RESUMED));
}

#[tokio::test]
async fn a_copy_made_while_paused_never_goes_after_the_resume() {
    let mut rig = Rig::new().await;
    rig.tor_up().await;
    pause(&mut rig, UNTIL_RESUMED).await;
    rig.pasteboard.copy("copied while paused");
    rig.advance(1.0).await;
    pause(&mut rig, 0).await;
    assert_eq!(rig.session.state().paused_until, None);
    rig.advance(1.0).await;
    assert!(clips(&rig.on_tor()).is_empty());
    rig.pasteboard.copy("after the resume");
    rig.advance(1.0).await;
    assert_eq!(clips(&rig.on_tor()), ["after the resume"]);
}

#[tokio::test]
async fn a_timed_pause_ends_on_the_computer_at_the_same_time() {
    let mut rig = Rig::new().await;
    rig.tor_up().await;
    let start = rig.now();
    pause(&mut rig, start + 60_000).await;
    rig.advance(61.0).await;
    assert_eq!(
        rig.session.state().paused_until,
        None,
        "no message: the phone ends it at the same time"
    );
    rig.pasteboard.copy("after the pause");
    rig.advance(1.0).await;
    assert_eq!(clips(&rig.on_tor()), ["after the pause"]);
}

#[tokio::test]
async fn a_pause_is_kept_across_a_restart() {
    let mut rig = Rig::new().await;
    rig.tor_up().await;
    pause(&mut rig, UNTIL_RESUMED).await;
    rig.restart().await;
    assert_eq!(rig.session.state().paused_until, Some(UNTIL_RESUMED));
}

#[tokio::test]
async fn forgetting_the_phone_ends_the_pause() {
    let mut rig = Rig::new().await;
    rig.tor_up().await;
    pause(&mut rig, UNTIL_RESUMED).await;
    assert!(rig.session.unpair_phone().await);
    assert_eq!(rig.session.state().paused_until, None);
    assert_eq!(lock(&rig.saved.prefs).paused_until, None);
}

#[tokio::test]
async fn sr_32_01_a_late_or_replayed_pause_cant_repause_a_resumed_computer() {
    let mut rig = Rig::new().await;
    rig.tor_up().await;
    let paused = rig.phone_hello(true, true, UNTIL_RESUMED, None); // seq n
    let resumed = rig.phone_hello(true, true, 0, None); // seq n + 1
    rig.session.received(resumed).await; // the resume arrives first
    rig.session.received(paused).await; // then the older pause, resent
    assert_eq!(
        rig.session.state().paused_until,
        None,
        "an older Hello never applies"
    );
}

#[tokio::test]
async fn sr_32_01_any_newer_hello_brings_the_current_state() {
    let mut rig = Rig::new().await;
    rig.tor_up().await;
    pause(&mut rig, UNTIL_RESUMED).await;
    // The resume itself was lost (say, it expired from the outbox): the next connection's Hello says it
    pause(&mut rig, 0).await;
    assert_eq!(rig.session.state().paused_until, None);
}

/// A pause end comes from the phone: one near the end of time is still a pause, never a crash.
#[tokio::test]
async fn a_pause_near_the_end_of_time_is_still_a_pause() {
    let mut rig = Rig::new().await;
    rig.tor_up().await;
    assert!(pause(&mut rig, i64::MAX - 1).await);
    rig.advance(60.0).await;
    assert_eq!(rig.session.state().paused_until, Some(i64::MAX - 1));
}

/// What was queued before the phone paused waits until it resumes: the Hello that carries the
/// pause doesn't flush it out, and nothing else does either.
#[tokio::test]
async fn what_was_queued_before_a_pause_waits_for_the_resume() {
    let mut rig = Rig::new().await;
    rig.tor_up().await;
    rig.send_something(1).await;
    assert_eq!(numbers(&rig.on_tor()), [1], "before the pause it goes");
    for e in rig.tor.delivered() {
        let acked = crate::lan::LanEvent::Delivered(rig.phone_id(), e.msg_id);
        rig.event(acked, seam_core::links::Path::Tor).await;
    }
    rig.event(
        crate::lan::LanEvent::Closed(rig.phone_id()),
        seam_core::links::Path::Tor,
    )
    .await;
    rig.send_something(2).await; // queued while the phone is away
    let paused = rig.phone_hello(true, true, UNTIL_RESUMED, None);
    rig.session.received(paused).await; // the pause arrives, and its Hello flushes the queue
    rig.tor_up().await;
    rig.advance(1.0).await;
    assert_eq!(numbers(&rig.on_tor()), [1], "nothing more while paused");
    assert!(
        rig.on_tor().iter().any(is_hello),
        "the computer's Hello still goes"
    );
    let resumed = rig.phone_hello(true, true, 0, None);
    rig.session.received(resumed).await;
    assert_eq!(
        numbers(&rig.on_tor()),
        [1, 2],
        "after the resume what waited goes"
    );
}
