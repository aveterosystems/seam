// SPDX-License-Identifier: AGPL-3.0-only
use std::net::IpAddr;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use prost::Message;
use seam_core::Device;
use seam_core::lan::LanChannel;
use seam_core::proto::v1::{
    ClientFrame, Delivered, Envelope, GetBundle, Login, ServerFrame, client_frame, server_frame,
};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;
use tokio::sync::mpsc;

use super::*;
use crate::testing::paired_devices;

/// A server tests can reach on 127.0.0.1, and its events.
/// Generous deadlines unless a test is about them: a parallel run generating keys can hold a client back.
fn server(
    device: Device,
    auth: Duration,
    first_bytes: Duration,
) -> (
    LanServer,
    mpsc::UnboundedReceiver<LanEvent>,
    Arc<Mutex<Device>>,
) {
    let device = Arc::new(Mutex::new(device));
    let (tx, rx) = mpsc::unbounded_channel();
    let config = LanConfig {
        listens: true,
        auth_deadline: auth,
        first_bytes_deadline: first_bytes,
        allow_loopback: true,
    };
    let s = LanServer::new(
        device.clone(),
        config,
        None,
        Arc::new(move |e| {
            let _ = tx.send(e);
        }),
    );
    (s, rx, device)
}

fn test_server(
    device: Device,
) -> (
    LanServer,
    mpsc::UnboundedReceiver<LanEvent>,
    Arc<Mutex<Device>>,
) {
    server(device, Duration::from_secs(10), Duration::from_secs(10))
}

/// The first `count` events, or what came before `within` ran out.
async fn collect(
    rx: &mut mpsc::UnboundedReceiver<LanEvent>,
    count: usize,
    within: Duration,
) -> Vec<LanEvent> {
    let mut out = Vec::new();
    let _ = tokio::time::timeout(within, async {
        while out.len() < count {
            match rx.recv().await {
                Some(e) => out.push(e),
                None => break,
            }
        }
    })
    .await;
    out
}

const FIVE: Duration = Duration::from_secs(5);

/// None when the other end closed (or nothing came within `within`).
async fn read(s: &mut TcpStream, within: Duration) -> Option<Vec<u8>> {
    let mut buf = vec![0u8; 1 << 21];
    match tokio::time::timeout(within, s.read(&mut buf)).await {
        Ok(Ok(n)) if n > 0 => Some(buf[..n].to_vec()),
        _ => None,
    }
}

/// True if the other end closed within `within` (false: still open, or it sent bytes).
async fn closed(s: &mut TcpStream, within: Duration) -> bool {
    let mut buf = [0u8; 1 << 16];
    matches!(
        tokio::time::timeout(within, s.read(&mut buf)).await,
        Ok(Ok(0)) | Ok(Err(_))
    )
}

/// The phone's side, driven by the core like the Android app does.
struct TestPhone {
    socket: TcpStream,
    channel: LanChannel,
}

impl TestPhone {
    async fn connect(phone: &Device, mac_id: &str, port: u16) -> Option<Self> {
        Self::connect_to(phone, mac_id, ("127.0.0.1", port)).await
    }

    async fn connect_to(
        phone: &Device,
        mac_id: &str,
        to: impl tokio::net::ToSocketAddrs,
    ) -> Option<Self> {
        let socket = TcpStream::connect(to).await.ok()?;
        Self::handshake(socket, LanChannel::initiator(phone, mac_id).ok()?).await
    }

    async fn pair(phone: &Device, relay_key: [u8; 32], token: &[u8], port: u16) -> Option<Self> {
        let socket = TcpStream::connect(("127.0.0.1", port)).await.ok()?;
        Self::handshake(
            socket,
            LanChannel::pairing_initiator(phone, relay_key, token).ok()?,
        )
        .await
    }

