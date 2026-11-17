// SPDX-License-Identifier: AGPL-3.0-only
//! What the screens show, made from the core's snapshot. The words and icons for each state come
//! from `seam-looks` (decision 0015), shared with the Mac and the phone; this only gives them
//! this computer's names and clock, and turns each icon meaning into an Adwaita icon
//! (docs/design/design-system.md, "Icons"). No widget decides a word or an icon itself.

use std::collections::HashMap;

use seam_computer::Event;
use seam_computer::computer::{Health, Snapshot};
use seam_computer::features::clipboard::Clip;
use seam_computer::pairing::{PairingFailure, PairingState};
use seam_computer::session::{Phone, Reconnect, Warning};
use seam_computer::tor::{TorBlock, TorState};
use seam_core::links::Path;
use seam_core::proto::v1::{Notification, NotificationSettings, Status};
use seam_looks::link::{self, LinkInput, Pause, Tor};
use seam_looks::notifications::{TileLook, TileMode};
use seam_looks::{DeviceStatusLook, Icon, LinkLook, Names, Side, TimeWords};

/// How this computer names itself and the phone.
pub fn names() -> Names {
    Names::new(Side::Computer, "this computer", "your phone")
}

/// The phone's own name, or "your phone" (capitalised to start a sentence).
pub fn phone_name(phone: Option<&Phone>, start: bool) -> String {
    match phone.map(|p| p.name.trim()).filter(|n| !n.is_empty()) {
        Some(n) => n.to_string(),
        None if start => "Your phone".into(),
        None => "your phone".into(),
    }
}

/// Everything the screens draw, in one place: the window, Preferences and the tray read only this.
#[derive(Debug, Clone, PartialEq)]
pub struct Model {
    pub health: Health,
    /// A startup or reset problem, in words.
    pub startup_error: Option<String>,
    pub phone: Option<Phone>,
    pub link_input: LinkInput,
    pub last_seen: Option<i64>,
    pub waiting: usize,
    pub paused: bool,
    pub pairing: PairingState,
    /// Something to show at the top of the window (a refused device, a save that failed).
    pub warning: Option<String>,
    pub wifi: bool,
    pub tor: bool,
    pub tor_state: TorState,
    pub reconnecting: Option<Reconnect>,
    pub clipboard_sync: bool,
    pub clips: Vec<Clip>,
    pub shown: Vec<Notification>,
    pub settings: Option<NotificationSettings>,
    pub icons_version: u64,
    pub arrivals: u64,
    pub status: Option<Status>,
    pub status_at: Option<i64>,
    /// Goes up with each opening of the saved data.
    pub opens: u64,
}

impl Default for Model {
    fn default() -> Self {
        Self {
            health: Health::Starting,
            startup_error: None,
            phone: None,
            link_input: LinkInput::new(),
            last_seen: None,
            waiting: 0,
            paused: false,
            pairing: PairingState::Idle,
            warning: None,
            wifi: true,
            tor: true,
            tor_state: TorState::Off,
            reconnecting: None,
            clipboard_sync: true,
            clips: Vec::new(),
            shown: Vec::new(),
            settings: None,
            icons_version: 0,
            arrivals: 0,
            status: None,
            status_at: None,
            opens: 0,
        }
    }
}

impl Model {
    /// The model after `s`. A starting snapshot changes nothing; a reset that fails says so.
    pub fn apply(&self, s: &Snapshot, resetting: bool) -> Model {
        let mut m = self.clone();
        m.opens = s.opens;
        match &s.health {
            Health::Starting => return m,
            Health::Ready => m.startup_error = None,
            Health::Unreadable => {
                m.startup_error = Some(
                    "Seam's keys on this computer can't be opened. Reset Seam to start again and pair your phone; the old data is kept aside."
                        .into(),
                )
            }
            Health::Failed { detail } => {
                m.startup_error = Some(if resetting {
                    format!("Seam couldn't reset: {detail}")
                } else {
                    format!("Seam couldn't start: {detail}")
                })
            }
        }
        m.health = s.health.clone();
        m.tor_state = s.tor_state;
        if let Some(session) = &s.session {
            m.phone = session.phone.clone();
            m.pairing = session.pairing.clone();
            m.last_seen = session.last_seen;
            m.waiting = session.waiting;
            m.paused = session.paused_until.is_some();
            m.wifi = session.wifi;
            m.tor = session.tor;
            m.reconnecting = session.reconnect;
            m.link_input = link_input(session, s.tor_state);
            m.warning = session.warning.map(warning_words);
        }
        if m.warning.is_none() && s.pair_again {
            m.warning = Some("Seam now connects through Tor. Pair your phone again.".into());
        }
        match &s.features {
            Some(f) => {
                m.clipboard_sync = f.clipboard.sync_on;
                m.clips = f.clipboard.clips.clone();
                m.shown = f.notifications.shown.clone();
                m.settings = f.notifications.settings.clone();
                m.icons_version = f.notifications.icons_version;
                m.arrivals = f.notifications.arrivals;
                m.status = f.status.status.clone();
                m.status_at = f.status.at;
            }
            None => {
                m.clips.clear();
                m.shown.clear();
            }
        }
        m
    }

