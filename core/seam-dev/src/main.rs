// SPDX-License-Identifier: AGPL-3.0-only
//! A stand-in Mac for testing the phone end to end before the Mac app runs: it uses the real
//! core and the real relay protocol, shows the pairing QR code in the terminal, and prints what
//! the phone sends. Clipboard goes both ways through this Mac's real clipboard (pbcopy/pbpaste);
//! it prints lengths only, never what was copied.
//!
//!     cargo run -p seam-dev -- ws://localhost:8787          (relay from `wrangler dev`)
//!     cargo run -p seam-dev -- wss://relay.avetero.com   (the deployed relay)
//!
//! With the phone on USB, `adb reverse tcp:8787 tcp:8787` lets it reach the same URL.
//! State lives in core/target/seam-dev/; delete that folder to start over.

use std::fs;
use std::io::{BufRead, Write};
use std::path::PathBuf;

use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD as B64;
use prost::Message;
use qrcode::QrCode;
use qrcode::render::unicode;
use rand::RngCore;
use seam_core::proto::v1::{
    ClientFrame, Clipboard, Control, Delivered, Login, OpenPairing, PairAccept, Payload,
    PutBundles, ServerFrame, client_frame, control, payload, server_frame,
};
use seam_core::{Device, Error, bundle};
use tungstenite::{Message as Ws, WebSocket, stream::MaybeTlsStream};

type Socket = WebSocket<MaybeTlsStream<std::net::TcpStream>>;

fn main() -> Result<(), Box<dyn std::error::Error>> {
    // wss:// needs a TLS crypto backend; ring is the one already in the build
    let _ = rustls::crypto::ring::default_provider().install_default();
    let relay = std::env::args()
        .nth(1)
        .unwrap_or_else(|| "ws://localhost:8787".into());
    let dir = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../target/seam-dev");
    fs::create_dir_all(&dir)?;
    // shortcut: the storage key sits in a plain file; fine for a dev tool, never for the apps
    let key: [u8; 32] = load_or(&dir.join("key.bin"), random::<32>)
        .try_into()
        .unwrap();
    let group = load_or(&dir.join("group.bin"), || random::<16>().to_vec());
    let mut device = match fs::read(dir.join("state.bin")) {
        Ok(blob) => Device::restore(&blob, &key)?,
        Err(_) => Device::generate()?,
    };
    let save =
        |d: &Device| fs::write(dir.join("state.bin"), d.export_state(&key).unwrap()).unwrap();
    save(&device);

    let url = format!("{relay}/v1/connect?group={}", B64.encode(&group));
    let (mut ws, _) = tungstenite::connect(url.as_str())?;
    println!("Connected to {relay} as {}", device.id());

    let Some(server_frame::Frame::Challenge(c)) = recv(&mut ws)? else {
        return Err("no challenge".into());
    };
    let signature = device.sign_relay_login(&c.nonce, &group).to_vec();
    let login = Login {
        group_id: group.clone(),
        device_key: device.relay_key().to_vec(),
        signature,
        join_token: vec![],
        // The Mac creates its group on first login; the relay never creates one by accident
        create_group: true,
        owner_key: Vec::new(),
    };
    send(&mut ws, client_frame::Frame::Login(login))?;
    match recv(&mut ws)? {
        Some(server_frame::Frame::LoginResult(r)) if r.ok => {
            println!("Logged in · {} device(s) in the group", r.members.len())
        }
        other => return Err(format!("login refused: {other:?}").into()),
    }

    if device.peers().is_empty() {
        let bundles = (0..20)
            .map(|_| bundle::encode(&device.prekey_bundle()?))
            .collect::<Result<_, Error>>()?;
        send(
            &mut ws,
            client_frame::Frame::PutBundles(PutBundles { bundles }),
        )?;
        let offer = device.start_pairing();
        send(
            &mut ws,
            client_frame::Frame::OpenPairing(OpenPairing {
                token_hash: offer.token_hash.to_vec(),
                ttl_s: 300,
            }),
        )?;
        save(&device);
        let qr = format!(
            "seam://pair?v=1&r={}&g={}&t={}&i={}&k={}&n={}",
            percent(&relay),
            B64.encode(&group),
            B64.encode(offer.token),
            B64.encode(device.identity_key()),
            B64.encode(device.relay_key()),
            percent("seam-dev Mac")
        );
        let code = QrCode::new(qr.as_bytes())?;
        println!("\nScan with Seam on your phone (valid 5 minutes):\n");
        println!(
            "{}",
            code.render::<unicode::Dense1x2>().quiet_zone(true).build()
        );
        println!("{qr}\n");
    } else {
        let names: Vec<_> = device.peers().values().map(|p| p.name.clone()).collect();
        println!("Paired with {}. Waiting for messages…", names.join(", "));
    }

    // Wake twice a second to check the Mac clipboard
    let tick = Some(std::time::Duration::from_millis(500));
    match ws.get_ref() {
        MaybeTlsStream::Plain(tcp) => tcp.set_read_timeout(tick)?,
        MaybeTlsStream::Rustls(tls) => tls.get_ref().set_read_timeout(tick)?,
        _ => {}
    }
    let mut clip = Clip {
        seen: pbpaste(),
        received: None,
    };
    loop {
        poll_clipboard(&mut device, &mut ws, &mut clip)?;
        save(&device);
        match recv(&mut ws)? {
            Some(server_frame::Frame::Deliver(env)) => {
                let bytes = env.encode_to_vec();
                handle(&mut device, &mut ws, &bytes, &mut clip)?;
                send(
                    &mut ws,
                    client_frame::Frame::Delivered(Delivered { msg_id: env.msg_id }),
                )?;
                save(&device);
            }
            Some(server_frame::Frame::PeerJoined(_)) => {
                println!("· a device joined the group, waiting for its pairing request")
            }
            Some(server_frame::Frame::Error(e)) => println!("· relay error: {}", e.code),
            Some(other) => println!("· {other:?}"),
            None => {}
        }
    }
}