    async fn handshake(mut socket: TcpStream, mut channel: LanChannel) -> Option<Self> {
        socket.write_all(&channel.start()).await.ok()?;
        while channel.peer_id().is_none() {
            let data = read(&mut socket, FIVE).await?;
            let out = channel.receive(&data).ok()?;
            if !out.send.is_empty() {
                socket.write_all(&out.send).await.ok()?;
            }
        }
        Some(Self { socket, channel })
    }

    async fn send(&mut self, frame: client_frame::Frame) {
        let bytes = ClientFrame { frame: Some(frame) }.encode_to_vec();
        let wire = self.channel.send(&bytes).unwrap();
        self.socket.write_all(&wire).await.unwrap();
    }

    async fn receive_frame(&mut self) -> Option<server_frame::Frame> {
        while let Some(data) = read(&mut self.socket, FIVE).await {
            if let Some(f) = self.channel.receive(&data).ok()?.frames.first() {
                return ServerFrame::decode(f.as_slice()).ok()?.frame;
            }
        }
        None
    }
}

fn deliver(e: &Envelope) -> ServerFrame {
    ServerFrame {
        frame: Some(server_frame::Frame::Deliver(e.clone())),
    }
}

fn env(id: &[u8]) -> Envelope {
    Envelope {
        msg_id: id.to_vec(),
        ..Default::default()
    }
}

#[tokio::test]
async fn a_paired_phone_authenticates_and_frames_flow_both_ways() {
    let (mac, phone) = paired_devices();
    let mac_id = mac.id().to_owned();
    let (server, mut events, _) = test_server(mac);
    let port = server.start().await.unwrap();
    let mut p = TestPhone::connect(&phone, &mac_id, port).await.unwrap();
    let pid = phone.id().to_owned();

    let e = env(&[1, 2]);
    p.send(client_frame::Frame::Send(e.clone())).await;
    p.send(client_frame::Frame::Delivered(Delivered {
        msg_id: vec![9],
    }))
    .await;
    p.send(client_frame::Frame::GetBundle(GetBundle::default()))
        .await; // pairing channels only: ignored
    assert_eq!(
        collect(&mut events, 3, FIVE).await,
        [
            LanEvent::Authenticated(pid.clone()),
            LanEvent::Envelope(pid.clone(), e.clone()),
            LanEvent::Delivered(pid.clone(), vec![9]),
        ]
    );

    assert!(server.send(&deliver(&e), &pid));
    assert_eq!(
        p.receive_frame().await,
        Some(server_frame::Frame::Deliver(e.clone()))
    );
    // The computer's ack for something the phone sent directly
    let ack = ServerFrame {
        frame: Some(server_frame::Frame::Delivered(Delivered {
            msg_id: vec![1, 2],
        })),
    };
    assert!(server.send(&ack, &pid));
    assert_eq!(
        p.receive_frame().await,
        Some(server_frame::Frame::Delivered(Delivered {
            msg_id: vec![1, 2]
        }))
    );
    assert!(!server.send(&deliver(&e), "someone-else"));
    server.close(&pid);
    assert_eq!(read(&mut p.socket, FIVE).await, None);
    assert_eq!(
        collect(&mut events, 1, Duration::from_secs(1)).await,
        [LanEvent::Closed(pid)]
    );
}

#[tokio::test]
async fn a_stranger_never_authenticates_and_is_closed() {
    let (mac, _) = paired_devices();
    let (other_mac, other_phone) = paired_devices();
    let (server, mut events, _) = test_server(mac);
    let port = server.start().await.unwrap();
    // A phone paired with another computer: our proof doesn't match, so it never gets in
    assert!(
        TestPhone::connect(&other_phone, other_mac.id(), port)
            .await
            .is_none()
    );
    let mut s = TcpStream::connect(("127.0.0.1", port)).await.unwrap();
    s.write_all(&[0, 3, 1, 2, 3]).await.unwrap(); // a garbage handshake
    assert_eq!(read(&mut s, FIVE).await, None, "closed by the computer");
    assert!(
        collect(&mut events, 1, Duration::from_millis(500))
            .await
            .is_empty()
    );
}

