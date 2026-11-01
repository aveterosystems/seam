// SPDX-License-Identifier: AGPL-3.0-only
use super::*;

#[test]
fn nothing_empty_or_oversized_goes_either_way() {
    let mut r = ClipRules::new();
    assert_eq!(r.outgoing(None, 0), None);
    assert_eq!(r.outgoing(Some(""), 0), None);
    let big = "x".repeat(MAX_BYTES + 1);
    assert_eq!(r.outgoing(Some(&big), 0), None);
    assert_eq!(r.incoming(&big), None);
    assert_eq!(r.forced(Some(&big), 0), None);
    let edge = "x".repeat(MAX_BYTES);
    assert_eq!(r.outgoing(Some(&edge), 0), Some(edge));
}

#[test]
fn the_limit_counts_bytes_not_characters() {
    let mut r = ClipRules::new();
    let emoji = "😀".repeat(MAX_BYTES / 4 + 1); // 4 bytes each
    assert_eq!(r.outgoing(Some(&emoji), 0), None);
}

#[test]
fn a_quick_repeat_is_skipped_a_later_one_goes() {
    let mut r = ClipRules::new();
    assert!(r.outgoing(Some("a"), 0).is_some());
    assert_eq!(r.outgoing(Some("a"), REPEAT_MS - 1), None);
    assert!(
        r.outgoing(Some("a"), REPEAT_MS).is_some(),
        "SR-4-10: copied again later goes"
    );
}

#[test]
fn what_arrived_never_echoes_back() {
    let mut r = ClipRules::new();
    assert_eq!(r.incoming("from phone"), Some("from phone".into()));
    assert_eq!(r.outgoing(Some("from phone"), 10_000), None);
    assert!(r.outgoing(Some("mine"), 10_000).is_some());
    assert!(
        r.outgoing(Some("from phone"), 20_000).is_some(),
        "once something else was copied, it's the user's again"
    );
}

#[test]
fn something_received_clears_the_repeat_guard() {
    let mut r = ClipRules::new();
    assert!(r.outgoing(Some("a"), 0).is_some());
    r.incoming("b");
    assert!(r.outgoing(Some("a"), 1).is_some());
}

#[test]
fn forced_ignores_echo_and_repeat() {
    let mut r = ClipRules::new();
    r.incoming("x");
    assert_eq!(r.forced(Some("x"), 0), Some("x".into()));
    assert_eq!(r.forced(Some("x"), 1), Some("x".into()));
    assert_eq!(r.outgoing(Some("x"), 2), None, "forced counts as sent");
}

#[test]
fn reset_forgets_the_peer() {
    let mut r = ClipRules::new();
    r.incoming("x");
    r.reset();
    assert!(r.outgoing(Some("x"), 0).is_some());
}