/// What this Mac's clipboard last held, and the last text the phone sent (so it isn't echoed back).
struct Clip {
    seen: Option<String>,
    received: Option<String>,
}

fn pbpaste() -> Option<String> {
    let out = std::process::Command::new("pbpaste").output().ok()?;
    Some(String::from_utf8_lossy(&out.stdout).into_owned()).filter(|t| !t.is_empty())
}

fn pbcopy(text: &str) -> std::io::Result<()> {
    let mut child = std::process::Command::new("pbcopy")
        .stdin(std::process::Stdio::piped())
        .spawn()?;
    child.stdin.take().unwrap().write_all(text.as_bytes())?;
    child.wait().map(|_| ())
}

/// Something new copied on this Mac goes to the phone, with the clipboard's 10-minute expiry.
fn poll_clipboard(
    device: &mut Device,
    ws: &mut Socket,
    clip: &mut Clip,
) -> Result<(), Box<dyn std::error::Error>> {
    let now = pbpaste();
    if now == clip.seen {
        return Ok(());
    }
    clip.seen = now.clone();
    let Some(text) = now.filter(|t| Some(t) != clip.received.as_ref()) else {
        return Ok(());
    };
    let Some(phone) = device
        .peers()
        .iter()
        .find(|(_, p)| p.confirmed)
        .map(|(id, _)| id.clone())
    else {
        return Ok(());
    };
    let payload = Payload {
        body: Some(payload::Body::Clipboard(Clipboard {
            text: text.clone(),
            image_png: vec![],
            copied_at: 0,
        })),
    };
    let env = device.seal(&phone, &payload.encode_to_vec(), 600)?;
    send(
        ws,
        client_frame::Frame::Send(seam_core::proto::v1::Envelope::decode(env.as_slice())?),
    )?;
    println!("📋 Mac → phone: {} characters", text.chars().count());
    Ok(())
}