#[tokio::test]
async fn closes_whoever_doesnt_authenticate_in_time() {
    let (mac, _) = paired_devices();
    let (server, _events, _) = server(mac, Duration::from_millis(300), Duration::from_secs(10));
    let port = server.start().await.unwrap();
    let mut s = TcpStream::connect(("127.0.0.1", port)).await.unwrap();
    s.write_all(&[0, 40]).await.unwrap(); // a length, then nothing: heard from, never authenticates
    let started = Instant::now();
    assert_eq!(read(&mut s, FIVE).await, None);
    assert!(started.elapsed() < Duration::from_secs(3));
}

#[tokio::test]
async fn closes_a_silent_socket_sooner() {
    let (mac, _) = paired_devices();
    let (server, _events, _) = server(mac, Duration::from_secs(30), Duration::from_millis(300));
    let port = server.start().await.unwrap();
    let mut s = TcpStream::connect(("127.0.0.1", port)).await.unwrap();
    let started = Instant::now();
    assert_eq!(read(&mut s, FIVE).await, None);
    assert!(started.elapsed() < Duration::from_secs(3));
}

#[tokio::test]
async fn idle_sockets_cant_lock_the_phone_out() {
    let (mac, phone) = paired_devices();
    let mac_id = mac.id().to_owned();
    let (server, mut events, _) = server(mac, Duration::from_secs(30), Duration::from_secs(30));
    let port = server.start().await.unwrap();
    let mut idle = Vec::new();
    for _ in 0..MAX_PENDING_PER_ADDRESS + 2 {
        idle.push(TcpStream::connect(("127.0.0.1", port)).await.unwrap());
    }
    tokio::time::sleep(Duration::from_millis(200)).await;
    // At most four wait per address: two went at once, not at the deadline
    let mut gone = 0;
    for s in &mut idle {
        if closed(s, Duration::from_millis(500)).await {
            gone += 1;
        }
    }
    assert_eq!(gone, 2);
    TestPhone::connect(&phone, &mac_id, port).await.unwrap();
    assert_eq!(
        collect(&mut events, 1, FIVE).await,
        [LanEvent::Authenticated(phone.id().to_owned())]
    );
}

#[tokio::test]
async fn what_was_sent_just_before_a_close_is_still_delivered() {
    let (mac, phone) = paired_devices();
    let mac_id = mac.id().to_owned();
    let pid = phone.id().to_owned();
    let (server, mut events, _) = test_server(mac);
    let port = server.start().await.unwrap();
    let mut p = TestPhone::connect(&phone, &mac_id, port).await.unwrap();
    collect(&mut events, 1, FIVE).await;
    // A big farewell, then closed at once: it must arrive whole before the end of the stream
    let farewell = Envelope {
        msg_id: vec![7],
        ciphertext: vec![1; 1_000_000],
        ..Default::default()
    };
    assert!(server.send(&deliver(&farewell), &pid));
    server.close(&pid);
    assert_eq!(
        p.receive_frame().await,
        Some(server_frame::Frame::Deliver(farewell.clone()))
    );
    assert!(
        !server.send(&deliver(&farewell), &pid),
        "gone for routing at once"
    );

    // And when the whole listener stops (Wi‑Fi switched off, phone forgotten)
    let mut q = TestPhone::connect(&phone, &mac_id, port).await.unwrap();
    assert_eq!(
        collect(&mut events, 2, FIVE).await,
        [
            LanEvent::Closed(pid.clone()),
            LanEvent::Authenticated(pid.clone())
        ]
    );
    assert!(server.send(&deliver(&farewell), &pid));
    server.stop();
    assert_eq!(
        q.receive_frame().await,
        Some(server_frame::Frame::Deliver(farewell))
    );
    assert!(
        closed(&mut q.socket, Duration::from_secs(3)).await,
        "then closed"
    );
}

