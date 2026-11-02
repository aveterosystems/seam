// SPDX-License-Identifier: AGPL-3.0-only
//! The computer's side of a direct link (protocol/README.md, "The direct connection"), over either
//! carrier: Same Wi‑Fi (a TCP listener, announced by the app) or Tor (streams from the computer's
//! onion service, handed in with `adopt`). All the cryptography is the core's `LanChannel`; this
//! only moves bytes, enforces the limits, and turns frames into events.

mod address;
mod link;

use std::collections::HashMap;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use prost::Message;
use seam_core::Device;
use seam_core::lan::{LanChannel, PAIRING_PEER};
use seam_core::proto::v1::{ClientFrame, Envelope, ServerFrame, client_frame};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::{Notify, mpsc};
use tokio::task::AbortHandle;

pub use address::{address_key, is_local};

/// A byte stream a link runs over: a TCP connection or a Tor stream.
pub trait Pipe: AsyncRead + AsyncWrite + Send + Unpin + 'static {}
impl<T: AsyncRead + AsyncWrite + Send + Unpin + 'static> Pipe for T {}
pub type Stream = Box<dyn Pipe>;

/// What a direct link reports, in order. `peer` is the authenticated paired device, or
/// [`PAIRING_PEER`] for a phone that proved it scanned the computer's code (a pairing channel,
/// which carries only the pairing exchange).
#[derive(Debug, Clone, PartialEq)]
pub enum LanEvent {
    /// A paired (or pairing) phone proved who it is; it may now be sent to.
    Authenticated(String),
    /// That phone's link closed (not reported when a newer link from it replaced this one).
    Closed(String),
    Envelope(String, Envelope),
    Delivered(String, Vec<u8>),
    /// A pairing phone asks for this computer's prekey bundle (pairing channels only).
    BundleRequest(String),
}

/// A direct link as the session sees it.
pub trait LanSender: Send + Sync {
    /// False when the phone isn't connected (send it another way).
    fn send(&self, frame: &ServerFrame, to: &str) -> bool;
    /// Closes gracefully: what was already sent is written first.
    fn close(&self, peer: &str);
}

/// Connections still authenticating. When full, one goes (a silent one first, chosen at random),
/// so idle sockets can't lock the phone out.
pub const MAX_PENDING: usize = 16;
/// Per bucket ([`address_key`]).
pub const MAX_PENDING_PER_ADDRESS: usize = 4;
/// A link closed on purpose waits this long at most for what was already sent to be written.
pub const CLOSE_GRACE: Duration = Duration::from_secs(2);

#[derive(Debug, Clone, Copy)]
pub struct LanConfig {
    /// False: no TCP listener; streams come in through `adopt` (the Tor carrier).
    pub listens: bool,
    pub auth_deadline: Duration,
    /// The phone writes its handshake as soon as it connects: a silent socket goes after this.
    pub first_bytes_deadline: Duration,
    /// Tests only: loopback is otherwise refused, both as an interface and as a peer address.
    pub allow_loopback: bool,
}

impl LanConfig {
    pub fn same_wifi() -> Self {
        Self {
            listens: true,
            auth_deadline: Duration::from_secs(10),
            first_bytes_deadline: Duration::from_secs(2),
            allow_loopback: false,
        }
    }

    /// Over Tor a handshake crosses about six relays each way: more time before a stream goes.
    pub fn tor() -> Self {
        Self {
            listens: false,
            auth_deadline: Duration::from_secs(45),
            first_bytes_deadline: Duration::from_secs(15),
            allow_loopback: false,
        }
    }
}

/// Which local addresses are Wi‑Fi or wired Ethernet (the app asks the OS): a connection that
/// arrives on any other interface (a VPN tunnel, mobile data, loopback) is refused.
pub type LanInterfaces = Arc<dyn Fn(IpAddr) -> bool + Send + Sync>;
pub type EventSink = Arc<dyn Fn(LanEvent) + Send + Sync>;

pub struct LanServer {
    shared: Arc<Shared>,
}

struct Shared {
    device: Arc<Mutex<Device>>,
    config: LanConfig,
    /// Called with the server's lock held, so events keep their order: never call back into it.
    on_event: EventSink,
    lan_interfaces: Option<LanInterfaces>,
    state: Mutex<State>,
    ids: AtomicU64,
}

#[derive(Default)]
struct State {
    stopped: bool,
    listener: Option<AbortHandle>,
    /// Oldest first.
    pending: Vec<Arc<Link>>,
    authed: HashMap<String, Arc<Link>>,
}

enum Write {
    Bytes(Vec<u8>),
    /// After everything queued before it.
    Close,
}

struct Link {
    id: u64,
    address: String,
    channel: Mutex<LanChannel>,
    peer: Mutex<Option<String>>,
    heard: AtomicBool,
    closed: AtomicBool,
    writes: mpsc::UnboundedSender<Write>,
    /// Ends both directions at once.
    kill: Notify,
}

impl Link {
    fn peer(&self) -> Option<String> {
        lock(&self.peer).clone()
    }
}

use crate::lock;

