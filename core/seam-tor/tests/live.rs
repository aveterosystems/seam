// SPDX-License-Identifier: AGPL-3.0-only
//! Against the real Tor network, so ignored by default (CI runs these against Chutney later):
//!   cargo test -p seam-tor --test live -- --ignored --nocapture
//! Shows that only an authorised phone can discover the Mac's service (restricted discovery hides
//! it; Seam's own login is the access control, SR-33-01), that no key reaches disk, and prints the
//! timings the spike gate in docs/tor/07-plan.md asks for.

use std::time::{Duration, Instant};

use seam_tor::{DiscoveryKey, Error, ErrorKind, ServiceKey, TorNode};
use tokio::io::{AsyncReadExt, AsyncWriteExt};

async fn node(dir: &tempfile::TempDir, name: &str) -> TorNode {
    let started = Instant::now();
    let app_dir = dir.path().join(name);
    std::fs::create_dir(&app_dir).unwrap();
    #[cfg(unix)]
    std::fs::set_permissions(
        &app_dir,
        std::os::unix::fs::PermissionsExt::from_mode(0o700),
    )
    .unwrap();
    let node = TorNode::start(&app_dir).await.expect("tor starts");
    eprintln!("{name}: started in {:?}", started.elapsed());
    node
}

#[tokio::test(flavor = "multi_thread")]
#[ignore = "uses the live Tor network"]
async fn only_the_paired_phone_reaches_the_mac() {
    let dir = tempfile::tempdir().unwrap();
    let (mac, phone, stranger) = tokio::join!(
        node(&dir, "mac"),
        node(&dir, "phone"),
        node(&dir, "stranger")
    );

    let service_key = ServiceKey::generate().unwrap();
    let phone_key = DiscoveryKey::generate().unwrap();
    let stranger_key = DiscoveryKey::generate().unwrap();

    let published = Instant::now();
    let mut service = mac
        .host(&service_key, &[phone_key.public()])
        .expect("service launches");
    let address = service.address();
    // A second key is a second service under its own address (the Mac pairs a new phone while
    // the old address still runs); each serves only under its own address
    let second = ServiceKey::generate().unwrap();
    let other = mac
        .host(&second, &[stranger_key.public()])
        .expect("a second service");
    assert_eq!(other.address(), second.address());
    assert_ne!(other.address(), address);
    drop(other);
    // The Mac echoes whatever arrives, like a login challenge and reply
    tokio::spawn(async move {
        while let Some(mut stream) = service.accept().await {
            tokio::spawn(async move {
                let mut buf = [0u8; 64];
                while let Ok(n) = stream.read(&mut buf).await {
                    if n == 0
                        || stream.write_all(&buf[..n]).await.is_err()
                        || stream.flush().await.is_err()
                    {
                        break;
                    }
                }
            });
        }
    });

    // The descriptor takes a while to publish; retry until the phone gets through
    let mut stream = loop {
        match phone.connect(address, &phone_key).await {
            Ok(stream) => break stream,
            Err(e) if published.elapsed() < Duration::from_secs(300) => {
                eprintln!("phone: not yet ({e}), retrying");
                tokio::time::sleep(Duration::from_secs(5)).await;
            }
            Err(e) => panic!("phone never reached the Mac: {e}"),
        }
    };
    eprintln!("mac: reachable {:?} after publishing", published.elapsed());

    let mut rtts = Vec::new();
    for i in 0..10u8 {
        let sent = Instant::now();
        stream.write_all(&[i; 32]).await.unwrap();
        stream.flush().await.unwrap();
        let mut back = [0u8; 32];
        stream.read_exact(&mut back).await.unwrap();
        assert_eq!(back, [i; 32]);
        rtts.push(sent.elapsed());
    }
    rtts.sort();
    eprintln!(
        "message round trip: median {:?}, worst {:?}",
        rtts[5], rtts[9]
    );

    let reconnect = Instant::now();
    drop(stream);
    phone
        .connect(address, &phone_key)
        .await
        .expect("second connection");
    eprintln!("phone: new connection in {:?}", reconnect.elapsed());

    // A stranger with its own key can't even find the service, and the refusal is about
    // authorisation, not some unrelated failure
    match stranger.connect(address, &stranger_key).await {
        Err(Error::Tor(
            ErrorKind::OnionServiceWrongClientAuth | ErrorKind::OnionServiceMissingClientAuth,
        )) => {}
        Err(e) => panic!("stranger failed for the wrong reason: {e}"),
        Ok(_) => panic!("stranger reached the Mac"),
    }
    eprintln!("stranger: refused, not authorised");

    // Keys never touch disk (SR-33-04): no private key file anywhere, no file holding the seed
    let seed = service_key.to_bytes();
    let mut dirs = vec![dir.path().to_path_buf()];
    while let Some(d) = dirs.pop() {
        for entry in std::fs::read_dir(&d).unwrap().flatten() {
            let path = entry.path();
            if path.is_dir() {
                dirs.push(path);
                continue;
            }
            let name = path.to_string_lossy().to_lowercase();
            assert!(
                !name.contains("private") && !name.contains("keystore"),
                "{name}"
            );
            let bytes = std::fs::read(&path).unwrap_or_default();
            assert!(
                !bytes.windows(32).any(|w| w == &seed[..]),
                "the seed is in {name}"
            );
        }
    }
    eprintln!("disk: no key material");
}