#[tokio::test]
async fn refuses_everyone_while_no_phone_is_paired() {
    let (server, _events, _) = test_server(Device::generate().unwrap());
    let port = server.start().await.unwrap();
    let mut s = TcpStream::connect(("127.0.0.1", port)).await.unwrap();
    assert_eq!(read(&mut s, FIVE).await, None);
}

#[tokio::test]
async fn a_new_link_from_the_phone_replaces_the_old() {
    let (mac, phone) = paired_devices();
    let mac_id = mac.id().to_owned();
    let pid = phone.id().to_owned();
    let (server, mut events, _) = test_server(mac);
    let port = server.start().await.unwrap();
    let mut first = TestPhone::connect(&phone, &mac_id, port).await.unwrap();
    let mut second = TestPhone::connect(&phone, &mac_id, port).await.unwrap();
    // Two authentications and no close: the phone stayed reachable throughout
    assert_eq!(
        collect(&mut events, 3, Duration::from_secs(1)).await.len(),
        2
    );
    assert_eq!(
        read(&mut first.socket, FIVE).await,
        None,
        "the old link is closed"
    );
    assert!(server.send(&deliver(&env(&[3])), &pid));
    assert_eq!(
        second.receive_frame().await,
        Some(server_frame::Frame::Deliver(env(&[3])))
    );
}

#[tokio::test]
async fn the_apps_listener_refuses_loopback() {
    let (mac, _) = paired_devices();
    let device = Arc::new(Mutex::new(mac));
    let server = LanServer::new(device, LanConfig::same_wifi(), None, Arc::new(|_| {}));
    let port = server.start().await.unwrap();
    // Either refused outright or closed at once: never accepted
    if let Ok(mut s) = TcpStream::connect(("127.0.0.1", port)).await {
        assert_eq!(read(&mut s, Duration::from_secs(3)).await, None);
    }
}

#[tokio::test]
async fn a_connection_on_an_interface_that_isnt_wifi_or_wired_is_refused() {
    let (mac, phone) = paired_devices();
    let mac_id = mac.id().to_owned();
    let device = Arc::new(Mutex::new(mac));
    // As if loopback were a private LAN address arriving on a VPN tunnel
    let config = LanConfig {
        allow_loopback: false,
        ..LanConfig::same_wifi()
    };
    let refused: LanInterfaces = Arc::new(|_| false);
    let server = LanServer::new(device, config, Some(refused), Arc::new(|_| {}));
    let shared = server.shared.clone();
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    tokio::spawn(async move {
        let (stream, _) = listener.accept().await.unwrap();
        // The peer passes the address check; only the interface check is left to refuse it
        shared.accept(stream, "192.168.1.5:5000".parse().unwrap());
    });
    assert!(TestPhone::connect(&phone, &mac_id, port).await.is_none());
}

// The pairing channel over the real listener (the Tor carrier runs the same code on its streams)