    pub fn needs_reset(&self) -> bool {
        self.health == Health::Unreadable
    }

    pub fn link(&self, now_ms: i64) -> LinkLook {
        link::look(&self.link_input, &names(), now_ms, &SystemTime)
    }

    /// "Connected", "Last seen 12 min ago", or why not.
    pub fn status_line(&self, now_ms: i64) -> String {
        link::status_line(
            &self.link(now_ms),
            &names(),
            self.last_seen,
            now_ms,
            &SystemTime,
        )
    }

    /// The status line short enough for the sidebar's card: "Paused until 15:00" rather than the
    /// whole sentence (which is its tooltip).
    pub fn card_line(&self, now_ms: i64) -> String {
        match self.link_input.pause {
            Some(p) if self.link(now_ms).kind == seam_looks::LinkKind::Paused => {
                match seam_looks::words::until(
                    (p.until_ms != link::UNTIL_RESUMED).then_some(p.until_ms),
                    now_ms,
                    &SystemTime,
                ) {
                    Some(until) => format!("Paused {until}"),
                    None => "Paused".into(),
                }
            }
            _ => self.status_line(now_ms),
        }
    }

    /// What a page says at its top while the phone can't be reached: paused (its own choice) or
    /// away. `waiting`: what's held for it, said too. None while connected.
    pub fn page_status(&self, now_ms: i64) -> Option<String> {
        let look = self.link(now_ms);
        self.phone.as_ref()?;
        if look.connected {
            return None;
        }
        let name = self.phone_name(true);
        if look.kind == seam_looks::LinkKind::Paused {
            return Some(format!(
                "{name} is paused {}. Nothing comes or goes until then.",
                self.card_line(now_ms).trim_start_matches("Paused ")
            ));
        }
        Some(if self.waiting > 0 {
            format!(
                "{name} is away: {} send when it's back.",
                seam_looks::words::waiting(self.waiting as u32)
            )
        } else {
            format!("{name} is away. What you do here sends when it's back.")
        })
    }

    /// The same with room to say what happens next (the Phone page's header).
    pub fn hero_line(&self, now_ms: i64) -> String {
        link::hero_line(
            &self.link(now_ms),
            &names(),
            self.last_seen,
            now_ms,
            &SystemTime,
        )
    }

    pub fn phone_status(&self, now_ms: i64) -> Option<DeviceStatusLook> {
        let look = self.link(now_ms);
        let s = self.status.as_ref()?;
        Some(seam_looks::device_status::look(
            s,
            look.connected,
            self.status_at,
            look.kind == seam_looks::LinkKind::SameWifi,
            &SystemTime,
        ))
    }

    pub fn notifications_tile(&self, now_ms: i64) -> TileLook {
        seam_looks::notifications::tile(
            self.settings.as_ref(),
            self.shown.len(),
            now_ms,
            &SystemTime,
        )
    }

    pub fn phone_name(&self, start: bool) -> String {
        phone_name(self.phone.as_ref(), start)
    }

    /// What the window's Notifications page shows when there's nothing to list.
    pub fn notifications_empty(&self, now_ms: i64) -> Option<Empty> {
        let name = self.phone_name(true);
        self.phone.as_ref()?; // none: the window shows pairing instead
        if self.settings.as_ref().is_some_and(|s| !s.enabled) {
            return Some(Empty {
                icon: "notifications-disabled-symbolic",
                title: "Notifications Are Off".into(),
                detail: format!("{name} isn't sending notifications to this computer."),
                action: Some(("Turn On", EmptyAction::TurnOnNotifications)),
            });
        }
        if !self.shown.is_empty() {
            return None;
        }
        let tile = self.notifications_tile(now_ms);
        if tile.mode == TileMode::Paused {
            return Some(Empty {
                icon: "notifications-disabled-symbolic",
                title: "Notifications Are Paused".into(),
                detail: tile.subtitle,
                action: Some(("Resume Now", EmptyAction::ResumeNotifications)),
            });
        }
        if tile.mode == TileMode::Waiting || !self.link(now_ms).connected {
            return Some(Empty {
                icon: "preferences-system-notifications-symbolic",
                title: format!("Waiting for {name}"),
                detail: "Its notifications show here once it connects. Turn on its screen to connect now.".into(),
                action: None,
            });
        }
        Some(Empty {
            icon: "preferences-system-notifications-symbolic",
            title: "No Notifications".into(),
            detail: format!("New ones from {name} show here and as banners."),
            action: Some(("Choose Apps", EmptyAction::ChooseApps)),
        })
    }

