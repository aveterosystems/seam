// SPDX-License-Identifier: AGPL-3.0-only
//! How the app names itself and the other device. Passed in, so each app (and Linux's name,
//! still to be decided) chooses its own words while the sentences stay the same.

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Side {
    Phone,
    Computer,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Names {
    /// Which app is asking.
    pub side: Side,
    /// How it names itself: "this Mac", "this phone".
    pub this: String,
    /// How it names the other device: "your phone", "your Mac".
    pub peer: String,
}

impl Names {
    pub fn new(side: Side, this: impl Into<String>, peer: impl Into<String>) -> Self {
        Self {
            side,
            this: this.into(),
            peer: peer.into(),
        }
    }

    /// "Your phone", for the start of a sentence.
    pub fn peer_start(&self) -> String {
        capitalise(&self.peer)
    }

    /// "This Mac", for the start of a sentence.
    pub fn this_start(&self) -> String {
        capitalise(&self.this)
    }
}

fn capitalise(s: &str) -> String {
    let mut chars = s.chars();
    chars
        .next()
        .map(|c| c.to_uppercase().chain(chars).collect())
        .unwrap_or_default()
}
