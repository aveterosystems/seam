// SPDX-License-Identifier: AGPL-3.0-only
//! The surface the apps see, generated into Swift and Kotlin (scripts/gen-bindings.sh).
//!
//! Only high-level operations are exposed: seal and open envelopes, pair, save and restore. The
//! apps can't encrypt, decrypt or pin keys directly, so they can't skip a check by accident.

use std::sync::{Arc, Mutex, MutexGuard};

use seam_core::{Device, SignalProtocolError, bundle, file, lan, links};

uniffi::setup_scaffolding!();

mod tor;
pub use tor::*;

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum SeamError {
    /// The other device isn't paired (or was unpaired).
    #[error("peer isn't paired")]
    NotPaired,
    /// A key doesn't match the one from pairing: treat as an attack, don't retry.
    #[error("peer's identity doesn't match the paired one")]
    UntrustedIdentity,
    /// The peer hasn't been confirmed yet, so only pairing messages are allowed.
    #[error("peer isn't confirmed yet")]
    NotConfirmed,
    /// Already received: ack it to the relay and ignore it.
    #[error("already received")]
    Duplicate,
    /// Pairing was refused (wrong or expired code, nothing open, already paired…).
    #[error("pairing failed: {reason}")]
    PairingFailed { reason: String },
    /// Saved state can't be opened: wrong storage key or damaged file.
    #[error("saved state can't be opened")]
    BadState,
    /// Anything else: malformed, tampered, misaddressed or unsupported input. Drop it.
    #[error("{detail}")]
    Invalid { detail: String },
}

impl From<seam_core::Error> for SeamError {
    fn from(e: seam_core::Error) -> Self {
        use seam_core::Error as E;
        match e {
            E::NotPaired => Self::NotPaired,
            // A LAN proof from a key that isn't the paired one: the same "not who we paired" signal
            E::Signal(SignalProtocolError::UntrustedIdentity(_)) | E::WrongPeer => {
                Self::UntrustedIdentity
            }
            E::Duplicate => Self::Duplicate,
            E::NotConfirmed => Self::NotConfirmed,
            E::BadPairing(reason) => Self::PairingFailed {
                reason: reason.to_owned(),
            },
            E::BadState => Self::BadState,
            other => Self::Invalid {
                detail: other.to_string(),
            },
        }
    }
}

type Result<T> = std::result::Result<T, SeamError>;

#[derive(uniffi::Record)]
pub struct PeerInfo {
    pub id: String,
    pub name: String,
    pub relay_key: Vec<u8>,
    /// False between a verified pairing request and confirmation; unconfirmed peers aren't saved.
    pub confirmed: bool,
}

/// A message that passed every check. `payload` is a seam.v1.Payload.
#[derive(uniffi::Record)]
pub struct OpenedMessage {
    pub peer_id: String,
    pub msg_id: Vec<u8>,
    pub sent_at: i64,
    pub payload: Vec<u8>,
}

/// What the Mac shows in the QR code (`token`) and sends the relay (`token_hash`).
#[derive(uniffi::Record)]
pub struct PairingOffer {
    pub token: Vec<u8>,
    pub token_hash: Vec<u8>,
}

/// The phone's first envelope to the Mac, and the Mac's id.
#[derive(uniffi::Record)]
pub struct PairingStart {
    pub peer_id: String,
    pub envelope: Vec<u8>,
}

/// A pairing request the Mac has verified, waiting for the user to confirm the code.
#[derive(uniffi::Record)]
pub struct PairingRequest {
    pub peer_id: String,
    pub device_name: String,
    pub model: String,
    pub safety_code: String,
}

/// A way to reach the paired device.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, uniffi::Enum)]
pub enum PeerPath {
    /// Same Wi-Fi, direct.
    Lan,
    /// Through Tor, to the Mac's private onion service.
    Tor,
}

