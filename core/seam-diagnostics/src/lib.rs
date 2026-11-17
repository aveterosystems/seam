// SPDX-License-Identifier: AGPL-3.0-only
//! Diagnostics: what this device did lately, kept in memory only and exported only when the user
//! asks (specs/diagnostics.md). Nothing here writes to disk or sends anything anywhere.

use std::collections::VecDeque;
use std::sync::{LazyLock, Mutex};
use std::time::SystemTime;

mod arti;
mod filter;
mod time;

pub use arti::ArtiLayer;

/// How much the recorder may hold. Once full, the oldest events go first.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Limits {
    /// Events kept at most.
    pub events: usize,
    /// Bytes kept at most, across every event.
    pub bytes: usize,
    /// Characters per event at most; longer ones are cut.
    pub line: usize,
}

/// What each device keeps (specs/diagnostics.md): a few pairing attempts with Arti's log, well
/// under a megabyte.
pub const LIMITS: Limits = Limits {
    events: 2_000,
    bytes: 512 * 1024,
    line: 400,
};

static RECORDER: LazyLock<Recorder> = LazyLock::new(|| Recorder::new(LIMITS));

/// This process's recorder: everything in the app records here, and the export reads it.
pub fn recorder() -> &'static Recorder {
    &RECORDER
}

/// One event into this process's recorder.
pub fn record(source: &str, event: &str) {
    RECORDER.record(source, event);
}

/// The last events, newest last.
pub struct Recorder {
    limits: Limits,
    events: Mutex<Kept>,
}

#[derive(Default)]
struct Kept {
    events: VecDeque<String>,
    bytes: usize,
}

impl Recorder {
    pub fn new(limits: Limits) -> Self {
        Self {
            limits,
            events: Mutex::default(),
        }
    }

    /// One event from `source` (a short fixed word: "tor", "pairing", "arti").
    pub fn record(&self, source: &str, event: &str) {
        let line = format!(
            "{} {source} {}",
            time::utc(SystemTime::now()),
            cut(&filter::filter(event), self.limits.line)
        );
        let mut kept = self.events.lock().unwrap_or_else(|p| p.into_inner());
        kept.bytes += line.len();
        kept.events.push_back(line);
        while kept.events.len() > self.limits.events || kept.bytes > self.limits.bytes {
            match kept.events.pop_front() {
                Some(old) => kept.bytes -= old.len(),
                None => break,
            }
        }
    }

    /// `header` (app, versions, network), when it was exported, then every event kept, oldest
    /// first.
    pub fn export(&self, header: &str) -> String {
        let kept = self.events.lock().unwrap_or_else(|p| p.into_inner());
        let mut out = format!("{header}\nexported {}\n", time::utc(SystemTime::now()));
        for e in kept.events.iter() {
            out.push_str(e);
            out.push('\n');
        }
        out
    }
}

/// At most `max` characters, the last one an ellipsis when anything was cut.
fn cut(event: &str, max: usize) -> String {
    if event.chars().count() <= max {
        return event.into();
    }
    let mut out: String = event.chars().take(max.saturating_sub(1)).collect();
    out.push('…');
    out
}
