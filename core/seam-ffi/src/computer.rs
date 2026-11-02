// SPDX-License-Identifier: AGPL-3.0-only
//! The computer side for the Mac (seam_computer, decision 0014): one `Computer` runs the session,
//! its paths and features; the app supplies its OS through the foreign interfaces here and in each
//! feature's file, and draws what `ComputerListener` hears. Protobuf messages cross as their
//! bytes, which the app reads with its generated types. Nothing here logs.

use std::collections::HashMap;
use std::net::IpAddr;
use std::sync::Arc;

use seam_computer::computer::{self as computer, Health};
use seam_computer::pairing::{PairingFailure, PairingState};
use seam_computer::session::{Phone, Reconnect, State, Warning};
use seam_computer::{Event, Problem};

use crate::clipboard::{AppPasteboard, ClipboardView, Pasteboard};
use crate::notifications::{AppNotificationCentre, NotificationCentre, NotificationsView};
use crate::status::{AppBattery, Battery, StatusView};
use crate::{PairingRequest, PeerPath, TorState};

// The crate's own types, as UniFFI sees them: the same shape, checked by the compiler

#[uniffi::remote(Enum)]
pub enum Health {
    Starting,
    Ready,
    /// Saved data exists but can't be opened: offer a reset.
    Unreadable,
    Failed {
        detail: String,
    },
}

#[uniffi::remote(Enum)]
pub enum Reconnect {
    Asked,
    PhoneOffline,
}

#[uniffi::remote(Enum)]
pub enum Warning {
    SaveFailed,
    UntrustedIdentity,
}

#[uniffi::remote(Enum)]
pub enum PairingFailure {
    Unreachable,
    NotSaved,
    DidntFinish,
}

#[uniffi::remote(Enum)]
pub enum Problem {
    SaveFailed,
    SealFailed,
    UntrustedIdentity,
    DroppedEnvelope,
    PairingFailed,
    PairingRefused,
    ListenerFailed,
    StoreUnreadable,
}

/// For the app's log (codes only, never contents) or to announce.
#[uniffi::remote(Enum)]
pub enum Event {
    Problem(Problem),
    ClipFromPhone,
    NotificationFromPhone { app_name: String },
}

#[uniffi::remote(Record)]
pub struct Phone {
    pub id: String,
    pub name: String,
    pub safety_code: String,
    pub paired_at: Option<i64>,
}

// What the app supplies

/// The OS's secret store (the Keychain): it alone holds the key the state is sealed with.
#[uniffi::export(with_foreign)]
pub trait KeyStore: Send + Sync {
    /// The 32-byte key. When there's none, makes one only if `create`; otherwise None. Never
    /// replaces a key it can't read. An error is anything that may be temporary.
    fn storage_key(&self, create: bool) -> Result<Option<Vec<u8>>, OsError>;
}

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum OsError {
    #[error("{detail}")]
    Failed { detail: String },
}

/// Same Wi‑Fi, as the OS provides it.
#[uniffi::export(with_foreign)]
pub trait SameWifi: Send + Sync {
    /// Announces Seam on the local network on this port (Bonjour), under the fixed name "Seam".
    fn announce(&self, port: u16);
    fn stop_announcing(&self);
    /// Whether this local address (as text) is on a Wi‑Fi or wired Ethernet interface.
    fn is_lan_address(&self, ip: String) -> bool;
}

/// Hears what to show, and what to log or announce. Called on Seam's threads, in order.
#[uniffi::export(with_foreign)]
pub trait ComputerListener: Send + Sync {
    fn on_snapshot(&self, snapshot: ComputerSnapshot);
    fn on_event(&self, event: Event);
}

/// What an earlier app kept in its own preferences, copied across once.
#[derive(uniffi::Record, Default)]
pub struct LegacyPreferences {
    pub wifi: Option<bool>,
    pub tor: Option<bool>,
    pub clipboard_sync: Option<bool>,
    /// Unix ms; i64::MAX: until resumed on the phone.
    pub paused_until_ms: Option<i64>,
    pub paired_at_ms: Option<i64>,
}

#[derive(uniffi::Record)]
pub struct ComputerSettings {
    /// The app's private data folder.
    pub folder: String,
    pub app_version: String,
    /// "macos" | "linux".
    pub platform: String,
    pub legacy: LegacyPreferences,
}

// What the app hears