impl From<links::Path> for PeerPath {
    fn from(p: links::Path) -> Self {
        match p {
            links::Path::Lan => Self::Lan,
            links::Path::Tor => Self::Tor,
        }
    }
}

fn paths(list: Vec<PeerPath>) -> Vec<links::Path> {
    list.into_iter().map(Into::into).collect()
}

impl From<PeerPath> for links::Path {
    fn from(p: PeerPath) -> Self {
        match p {
            PeerPath::Lan => Self::Lan,
            PeerPath::Tor => Self::Tor,
        }
    }
}

/// The app's saved data for its pairing (seam_core::links::AppData).
#[derive(Clone, PartialEq, Eq, uniffi::Record)]
pub struct AppData {
    /// Mac: its onion service's secret seed. Phone: its restricted-discovery secret. 32 bytes.
    pub tor_secret: Vec<u8>,
    /// Phone: the Mac's onion address. Mac: empty.
    pub onion_address: String,
    /// Mac: the paired phone's discovery public key (32 bytes). Phone: empty.
    pub peer_discovery: Vec<u8>,
    pub hello_seq_sent: u64,
    pub hello_seq_applied: u64,
    /// The peer's paths from its last Hello. None: everything (never heard, or an older version).
    pub peer_allowed: Option<Vec<PeerPath>>,
}

impl std::fmt::Debug for AppData {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        // The secret never prints; the core's own Debug already redacts it
        std::fmt::Debug::fmt(&links::AppData::from(self.clone()), f)
    }
}

impl From<links::AppData> for AppData {
    fn from(d: links::AppData) -> Self {
        Self {
            tor_secret: d.tor_secret,
            onion_address: d.onion_address,
            peer_discovery: d.peer_discovery,
            hello_seq_sent: d.hello_seq_sent,
            hello_seq_applied: d.hello_seq_applied,
            peer_allowed: d
                .peer_allowed
                .map(|l| l.into_iter().map(Into::into).collect()),
        }
    }
}

impl From<AppData> for links::AppData {
    fn from(d: AppData) -> Self {
        Self {
            tor_secret: d.tor_secret,
            onion_address: d.onion_address,
            peer_discovery: d.peer_discovery,
            hello_seq_sent: d.hello_seq_sent,
            hello_seq_applied: d.hello_seq_applied,
            peer_allowed: d
                .peer_allowed
                .map(|l| l.into_iter().map(Into::into).collect()),
        }
    }
}

/// A Hello's ordering and switches, for `seam.v1.Hello`.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct HelloOut {
    pub seq: u64,
    /// seam.v1.Transport numbers.
    pub allowed_transports: Vec<i32>,
}

/// This device: identity, peers and sessions. Safe to share across threads.
#[derive(uniffi::Object)]
pub struct SeamDevice {
    inner: Mutex<Device>,
}

impl SeamDevice {
    fn lock(&self) -> MutexGuard<'_, Device> {
        self.inner.lock().unwrap_or_else(|p| p.into_inner())
    }
}

#[uniffi::export]
impl SeamDevice {
    /// A brand-new device identity. Call once, then `export_state` and keep the blob.
    #[uniffi::constructor]
    pub fn generate() -> Result<Arc<Self>> {
        Ok(Arc::new(Self {
            inner: Mutex::new(Device::generate()?),
        }))
    }

    /// Rebuild from `export_state`'s blob and the storage key it was sealed with.
    #[uniffi::constructor]
    pub fn restore(state: Vec<u8>, storage_key: Vec<u8>) -> Result<Arc<Self>> {
        let key = key32(&storage_key)?;
        Ok(Arc::new(Self {
            inner: Mutex::new(Device::restore(&state, &key)?),
        }))
    }

    /// Everything to carry on after a restart, sealed under `storage_key` (32 bytes). Save it
    /// after anything that changes state: a seal, an open, pairing, new bundles.
    pub fn export_state(&self, storage_key: Vec<u8>) -> Result<Vec<u8>> {
        Ok(self.lock().export_state(&key32(&storage_key)?)?)
    }

