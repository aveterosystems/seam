// SPDX-License-Identifier: AGPL-3.0-only
//! Debug builds only: SEAM_DEMO=<state> shows made-up phone state, for looking at every screen
//! without a phone. Nothing is saved or sent: Seam never starts. Invented names, no real data.
//!
//! States: connected (default), tor, offline, paused, connecting, empty, off, unpaired, confirm,
//! error.

use seam_computer::computer::Health;
use seam_computer::features::clipboard::Clip;
use seam_computer::pairing::PairingState;
use seam_computer::session::Phone;
use seam_core::PairingRequest;
use seam_core::proto::v1::{
    Notification, NotificationApp, NotificationButton, NotificationMode, NotificationSettings,
    Status,
};
use seam_looks::link::{self, LinkInput, Pause};

use crate::model::{Icons, Model};

const MIN: i64 = 60_000;

pub fn model(state: &str) -> Model {
    let now = crate::ui::now_ms();
    let mut m = Model {
        health: Health::Ready,
        phone: Some(Phone {
            id: "demo".into(),
            name: "Pixel 9".into(),
            safety_code: "482 913".into(),
            paired_at: Some(1_789_000_000_000),
        }),
        tor_state: seam_computer::tor::TorState::Ready,
        settings: Some(settings()),
        shown: shown(now),
        clips: clips(now),
        status: Some(Status {
            battery_pct: 82,
            charging: true,
            network: "wifi".into(),
            storage_free_bytes: 112_000_000_000,
            storage_total_bytes: 256_000_000_000,
            power_mw: Some(18_000),
            minutes_to_full: Some(42),
            ..Default::default()
        }),
        status_at: Some(now - 12 * MIN),
        ..Model::default()
    };
    let mut input = LinkInput::new();
    input.route = Some(link::Path::Lan);
    match state {
        "tor" => input.route = Some(link::Path::Tor),
        "offline" => {
            input.route = None;
            m.last_seen = Some(now - 12 * MIN);
            m.waiting = 3;
            if let Some(s) = &mut m.status {
                s.charging = false;
                s.battery_pct = 12;
            }
        }
        "paused" => {
            input.pause = Some(Pause {
                until_ms: now + 90 * MIN,
                here: false,
            })
        }
        "connecting" => {
            input.route = None;
            input.tor = link::Tor::Starting;
            input.lan_allowed = false;
        }
        "empty" => {
            m.shown.clear();
            m.clips.clear();
        }
        "off" => {
            if let Some(s) = &mut m.settings {
                s.enabled = false;
            }
            m.shown.clear();
            m.clipboard_sync = false;
            m.clips.clear();
        }
        "unpaired" => {
            m.phone = None;
            m.pairing = PairingState::Waiting {
                qr: "seam://pair?v=1&k=DEMO-NOT-A-REAL-CODE-0123456789abcdef".into(),
                expires_at: now + 4 * MIN + 59_000,
            };
        }
        "confirm" => {
            m.phone = None;
            m.pairing = PairingState::Confirm(PairingRequest {
                peer_id: "demo".into(),
                device_name: "Pixel 9".into(),
                model: "Pixel 9".into(),
                safety_code: "482 913".into(),
            });
        }
        "error" => {
            // The saved data can't be opened: no phone is known, and pairing waits for the reset
            m.phone = None;
            m.health = Health::Unreadable;
            m.startup_error = Some(
                "Its keys on this computer can't be opened. Reset Seam to start again and pair your phone; the old data is kept aside.".into(),
            );
        }
        _ => {}
    }
    m.link_input = input;
    m
}

fn settings() -> NotificationSettings {
    let app = |id: &str, name: &str, mode: NotificationMode, reply: bool| NotificationApp {
        app_id: id.into(),
        app_name: name.into(),
        mode: mode as i32,
        can_reply: reply,
        ..Default::default()
    };
    NotificationSettings {
        enabled: true,
        dismiss_both: true,
        quiet_while_using_phone: false,
        apps: vec![
            app("chat", "Chat", NotificationMode::On, true),
            app("mail", "Mail", NotificationMode::TitleOnly, false),
            app("calendar", "Calendar", NotificationMode::On, false),
            app("bank", "Bank", NotificationMode::Off, false),
            app("delivery", "Parcels", NotificationMode::On, false),
        ],
        paused_until: 0,
    }
}

