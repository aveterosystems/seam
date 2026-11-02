// SPDX-License-Identifier: AGPL-3.0-only
//! What every screen shows (seam-looks, decision 0015), for the Mac and Android. Plain data in,
//! plain data out; the app passes its names for the two devices and its locale's time words.
//! Protobuf messages cross as bytes. Names carry a `Look` prefix where a platform already uses
//! the plain one (Compose's `Icon`).

use std::sync::Arc;

use prost::Message;
use seam_core::proto::v1::{NotificationMode, NotificationSettings, Status};
use seam_looks::{self as looks, link};

use crate::{PeerPath, TorBlock, TorState};

/// The app's own clock and calendar words, in the user's locale and time zone.
#[uniffi::export(with_foreign)]
pub trait TimeWords: Send + Sync {
    /// "14:02"
    fn time(&self, ms: i64) -> String;
    /// "3 Nov"
    fn date(&self, ms: i64) -> String;
    /// Calendar days from `ms` to `now_ms`: 0 the same day, 1 yesterday, -1 tomorrow.
    fn days_between(&self, ms: i64, now_ms: i64) -> i32;
}

struct AppTime(Arc<dyn TimeWords>);

impl looks::TimeWords for AppTime {
    fn time(&self, ms: i64) -> String {
        self.0.time(ms)
    }
    fn date(&self, ms: i64) -> String {
        self.0.date(ms)
    }
    fn days_between(&self, ms: i64, now_ms: i64) -> i32 {
        self.0.days_between(ms, now_ms)
    }
}

#[derive(uniffi::Enum)]
pub enum LookSide {
    Phone,
    Computer,
}

/// How the app names itself ("this Mac") and the other device ("your phone").
#[derive(uniffi::Record)]
pub struct LookNames {
    pub side: LookSide,
    pub this: String,
    pub peer: String,
}

impl From<&LookNames> for looks::Names {
    fn from(n: &LookNames) -> Self {
        let side = match n.side {
            LookSide::Phone => looks::Side::Phone,
            LookSide::Computer => looks::Side::Computer,
        };
        looks::Names::new(side, n.this.clone(), n.peer.clone())
    }
}

/// An icon as a meaning; each app maps it to its own icon set.
#[derive(Debug, PartialEq, uniffi::Enum)]
pub enum LookIcon {
    SameWifi,
    ThroughTor,
    Paused,
    Battery { level: u8, charging: bool },
    Wifi,
    MobileSignal { fraction: f32 },
    Storage,
    DoNotDisturb,
    Notifications,
    NotificationsOff,
}

impl From<looks::Icon> for LookIcon {
    fn from(i: looks::Icon) -> Self {
        use looks::Icon as I;
        match i {
            I::SameWifi => Self::SameWifi,
            I::ThroughTor => Self::ThroughTor,
            I::Paused => Self::Paused,
            I::Battery { level, charging } => Self::Battery { level, charging },
            I::Wifi => Self::Wifi,
            I::MobileSignal { fraction } => Self::MobileSignal { fraction },
            I::Storage => Self::Storage,
            I::DoNotDisturb => Self::DoNotDisturb,
            I::Notifications => Self::Notifications,
            I::NotificationsOff => Self::NotificationsOff,
        }
    }
}

#[derive(Debug, PartialEq, uniffi::Enum)]
pub enum LookLine {
    Solid,
    Dashed,
    Dotted,
    Broken,
}

#[derive(Debug, PartialEq, uniffi::Enum)]
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

#[derive(uniffi::Enum)]
pub enum LinkReconnect {
    Asked,
    PeerUnreachable,
}

/// What the link look is made from.
#[derive(uniffi::Record)]
pub struct LinkInput {
    pub route: Option<PeerPath>,
    pub lan_allowed: bool,
    pub tor_allowed: bool,
    pub tor: TorState,
    /// Unix ms; i64::MAX for until resumed.
    pub paused_until_ms: Option<i64>,
    /// The phone showing its own pause.
    pub paused_here: bool,
    pub reconnect: Option<LinkReconnect>,
    pub idle: bool,
}