    pub fn id(&self) -> String {
        self.lock().id().to_owned()
    }

    pub fn identity_key(&self) -> Vec<u8> {
        self.lock().identity_key()
    }

    pub fn relay_key(&self) -> Vec<u8> {
        self.lock().relay_key().to_vec()
    }

    /// The answer to the relay's login challenge.
    pub fn sign_relay_login(&self, nonce: Vec<u8>, group_id: Vec<u8>) -> Vec<u8> {
        self.lock().sign_relay_login(&nonce, &group_id).to_vec()
    }

    /// `count` fresh signed prekey bundles to upload to the relay.
    pub fn prekey_bundles(&self, count: u32) -> Result<Vec<Vec<u8>>> {
        let mut d = self.lock();
        (0..count)
            .map(|_| Ok(bundle::encode(&d.prekey_bundle()?)?))
            .collect()
    }

    pub fn peers(&self) -> Vec<PeerInfo> {
        self.lock()
            .peers()
            .iter()
            .map(|(id, p)| PeerInfo {
                id: id.clone(),
                name: p.name.clone(),
                relay_key: p.relay_key.to_vec(),
                confirmed: p.confirmed,
            })
            .collect()
    }

    pub fn remove_peer(&self, peer_id: String) {
        self.lock().remove_peer(&peer_id);
    }

    pub fn safety_code(&self, peer_id: String) -> Result<String> {
        Ok(self.lock().safety_code(&peer_id)?)
    }

    /// The app's relay group and Hello state, sealed inside `export_state` with the keys. None
    /// before any was set. Read from whatever format an earlier version saved.
    pub fn app_data(&self) -> Option<AppData> {
        self.lock().app_data().map(Into::into)
    }

    /// Fails (and changes nothing) for app data that wouldn't read back: a group id that isn't
    /// 16 bytes, or a relay URL or owner key over 65,535 bytes.
    pub fn set_app_data(&self, data: AppData) -> Result<()> {
        Ok(self.lock().set_app_data(&data.into())?)
    }

    /// Unpaired: drops the relay group and Hello state.
    pub fn clear_app_data(&self) {
        self.lock().clear_app_data();
    }

    /// Paths switched on at both ends (decision 0006), in order of preference: this device's
    /// switches (`enabled`, any order) and the peer's last applied Hello.
    pub fn allowed_paths(&self, enabled: Vec<PeerPath>) -> Vec<PeerPath> {
        let enabled = paths(enabled);
        let allowed = self
            .lock()
            .app_data()
            .map_or_else(|| links::mine(&enabled), |d| d.allowed(&enabled));
        allowed.into_iter().map(Into::into).collect()
    }

    /// The path to send on now (Same Wi-Fi first), or None to wait. A Hello (`hello`) may take
    /// any up path this device's switches allow, so a switch change always reaches the peer.
    ///
    /// `enabled`: this device's switches; `up`: the paths connected now. Any order: the core
    /// decides preference.
    pub fn route(
        &self,
        enabled: Vec<PeerPath>,
        up: Vec<PeerPath>,
        hello: bool,
    ) -> Option<PeerPath> {
        let (enabled, up) = (paths(enabled), paths(up));
        let path = match self.lock().app_data() {
            Some(d) => d.route(&enabled, &up, hello),
            None => links::route(&links::mine(&enabled), &up),
        };
        path.map(Into::into)
    }

    /// The next Hello to send: its seq and `allowed_transports` (seam.v1.Transport numbers).
    /// Counts up in the app data: save `export_state` before the Hello leaves. None without app data.
    pub fn next_hello(&self, enabled: Vec<PeerPath>) -> Option<HelloOut> {
        let enabled = paths(enabled);
        let (seq, allowed_transports) = self.lock().edit_app_data(|d| d.next_hello(&enabled))?;
        Some(HelloOut {
            seq,
            allowed_transports,
        })
    }

