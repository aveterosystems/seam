// SPDX-License-Identifier: AGPL-3.0-only
//! The link between the devices, everywhere it's shown (docs/design/design-system.md, "The
//! stitch"): the line, the path's icon and the words always come from here, together.

use crate::{Icon, Names, Side, TimeWords, words};

/// The stitch's style.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Line {
    Solid,
    Dashed,
    Dotted,
    Broken,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Path {
    Lan,
    Tor,
}

/// Tor's state on this device.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Tor {
    Off,
    Starting,
    Ready,
    Blocked(TorBlock),
    Failed,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TorBlock {
    Offline,
    Filtered,
    ClockSkewed,
    Other,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Reconnect {
    /// The other device was asked to dial again.
    Asked,
    /// The computer can't reach the phone, so only its own side started again.
    PeerUnreachable,
}

/// Paused on the phone (only the phone pauses).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Pause {
    /// Unix ms; `UNTIL_RESUMED` for no end.
    pub until_ms: i64,
    /// The phone showing its own pause, rather than the computer showing the phone's.
    pub here: bool,
}

/// A pause with no end: both apps already use the largest value for it.
pub const UNTIL_RESUMED: i64 = i64::MAX;

/// What the link look is made from.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct LinkInput {
    /// The path while connected.
    pub route: Option<Path>,
    /// Which paths both devices allow.
    pub lan_allowed: bool,
    pub tor_allowed: bool,
    pub tor: Tor,
    pub pause: Option<Pause>,
    pub reconnect: Option<Reconnect>,
    /// The phone is paired but not connecting: nothing to sync.
    pub idle: bool,
}

impl LinkInput {
    /// Not connected, both paths allowed, Tor ready, nothing else going on.
    pub fn new() -> Self {
        Self {
            route: None,
            lan_allowed: true,
            tor_allowed: true,
            tor: Tor::Ready,
            pause: None,
            reconnect: None,
            idle: false,
        }
    }
}

impl Default for LinkInput {
    fn default() -> Self {
        Self::new()
    }
}

/// Which state the look shows, so an app can tell states apart without comparing words.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LinkKind {
    SameWifi,
    ThroughTor,
    Paused,
    NoSharedPath,
    TorClock,
    TorBlocked,
    TorFailed,
    Reconnecting,
    WakePeer,
    Idle,
    WaitingOnWifi,
    ConnectingTor,
    Offline,
}

#[derive(Debug, Clone, PartialEq)]
pub struct LinkLook {
    pub kind: LinkKind,
    pub line: Line,
    pub icon: Option<Icon>,
    /// The path's name while connected; otherwise what's happening.
    pub label: String,
    pub connected: bool,
    /// The phone showing its own pause (its screens turn tertiary).
    pub paused_here: bool,
    /// Why, and what happens next, or which path: shown on a click or tap.
    pub detail: Option<String>,
}

impl LinkLook {
    /// What's written beside the icon: "Connected" (the icon names the path), or the reason.
    pub fn short(&self) -> String {
        if self.connected {
            "Connected".into()
        } else {
            self.label.clone()
        }
    }

    /// The whole line in words, for screen readers and tooltips.
    pub fn sentence(&self) -> String {
        if self.connected {
            format!("Connected · {}", self.label)
        } else {
            self.label.clone()
        }
    }
}

