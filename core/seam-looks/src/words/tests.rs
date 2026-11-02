// SPDX-License-Identifier: AGPL-3.0-only
use super::*;
use crate::testing::{HOUR, MIN, NOW, Utc};

#[test]
fn sizes_read_like_a_phone() {
    assert_eq!(size(112_000_000_000), "112 GB");
    assert_eq!(size(400_000_000), "400 MB");
    assert_eq!(size(1_000_000_000_000), "1 TB");
    assert_eq!(size(1_500_000_000_000), "1.5 TB");
}

#[test]
fn memory_reads_like_the_computer_labels_it() {
    assert_eq!(
        memory(24 << 30),
        "24 GB",
        "not 26: memory is counted in 1024s"
    );
    assert_eq!(memory(9_500 << 20), "9.3 GB");
    assert_eq!(memory(8 << 30), "8 GB");
}

#[test]
fn ago_says_how_long() {
    assert_eq!(ago(NOW - 20_000, NOW, &Utc, "just now"), "just now");
    assert_eq!(ago(NOW - 20_000, NOW, &Utc, "now"), "now");
    assert_eq!(ago(NOW - 12 * MIN, NOW, &Utc, "just now"), "12 min ago");
    assert_eq!(ago(NOW - 2 * HOUR, NOW, &Utc, "just now"), "today at 10:00");
    assert_eq!(
        ago(NOW - 24 * HOUR, NOW, &Utc, "just now"),
        "yesterday at 12:00"
    );
    assert_eq!(
        ago(NOW - 72 * HOUR, NOW, &Utc, "just now"),
        Utc.date(NOW - 72 * HOUR)
    );
}

#[test]
fn until_names_the_day() {
    assert_eq!(
        until(Some(NOW + 2 * HOUR + 44 * MIN), NOW, &Utc).unwrap(),
        "until 14:44"
    );
    assert_eq!(
        until(Some(NOW + 24 * HOUR), NOW, &Utc).unwrap(),
        "until tomorrow 12:00"
    );
    assert!(
        until(Some(NOW + 72 * HOUR), NOW, &Utc)
            .unwrap()
            .starts_with("until day ")
    );
    assert_eq!(until(None, NOW, &Utc), None);
}