    /// Applies the peer's Hello if its seq is newer than any before. True if it changed anything;
    /// save `export_state` then.
    pub fn apply_hello(&self, seq: u64, allowed_transports: Vec<i32>) -> bool {
        self.lock()
            .edit_app_data(|d| d.apply_hello(seq, &allowed_transports))
            .unwrap_or(false)
    }

    /// The peer was forgotten or replaced: Hello state starts again (the relay group stays).
    pub fn forget_hello(&self) {
        self.lock().edit_app_data(links::AppData::forget_peer);
    }

    /// Seal a seam.v1.Payload for a paired peer. Returns the Envelope to send. `ttl_s` 0 = 24 h.
    /// The ratchet has advanced: save `export_state` durably *before* sending the envelope.
    pub fn seal(&self, peer_id: String, payload: Vec<u8>, ttl_s: u32) -> Result<Vec<u8>> {
        Ok(self.lock().seal(&peer_id, &payload, ttl_s)?)
    }

    /// Open an Envelope from a paired peer.
    pub fn open(&self, envelope: Vec<u8>) -> Result<OpenedMessage> {
        let o = self.lock().open(&envelope)?;
        Ok(OpenedMessage {
            peer_id: o.peer_id,
            msg_id: o.msg_id,
            sent_at: o.sent_at,
            payload: o.payload,
        })
    }

    /// Mac: open a 5-minute pairing slot.
    pub fn start_pairing(&self) -> PairingOffer {
        let o = self.lock().start_pairing();
        PairingOffer {
            token: o.token.to_vec(),
            token_hash: o.token_hash.to_vec(),
        }
    }

    /// Phone: pair with the Mac from its QR code and bundle. Send the returned envelope.
    #[allow(clippy::too_many_arguments)]
    pub fn pair_with(
        &self,
        identity_key: Vec<u8>,
        relay_key: Vec<u8>,
        name: String,
        bundle: Vec<u8>,
        token: Vec<u8>,
        my_name: String,
        my_model: String,
    ) -> Result<PairingStart> {
        let bundle = bundle::decode(&bundle)?;
        let (peer_id, envelope) = self.lock().pair_with(
            &identity_key,
            key32(&relay_key)?,
            &name,
            &bundle,
            &token,
            &my_name,
            &my_model,
        )?;
        Ok(PairingStart { peer_id, envelope })
    }

    /// Mac: the user confirmed the safety code. Then seal and send PairAccept.
    pub fn confirm_pairing(&self, peer_id: String) -> Result<()> {
        Ok(self.lock().confirm_pairing(&peer_id)?)
    }

    /// Mac: the codes didn't match, or the user cancelled. The phone is forgotten.
    pub fn reject_pairing(&self, peer_id: String) {
        self.lock().reject_pairing(&peer_id);
    }

    /// Mac: close the pairing slot early (window closed, QR expired).
    pub fn cancel_pairing(&self) {
        self.lock().cancel_pairing();
    }

    /// Mac: check the phone's first envelope against the open pairing slot.
    pub fn accept_pairing(&self, envelope: Vec<u8>) -> Result<PairingRequest> {
        let r = self.lock().accept_pairing(&envelope)?;
        Ok(PairingRequest {
            peer_id: r.peer_id,
            device_name: r.device_name,
            model: r.model,
            safety_code: r.safety_code,
        })
    }
}

/// Seals one file for sending. Makes its own key and id (put them in the FileOffer), and
/// enforces chunk order and a single last chunk, so a nonce can never repeat.
#[derive(uniffi::Object)]
pub struct FileSealer {
    inner: Mutex<file::FileSealer>,
}