impl LanServer {
    pub fn new(
        device: Arc<Mutex<Device>>,
        config: LanConfig,
        lan_interfaces: Option<LanInterfaces>,
        on_event: EventSink,
    ) -> Self {
        Self {
            shared: Arc::new(Shared {
                device,
                config,
                on_event,
                lan_interfaces,
                state: Mutex::default(),
                ids: AtomicU64::new(0),
            }),
        }
    }

    /// Listens on any free port and returns it (0 for the Tor carrier, which only adopts).
    /// Single use: after `stop`, make a new one.
    pub async fn start(&self) -> std::io::Result<u16> {
        if !self.shared.config.listens {
            return Ok(0);
        }
        let listener = bind(self.shared.config.allow_loopback)?;
        let port = listener.local_addr()?.port();
        let shared = self.shared.clone();
        let task = tokio::spawn(async move { shared.accept_loop(listener).await });
        let mut state = lock(&self.shared.state);
        if state.stopped {
            task.abort();
        } else {
            state.listener = Some(task.abort_handle());
        }
        Ok(port)
    }

    /// Closes the listener and every link (gracefully), reporting `Closed` for an authenticated phone.
    pub fn stop(&self) {
        let mut state = lock(&self.shared.state);
        state.stopped = true;
        if let Some(l) = state.listener.take() {
            l.abort();
        }
        let links: Vec<_> = state
            .pending
            .iter()
            .chain(state.authed.values())
            .cloned()
            .collect();
        for link in links {
            self.shared.drop_link(&mut state, &link, true);
        }
    }

    /// A stream from the computer's onion service, handled exactly like a Same Wi‑Fi connection.
    /// Only the paired phone (or, while pairing, the phone that scanned the code) can find the
    /// service, and the channel still has to authenticate it.
    pub fn adopt(&self, stream: Stream) {
        let channel = {
            if lock(&self.shared.state).stopped {
                return;
            }
            match LanChannel::responder(&lock(&self.shared.device)) {
                Ok(c) => c,
                Err(_) => return,
            }
        };
        // Tor streams carry no address to bucket by: each is its own, so only the overall limit applies
        let address = format!("tor-{}", self.shared.ids.fetch_add(1, Ordering::Relaxed));
        self.shared.take(stream, channel, address);
    }
}

impl LanSender for LanServer {
    fn send(&self, frame: &ServerFrame, to: &str) -> bool {
        let bytes = frame.encode_to_vec();
        let mut state = lock(&self.shared.state);
        let Some(link) = state.authed.get(to).cloned() else {
            return false;
        };
        let sent = lock(&link.channel)
            .send(&bytes)
            .is_ok_and(|wire| link.writes.send(Write::Bytes(wire)).is_ok());
        if !sent {
            self.shared.drop_link(&mut state, &link, false);
        }
        sent
    }

    fn close(&self, peer: &str) {
        let mut state = lock(&self.shared.state);
        if let Some(link) = state.authed.get(peer).cloned() {
            self.shared.drop_link(&mut state, &link, true);
        }
    }
}

impl Drop for LanServer {
    fn drop(&mut self) {
        self.stop();
    }
}

/// IPv4 and IPv6 on one socket; loopback only for tests.
fn bind(loopback: bool) -> std::io::Result<TcpListener> {
    use socket2::{Domain, Socket, Type};
    let (domain, addr) = if loopback {
        (Domain::IPV4, SocketAddr::from((Ipv4Addr::LOCALHOST, 0)))
    } else {
        (Domain::IPV6, SocketAddr::from((Ipv6Addr::UNSPECIFIED, 0)))
    };
    let socket = Socket::new(domain, Type::STREAM, None)?;
    if !loopback {
        socket.set_only_v6(false)?;
    }
    socket.set_nonblocking(true)?;
    socket.bind(&addr.into())?;
    socket.listen(64)?;
    TcpListener::from_std(socket.into())
}

/// A phone that vanished (Wi‑Fi off, out of range) is noticed in about two minutes.
fn tune(stream: &TcpStream) {
    let _ = stream.set_nodelay(true);
    let keepalive = socket2::TcpKeepalive::new()
        .with_time(Duration::from_secs(60))
        .with_interval(Duration::from_secs(15))
        .with_retries(4);
    let _ = socket2::SockRef::from(stream).set_tcp_keepalive(&keepalive);
}

/// An IPv4-mapped IPv6 address as plain IPv4 (a dual-stack socket reports IPv4 peers that way).
fn canonical(ip: IpAddr) -> IpAddr {
    match ip {
        IpAddr::V6(v6) => v6.to_ipv4_mapped().map_or(ip, IpAddr::V4),
        v4 => v4,
    }
}

/// Which waiting connection makes room: one that has sent nothing yet, at random, else any at
/// random. Random, so an attacker can't predict which of its own (or the phone's) goes.
pub fn victim<T: Clone>(candidates: &[T], heard: impl Fn(&T) -> bool) -> Option<T> {
    use rand::seq::IndexedRandom;
    let silent: Vec<_> = candidates.iter().filter(|c| !heard(c)).collect();
    let mut rng = rand::rng();
    silent
        .choose(&mut rng)
        .map(|c| (*c).clone())
        .or_else(|| candidates.choose(&mut rng).cloned())
}

/// The peer id of a pairing channel.
pub const PAIRING: &str = PAIRING_PEER;

#[cfg(test)]
mod tests;