/// The one place the link's state becomes a look. First match wins: a pause, the connected path,
/// then why there isn't one. Tor's trouble is always said, never shown as plain offline.
pub fn look(input: &LinkInput, names: &Names, now_ms: i64, time: &dyn TimeWords) -> LinkLook {
    let (peer, this) = (&names.peer, &names.this);
    let make = |kind, line, icon, label: String, detail: Option<String>| LinkLook {
        kind,
        line,
        icon,
        label,
        connected: matches!(kind, LinkKind::SameWifi | LinkKind::ThroughTor),
        paused_here: false,
        detail,
    };
    let broken =
        |kind, label: String, detail: Option<String>| make(kind, Line::Broken, None, label, detail);

    if let Some(p) = input.pause.filter(|p| p.until_ms > now_ms) {
        let until = (p.until_ms != UNTIL_RESUMED).then_some(p.until_ms);
        let when = words::until(until, now_ms, time);
        return if p.here {
            LinkLook {
                paused_here: true,
                ..make(
                    LinkKind::Paused,
                    Line::Broken,
                    Some(Icon::Paused),
                    format!(
                        "Paused · {}",
                        when.unwrap_or_else(|| "until you resume".into())
                    ),
                    None,
                )
            }
        } else {
            make(
                LinkKind::Paused,
                Line::Broken,
                Some(Icon::Paused),
                format!(
                    "Paused on {peer} {}",
                    when.unwrap_or_else(|| "until you resume it there".into())
                ),
                Some(format!(
                    "Nothing syncs until the pause ends. Only {peer} can resume it."
                )),
            )
        };
    }

    match input.route {
        Some(Path::Lan) => {
            return make(
                LinkKind::SameWifi,
                Line::Solid,
                Some(Icon::SameWifi),
                "Same Wi‑Fi".into(),
                Some(format!(
                    "Same Wi‑Fi: straight to {peer} on this network. Nothing leaves it."
                )),
            );
        }
        Some(Path::Tor) => {
            return make(
                LinkKind::ThroughTor,
                Line::Dashed,
                Some(Icon::ThroughTor),
                "Through Tor".into(),
                Some(format!(
                    "Through Tor: reaches {peer} anywhere and hides where you are."
                )),
            );
        }
        None => {}
    }

    if !input.lan_allowed && !input.tor_allowed {
        return broken(
            LinkKind::NoSharedPath,
            format!(
                "{} and {this} have no connection type on in common",
                names.peer_start()
            ),
            Some("Turn on the same connection type, Same Wi‑Fi or Tor, on both devices.".into()),
        );
    }
    if input.tor_allowed {
        match input.tor {
            Tor::Blocked(TorBlock::ClockSkewed) => {
                return broken(
                    LinkKind::TorClock,
                    "Tor needs the correct time. Check your clock".into(),
                    Some(format!(
                        "Tor won't connect while {this}'s clock is wrong. Turn on automatic time in its settings."
                    )),
                );
            }
            Tor::Blocked(TorBlock::Filtered | TorBlock::Other) => {
                return broken(
                    LinkKind::TorBlocked,
                    "Tor is blocked on this network".into(),
                    Some(format!(
                        "This network stops Tor connections. {} can still connect on the same Wi‑Fi.",
                        names.peer_start()
                    )),
                );
            }
            Tor::Failed => {
                return broken(
                    LinkKind::TorFailed,
                    format!("Tor couldn't start on {this}"),
                    None,
                );
            }
            _ => {}
        }
    }
    match input.reconnect {
        Some(Reconnect::Asked) => {
            return make(
                LinkKind::Reconnecting,
                Line::Dotted,
                None,
                "Reconnecting".into(),
                None,
            );
        }
        Some(Reconnect::PeerUnreachable) => {
            return broken(
                LinkKind::WakePeer,
                format!("Turn on {peer}'s screen to connect"),
                Some(format!(
                    "{} started its side again. {} can't be reached from here: it connects when its screen is on, or when it has something to send.",
                    names.this_start(),
                    names.peer_start()
                )),
            );
        }
        None => {}
    }
    if input.idle {
        return broken(
            LinkKind::Idle,
            "Paired · connects when there's something to sync".into(),
            None,
        );
    }
    if !input.tor_allowed {
        return broken(
            LinkKind::WaitingOnWifi,
            format!("Waiting for {peer} on the same Wi‑Fi"),
            Some(format!(
                "Only Same Wi‑Fi is on, so {peer} connects when it's on {this}'s network. Turn on Tor to connect from anywhere."
            )),
        );
    }
    // The computer's Tor starts with the app, so off means not up yet; the phone starts Tor on demand
    if input.tor == Tor::Starting || (input.tor == Tor::Off && names.side == Side::Computer) {
        return make(
            LinkKind::ConnectingTor,
            Line::Dotted,
            Some(Icon::ThroughTor),
            "Connecting through Tor".into(),
            None,
        );
    }
    let detail = (names.side == Side::Computer).then(|| format!(
        "To save battery, {peer} disconnects about a minute after its screen goes off. It's back when you unlock it or it has something to send. Copies from {this} wait up to 10 minutes."));
    broken(
        LinkKind::Offline,
        "Offline · will send when it's back".into(),
        detail,
    )
}

/// The status line under the other device's name. The computer says when the phone was last
/// here instead of plain offline; any other reason stays in words, never hidden behind a time.
pub fn status_line(
    look: &LinkLook,
    names: &Names,
    last_seen_ms: Option<i64>,
    now_ms: i64,
    time: &dyn TimeWords,
) -> String {
    match last_seen_ms {
        Some(at) if look.kind == LinkKind::Offline && names.side == Side::Computer => {
            format!("Last seen {}", words::ago(at, now_ms, time, "just now"))
        }
        _ => look.short(),
    }
}

/// The same, with room to say what happens next (Settings' phone card).
pub fn hero_line(
    look: &LinkLook,
    names: &Names,
    last_seen_ms: Option<i64>,
    now_ms: i64,
    time: &dyn TimeWords,
) -> String {
    let line = status_line(look, names, last_seen_ms, now_ms, time);
    if look.kind == LinkKind::Offline && last_seen_ms.is_some() && names.side == Side::Computer {
        format!("{line} · will send when it's back")
    } else {
        line
    }
}

/// Under the connection switches: why the last one on can't be turned off.
pub fn connections_footnote(lan_on: bool, tor_on: bool, names: &Names) -> String {
    match (lan_on, tor_on) {
        (true, false) => format!(
            "Only on the same network as {}. Turn on Tor to reach it anywhere.",
            names.peer
        ),
        (false, true) => "Through Tor even at home. Same Wi‑Fi is faster for big files.".into(),
        _ => "One always stays on. Both are equally encrypted.".into(),
    }
}

#[cfg(test)]
mod tests;