#[tokio::test]
async fn a_pairing_channel_carries_the_exchange() {
    let mut mac = Device::generate().unwrap();
    let mut phone = Device::generate().unwrap();
    let offer = mac.start_pairing();
    let relay_key = mac.relay_key();
    let identity = mac.identity_key();
    let (server, mut events, device) = test_server(mac);
    let port = server.start().await.unwrap();
    let mut p = TestPhone::pair(&phone, relay_key, &offer.token, port)
        .await
        .unwrap();
    p.send(client_frame::Frame::GetBundle(GetBundle {
        device_key: relay_key.to_vec(),
    }))
    .await;
    assert_eq!(
        collect(&mut events, 2, FIVE).await,
        [
            LanEvent::Authenticated(PAIRING.into()),
            LanEvent::BundleRequest(PAIRING.into())
        ]
    );
    let bundle = lock(&device).prekey_bundle().unwrap();
    let bytes = seam_core::bundle::encode(&bundle).unwrap();
    let frame = ServerFrame {
        frame: Some(server_frame::Frame::Bundle(
            seam_core::proto::v1::BundleResult {
                device_key: relay_key.to_vec(),
                bundle: bytes.clone(),
            },
        )),
    };
    assert!(server.send(&frame, PAIRING));
    let Some(server_frame::Frame::Bundle(got)) = p.receive_frame().await else {
        panic!("no bundle");
    };
    assert_eq!(got.bundle, bytes);
    let (_, request) = phone
        .pair_with(
            &identity,
            relay_key,
            "Mac",
            &bundle,
            &offer.token,
            "Pixel",
            "Test",
        )
        .unwrap();
    let request = Envelope::decode(request.as_slice()).unwrap();
    p.send(client_frame::Frame::Send(request.clone())).await;
    p.send(client_frame::Frame::Delivered(Delivered {
        msg_id: vec![7],
    }))
    .await;
    assert_eq!(
        collect(&mut events, 2, FIVE).await,
        [
            LanEvent::Envelope(PAIRING.into(), request),
            LanEvent::Delivered(PAIRING.into(), vec![7])
        ]
    );
}

#[tokio::test]
async fn a_pairing_channel_closes_on_anything_else() {
    for frame in [
        client_frame::Frame::GetBundle(GetBundle {
            device_key: vec![9; 32],
        }),
        client_frame::Frame::Login(Login {
            device_key: vec![1; 32],
            ..Default::default()
        }),
    ] {
        let mut mac = Device::generate().unwrap();
        let offer = mac.start_pairing();
        let relay_key = mac.relay_key();
        let (server, mut events, _) = test_server(mac);
        let port = server.start().await.unwrap();
        let mut p = TestPhone::pair(&Device::generate().unwrap(), relay_key, &offer.token, port)
            .await
            .unwrap();
        p.send(frame).await;
        assert!(
            closed(&mut p.socket, Duration::from_secs(3)).await,
            "closed by the computer"
        );
        assert_eq!(
            collect(&mut events, 2, Duration::from_secs(1)).await,
            [
                LanEvent::Authenticated(PAIRING.into()),
                LanEvent::Closed(PAIRING.into())
            ]
        );
    }
}

#[tokio::test]
async fn no_pairing_channel_with_the_wrong_code_or_none_open() {
    let mut mac = Device::generate().unwrap();
    let offer = mac.start_pairing();
    let relay_key = mac.relay_key();
    let (server, mut events, device) = test_server(mac);
    let port = server.start().await.unwrap();
    // The computer proves itself first; the phone's proof with the wrong token then fails
    if let Some(mut wrong) =
        TestPhone::pair(&Device::generate().unwrap(), relay_key, &[1; 32], port).await
    {
        assert!(closed(&mut wrong.socket, Duration::from_secs(3)).await);
    }
    lock(&device).cancel_pairing(); // the code expired or was cancelled
    assert!(
        TestPhone::pair(&Device::generate().unwrap(), relay_key, &offer.token, port)
            .await
            .is_none()
    );
    assert!(
        collect(&mut events, 1, Duration::from_millis(500))
            .await
            .is_empty()
    );
}

#[test]
fn accepts_only_private_and_link_local_addresses() {
    for ok in [
        "10.0.0.5",
        "172.16.0.1",
        "172.31.255.254",
        "192.168.1.20",
        "169.254.3.4",
        "fe80::1",
        "febf::1",
        "fd12:3456::1",
        "fc00::1",
        "::ffff:192.168.0.2",
    ] {
        assert!(is_local(ok.parse::<IpAddr>().unwrap()), "{ok}");
    }
    for bad in [
        "8.8.8.8",
        "172.32.0.1",
        "172.15.0.1",
        "192.169.0.1",
        "127.0.0.1",
        "0.0.0.0",
        "100.64.0.1",
        "::1",
        "2001:db8::1",
        "fec0::1",
        "::ffff:8.8.8.8",
    ] {
        assert!(!is_local(bad.parse::<IpAddr>().unwrap()), "{bad}");
    }
}