#[uniffi::export]
impl FileSealer {
    #[uniffi::constructor]
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            inner: Mutex::new(file::FileSealer::new()),
        })
    }

    pub fn key(&self) -> Vec<u8> {
        self.inner
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .key()
            .to_vec()
    }

    pub fn file_id(&self) -> Vec<u8> {
        self.inner
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .id()
            .to_vec()
    }

    /// Every chunk but the last must be exactly `chunk_data_size()` bytes.
    pub fn seal_next(&self, data: Vec<u8>, last: bool) -> Result<Vec<u8>> {
        Ok(self
            .inner
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .seal_next(&data, last)?)
    }
}

/// Opens one received file's chunks, in order, and checks it wasn't cut short.
#[derive(uniffi::Object)]
pub struct FileReceiver {
    inner: Mutex<file::FileReceiver>,
}

#[uniffi::export]
impl FileReceiver {
    #[uniffi::constructor]
    pub fn new(key: Vec<u8>, file_id: Vec<u8>) -> Result<Arc<Self>> {
        let id = file_id
            .as_slice()
            .try_into()
            .map_err(|_| invalid("file id must be 16 bytes"))?;
        Ok(Arc::new(Self {
            inner: Mutex::new(file::FileReceiver::new(key32(&key)?, id)),
        }))
    }

    pub fn open_next(&self, sealed: Vec<u8>, last: bool) -> Result<Vec<u8>> {
        Ok(self
            .inner
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .open_next(&sealed, last)?)
    }

    /// Fails unless the last chunk arrived.
    pub fn finish(&self) -> Result<()> {
        Ok(self
            .inner
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .finish()?)
    }
}

/// What one `LanChannel.receive` produced: write `send` to the socket, handle `frames`.
#[derive(uniffi::Record)]
pub struct LanOutput {
    pub send: Vec<u8>,
    /// seam.v1 ClientFrame (Mac side) or ServerFrame (phone side) bytes, from the authenticated peer.
    pub frames: Vec<Vec<u8>>,
    /// Set once, on the call that finished authentication: the paired peer's id, or "pairing".
    pub authenticated: Option<String>,
}

/// One direct connection to a paired device (same Wi-Fi): Noise handshake, then both sides
/// prove they hold the keys pinned at pairing. Feed it every byte read from the socket and write
/// back what it returns. Any error means close the socket; the channel can't be reused.
#[derive(uniffi::Object)]
pub struct LanChannel {
    inner: Mutex<lan::LanChannel>,
}

#[uniffi::export]
impl LanChannel {
    /// The phone's side, connecting to the paired Mac. Write `start()` as soon as the socket connects.
    #[uniffi::constructor]
    pub fn initiator(device: &SeamDevice, peer_id: String) -> Result<Arc<Self>> {
        Ok(Arc::new(Self {
            inner: Mutex::new(lan::LanChannel::initiator(&device.lock(), &peer_id)?),
        }))
    }

    /// The phone's side while pairing: the Mac pinned from its QR code (`relay_key`), the phone
    /// proving it scanned it with the QR's `token`. Write `start()` as soon as the stream connects.
    #[uniffi::constructor]
    pub fn pairing_initiator(
        device: &SeamDevice,
        relay_key: Vec<u8>,
        token: Vec<u8>,
    ) -> Result<Arc<Self>> {
        let key: [u8; 32] = relay_key.try_into().map_err(|_| SeamError::Invalid {
            detail: "relay key".into(),
        })?;
        Ok(Arc::new(Self {
            inner: Mutex::new(lan::LanChannel::pairing_initiator(
                &device.lock(),
                key,
                &token,
            )?),
        }))
    }

    /// The Mac's side, for one incoming connection: a paired phone, or while a pairing is open, a
    /// phone that proves it scanned the code. Fails when neither could authenticate.
    #[uniffi::constructor]
    pub fn responder(device: &SeamDevice) -> Result<Arc<Self>> {
        Ok(Arc::new(Self {
            inner: Mutex::new(lan::LanChannel::responder(&device.lock())?),
        }))
    }

