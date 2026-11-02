// SPDX-License-Identifier: AGPL-3.0-only
//! The whole computer side end to end: the actor, the store on disk, the real direct link over a
//! Tor stream (in memory), and a real phone device that pairs, comes back and sends a clip.

use std::net::IpAddr;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use async_trait::async_trait;
use prost::Message;
use seam_core::Device;
use seam_core::lan::LanChannel;
use seam_core::proto::v1::{
    self, ClientFrame, Delivered, Envelope, GetBundle, Payload, ServerFrame, client_frame,
    payload::Body, server_frame,
};
use tokio::io::{AsyncReadExt, AsyncWriteExt, DuplexStream};
use tokio::sync::watch;

use super::*;
use crate::features::clipboard::PasteboardItem;
use crate::pairing::PairingState;
use crate::pairing::qr::PairingQr;
use crate::session::TorServiceSpec;
use crate::store::Legacy;
use crate::tor::{StateSink, StreamSink, TorHosted};

#[derive(Default)]
struct Keys(Mutex<Option<Vec<u8>>>);

impl KeyStore for Keys {
    fn storage_key(&self, create: bool) -> Result<Option<Vec<u8>>, String> {
        let mut k = self.0.lock().unwrap();
        if k.is_none() && create {
            *k = Some(vec![3; 32]);
        }
        Ok(k.clone())
    }
}

#[derive(Default)]
struct Board(Mutex<(i64, Option<String>)>);

#[async_trait]
impl Pasteboard for Board {
    async fn change_count(&self) -> i64 {
        self.0.lock().unwrap().0
    }
    async fn first_item(&self, if_still: i64) -> Option<PasteboardItem> {
        let s = self.0.lock().unwrap();
        (s.0 == if_still).then(|| PasteboardItem {
            types: vec!["public.utf8-plain-text".into()],
            text: s.1.clone(),
        })
    }
    async fn write(&self, text: String) -> i64 {
        let mut s = self.0.lock().unwrap();
        s.0 += 1;
        s.1 = Some(text);
        s.0
    }
}

struct Quiet;

#[async_trait]
impl NotificationCentre for Quiet {
    async fn show(&self, _: v1::Notification, _: Option<Vec<u8>>) {}
    async fn dismiss(&self, _: String) {}
    async fn remove_all(&self) {}
}

impl Battery for Quiet {
    fn read(&self) -> Option<v1::Status> {
        None
    }
}

impl SameWifi for Quiet {
    fn announce(&self, _: u16) {}
    fn stop_announcing(&self) {}
    fn is_lan_address(&self, _: IpAddr) -> bool {
        false
    }
}

struct Watch(watch::Sender<Option<Snapshot>>);

impl Listener for Watch {
    fn on_snapshot(&self, s: Snapshot) {
        self.0.send_replace(Some(s));
    }
    fn on_event(&self, _: Event) {}
}

/// Tor that's ready at once; the test reaches each hosted service through its stream sink.
#[derive(Default)]
struct Tor(Mutex<Vec<(TorServiceSpec, StreamSink)>>);

struct Up;
impl TorHosted for Up {
    fn stop(&self) {}
}

impl TorRunner for Tor {
    fn start(&self, on_state: StateSink) {
        on_state(TorState::Ready);
    }
    fn host(&self, spec: &TorServiceSpec, on_stream: StreamSink) -> Option<Box<dyn TorHosted>> {
        self.0.lock().unwrap().push((spec.clone(), on_stream));
        Some(Box::new(Up))
    }
}

impl Tor {
    /// A stream into the newest service hosted for `spec`.
    fn dial(&self, spec: &TorServiceSpec) -> DuplexStream {
        let sink = self
            .0
            .lock()
            .unwrap()
            .iter()
            .rev()
            .find(|(s, _)| s == spec)
            .unwrap()
            .1
            .clone();
        let (ours, theirs) = tokio::io::duplex(1 << 20);
        sink(Box::new(theirs));
        ours
    }
}