/// The link, ready to draw.
#[derive(Debug, PartialEq, uniffi::Record)]
pub struct LinkLook {
    pub kind: LinkKind,
    pub line: LookLine,
    pub icon: Option<LookIcon>,
    pub label: String,
    pub connected: bool,
    pub paused_here: bool,
    pub detail: Option<String>,
    /// Beside the icon: "Connected", or the reason.
    pub short: String,
    /// In full, for screen readers: "Connected · Same Wi‑Fi".
    pub sentence: String,
    /// Under the other device's name: "Last seen 12 min ago" on the computer when it knows.
    pub status_line: String,
    /// The same with room to say what happens next.
    pub hero_line: String,
}

#[uniffi::export]
pub fn link_look(
    input: LinkInput,
    names: LookNames,
    last_seen_ms: Option<i64>,
    now_ms: i64,
    time: Arc<dyn TimeWords>,
) -> LinkLook {
    let names = looks::Names::from(&names);
    let time = AppTime(time);
    let tor = match input.tor {
        TorState::Off => link::Tor::Off,
        TorState::Starting { .. } => link::Tor::Starting,
        TorState::Ready => link::Tor::Ready,
        TorState::Failed => link::Tor::Failed,
        TorState::Blocked { reason } => link::Tor::Blocked(match reason {
            TorBlock::Offline => link::TorBlock::Offline,
            TorBlock::Filtered => link::TorBlock::Filtered,
            TorBlock::ClockSkewed => link::TorBlock::ClockSkewed,
            TorBlock::Other => link::TorBlock::Other,
        }),
    };
    let i = link::LinkInput {
        route: input.route.map(|p| match p {
            PeerPath::Lan => link::Path::Lan,
            PeerPath::Tor => link::Path::Tor,
        }),
        lan_allowed: input.lan_allowed,
        tor_allowed: input.tor_allowed,
        tor,
        pause: input.paused_until_ms.map(|until_ms| link::Pause {
            until_ms,
            here: input.paused_here,
        }),
        reconnect: input.reconnect.map(|r| match r {
            LinkReconnect::Asked => link::Reconnect::Asked,
            LinkReconnect::PeerUnreachable => link::Reconnect::PeerUnreachable,
        }),
        idle: input.idle,
    };
    let l = link::look(&i, &names, now_ms, &time);
    LinkLook {
        status_line: link::status_line(&l, &names, last_seen_ms, now_ms, &time),
        hero_line: link::hero_line(&l, &names, last_seen_ms, now_ms, &time),
        short: l.short(),
        sentence: l.sentence(),
        kind: kind(l.kind),
        line: match l.line {
            link::Line::Solid => LookLine::Solid,
            link::Line::Dashed => LookLine::Dashed,
            link::Line::Dotted => LookLine::Dotted,
            link::Line::Broken => LookLine::Broken,
        },
        icon: l.icon.map(Into::into),
        label: l.label,
        connected: l.connected,
        paused_here: l.paused_here,
        detail: l.detail,
    }
}

fn kind(k: link::LinkKind) -> LinkKind {
    use link::LinkKind as K;
    match k {
        K::SameWifi => LinkKind::SameWifi,
        K::ThroughTor => LinkKind::ThroughTor,
        K::Paused => LinkKind::Paused,
        K::NoSharedPath => LinkKind::NoSharedPath,
        K::TorClock => LinkKind::TorClock,
        K::TorBlocked => LinkKind::TorBlocked,
        K::TorFailed => LinkKind::TorFailed,
        K::Reconnecting => LinkKind::Reconnecting,
        K::WakePeer => LinkKind::WakePeer,
        K::Idle => LinkKind::Idle,
        K::WaitingOnWifi => LinkKind::WaitingOnWifi,
        K::ConnectingTor => LinkKind::ConnectingTor,
        K::Offline => LinkKind::Offline,
    }
}

/// Under the connection switches: why the last one on can't be turned off.
#[uniffi::export]
pub fn connections_footnote(lan_on: bool, tor_on: bool, names: LookNames) -> String {
    link::connections_footnote(lan_on, tor_on, &(&names).into())
}

/// The other device's battery, network, storage and Do Not Disturb, ready to draw.
#[derive(Debug, PartialEq, uniffi::Record)]
pub struct DeviceStatusLook {
    pub percent: u32,
    pub charging: bool,
    pub low: bool,
    pub battery: String,
    pub battery_icon: LookIcon,
    pub battery_detail: String,
    pub network: Option<String>,
    pub network_icon: Option<LookIcon>,
    pub storage: Option<String>,
    pub storage_detail: Option<String>,
    pub dnd: bool,
    pub stale: bool,
    pub meta: Option<String>,
    pub spoken: String,
}