#[test]
fn makes_room_with_a_silent_connection_first() {
    let waiting = [(1, true), (2, false), (3, true), (4, false)];
    for _ in 0..50 {
        assert!([2, 4].contains(&victim(&waiting, |w| w.1).unwrap().0));
    }
    let all_heard = waiting.map(|w| (w.0, true));
    let picked: std::collections::HashSet<_> = (0..200)
        .filter_map(|_| victim(&all_heard, |w| w.1))
        .map(|w| w.0)
        .collect();
    assert!(picked.len() > 1, "random, not always the same one");
    assert!(victim::<(i32, bool)>(&[], |w| w.1).is_none());
}

#[test]
fn groups_ipv6_by_its_slash_64_and_ipv4_by_address() {
    let key = |s: &str| address_key(s.parse().unwrap());
    assert_ne!(
        key("fe80::1"),
        key("fe80::2"),
        "link-local: the whole LAN shares fe80::/64"
    );
    assert_eq!(key("fe80::1"), key("fe80::1"));
    assert_eq!(key("fd00:1:2:3::1"), key("fd00:1:2:3::abcd"));
    assert_ne!(key("fd00:1:2:3::1"), key("fd00:1:2:4::1"));
    assert_ne!(key("192.168.1.2"), key("192.168.1.3"));
    assert_eq!(key("::ffff:192.168.1.2"), key("192.168.1.2"));
}

/// This computer's private IPv4 address on its network, if it has one (CI machines often don't).
fn private_lan_address() -> Option<IpAddr> {
    let probe = std::net::UdpSocket::bind("0.0.0.0:0").ok()?;
    probe
        .connect("192.168.255.255:9")
        .or_else(|_| probe.connect("10.255.255.255:9"))
        .ok()?;
    Some(probe.local_addr().ok()?.ip()).filter(|ip| is_local(*ip))
}

/// The production listener, reached the way a phone does: over the network, not loopback. No
/// test-only switches. Skipped on a machine with no private network address.
#[tokio::test]
async fn a_phone_on_the_network_reaches_the_real_listener() {
    let Some(ip) = private_lan_address() else {
        eprintln!("skipped: no private network address");
        return;
    };
    let (mac, phone) = paired_devices();
    let mac_id = mac.id().to_owned();
    let device = Arc::new(Mutex::new(mac));
    let (tx, mut events) = mpsc::unbounded_channel();
    let server = LanServer::new(
        device,
        LanConfig::same_wifi(),
        None,
        Arc::new(move |e| {
            let _ = tx.send(e);
        }),
    );
    let port = server.start().await.unwrap();
    let mut p = TestPhone::connect_to(&phone, &mac_id, (ip, port))
        .await
        .expect("couldn't reach the listener");
    assert_eq!(
        collect(&mut events, 1, FIVE).await,
        [LanEvent::Authenticated(phone.id().to_owned())]
    );
    let e = env(&[4, 2]);
    p.send(client_frame::Frame::Send(e.clone())).await;
    assert_eq!(
        collect(&mut events, 1, FIVE).await,
        [LanEvent::Envelope(phone.id().to_owned(), e.clone())]
    );
    assert!(server.send(&deliver(&e), phone.id()));
    assert_eq!(
        p.receive_frame().await,
        Some(server_frame::Frame::Deliver(e))
    );
    // A phone paired with some other computer, on the same address, never gets in
    let (other_mac, other_phone) = paired_devices();
    assert!(
        TestPhone::connect_to(&other_phone, other_mac.id(), (ip, port))
            .await
            .is_none()
    );
}