/// The phone's end of a link.
struct Phone {
    stream: DuplexStream,
    channel: LanChannel,
}

impl Phone {
    async fn open(mut stream: DuplexStream, mut channel: LanChannel) -> Self {
        stream.write_all(&channel.start()).await.unwrap();
        let mut buf = vec![0; 1 << 20];
        while channel.peer_id().is_none() {
            let n = stream.read(&mut buf).await.unwrap();
            assert!(n > 0, "closed during the handshake");
            let out = channel.receive(&buf[..n]).unwrap();
            if !out.send.is_empty() {
                stream.write_all(&out.send).await.unwrap();
            }
        }
        Self { stream, channel }
    }

    async fn send(&mut self, frame: client_frame::Frame) {
        let bytes = ClientFrame { frame: Some(frame) }.encode_to_vec();
        let wire = self.channel.send(&bytes).unwrap();
        self.stream.write_all(&wire).await.unwrap();
    }

    async fn frame(&mut self) -> server_frame::Frame {
        let mut buf = vec![0; 1 << 20];
        loop {
            let n = tokio::time::timeout(Duration::from_secs(10), self.stream.read(&mut buf))
                .await
                .unwrap()
                .unwrap();
            assert!(n > 0, "closed");
            if let Some(f) = self.channel.receive(&buf[..n]).unwrap().frames.first() {
                return ServerFrame::decode(f.as_slice()).unwrap().frame.unwrap();
            }
        }
    }
}