/// From a seam.v1.Status; None if the bytes aren't one.
#[uniffi::export]
pub fn device_status_look(
    status: Vec<u8>,
    connected: bool,
    at_ms: Option<i64>,
    same_wifi: bool,
    time: Arc<dyn TimeWords>,
) -> Option<DeviceStatusLook> {
    let s = Status::decode(status.as_slice()).ok()?;
    let l = looks::device_status::look(&s, connected, at_ms, same_wifi, &AppTime(time));
    Some(DeviceStatusLook {
        percent: l.percent,
        charging: l.charging,
        low: l.low,
        battery: l.battery,
        battery_icon: l.battery_icon.into(),
        battery_detail: l.battery_detail,
        network: l.network,
        network_icon: l.network_icon.map(Into::into),
        storage: l.storage,
        storage_detail: l.storage_detail,
        dnd: l.dnd,
        stale: l.stale,
        meta: l.meta,
        spoken: l.spoken,
    })
}

#[derive(Debug, PartialEq, uniffi::Enum)]
pub enum NotificationsTileMode {
    Waiting,
    On,
    Paused,
    Off,
}

#[derive(Debug, PartialEq, uniffi::Record)]
pub struct NotificationsTile {
    pub mode: NotificationsTileMode,
    pub subtitle: String,
    pub icon: LookIcon,
}

fn settings(bytes: &[u8]) -> NotificationSettings {
    NotificationSettings::decode(bytes).unwrap_or_default()
}

/// The computer's Notifications tile, from the phone's seam.v1.NotificationSettings (None until
/// it has sent them).
#[uniffi::export]
pub fn notifications_tile(
    settings_bytes: Option<Vec<u8>>,
    showing: u32,
    now_ms: i64,
    time: Arc<dyn TimeWords>,
) -> NotificationsTile {
    let s = settings_bytes.map(|b| settings(&b));
    let t = looks::notifications::tile(s.as_ref(), showing as usize, now_ms, &AppTime(time));
    NotificationsTile {
        mode: match t.mode {
            looks::notifications::TileMode::Waiting => NotificationsTileMode::Waiting,
            looks::notifications::TileMode::On => NotificationsTileMode::On,
            looks::notifications::TileMode::Paused => NotificationsTileMode::Paused,
            looks::notifications::TileMode::Off => NotificationsTileMode::Off,
        },
        subtitle: t.subtitle,
        icon: t.icon.into(),
    }
}

/// On, Sender only or Off, for a seam.v1.NotificationMode value.
#[uniffi::export]
pub fn notification_mode_word(mode: i32) -> String {
    looks::notifications::mode_word(NotificationMode::try_from(mode).unwrap_or_default()).into()
}

#[uniffi::export]
pub fn notification_mode_explanation(mode: i32, can_reply: bool, names: LookNames) -> String {
    looks::notifications::mode_explanation(
        NotificationMode::try_from(mode).unwrap_or_default(),
        can_reply,
        &(&names).into(),
    )
}

#[uniffi::export]
pub fn sender_only_footnote() -> String {
    looks::notifications::SENDER_ONLY_FOOTNOTE.into()
}

/// "6 of 10 on".
#[uniffi::export]
pub fn notification_apps_on(settings_bytes: Vec<u8>) -> String {
    looks::notifications::apps_on(&settings(&settings_bytes).apps)
}

#[uniffi::export]
pub fn notification_apps_on_count(settings_bytes: Vec<u8>) -> u32 {
    looks::notifications::apps_on_count(&settings(&settings_bytes).apps) as u32
}

/// The phone's home line: "On · 6 apps", or "Off".
#[uniffi::export]
pub fn notifications_phone_short(settings_bytes: Vec<u8>) -> String {
    let s = settings(&settings_bytes);
    looks::notifications::phone_short(s.enabled, &s.apps)
}

/// The phone's master card: "6 of 10 apps".
#[uniffi::export]
pub fn notifications_phone_count(settings_bytes: Vec<u8>) -> String {
    looks::notifications::phone_app_count(&settings(&settings_bytes).apps)
}