fn handle(
    device: &mut Device,
    ws: &mut Socket,
    envelope: &[u8],
    clip: &mut Clip,
) -> Result<(), Box<dyn std::error::Error>> {
    let opened = match device.open(envelope) {
        Ok(o) => o,
        Err(Error::NotPaired) => return pair(device, ws, envelope),
        Err(Error::Duplicate) => return Ok(()),
        Err(e) => {
            println!("✗ dropped a message: {e}");
            return Ok(());
        }
    };
    let name = device
        .peers()
        .get(&opened.peer_id)
        .map(|p| p.name.clone())
        .unwrap_or_default();
    match Payload::decode(opened.payload.as_slice())?.body {
        Some(payload::Body::Status(s)) => {
            let charging = if s.charging { ", charging" } else { "" };
            println!("🔋 {name}: {}%{charging}", s.battery_pct);
        }
        Some(payload::Body::Clipboard(c)) => {
            pbcopy(&c.text)?;
            println!(
                "📋 {name} → Mac clipboard: {} characters",
                c.text.chars().count()
            );
            clip.received = Some(c.text.clone());
            clip.seen = Some(c.text);
        }
        other => println!("· {name}: {other:?}"),
    }
    Ok(())
}

fn pair(
    device: &mut Device,
    ws: &mut Socket,
    envelope: &[u8],
) -> Result<(), Box<dyn std::error::Error>> {
    let request = match device.accept_pairing(envelope) {
        Ok(r) => r,
        Err(e) => {
            println!("✗ pairing refused: {e}");
            return Ok(());
        }
    };
    print!(
        "\n{} ({}) wants to pair. Code {}. Same on the phone? [y/N] ",
        request.device_name, request.model, request.safety_code
    );
    std::io::stdout().flush()?;
    let mut answer = String::new();
    std::io::stdin().lock().read_line(&mut answer)?;
    if !answer.trim().eq_ignore_ascii_case("y") {
        device.reject_pairing(&request.peer_id);
        println!("Cancelled.");
        return Ok(());
    }
    device.confirm_pairing(&request.peer_id)?;
    let accept = Payload {
        body: Some(payload::Body::Control(Control {
            body: Some(control::Body::PairAccept(PairAccept {})),
        })),
    };
    let env = device.seal(&request.peer_id, &accept.encode_to_vec(), 0)?;
    send(
        ws,
        client_frame::Frame::Send(seam_core::proto::v1::Envelope::decode(env.as_slice())?),
    )?;
    println!("✓ Paired with {}", request.device_name);
    Ok(())
}

fn send(ws: &mut Socket, frame: client_frame::Frame) -> Result<(), Box<dyn std::error::Error>> {
    ws.send(Ws::Binary(
        ClientFrame { frame: Some(frame) }.encode_to_vec().into(),
    ))?;
    Ok(())
}

fn recv(ws: &mut Socket) -> Result<Option<server_frame::Frame>, Box<dyn std::error::Error>> {
    let msg = match ws.read() {
        Ok(m) => m,
        Err(tungstenite::Error::Io(e))
            if matches!(
                e.kind(),
                std::io::ErrorKind::WouldBlock | std::io::ErrorKind::TimedOut
            ) =>
        {
            return Ok(None); // the read timeout: time to check the clipboard
        }
        Err(e) => return Err(e.into()),
    };
    match msg {
        Ws::Binary(b) => Ok(ServerFrame::decode(b.as_ref())?.frame),
        Ws::Close(_) => Err("relay closed the connection".into()),
        _ => Ok(None),
    }
}

fn load_or(path: &PathBuf, make: impl FnOnce() -> Vec<u8>) -> Vec<u8> {
    fs::read(path).unwrap_or_else(|_| {
        let v = make();
        fs::write(path, &v).unwrap();
        v
    })
}

fn random<const N: usize>() -> Vec<u8> {
    let mut b = vec![0u8; N];
    rand::rng().fill_bytes(&mut b);
    b
}

fn percent(s: &str) -> String {
    s.bytes()
        .map(|b| match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'.' | b'_' | b'~' => {
                (b as char).to_string()
            }
            _ => format!("%{b:02X}"),
        })
        .collect()
}