/// Manual check against a real phone on the same network: a computer with its own identity,
/// listening as Seam does, which a paired phone must refuse.
///     SEAM_FAKE_COMPUTER_SECONDS=90 cargo test -p seam-computer a_fake_computer -- --ignored --nocapture
/// Announce it with `dns-sd -R Seam _seam._tcp local <port>` in another terminal.
#[tokio::test]
#[ignore = "manual, with a real phone"]
async fn a_fake_computer() {
    let seconds: u64 = std::env::var("SEAM_FAKE_COMPUTER_SECONDS")
        .ok()
        .and_then(|s| s.parse().ok())
        .unwrap_or(60);
    let (mac, _) = paired_devices();
    let (tx, mut events) = mpsc::unbounded_channel();
    let server = LanServer::new(
        Arc::new(Mutex::new(mac)),
        LanConfig::same_wifi(),
        None,
        Arc::new(move |e| {
            let _ = tx.send(e);
        }),
    );
    let port = server.start().await.unwrap();
    println!("fake computer listening on {port}");
    tokio::time::sleep(Duration::from_secs(seconds)).await;
    let heard = collect(&mut events, 1, Duration::from_millis(100)).await;
    assert!(
        !heard
            .iter()
            .any(|e| matches!(e, LanEvent::Authenticated(_))),
        "a paired phone got in"
    );
}

/// Manual check that nothing readable crosses the network: the real listener and a phone talk
/// through a relay on another device, which records every byte (e.g. on the phone:
/// `nc -L -p <port> nc -O <file> <this computer> $(cat /tmp/seam-capture-port)`).
///     SEAM_CAPTURE_VIA=<phone ip>:<port> cargo test -p seam-computer through_another_device -- --ignored --nocapture
/// Waits for /tmp/seam-capture-ready. The marker must only ever appear encrypted in the capture.
#[tokio::test]
#[ignore = "manual, with another device relaying"]
async fn through_another_device() {
    const MARKER: &str = "SEAM-WIRE-MARKER-7f3a";
    let via = std::env::var("SEAM_CAPTURE_VIA").expect("SEAM_CAPTURE_VIA=<ip>:<port>");
    let (mac, phone) = paired_devices();
    let mac_id = mac.id().to_owned();
    let (tx, mut events) = mpsc::unbounded_channel();
    let server = LanServer::new(
        Arc::new(Mutex::new(mac)),
        LanConfig::same_wifi(),
        None,
        Arc::new(move |e| {
            let _ = tx.send(e);
        }),
    );
    let port = server.start().await.unwrap();
    let _ = std::fs::remove_file("/tmp/seam-capture-ready");
    std::fs::write("/tmp/seam-capture-port", port.to_string()).unwrap();
    for _ in 0..120 {
        if std::path::Path::new("/tmp/seam-capture-ready").exists() {
            break;
        }
        tokio::time::sleep(Duration::from_secs(1)).await;
    }
    let mut p = TestPhone::connect_to(&phone, &mac_id, via.as_str())
        .await
        .expect("couldn't reach the relay");
    assert_eq!(
        collect(&mut events, 1, FIVE).await,
        [LanEvent::Authenticated(phone.id().to_owned())]
    );
    let there = Envelope {
        msg_id: vec![7],
        ciphertext: format!("{MARKER} phone to computer").into_bytes(),
        ..Default::default()
    };
    p.send(client_frame::Frame::Send(there.clone())).await;
    assert_eq!(
        collect(&mut events, 1, FIVE).await,
        [LanEvent::Envelope(phone.id().to_owned(), there)]
    );
    let back = Envelope {
        msg_id: vec![8],
        ciphertext: format!("{MARKER} computer to phone").into_bytes(),
        ..Default::default()
    };
    assert!(server.send(&deliver(&back), phone.id()));
    assert_eq!(
        p.receive_frame().await,
        Some(server_frame::Frame::Deliver(back))
    );
}
