// SPDX-License-Identifier: AGPL-3.0-only
use super::*;
use crate::testing::{HOUR, MIN, NOW, Utc, mac, phone};

fn on_mac(input: LinkInput) -> LinkLook {
    look(&input, &mac(), NOW, &Utc)
}

fn on_phone(input: LinkInput) -> LinkLook {
    look(&input, &phone(), NOW, &Utc)
}

fn base() -> LinkInput {
    LinkInput::new()
}

#[test]
fn each_path_has_its_own_line_icon_and_words() {
    let wifi = on_mac(LinkInput {
        route: Some(Path::Lan),
        ..base()
    });
    assert_eq!(
        (wifi.kind, wifi.line, wifi.icon),
        (LinkKind::SameWifi, Line::Solid, Some(Icon::SameWifi))
    );
    assert_eq!(
        (wifi.short(), wifi.sentence()),
        ("Connected".into(), "Connected · Same Wi‑Fi".into())
    );
    let tor = on_mac(LinkInput {
        route: Some(Path::Tor),
        ..base()
    });
    assert_eq!((tor.line, tor.icon), (Line::Dashed, Some(Icon::ThroughTor)));
    assert_eq!(tor.sentence(), "Connected · Through Tor");
    for l in [wifi, tor] {
        assert!(
            l.connected && l.detail.unwrap().starts_with(&l.label),
            "clicking the icon names the path"
        );
    }
}

#[test]
fn the_words_name_the_other_device() {
    let wifi = LinkInput {
        route: Some(Path::Lan),
        ..base()
    };
    assert!(
        on_mac(wifi)
            .detail
            .unwrap()
            .contains("straight to your phone")
    );
    assert!(
        on_phone(wifi)
            .detail
            .unwrap()
            .contains("straight to your Mac")
    );
    assert_eq!(
        on_phone(base()).label,
        "Offline · will send when your Mac is back"
    );
    assert_eq!(
        on_mac(base()).label,
        "Offline · will send when your phone is back"
    );
}

#[test]
fn not_connected_is_broken_and_says_why() {
    let none = on_mac(LinkInput {
        lan_allowed: false,
        tor_allowed: false,
        ..base()
    });
    assert_eq!(
        none.label,
        "Your phone and this Mac have no connection type on in common"
    );
    assert_eq!(
        on_phone(LinkInput {
            lan_allowed: false,
            tor_allowed: false,
            ..base()
        })
        .label,
        "Your Mac and this phone have no connection type on in common"
    );
    assert_eq!(
        on_mac(LinkInput {
            tor_allowed: false,
            ..base()
        })
        .kind,
        LinkKind::WaitingOnWifi
    );
    assert_eq!(on_mac(base()).kind, LinkKind::Offline);
    for l in [
        none,
        on_mac(LinkInput {
            tor_allowed: false,
            ..base()
        }),
        on_mac(base()),
    ] {
        assert!(l.line == Line::Broken && l.icon.is_none() && !l.connected);
        assert_eq!(l.sentence(), l.label);
        assert_eq!(l.short(), l.label);
        assert!(
            l.detail.is_some(),
            "every reason on the computer can be clicked: {}",
            l.label
        );
    }
    assert_eq!(
        on_phone(base()).detail,
        None,
        "the phone has no battery-saving story to tell"
    );
}

