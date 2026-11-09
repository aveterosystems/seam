// SPDX-License-Identifier: AGPL-3.0-only
use std::sync::{Arc, Mutex};

use seam_core::proto::v1::{self, payload::Body};

use super::{Battery, Command, Status};
use crate::features::Reach;
use crate::features::testing::FakeHost;
use crate::lock;

#[derive(Default)]
struct Power(Mutex<Option<v1::Status>>);

impl Battery for Power {
    fn read(&self) -> Option<v1::Status> {
        lock(&self.0).clone()
    }
}

fn level(pct: u32) -> v1::Status {
    v1::Status {
        battery_pct: pct,
        ..Default::default()
    }
}

fn sent(host: &FakeHost) -> Vec<u32> {
    host.bodies()
        .into_iter()
        .filter_map(|b| match b {
            Body::Status(s) => Some(s.battery_pct),
            _ => None,
        })
        .collect()
}

#[test]
fn the_phones_battery_never_shows_more_than_a_hundred_and_says_when_it_came() {
    let mut s = Status::new(Arc::new(Power::default()));
    let mut host = FakeHost::default();
    s.receive(&Body::Status(level(250)), &mut host);
    assert_eq!(s.view().status.unwrap().battery_pct, 100);
    assert_eq!(s.view().at, Some(host.now));
    s.reset();
    assert_eq!(s.view().status, None);
}

#[test]
fn this_computers_battery_goes_when_the_phone_connects_and_when_it_changes() {
    let power = Arc::new(Power::default());
    let mut s = Status::new(power.clone());
    let mut host = FakeHost {
        reach: Reach::Away,
        ..Default::default()
    };
    *lock(&power.0) = Some(level(64));
    s.command(Command::BatteryChanged, &mut host);
    assert!(
        sent(&host).is_empty(),
        "nothing is queued while the phone is away"
    );
    host.reach = Reach::Connected;
    s.connected(&mut host);
    assert_eq!(sent(&host), [64]);
    *lock(&power.0) = Some(level(130));
    s.command(Command::BatteryChanged, &mut host);
    assert_eq!(sent(&host), [64, 100], "clamped here too");
    host.reach = Reach::Off; // paused on the phone
    s.command(Command::BatteryChanged, &mut host);
    assert_eq!(sent(&host), [64, 100]);
}

#[test]
fn a_computer_without_a_battery_sends_no_status() {
    let mut s = Status::new(Arc::new(Power::default()));
    let mut host = FakeHost::default();
    s.connected(&mut host);
    s.command(Command::BatteryChanged, &mut host);
    assert!(host.sent.is_empty());
}

#[test]
fn the_phone_asking_gets_a_fresh_reading_but_only_while_connected() {
    let power = Arc::new(Power::default());
    let mut s = Status::new(power.clone());
    let mut host = FakeHost::default();
    *lock(&power.0) = Some(v1::Status {
        cpu_pct: Some(250),
        memory_used_bytes: 9,
        memory_total_bytes: 16,
        ..level(64)
    });
    s.receive(
        &Body::StatusRequest(v1::StatusRequest::default()),
        &mut host,
    );
    let Some(Body::Status(sent)) = host.bodies().pop() else {
        panic!("no status sent")
    };
    assert_eq!(
        (sent.battery_pct, sent.cpu_pct, sent.memory_total_bytes),
        (64, Some(100), 16),
        "CPU clamped too"
    );
    assert_eq!(s.view().status, None, "a request isn't the phone's status");
    host.reach = Reach::Away;
    let before = host.sent.len();
    s.receive(
        &Body::StatusRequest(v1::StatusRequest::default()),
        &mut host,
    );
    assert_eq!(host.sent.len(), before);
}

#[test]
fn asking_the_phone_goes_only_while_connected() {
    let mut s = Status::new(Arc::new(Power::default()));
    let mut host = FakeHost {
        reach: Reach::Away,
        ..Default::default()
    };
    s.command(Command::AskPhone, &mut host);
    host.reach = Reach::Off;
    s.command(Command::AskPhone, &mut host);
    assert!(host.sent.is_empty(), "never wakes or queues for the phone");
    host.reach = Reach::Connected;
    s.command(Command::AskPhone, &mut host);
    assert!(matches!(host.bodies().as_slice(), [Body::StatusRequest(_)]));
}