    /// The first bytes to write (initiator only; empty afterwards and on the responder).
    pub fn start(&self) -> Vec<u8> {
        self.lock().start()
    }

    pub fn receive(&self, bytes: Vec<u8>) -> Result<LanOutput> {
        let o = self.lock().receive(&bytes)?;
        Ok(LanOutput {
            send: o.send,
            frames: o.frames,
            authenticated: o.authenticated,
        })
    }

    /// One frame, sealed for the wire. Fails until authenticated.
    pub fn send(&self, frame: Vec<u8>) -> Result<Vec<u8>> {
        Ok(self.lock().send(&frame)?)
    }

    pub fn peer_id(&self) -> Option<String> {
        self.lock().peer_id().map(str::to_owned)
    }

    /// A pairing channel: a phone that proved it scanned the code, not a paired peer. Only the
    /// pairing exchange (the Mac's bundle, the PairRequest, the PairAccept) may travel over it.
    pub fn is_pairing(&self) -> bool {
        self.lock().is_pairing()
    }
}

impl LanChannel {
    fn lock(&self) -> MutexGuard<'_, lan::LanChannel> {
        self.inner.lock().unwrap_or_else(|p| p.into_inner())
    }
}

/// File bytes per chunk (everything but the last chunk must be exactly this size).
#[uniffi::export]
pub fn chunk_data_size() -> u32 {
    file::CHUNK_DATA as u32
}

fn key32(bytes: &[u8]) -> Result<[u8; 32]> {
    bytes
        .try_into()
        .map_err(|_| invalid("key must be 32 bytes"))
}