#[derive(uniffi::Enum)]
pub enum PairingStep {
    Idle,
    Waiting {
        qr: String,
        expires_at_ms: i64,
    },
    /// A verified request: the user compares the safety code.
    Confirm {
        request: PairingRequest,
    },
    /// Pair was clicked; nothing can cancel it now.
    Confirming {
        request: PairingRequest,
    },
    Paired {
        name: String,
    },
    Expired,
    Failed {
        reason: PairingFailure,
    },
}

#[derive(uniffi::Record)]
pub struct SessionSnapshot {
    pub phone: Option<Phone>,
    /// Where a message would go now; None: it waits.
    pub route: Option<PeerPath>,
    /// Paths switched on at both ends.
    pub allowed: Vec<PeerPath>,
    pub waiting: u32,
    pub pairing: PairingStep,
    pub warning: Option<Warning>,
    /// Unix ms; i64::MAX: until resumed on the phone. None: not paused.
    pub paused_until_ms: Option<i64>,
    pub reconnect: Option<Reconnect>,
    /// When the link last went from connected to not (unix ms). This run only.
    pub last_seen_ms: Option<i64>,
    /// The connection switches.
    pub wifi: bool,
    pub tor: bool,
}

#[derive(uniffi::Record)]
pub struct FeatureViews {
    pub clipboard: ClipboardView,
    pub notifications: NotificationsView,
    pub status: StatusView,
}

#[derive(uniffi::Record)]
pub struct ComputerSnapshot {
    pub health: Health,
    /// Goes up each time the saved data is opened (launch, reset).
    pub opens: u64,
    pub pair_again: bool,
    pub session: Option<SessionSnapshot>,
    pub features: Option<FeatureViews>,
    pub tor_state: TorState,
}

/// What the user does to a feature, from the app's screens. Protobuf messages as their bytes;
/// anything that doesn't decode is ignored.
#[derive(uniffi::Enum)]
pub enum FeatureCommand {
    SetClipboardSync {
        on: bool,
    },
    CopyAgain {
        text: String,
    },
    ClearClips,
    /// A seam.v1.NotificationSettingsChange.
    ChangeNotificationSettings {
        change: Vec<u8>,
    },
    /// A seam.v1.NotificationAction.
    ActOnNotification {
        action: Vec<u8>,
    },
    DismissNotification {
        key: String,
    },
    BatteryChanged,
}

/// Seam on this computer. Commands return at once; what they change arrives as a snapshot.
#[derive(uniffi::Object)]
pub struct Computer {
    inner: computer::Computer,
}

#[uniffi::export]
impl Computer {
    #[uniffi::constructor]
    pub fn new(
        settings: ComputerSettings,
        keys: Arc<dyn KeyStore>,
        pasteboard: Arc<dyn Pasteboard>,
        notifications: Arc<dyn NotificationCentre>,
        battery: Arc<dyn Battery>,
        same_wifi: Arc<dyn SameWifi>,
        listener: Arc<dyn ComputerListener>,
    ) -> Arc<Self> {
        let l = settings.legacy;
        let settings = computer::Settings {
            folder: settings.folder.into(),
            app_version: settings.app_version,
            platform: settings.platform,
            legacy: seam_computer::store::Legacy {
                wifi: l.wifi,
                tor: l.tor,
                clipboard_sync: l.clipboard_sync,
                paused_until: l.paused_until_ms,
                paired_at: l.paired_at_ms,
            },
        };
        let os = computer::Os {
            keys: Arc::new(AppKeyStore(keys)),
            pasteboard: Arc::new(AppPasteboard(pasteboard)),
            notifications: Arc::new(AppNotificationCentre(notifications)),
            battery: Arc::new(AppBattery(battery)),
            same_wifi: Arc::new(AppSameWifi(same_wifi)),
            listener: Arc::new(AppListener(listener)),
        };
        Arc::new(Self {
            inner: computer::Computer::start(settings, os),
        })
    }

    pub fn set_wifi(&self, on: bool) {
        self.inner.set_wifi(on);
    }
    pub fn set_tor(&self, on: bool) {
        self.inner.set_tor(on);
    }
    pub fn start_pairing(&self, name: String) {
        self.inner.start_pairing(name);
    }
    pub fn confirm_pairing(&self) {
        self.inner.confirm_pairing();
    }
    pub fn cancel_pairing(&self) {
        self.inner.cancel_pairing();
    }
    pub fn unpair(&self) {
        self.inner.unpair();
    }
    pub fn reconnect(&self) {
        self.inner.reconnect();
    }
    pub fn feature(&self, command: FeatureCommand) {
        if let Some(c) = feature_command(command) {
            self.inner.feature(c);
        }
    }
    pub fn woke(&self) {
        self.inner.woke();
    }
    pub fn reset(&self) {
        self.inner.reset();
    }
    /// The phone apps' icon PNGs, by app id.
    pub fn icons(&self) -> HashMap<String, Vec<u8>> {
        self.inner.icons()
    }
}