#[test]
fn tor_says_why_it_cant_connect() {
    let blocked = |b| {
        on_mac(LinkInput {
            tor: Tor::Blocked(b),
            ..base()
        })
    };
    assert_eq!(
        blocked(TorBlock::Filtered).label,
        "Tor is blocked on this network"
    );
    assert_eq!(blocked(TorBlock::Other).kind, LinkKind::TorBlocked);
    assert_eq!(
        blocked(TorBlock::ClockSkewed).label,
        "Tor needs the correct time. Check your clock"
    );
    assert!(
        blocked(TorBlock::ClockSkewed)
            .detail
            .unwrap()
            .contains("this Mac's clock")
    );
    assert_eq!(blocked(TorBlock::Offline).kind, LinkKind::Offline);
    assert_eq!(
        on_phone(LinkInput {
            tor: Tor::Failed,
            ..base()
        })
        .label,
        "Tor couldn't start on this phone"
    );
    assert_eq!(
        on_mac(LinkInput {
            tor: Tor::Failed,
            ..base()
        })
        .label,
        "Tor couldn't start on this Mac"
    );
    assert_eq!(
        on_mac(LinkInput {
            tor: Tor::Starting,
            ..base()
        })
        .kind,
        LinkKind::ConnectingTor
    );
    assert_eq!(
        on_mac(LinkInput {
            tor: Tor::Ready,
            ..base()
        })
        .kind,
        LinkKind::Offline
    );
    let tor_off_wifi_only = LinkInput {
        tor_allowed: false,
        tor: Tor::Blocked(TorBlock::Filtered),
        ..base()
    };
    assert_eq!(
        on_mac(tor_off_wifi_only).kind,
        LinkKind::WaitingOnWifi,
        "Tor switched off: not its problem"
    );
    let wifi_up = LinkInput {
        route: Some(Path::Lan),
        tor: Tor::Blocked(TorBlock::Filtered),
        ..base()
    };
    assert_eq!(
        on_mac(wifi_up).kind,
        LinkKind::SameWifi,
        "Same Wi‑Fi still works"
    );
}

#[test]
fn tor_off_is_connecting_on_the_computer_and_offline_on_the_phone() {
    let off = LinkInput {
        tor: Tor::Off,
        ..base()
    };
    let connecting = on_mac(off);
    assert_eq!(
        (connecting.kind, connecting.line, connecting.icon),
        (
            LinkKind::ConnectingTor,
            Line::Dotted,
            Some(Icon::ThroughTor)
        )
    );
    assert_eq!(on_phone(off).kind, LinkKind::Offline);
}

#[test]
fn a_reconnect_shows_until_a_path_is_up_but_never_hides_a_reason() {
    let asked = on_mac(LinkInput {
        reconnect: Some(Reconnect::Asked),
        ..base()
    });
    assert_eq!(
        (asked.kind, asked.line, asked.sentence()),
        (LinkKind::Reconnecting, Line::Dotted, "Reconnecting".into())
    );
    let out = on_mac(LinkInput {
        reconnect: Some(Reconnect::PeerUnreachable),
        ..base()
    });
    assert_eq!(out.label, "Turn on your phone's screen to connect");
    assert!(
        out.detail
            .unwrap()
            .starts_with("This Mac started its side again. Your phone can't be reached")
    );
    assert_eq!(
        on_mac(LinkInput {
            route: Some(Path::Lan),
            reconnect: Some(Reconnect::Asked),
            ..base()
        })
        .kind,
        LinkKind::SameWifi
    );
    let blocked = LinkInput {
        tor: Tor::Blocked(TorBlock::Filtered),
        reconnect: Some(Reconnect::Asked),
        ..base()
    };
    assert_eq!(on_mac(blocked).kind, LinkKind::TorBlocked);
    let none = LinkInput {
        lan_allowed: false,
        tor_allowed: false,
        reconnect: Some(Reconnect::Asked),
        ..base()
    };
    assert_eq!(on_mac(none).kind, LinkKind::NoSharedPath);
}

#[test]
fn the_phone_says_when_its_idle() {
    let idle = on_phone(LinkInput {
        idle: true,
        ..base()
    });
    assert_eq!(
        idle.label,
        "Paired · connects when there's something to sync"
    );
    assert_eq!(
        on_phone(LinkInput {
            idle: true,
            reconnect: Some(Reconnect::Asked),
            ..base()
        })
        .kind,
        LinkKind::Reconnecting
    );
}

#[test]
fn a_pause_comes_first_and_says_until_when() {
    let there = |until_ms| {
        on_mac(LinkInput {
            route: Some(Path::Lan),
            pause: Some(Pause {
                until_ms,
                here: false,
            }),
            ..base()
        })
    };
    let paused = there(NOW + 2 * HOUR + 44 * MIN);
    assert_eq!(
        (paused.kind, paused.line, paused.icon, paused.connected),
        (LinkKind::Paused, Line::Broken, Some(Icon::Paused), false)
    );
    assert_eq!(paused.label, "Paused on your phone until 14:44");
    assert!(
        paused
            .detail
            .unwrap()
            .contains("Only your phone can resume it")
    );
    assert_eq!(
        there(UNTIL_RESUMED).label,
        "Paused on your phone until you resume it there"
    );
    assert_eq!(there(NOW).kind, LinkKind::SameWifi, "over: back to normal");
    let here = |until_ms| {
        on_phone(LinkInput {
            pause: Some(Pause {
                until_ms,
                here: true,
            }),
            ..base()
        })
    };
    assert_eq!(here(NOW + 24 * HOUR).label, "Paused · until tomorrow 12:00");
    assert_eq!(here(UNTIL_RESUMED).label, "Paused · until you resume");
    assert!(here(UNTIL_RESUMED).paused_here && !there(UNTIL_RESUMED).paused_here);
    let reconnecting = LinkInput {
        pause: Some(Pause {
            until_ms: UNTIL_RESUMED,
            here: false,
        }),
        reconnect: Some(Reconnect::Asked),
        ..base()
    };
    assert_eq!(
        on_mac(reconnecting).icon,
        Some(Icon::Paused),
        "a pause beats a reconnect"
    );
}