    /// "Clear 3 notifications on this computer and Pixel 9?"
    pub fn clear_all_title(&self) -> String {
        let n = self.shown.len();
        let what = if n == 1 {
            "1 notification".to_string()
        } else {
            format!("{n} notifications")
        };
        if self.settings.as_ref().is_some_and(|s| !s.dismiss_both) {
            format!("Clear {what} on this computer?")
        } else {
            format!(
                "Clear {what} on this computer and {}?",
                self.phone_name(false)
            )
        }
    }

    /// The note under the notifications: whether clearing here reaches the phone.
    pub fn clear_note(&self) -> String {
        if self.settings.as_ref().is_some_and(|s| !s.dismiss_both) {
            format!(
                "Notifications you clear here stay on {}.",
                self.phone_name(false)
            )
        } else {
            format!(
                "Notifications you clear here are also cleared on {}.",
                self.phone_name(false)
            )
        }
    }
}

/// A page's empty state: an icon, what's going on, and the one thing to do about it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Empty {
    pub icon: &'static str,
    pub title: String,
    pub detail: String,
    pub action: Option<(&'static str, EmptyAction)>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum EmptyAction {
    TurnOnNotifications,
    ResumeNotifications,
    ChooseApps,
}

/// The link look's inputs, from the session and Tor's state.
pub fn link_input(session: &seam_computer::session::State, tor: TorState) -> LinkInput {
    LinkInput {
        route: session.route.map(|p| match p {
            Path::Lan => link::Path::Lan,
            Path::Tor => link::Path::Tor,
        }),
        lan_allowed: session.allowed.contains(&Path::Lan),
        tor_allowed: session.allowed.contains(&Path::Tor),
        tor: tor_look(tor),
        pause: session.paused_until.map(|until_ms| Pause {
            until_ms,
            here: false,
        }),
        reconnect: session.reconnect.map(|r| match r {
            Reconnect::Asked => link::Reconnect::Asked,
            _ => link::Reconnect::PeerUnreachable,
        }),
        idle: false,
    }
}

pub fn tor_look(t: TorState) -> Tor {
    match t {
        TorState::Off => Tor::Off,
        TorState::Starting(_) => Tor::Starting,
        TorState::Ready => Tor::Ready,
        TorState::Failed => Tor::Failed,
        TorState::Blocked(b) => Tor::Blocked(match b {
            TorBlock::Offline => link::TorBlock::Offline,
            TorBlock::Filtered => link::TorBlock::Filtered,
            TorBlock::ClockSkewed => link::TorBlock::ClockSkewed,
            TorBlock::Other => link::TorBlock::Other,
        }),
    }
}

pub fn pairing_failure_words(f: PairingFailure) -> &'static str {
    match f {
        PairingFailure::Unreachable => {
            "Couldn't reach your phone. Show a new code and scan it again."
        }
        PairingFailure::NotSaved => {
            "Seam couldn't save the pairing on this computer. Show a new code and scan it again."
        }
        PairingFailure::DidntFinish => "Pairing didn't finish. Show a new code and scan it again.",
    }
}

pub fn warning_words(w: Warning) -> String {
    match w {
        Warning::SaveFailed => "Seam couldn't save to disk. Check there's free space; messages wait until it can.".into(),
        Warning::UntrustedIdentity => {
            "A device tried to talk to this computer with keys that don't match your paired phone. It was ignored.".into()
        }
    }
}

/// What an arrival says, for screen readers. Never the contents.
pub fn announcement(e: &Event) -> Option<String> {
    match e {
        Event::ClipFromPhone => Some("Copied from your phone".into()),
        Event::NotificationFromPhone { app_name } => Some(format!("Notification from {app_name}")),
        Event::Problem(_) => None,
    }
}

