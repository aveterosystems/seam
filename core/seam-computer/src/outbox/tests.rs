// SPDX-License-Identifier: AGPL-3.0-only
use seam_core::proto::v1::Envelope;

use super::{CAP, Outbox, OutboxFile};
use crate::durable::DurableFile;

const NOW_MS: i64 = 1_000_000;

fn env(id: u8, sent_at: i64, ttl_s: u32, size: usize) -> Envelope {
    Envelope {
        msg_id: vec![id],
        sent_at,
        ttl_s,
        ciphertext: vec![id; size],
        ..Default::default()
    }
}

fn e(id: u8) -> Envelope {
    env(id, 999_000, 600, 32)
}

fn ids(list: &[Envelope]) -> Vec<Vec<u8>> {
    list.iter().map(|e| e.msg_id.clone()).collect()
}

#[test]
fn tracks_waiting_and_sent_and_requeues_what_wasnt_acked() {
    let mut b = Outbox::default();
    b.add(e(1));
    b.add(e(2));
    b.add(e(1)); // a retry: not twice
    assert_eq!(ids(b.waiting()), [vec![1], vec![2]]);
    b.mark_sent(&[1]);
    assert_eq!(ids(b.sent()), [vec![1]]);
    assert_eq!(ids(b.waiting()), [vec![2]]);
    b.add(e(1)); // already sent, waiting for its ack: not queued again
    assert_eq!(b.waiting().len(), 1);
    b.requeue_sent();
    assert_eq!(ids(b.waiting()), [vec![1], vec![2]], "unacked goes first");
    assert!(b.sent().is_empty());
    b.remove(&[1]);
    b.remove(&[2]);
    assert!(b.is_empty());
}

#[test]
fn caps_and_drops_what_expired() {
    let mut b = Outbox::default();
    b.add(env(1, 990_000, 5, 32)); // expired at 995 s
    b.add(env(2, 990_000, 600, 32));
    b.add(env(3, 990_000, 0, 32)); // the default 24 h
    assert!(b.drop_expired(NOW_MS));
    assert_eq!(ids(b.waiting()), [vec![2], vec![3]]);
    assert!(!b.drop_expired(NOW_MS));
    for i in 0..CAP + 5 {
        b.add(e(10 + (i % 200) as u8));
    }
    assert_eq!(b.waiting().len(), CAP);
}

fn file() -> (tempfile::TempDir, OutboxFile) {
    let dir = tempfile::tempdir().unwrap();
    let f = OutboxFile::new(DurableFile::new(dir.path().join("outbox.bin")));
    (dir, f)
}

#[test]
fn round_trips() {
    let (_dir, f) = file();
    let mut b = Outbox::default();
    b.add(env(1, 999_000, 600, 1000));
    b.add(env(2, 999_000, 600, 1000));
    b.mark_sent(&[2]);
    f.save(&b).unwrap();
    assert_eq!(f.load(NOW_MS), b);
}

/// The exact bytes the Swift app wrote: an update reads what an older Seam saved.
#[test]
fn reads_the_swift_apps_format() {
    let envelope = |id: u8| {
        use prost::Message;
        env(id, 999_000, 600, 4).encode_to_vec()
    };
    let mut swift = b"SOB1".to_vec();
    swift.extend(1u32.to_be_bytes());
    swift.extend((envelope(1).len() as u32).to_be_bytes());
    swift.extend(envelope(1));
    swift.extend(1u32.to_be_bytes());
    swift.extend((envelope(2).len() as u32).to_be_bytes());
    swift.extend(envelope(2));
    let b = Outbox::decode(&swift).unwrap();
    assert_eq!(ids(b.waiting()), [vec![1]]);
    assert_eq!(ids(b.sent()), [vec![2]]);
    assert_eq!(b.encode(), swift, "and writes it back the same");
}

#[test]
fn drops_expired_on_load() {
    let (_dir, f) = file();
    let mut b = Outbox::default();
    b.add(env(1, 990_000, 5, 1000));
    b.add(env(2, 999_000, 600, 1000));
    b.mark_sent(&[1]);
    f.save(&b).unwrap();
    let loaded = f.load(NOW_MS);
    assert_eq!(ids(loaded.waiting()), [vec![2]]);
    assert!(loaded.sent().is_empty());
}

#[test]
fn a_missing_or_damaged_file_is_empty_never_a_crash() {
    let (_dir, f) = file();
    assert!(f.load(NOW_MS).is_empty(), "missing");
    let mut b = Outbox::default();
    b.add(e(1));
    let good = b.encode();
    let mut wrong_magic = b"SOB2".to_vec();
    wrong_magic.extend(&good[4..]);
    let mut longer = good.clone();
    longer.push(0);
    let noise: Vec<u8> = (0..5000).map(|_| rand::random()).collect();
    let bads: Vec<Vec<u8>> = vec![
        vec![],
        wrong_magic,
        good[..good.len() - 1].to_vec(),
        longer,
        [b"SOB1".as_slice(), &[0, 0, 0, 200]].concat(),
        [b"SOB1".as_slice(), &[0, 0, 0, 1, 0xFF, 0xFF, 0xFF, 0xFF]].concat(),
        noise,
    ];
    for bad in bads {
        f.file().write(&bad).unwrap();
        assert!(f.load(NOW_MS).is_empty());
    }
}

#[test]
fn deleting_clears_it() {
    let (_dir, f) = file();
    let mut b = Outbox::default();
    b.add(e(1));
    f.save(&b).unwrap();
    f.delete();
    assert!(f.load(NOW_MS).is_empty());
    assert!(!f.file().path().exists());
}

/// `swift-outbox.bin` was written by the Swift app (Seam 0.2.4's `OutboxFile`): three envelopes,
/// the second sent and waiting for its ack. An update must read it, and write it back the same.
#[test]
fn reads_a_file_the_swift_app_wrote() {
    let swift = include_bytes!("swift-outbox.bin");
    let b = Outbox::decode(swift).expect("the Swift app's file");
    assert_eq!(ids(b.waiting()), [vec![1; 16], vec![3; 16]]);
    assert_eq!(ids(b.sent()), [vec![2; 16]]);
    let e = &b.waiting()[0];
    assert_eq!(
        (e.version, e.sent_at, e.ttl_s, e.signal_type),
        (1, 1_793_000_000_001, 600, 2)
    );
    assert_eq!(e.from, vec![0xA; 32]);
    assert_eq!(e.ciphertext.len(), 200);
    assert_eq!(b.encode(), swift, "a downgrade reads what this writes");
}