#[test]
fn the_computer_says_when_the_phone_was_last_here() {
    let away = on_mac(base());
    assert_eq!(
        status_line(&away, &mac(), Some(NOW - 20_000), NOW, &Utc),
        "Last seen just now"
    );
    assert_eq!(
        status_line(&away, &mac(), Some(NOW - 12 * MIN), NOW, &Utc),
        "Last seen 12 min ago"
    );
    assert_eq!(
        status_line(&away, &mac(), None, NOW, &Utc),
        away.label,
        "never seen this run: the reason"
    );
    assert_eq!(
        hero_line(&away, &mac(), Some(NOW - 20_000), NOW, &Utc),
        "Last seen just now · will send when it's back"
    );
    let wifi = on_mac(LinkInput {
        route: Some(Path::Lan),
        ..base()
    });
    assert_eq!(
        status_line(&wifi, &mac(), Some(NOW), NOW, &Utc),
        "Connected",
        "the icon beside it names the path"
    );
    let blocked = on_mac(LinkInput {
        tor: Tor::Blocked(TorBlock::Filtered),
        ..base()
    });
    assert_eq!(
        hero_line(&blocked, &mac(), Some(NOW), NOW, &Utc),
        blocked.label,
        "a reason is never hidden behind a time"
    );
}

#[test]
fn the_footnote_explains_why_the_last_connection_stays_on() {
    assert_eq!(
        connections_footnote(true, false, &mac()),
        "Your phone connects only on this Mac's network. Turn on Tor to reach it anywhere."
    );
    assert_eq!(
        connections_footnote(false, true, &mac()),
        "Through Tor even at home. Same Wi‑Fi is faster for big files."
    );
    assert_eq!(
        connections_footnote(true, true, &mac()),
        "One always stays on. The encryption is the same on both."
    );
}

#[test]
fn no_label_trails_off() {
    let inputs = [
        base(),
        LinkInput {
            route: Some(Path::Lan),
            ..base()
        },
        LinkInput {
            route: Some(Path::Tor),
            ..base()
        },
        LinkInput {
            lan_allowed: false,
            tor_allowed: false,
            ..base()
        },
        LinkInput {
            tor_allowed: false,
            ..base()
        },
        LinkInput {
            tor: Tor::Blocked(TorBlock::Filtered),
            ..base()
        },
        LinkInput {
            tor: Tor::Blocked(TorBlock::ClockSkewed),
            ..base()
        },
        LinkInput {
            tor: Tor::Failed,
            ..base()
        },
        LinkInput {
            tor: Tor::Starting,
            ..base()
        },
        LinkInput {
            reconnect: Some(Reconnect::Asked),
            ..base()
        },
        LinkInput {
            reconnect: Some(Reconnect::PeerUnreachable),
            ..base()
        },
        LinkInput {
            idle: true,
            ..base()
        },
        LinkInput {
            pause: Some(Pause {
                until_ms: UNTIL_RESUMED,
                here: true,
            }),
            ..base()
        },
        LinkInput {
            pause: Some(Pause {
                until_ms: UNTIL_RESUMED,
                here: false,
            }),
            ..base()
        },
    ];
    for input in inputs {
        for l in [on_mac(input), on_phone(input)] {
            for words in [
                Some(l.label.clone()),
                l.detail.clone(),
                Some(l.short()),
                Some(l.sentence()),
            ]
            .into_iter()
            .flatten()
            {
                assert!(!words.contains('…') && !words.ends_with("..."), "{words}");
            }
        }
    }
}