/// The battery's Adwaita icon, filled to the level in tens: `charged` when full on the adapter.
pub fn battery_icon(percent: u32, charging: bool) -> String {
    let level = (percent.min(100) / 10) * 10;
    match (level, charging) {
        (100, true) => "battery-level-100-charged-symbolic".into(),
        (l, true) => format!("battery-level-{l}-charging-symbolic"),
        (l, false) => format!("battery-level-{l}-symbolic"),
    }
}

/// The Adwaita icon for each meaning (design-system.md, "Icons").
pub fn icon_name(icon: Icon) -> String {
    match icon {
        Icon::SameWifi | Icon::Wifi => "network-wireless-symbolic".into(),
        // Adwaita has nothing that reads as a globe: Seam ships its own (design-system.md, "Icons")
        Icon::ThroughTor => "seam-globe-symbolic".into(),
        Icon::Paused => "media-playback-pause-symbolic".into(),
        Icon::Battery { level, charging } => battery_icon(level as u32, charging),
        Icon::MobileSignal { fraction } => {
            let word = match fraction {
                f if f >= 0.9 => "excellent",
                f if f >= 0.6 => "good",
                f if f >= 0.35 => "ok",
                f if f > 0.0 => "weak",
                _ => "none",
            };
            format!("network-cellular-signal-{word}-symbolic")
        }
        Icon::Storage => "drive-harddisk-symbolic".into(),
        Icon::DoNotDisturb => "weather-clear-night-symbolic".into(),
        Icon::Cpu => "seam-cpu-symbolic".into(),
        Icon::Memory => "seam-memory-symbolic".into(),
        Icon::Notifications => "preferences-system-notifications-symbolic".into(),
        Icon::NotificationsOff => "notifications-disabled-symbolic".into(),
    }
}

/// This computer's clock and calendar, in the user's locale and time zone.
pub struct SystemTime;

fn local(ms: i64) -> Option<gtk::glib::DateTime> {
    gtk::glib::DateTime::from_unix_local(ms.div_euclid(1000)).ok()
}

impl TimeWords for SystemTime {
    fn time(&self, ms: i64) -> String {
        let Some(d) = local(ms) else {
            return String::new();
        };
        let format = match clock_24h() {
            Some(true) => "%H:%M",
            Some(false) => "%-l:%M %p",
            None => return d.format("%X").map(|s| trim_seconds(&s)).unwrap_or_default(),
        };
        d.format(format)
            .map(|s| s.trim().to_string())
            .unwrap_or_default()
    }

    fn date(&self, ms: i64) -> String {
        local(ms)
            .and_then(|d| d.format("%-d %b %Y").ok())
            .map(|s| s.to_string())
            .unwrap_or_default()
    }

    fn days_between(&self, ms: i64, now_ms: i64) -> i32 {
        let (Some(a), Some(b)) = (local(ms), local(now_ms)) else {
            return 0;
        };
        let day = |d: &gtk::glib::DateTime| {
            gtk::glib::DateTime::from_local(d.year(), d.month(), d.day_of_month(), 0, 0, 0.0).ok()
        };
        match (day(&a), day(&b)) {
            (Some(a), Some(b)) => (b.difference(&a).as_seconds() as f64 / 86_400.0).round() as i32,
            _ => 0,
        }
    }
}

impl SystemTime {
    /// "14 September 2026".
    pub fn date_long(&self, ms: i64) -> String {
        local(ms)
            .and_then(|d| d.format("%-d %B %Y").ok())
            .map(|s| s.to_string())
            .unwrap_or_default()
    }
}

/// The desktop's clock setting (GNOME's, which KDE's GTK settings mirror): None when there's
/// none to read, and the locale decides.
fn clock_24h() -> Option<bool> {
    let source = gtk::gio::SettingsSchemaSource::default()?;
    let schema = source.lookup("org.gnome.desktop.interface", true)?;
    if !schema.has_key("clock-format") {
        return None;
    }
    use gtk::gio::prelude::SettingsExt;
    let format = gtk::gio::Settings::new("org.gnome.desktop.interface").string("clock-format");
    Some(format != "12h")
}

/// The locale's time ("14:02:09", "2:02:09 PM") without its seconds.
fn trim_seconds(t: &str) -> String {
    let parts: Vec<&str> = t.splitn(3, ':').collect();
    if parts.len() == 3 {
        let rest: String = parts[2]
            .chars()
            .skip_while(|c| c.is_ascii_digit())
            .collect();
        format!("{}:{}{}", parts[0], parts[1], rest)
    } else {
        t.to_string()
    }
}

/// A phone app's icons by id, decoded once per `icons_version`.
pub type Icons = HashMap<String, Vec<u8>>;

#[cfg(test)]
mod tests;
