// SPDX-License-Identifier: AGPL-3.0-only
//! The recorder through its public API: bounded, oldest first out, nothing kept beyond the caps.

use seam_diagnostics::{Limits, Recorder};

fn limits(events: usize, bytes: usize, line: usize) -> Limits {
    Limits {
        events,
        bytes,
        line,
    }
}

fn lines(export: &str) -> Vec<&str> {
    export.lines().filter(|l| l.contains(" tor ")).collect()
}

#[test]
fn keeps_only_the_newest_events_once_full() {
    let r = Recorder::new(limits(3, 1 << 20, 400));
    for n in 1..=5 {
        r.record("tor", &format!("event {n}"));
    }
    let out = r.export("header");
    let kept = lines(&out);
    assert_eq!(kept.len(), 3, "{out}");
    assert!(kept[0].ends_with("event 3"), "{out}");
    assert!(kept[2].ends_with("event 5"), "{out}");
}

#[test]
fn keeps_within_the_byte_budget_dropping_the_oldest() {
    // Each stored event is "2026-11-17T15:06:17.123Z tor " (29 bytes) plus its text: 129 bytes here
    let r = Recorder::new(limits(1000, 350, 400));
    for n in 0..10 {
        // Short words, so the filter leaves them whole: 100 characters each
        r.record("tor", &format!("{n}{}", " xy".repeat(33)));
    }
    let out = r.export("header");
    let kept = lines(&out);
    assert_eq!(kept.len(), 2, "two fit in 350 bytes, three don't: {out}");
    assert!(kept[1].contains(" tor 9"), "the newest is kept: {out}");
}

#[test]
fn an_event_longer_than_the_cut_is_shortened() {
    let r = Recorder::new(limits(10, 1 << 20, 50));
    r.record("tor", &" y".repeat(250));
    let out = r.export("header");
    let kept = lines(&out);
    assert_eq!(kept.len(), 1);
    assert!(
        kept[0].chars().count() <= 29 + 50 + 1,
        "{} chars",
        kept[0].chars().count()
    );
    assert!(kept[0].ends_with('…'), "a cut shows: {}", kept[0]);
}