async fn until(
    rx: &mut watch::Receiver<Option<Snapshot>>,
    what: impl Fn(&Snapshot) -> bool,
) -> Snapshot {
    tokio::time::timeout(Duration::from_secs(10), async {
        loop {
            if let Some(s) = rx.borrow_and_update().clone()
                && what(&s)
            {
                return s;
            }
            rx.changed().await.unwrap();
        }
    })
    .await
    .expect("the snapshot never got there")
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn pairs_through_tor_comes_back_as_itself_and_a_clip_crosses() {
    let dir = tempfile::tempdir().unwrap();
    let (snapshots, mut rx) = watch::channel(None);
    let board = Arc::new(Board::default());
    let tor = Arc::new(Tor::default());
    let os = Os {
        keys: Arc::new(Keys::default()),
        pasteboard: board.clone(),
        notifications: Arc::new(Quiet),
        battery: Arc::new(Quiet),
        same_wifi: Arc::new(Quiet),
        listener: Arc::new(Watch(snapshots)),
    };
    let settings = Settings {
        folder: dir.path().to_owned(),
        app_version: "test".into(),
        platform: "macos".into(),
        legacy: Legacy {
            wifi: Some(false),
            ..Default::default()
        },
    };
    let computer = Computer::start_on(
        &tokio::runtime::Handle::current(),
        settings,
        os,
        tor.clone(),
    );
    until(&mut rx, |s| s.health == Health::Ready).await;

    // The code
    computer.start_pairing("Mac".into());
    let s = until(&mut rx, |s| {
        matches!(
            s.session.as_ref().unwrap().pairing,
            PairingState::Waiting { .. }
        )
    })
    .await;
    let PairingState::Waiting { qr, .. } = &s.session.as_ref().unwrap().pairing else {
        unreachable!()
    };
    let qr = PairingQr::parse(qr).unwrap();
    let service = s.session.as_ref().unwrap().tor_services[0].clone();

    // The phone scans it and reaches the code's own service
    let mut phone = Device::generate().unwrap();
    let relay_key: [u8; 32] = qr.relay_key.clone().try_into().unwrap();
    tokio::time::sleep(Duration::from_millis(100)).await; // the service is hosted on the next update
    let channel = LanChannel::pairing_initiator(&phone, relay_key, &qr.token).unwrap();
    let mut link = Phone::open(tor.dial(&service), channel).await;
    link.send(client_frame::Frame::GetBundle(GetBundle {
        device_key: qr.relay_key.clone(),
    }))
    .await;
    let server_frame::Frame::Bundle(b) = link.frame().await else {
        panic!("no bundle")
    };
    let bundle = seam_core::bundle::decode(&b.bundle).unwrap();
    let (mac_id, request) = phone
        .pair_with(
            &qr.identity_key,
            relay_key,
            &qr.name,
            &bundle,
            &qr.token,
            "Pixel",
            "Test",
        )
        .unwrap();
    link.send(client_frame::Frame::Send(
        Envelope::decode(request.as_slice()).unwrap(),
    ))
    .await;
    assert!(
        matches!(link.frame().await, server_frame::Frame::Delivered(_)),
        "the request is acked"
    );
    until(&mut rx, |s| {
        matches!(
            s.session.as_ref().unwrap().pairing,
            PairingState::Confirm(_)
        )
    })
    .await;

    // Pair, and the phone acks the PairAccept
    computer.confirm_pairing();
    let server_frame::Frame::Deliver(accept) = link.frame().await else {
        panic!("no accept")
    };
    phone.open(&accept.encode_to_vec()).unwrap();
    link.send(client_frame::Frame::Delivered(Delivered {
        msg_id: accept.msg_id,
    }))
    .await;
    let s = until(&mut rx, |s| s.session.as_ref().unwrap().phone.is_some()).await;
    assert_eq!(
        s.session.as_ref().unwrap().pairing,
        PairingState::Paired {
            name: "Pixel".into()
        }
    );
    assert!(
        !s.session
            .as_ref()
            .unwrap()
            .phone
            .as_ref()
            .unwrap()
            .safety_code
            .is_empty()
    );

    // It comes back as itself, on the same service, and hears the computer's Hello
    let channel = LanChannel::initiator(&phone, &mac_id).unwrap();
    let mut link = Phone::open(tor.dial(&service), channel).await;
    let server_frame::Frame::Deliver(hello) = link.frame().await else {
        panic!("no Hello")
    };
    let opened = phone.open(&hello.encode_to_vec()).unwrap();
    let hello = Payload::decode(opened.payload.as_slice()).unwrap();
    assert!(matches!(hello.body, Some(Body::Control(_))));

    // A clip from the phone lands on the computer's clipboard
    let clip = Payload {
        body: Some(Body::Clipboard(v1::Clipboard {
            text: "from the phone".into(),
            ..Default::default()
        })),
    };
    let sealed = phone.seal(&mac_id, &clip.encode_to_vec(), 0).unwrap();
    link.send(client_frame::Frame::Send(
        Envelope::decode(sealed.as_slice()).unwrap(),
    ))
    .await;
    let s = until(&mut rx, |s| {
        !s.features.as_ref().unwrap().clipboard.clips.is_empty()
    })
    .await;
    assert_eq!(
        s.features.unwrap().clipboard.clips[0].text,
        "from the phone"
    );
    assert_eq!(board.0.lock().unwrap().1.as_deref(), Some("from the phone"));

    // And it survives a restart: the same phone, from the files on disk
    drop(computer);
    let (snapshots, mut rx) = watch::channel(None);
    let os = Os {
        keys: Arc::new(Keys(Mutex::new(Some(vec![3; 32])))),
        pasteboard: Arc::new(Board::default()),
        notifications: Arc::new(Quiet),
        battery: Arc::new(Quiet),
        same_wifi: Arc::new(Quiet),
        listener: Arc::new(Watch(snapshots)),
    };
    let settings = Settings {
        folder: dir.path().to_owned(),
        app_version: "test".into(),
        platform: "macos".into(),
        legacy: Legacy {
            wifi: Some(false),
            ..Default::default()
        },
    };
    let again = Computer::start_on(
        &tokio::runtime::Handle::current(),
        settings,
        os,
        Arc::new(Tor::default()),
    );
    let s = until(&mut rx, |s| s.health == Health::Ready).await;
    assert_eq!(
        s.session.unwrap().phone.map(|p| p.id),
        Some(phone.id().to_owned())
    );
    drop(again);
}