fn invalid(detail: &str) -> SeamError {
    SeamError::Invalid {
        detail: detail.to_owned(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The whole M1 flow through the exported API, as the apps will run it.
    #[test]
    fn pair_save_restore_and_talk_through_the_exported_api() {
        let key = vec![7u8; 32];
        let mac = SeamDevice::generate().unwrap();
        let phone = SeamDevice::generate().unwrap();

        // Mac shows a QR code and uploads bundles; the phone scans and fetches one
        let offer = mac.start_pairing();
        let bundles = mac.prekey_bundles(3).unwrap();
        assert_eq!(bundles.len(), 3);
        let start = phone
            .pair_with(
                mac.identity_key(),
                mac.relay_key(),
                "Mac".into(),
                bundles[0].clone(),
                offer.token,
                "Phone".into(),
                "SM-S938B".into(),
            )
            .unwrap();
        assert_eq!(start.peer_id, mac.id());

        let request = mac.accept_pairing(start.envelope).unwrap();
        assert_eq!(request.peer_id, phone.id());
        assert_eq!(request.safety_code, phone.safety_code(mac.id()).unwrap());
        mac.confirm_pairing(request.peer_id.clone()).unwrap();
        let accept = vec![0x0a, 0x02, 0x32, 0x00]; // Payload{control{pair_accept{}}}
        phone
            .open(mac.seal(request.peer_id, accept, 0).unwrap())
            .unwrap();

        // Both restart from saved state
        let mac = SeamDevice::restore(mac.export_state(key.clone()).unwrap(), key.clone()).unwrap();
        let phone =
            SeamDevice::restore(phone.export_state(key.clone()).unwrap(), key.clone()).unwrap();
        assert_eq!(mac.peers()[0].name, "Phone");

        // Payload bytes: field 50 (status), battery_pct = 82
        let status = vec![0x92, 0x03, 0x02, 0x08, 82];
        let opened = mac
            .open(phone.seal(mac.id(), status.clone(), 0).unwrap())
            .unwrap();
        assert_eq!(opened.payload, status);
        assert_eq!(opened.peer_id, phone.id());
    }

    /// The connection rules as both apps call them: one device's Hello reaches the other's rules.
    #[test]
    fn hello_and_routing_through_the_exported_api() {
        let key = vec![7u8; 32];
        let (mac, phone) = (
            SeamDevice::generate().unwrap(),
            SeamDevice::generate().unwrap(),
        );
        assert_eq!(
            mac.next_hello(vec![PeerPath::Lan, PeerPath::Tor]),
            None,
            "no app data yet"
        );
        assert_eq!(mac.allowed_paths(vec![PeerPath::Lan]), vec![PeerPath::Lan]);
        for d in [&mac, &phone] {
            d.set_app_data(AppData {
                tor_secret: vec![1; 32],
                onion_address: String::new(),
                peer_discovery: vec![],
                hello_seq_sent: 0,
                hello_seq_applied: 0,
                peer_allowed: None,
            })
            .unwrap();
        }
        // The phone allows only Same Wi-Fi
        let hello = phone.next_hello(vec![PeerPath::Lan]).unwrap();
        assert_eq!(
            hello,
            HelloOut {
                seq: 1,
                allowed_transports: vec![1]
            }
        );
        assert!(mac.apply_hello(hello.seq, hello.allowed_transports.clone()));
        assert!(
            !mac.apply_hello(hello.seq, vec![2]),
            "a replay changes nothing"
        );
        assert_eq!(
            mac.allowed_paths(vec![PeerPath::Lan, PeerPath::Tor]),
            vec![PeerPath::Lan]
        );
        assert_eq!(
            mac.route(
                vec![PeerPath::Lan, PeerPath::Tor],
                vec![PeerPath::Tor],
                false
            ),
            None,
            "waits for Same Wi-Fi"
        );
        assert_eq!(
            mac.route(
                vec![PeerPath::Lan, PeerPath::Tor],
                vec![PeerPath::Tor],
                true
            ),
            Some(PeerPath::Tor),
            "a Hello still goes"
        );

        let mac = SeamDevice::restore(mac.export_state(key.clone()).unwrap(), key).unwrap();
        assert_eq!(
            mac.app_data().unwrap().peer_allowed,
            Some(vec![PeerPath::Lan]),
            "kept across a restart"
        );
        mac.forget_hello();
        assert_eq!(
            mac.allowed_paths(vec![PeerPath::Lan, PeerPath::Tor]),
            vec![PeerPath::Lan, PeerPath::Tor]
        );
        assert_eq!(mac.app_data().unwrap().tor_secret, vec![1; 32]);

        // SR-29-01: app data that wouldn't read back is refused, and nothing changes
        let mut bad = mac.app_data().unwrap();
        bad.tor_secret = vec![1; 3];
        assert!(matches!(
            mac.set_app_data(bad),
            Err(SeamError::Invalid { .. })
        ));
        assert_eq!(mac.app_data().unwrap().tor_secret, vec![1; 32]);
    }

    #[test]
    fn errors_map_to_what_the_apps_handle() {
        let mac = SeamDevice::generate().unwrap();
        let stranger = SeamDevice::generate().unwrap();
        let bundle = mac.prekey_bundles(1).unwrap().remove(0);
        let env = stranger
            .pair_with(
                mac.identity_key(),
                mac.relay_key(),
                "Mac".into(),
                bundle,
                vec![1; 32],
                "x".into(),
                "y".into(),
            )
            .unwrap()
            .envelope;
        assert!(matches!(mac.open(env.clone()), Err(SeamError::NotPaired)));
        assert!(matches!(
            mac.accept_pairing(env),
            Err(SeamError::PairingFailed { .. })
        ));
        assert!(matches!(
            SeamDevice::restore(vec![1, 2, 3], vec![0; 32]),
            Err(SeamError::BadState)
        ));
        assert!(matches!(
            mac.export_state(vec![0; 31]),
            Err(SeamError::Invalid { .. })
        ));
        assert!(matches!(
            FileReceiver::new(vec![0; 31], vec![0; 16]),
            Err(SeamError::Invalid { .. })
        ));
    }
}
