// SPDX-License-Identifier: AGPL-3.0-only
//! Arti's own log reaches the recorder: Arti's crates at info, its onion-service client and
//! service at debug (introductions, rendezvous, descriptors), filtered like everything else.
//! Nothing else in the process is recorded.

use seam_diagnostics::{ArtiLayer, Limits, Recorder};
use tracing_subscriber::layer::SubscriberExt;

fn recorder() -> &'static Recorder {
    Box::leak(Box::new(Recorder::new(Limits {
        events: 100,
        bytes: 1 << 20,
        line: 400,
    })))
}

#[test]
fn records_arti_filtered_and_nothing_else() {
    let r = recorder();
    let subscriber = tracing_subscriber::registry().with(ArtiLayer::new(r));
    tracing::subscriber::with_default(subscriber, || {
        tracing::debug!(
            target: "tor_hsclient::connect",
            "Introduction to vww6ybal4bd7szmgncyruucpgfkqahzddi37ktceo3ah7ngmcopnpyyd.onion NACKed"
        );
        tracing::info!(target: "tor_dirmgr", "Marked consensus usable.");
        tracing::warn!(target: "tor_hsservice::publish", attempt = 3, "upload failed");
        tracing::debug!(target: "tor_proto::channel", "chatty and not about onion services");
        tracing::trace!(target: "tor_hsclient::connect", "too fine-grained");
        tracing::info!(target: "hyper::client", "not Arti");
    });
    let out = r.export("");
    assert!(
        out.contains("arti DEBUG tor_hsclient::connect: Introduction to [onion] NACKed"),
        "{out}"
    );
    assert!(
        out.contains("arti INFO tor_dirmgr: Marked consensus usable."),
        "{out}"
    );
    assert!(
        out.contains("arti WARN tor_hsservice::publish: upload failed attempt=3"),
        "{out}"
    );
    for absent in ["chatty", "too fine-grained", "not Arti", "vww6ybal"] {
        assert!(!out.contains(absent), "{absent} in {out}");
    }
}
