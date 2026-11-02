// SPDX-License-Identifier: AGPL-3.0-only
//! Notifications: the modes' words, the computer's tile, and the counts both sides show. The
//! phone owns the settings; Sender only counts as on.

use seam_core::proto::v1::{NotificationApp, NotificationMode, NotificationSettings};

use crate::{Icon, Names, TimeWords};

/// On, Sender only or Off.
pub fn mode_word(mode: NotificationMode) -> &'static str {
    match mode {
        NotificationMode::On => "On",
        NotificationMode::TitleOnly => "Sender only",
        NotificationMode::Off | NotificationMode::Unspecified => "Off",
    }
}

/// What a mode sends, under its name.
pub fn mode_explanation(mode: NotificationMode, can_reply: bool, names: &Names) -> String {
    match mode {
        NotificationMode::On if can_reply => format!("Everything, and reply from {}", names.peer),
        NotificationMode::On => "Everything".into(),
        NotificationMode::TitleOnly => "Who it's from, never what it says".into(),
        NotificationMode::Off | NotificationMode::Unspecified => format!("Stays on {}", names.this),
    }
}

/// The footnote under the apps.
pub const SENDER_ONLY_FOOTNOTE: &str =
    "Sender only shows who a notification is from, never what it says.";

fn is_on(app: &NotificationApp) -> bool {
    matches!(
        app.mode(),
        NotificationMode::On | NotificationMode::TitleOnly
    )
}

pub fn apps_on_count(apps: &[NotificationApp]) -> usize {
    apps.iter().filter(|a| is_on(a)).count()
}

/// The computer's Apps header: "6 of 10 on".
pub fn apps_on(apps: &[NotificationApp]) -> String {
    format!("{} of {} on", apps_on_count(apps), apps.len())
}

/// The phone's home line: "On · 6 apps", or "Off".
pub fn phone_short(enabled: bool, apps: &[NotificationApp]) -> String {
    if enabled {
        format!("On · {} apps", apps_on_count(apps))
    } else {
        "Off".into()
    }
}

/// The phone's master card: "6 of 10 apps".
pub fn phone_app_count(apps: &[NotificationApp]) -> String {
    format!("{} of {} apps", apps_on_count(apps), apps.len())
}

/// The phone's Settings line: "6 of 10 apps · Sender only for Monzo", naming one or two apps.
pub fn phone_long(enabled: bool, apps: &[NotificationApp]) -> String {
    let sender_only: Vec<&str> = apps
        .iter()
        .filter(|a| a.mode() == NotificationMode::TitleOnly)
        .map(|a| a.app_name.as_str())
        .collect();
    let names = match sender_only.len() {
        0 => None,
        1 | 2 => Some(format!("Sender only for {}", sender_only.join(" and "))),
        n => Some(format!("Sender only for {n} apps")),
    };
    let count = phone_app_count(apps);
    [(!enabled).then(|| "Off".to_string()), Some(count), names]
        .into_iter()
        .flatten()
        .collect::<Vec<_>>()
        .join(" · ")
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TileMode {
    /// The phone hasn't sent its settings yet.
    Waiting,
    On,
    Paused,
    Off,
}

/// The computer's Notifications tile.
#[derive(Debug, Clone, PartialEq)]
pub struct TileLook {
    pub mode: TileMode,
    pub subtitle: String,
    pub icon: Icon,
}

pub fn tile(
    settings: Option<&NotificationSettings>,
    showing: usize,
    now_ms: i64,
    time: &dyn TimeWords,
) -> TileLook {
    let (mode, subtitle) = match settings {
        None => (TileMode::Waiting, "Waiting for phone".to_string()),
        Some(s) if s.enabled && s.paused_until > now_ms => {
            // The tile has room for a time or a word, not both
            let when = if time.days_between(now_ms, s.paused_until) == 0 {
                time.time(s.paused_until)
            } else {
                "tomorrow".into()
            };
            (TileMode::Paused, format!("Paused · {when}"))
        }
        Some(s) if s.enabled => (
            TileMode::On,
            if showing > 0 {
                format!("On · {showing} showing")
            } else {
                "On".into()
            },
        ),
        Some(_) => (TileMode::Off, "Off".into()),
    };
    let icon = if mode == TileMode::On {
        Icon::Notifications
    } else {
        Icon::NotificationsOff
    };
    TileLook {
        mode,
        subtitle,
        icon,
    }
}

#[cfg(test)]
mod tests;