fn feature_command(c: FeatureCommand) -> Option<seam_computer::features::Command> {
    use prost::Message;
    use seam_computer::features::{Command as C, clipboard, notifications, status};
    use seam_core::proto::v1;
    Some(match c {
        FeatureCommand::SetClipboardSync { on } => C::Clipboard(clipboard::Command::SetSync(on)),
        FeatureCommand::CopyAgain { text } => C::Clipboard(clipboard::Command::CopyAgain(text)),
        FeatureCommand::ClearClips => C::Clipboard(clipboard::Command::ClearClips),
        FeatureCommand::ChangeNotificationSettings { change } => {
            C::Notifications(notifications::Command::Change(
                v1::NotificationSettingsChange::decode(change.as_slice()).ok()?,
            ))
        }
        FeatureCommand::ActOnNotification { action } => C::Notifications(
            notifications::Command::Act(v1::NotificationAction::decode(action.as_slice()).ok()?),
        ),
        FeatureCommand::DismissNotification { key } => {
            C::Notifications(notifications::Command::Dismiss(key))
        }
        FeatureCommand::BatteryChanged => C::Status(status::Command::BatteryChanged),
    })
}

// The app's interfaces, as seam-computer's

struct AppKeyStore(Arc<dyn KeyStore>);
impl seam_computer::store::KeyStore for AppKeyStore {
    fn storage_key(&self, create: bool) -> Result<Option<Vec<u8>>, String> {
        self.0.storage_key(create).map_err(|e| e.to_string())
    }
}

struct AppSameWifi(Arc<dyn SameWifi>);
impl computer::SameWifi for AppSameWifi {
    fn announce(&self, port: u16) {
        self.0.announce(port);
    }
    fn stop_announcing(&self) {
        self.0.stop_announcing();
    }
    fn is_lan_address(&self, ip: IpAddr) -> bool {
        self.0.is_lan_address(ip.to_string())
    }
}

struct AppListener(Arc<dyn ComputerListener>);
impl computer::Listener for AppListener {
    fn on_snapshot(&self, s: computer::Snapshot) {
        self.0.on_snapshot(ComputerSnapshot {
            health: s.health,
            opens: s.opens,
            pair_again: s.pair_again,
            session: s.session.map(session),
            features: s.features.map(|f| FeatureViews {
                clipboard: f.clipboard.into(),
                notifications: f.notifications.into(),
                status: f.status.into(),
            }),
            tor_state: crate::tor::tor_state(s.tor_state),
        });
    }
    fn on_event(&self, e: Event) {
        self.0.on_event(e);
    }
}

fn request(r: seam_core::PairingRequest) -> PairingRequest {
    PairingRequest {
        peer_id: r.peer_id,
        device_name: r.device_name,
        model: r.model,
        safety_code: r.safety_code,
    }
}

fn session(s: State) -> SessionSnapshot {
    SessionSnapshot {
        phone: s.phone,
        route: s.route.map(Into::into),
        allowed: s.allowed.into_iter().map(Into::into).collect(),
        waiting: s.waiting as u32,
        pairing: match s.pairing {
            PairingState::Idle => PairingStep::Idle,
            PairingState::Waiting { qr, expires_at } => PairingStep::Waiting {
                qr,
                expires_at_ms: expires_at,
            },
            PairingState::Confirm(r) => PairingStep::Confirm {
                request: request(r),
            },
            PairingState::Confirming(r) => PairingStep::Confirming {
                request: request(r),
            },
            PairingState::Paired { name } => PairingStep::Paired { name },
            PairingState::Expired => PairingStep::Expired,
            PairingState::Failed(reason) => PairingStep::Failed { reason },
        },
        warning: s.warning,
        paused_until_ms: s.paused_until,
        reconnect: s.reconnect,
        last_seen_ms: s.last_seen,
        wifi: s.wifi,
        tor: s.tor,
    }
}

#[cfg(test)]
mod tests;
