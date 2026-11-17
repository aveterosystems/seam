// SPDX-License-Identifier: AGPL-3.0-only
//! Arti's own log, into the recorder. Arti logs through `tracing`; this layer keeps its crates at
//! info and its onion-service client and service at debug (where introductions, rendezvous and
//! descriptor fetches and uploads are told), and drops everything else. Each line goes through
//! the recorder's filter like any other event. Arti's safe logging must stay on as well: the app's
//! Tor layer locks it on before Arti starts (`seam-tor`).

use std::fmt::Write;

use tracing::field::{Field, Visit};
use tracing::{Event, Level, Subscriber};
use tracing_subscriber::layer::{Context, Layer};

use crate::Recorder;

/// Where Arti tells how an onion service connection or publication went.
const ONION_SERVICES: [&str; 2] = ["tor_hsclient", "tor_hsservice"];

pub struct ArtiLayer {
    recorder: &'static Recorder,
}

impl ArtiLayer {
    pub fn new(recorder: &'static Recorder) -> Self {
        Self { recorder }
    }
}

fn wanted(target: &str, level: Level) -> bool {
    let krate = target.split("::").next().unwrap_or(target);
    let arti = krate.starts_with("tor_") || krate.starts_with("arti");
    let finest = if ONION_SERVICES.contains(&krate) {
        Level::DEBUG
    } else {
        Level::INFO
    };
    arti && level <= finest
}

impl<S: Subscriber> Layer<S> for ArtiLayer {
    fn enabled(&self, metadata: &tracing::Metadata<'_>, _: Context<'_, S>) -> bool {
        wanted(metadata.target(), *metadata.level())
    }

    fn on_event(&self, event: &Event<'_>, _: Context<'_, S>) {
        let meta = event.metadata();
        if !wanted(meta.target(), *meta.level()) {
            return;
        }
        let mut fields = Fields::default();
        event.record(&mut fields);
        let line = format!(
            "{} {}: {}{}",
            meta.level(),
            meta.target(),
            fields.message,
            fields.rest
        );
        self.recorder.record("arti", &line);
    }
}

/// The message, then every other field as ` name=value`.
#[derive(Default)]
struct Fields {
    message: String,
    rest: String,
}

impl Visit for Fields {
    fn record_debug(&mut self, field: &Field, value: &dyn std::fmt::Debug) {
        if field.name() == "message" {
            let _ = write!(self.message, "{value:?}");
        } else {
            let _ = write!(self.rest, " {}={value:?}", field.name());
        }
    }

    fn record_str(&mut self, field: &Field, value: &str) {
        if field.name() == "message" {
            self.message.push_str(value);
        } else {
            let _ = write!(self.rest, " {}={value}", field.name());
        }
    }
}