fn shown(now: i64) -> Vec<Notification> {
    vec![
        Notification {
            key: "1".into(),
            app_id: "chat".into(),
            app_name: "Chat".into(),
            title: "Ana Silva".into(),
            text: "Running ten minutes late, save me a seat".into(),
            posted_at: now - 2 * MIN,
            can_reply: true,
            buttons: vec![NotificationButton {
                id: "read".into(),
                title: "Mark as read".into(),
            }],
            ..Default::default()
        },
        Notification {
            key: "2".into(),
            app_id: "calendar".into(),
            app_name: "Calendar".into(),
            title: "Design review".into(),
            text: "15:00 – 15:30 · Room 4".into(),
            posted_at: now - 14 * MIN,
            ..Default::default()
        },
        Notification {
            key: "3".into(),
            app_id: "delivery".into(),
            app_name: "Parcels".into(),
            title: "Your parcel is out for delivery".into(),
            text: "Arriving between 13:00 and 15:00".into(),
            posted_at: now - 63 * MIN,
            ..Default::default()
        },
    ]
}

fn clips(now: i64) -> Vec<Clip> {
    vec![
        Clip {
            text: "https://example.com/tickets/4821".into(),
            at: now - 3 * MIN,
            from_phone: true,
        },
        Clip {
            text: "The meeting moved to Thursday at 10, same room.".into(),
            at: now - 25 * MIN,
            from_phone: false,
        },
        Clip {
            text: "Flat 4, 12 Orchard Lane".into(),
            at: now - 80 * MIN,
            from_phone: true,
        },
    ]
}

/// Plain coloured squares stand in for the phone's app icons.
pub fn icons() -> Icons {
    let mut out = Icons::new();
    for (id, rgb) in [
        ("chat", [46, 160, 103]),
        ("calendar", [66, 133, 244]),
        ("delivery", [230, 140, 40]),
        ("mail", [200, 70, 70]),
    ] {
        out.insert(id.into(), square_png(rgb));
    }
    out
}

/// A tiny solid PNG, built by hand (stored, uncompressed), so the demo needs no image crate.
fn square_png(rgb: [u8; 3]) -> Vec<u8> {
    let size = 48u32;
    let mut raw = Vec::new();
    let row: Vec<u8> = std::iter::once(0)
        .chain(rgb.iter().copied().cycle().take(size as usize * 3))
        .collect(); // no filter, then the pixels
    for _ in 0..size {
        raw.extend_from_slice(&row);
    }
    let mut z = vec![0x78, 0x01];
    for (i, chunk) in raw.chunks(65_535).enumerate() {
        let last = (i + 1) * 65_535 >= raw.len();
        z.push(last as u8);
        let len = chunk.len() as u16;
        z.extend_from_slice(&len.to_le_bytes());
        z.extend_from_slice(&(!len).to_le_bytes());
        z.extend_from_slice(chunk);
    }
    let (mut a, mut b) = (1u32, 0u32);
    for x in &raw {
        a = (a + *x as u32) % 65_521;
        b = (b + a) % 65_521;
    }
    z.extend_from_slice(&((b << 16) | a).to_be_bytes());
    let mut png = b"\x89PNG\r\n\x1a\n".to_vec();
    let mut chunk = |kind: &[u8], data: &[u8]| {
        png.extend_from_slice(&(data.len() as u32).to_be_bytes());
        let mut body = kind.to_vec();
        body.extend_from_slice(data);
        png.extend_from_slice(&body);
        png.extend_from_slice(&crc(&body).to_be_bytes());
    };
    let mut ihdr = Vec::new();
    ihdr.extend_from_slice(&size.to_be_bytes());
    ihdr.extend_from_slice(&size.to_be_bytes());
    ihdr.extend_from_slice(&[8, 2, 0, 0, 0]);
    chunk(b"IHDR", &ihdr);
    chunk(b"IDAT", &z);
    chunk(b"IEND", &[]);
    png
}

fn crc(data: &[u8]) -> u32 {
    let mut c = 0xFFFF_FFFFu32;
    for &b in data {
        c ^= b as u32;
        for _ in 0..8 {
            c = if c & 1 != 0 {
                0xEDB8_8320 ^ (c >> 1)
            } else {
                c >> 1
            };
        }
    }
    !c
}