/// The phone's Settings line: "6 of 10 apps · Sender only for Monzo".
#[uniffi::export]
pub fn notifications_phone_long(settings_bytes: Vec<u8>) -> String {
    let s = settings(&settings_bytes);
    looks::notifications::phone_long(s.enabled, &s.apps)
}

/// The clipboard tile: Syncing, On or Off.
#[uniffi::export]
pub fn clipboard_subtitle(sync_on: bool, connected: bool) -> String {
    looks::clipboard::subtitle(sync_on, connected).into()
}

/// "112 GB".
#[uniffi::export]
pub fn size_words(bytes: u64) -> String {
    looks::words::size(bytes)
}

/// When a pause ends: "until 14:44", "until tomorrow 14:44", "until 3 Nov 14:44", or "until you
/// resume" for i64::MAX.
#[uniffi::export]
pub fn until_words(until_ms: i64, now_ms: i64, time: Arc<dyn TimeWords>) -> String {
    let until = (until_ms != looks::link::UNTIL_RESUMED).then_some(until_ms);
    looks::words::until(until, now_ms, &AppTime(time)).unwrap_or_else(|| "until you resume".into())
}

/// The queue bar: "3 waiting".
#[uniffi::export]
pub fn waiting_words(count: u32) -> String {
    looks::words::waiting(count)
}

/// "12 min ago", "today at 14:02"; `just_now` for under a minute ("just now", or "now").
#[uniffi::export]
pub fn ago_words(at_ms: i64, now_ms: i64, just_now: String, time: Arc<dyn TimeWords>) -> String {
    looks::words::ago(at_ms, now_ms, &AppTime(time), &just_now)
}

#[cfg(test)]
mod tests {
    use super::*;

    struct Utc;
    impl TimeWords for Utc {
        fn time(&self, ms: i64) -> String {
            let m = ms.rem_euclid(86_400_000) / 60_000;
            format!("{:02}:{:02}", m / 60, m % 60)
        }
        fn date(&self, _: i64) -> String {
            "a day".into()
        }
        fn days_between(&self, ms: i64, now_ms: i64) -> i32 {
            (now_ms.div_euclid(86_400_000) - ms.div_euclid(86_400_000)) as i32
        }
    }

    fn mac() -> LookNames {
        LookNames {
            side: LookSide::Computer,
            this: "this Mac".into(),
            peer: "your phone".into(),
        }
    }

    // The bridge only converts: one pass through each export proves the wiring
    #[test]
    fn the_bridge_passes_looks_through() {
        let input = LinkInput {
            route: Some(PeerPath::Lan),
            lan_allowed: true,
            tor_allowed: true,
            tor: TorState::Ready,
            paused_until_ms: None,
            paused_here: false,
            reconnect: None,
            idle: false,
        };
        let l = link_look(input, mac(), None, 0, Arc::new(Utc));
        assert_eq!(
            (l.kind, l.icon, l.short.as_str(), l.sentence.as_str()),
            (
                LinkKind::SameWifi,
                Some(LookIcon::SameWifi),
                "Connected",
                "Connected · Same Wi‑Fi"
            )
        );
        let away = LinkInput {
            route: None,
            lan_allowed: true,
            tor_allowed: true,
            tor: TorState::Blocked {
                reason: TorBlock::Offline,
            },
            paused_until_ms: None,
            paused_here: false,
            reconnect: None,
            idle: false,
        };
        assert_eq!(
            link_look(away, mac(), Some(-720_000), 0, Arc::new(Utc)).status_line,
            "Last seen 12 min ago"
        );
        let status = Status {
            battery_pct: 82,
            storage_free_bytes: 112_000_000_000,
            storage_total_bytes: 256_000_000_000,
            ..Default::default()
        };
        let d =
            device_status_look(status.encode_to_vec(), true, None, false, Arc::new(Utc)).unwrap();
        assert_eq!(
            (d.battery.as_str(), d.storage.as_deref()),
            ("82%", Some("112 GB"))
        );
        assert_eq!(
            notifications_tile(None, 0, 0, Arc::new(Utc)).subtitle,
            "Waiting for phone"
        );
        assert_eq!(
            notification_mode_word(NotificationMode::TitleOnly as i32),
            "Sender only"
        );
        assert_eq!(size_words(400_000_000), "400 MB");
    }
}
