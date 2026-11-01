// SPDX-License-Identifier: AGPL-3.0-only
//! What crosses between the two clipboards: nothing empty or oversized, the same copy not sent
//! twice in quick succession, and never an echo of what the other side just sent, so a copy can't
//! bounce back and forth. Which items may be read at all (private or file types) is the
//! platform's to say.

#[cfg(test)]
mod tests;

/// Fits the 256 KB padding bucket with room for the envelope.
pub const MAX_BYTES: usize = 200_000;
/// A repeat counts as a duplicate only this soon after, so a copy that failed to send goes if
/// it's copied again (SR-4-10).
pub const REPEAT_MS: i64 = 2_000;

#[derive(Debug, Default)]
pub struct ClipRules {
    last_sent: Option<String>,
    last_sent_at: Option<i64>,
    last_received: Option<String>,
}

impl ClipRules {
    pub fn new() -> Self {
        Self::default()
    }

    /// The text to send for something copied here (`now_ms`: unix ms), or None to skip it.
    pub fn outgoing(&mut self, text: Option<&str>, now_ms: i64) -> Option<String> {
        let text = text.filter(|t| fits(t))?;
        if self.last_received.as_deref() == Some(text) {
            return None;
        }
        let repeat = self.last_sent.as_deref() == Some(text)
            && self.last_sent_at.is_some_and(|at| now_ms - at < REPEAT_MS);
        if repeat {
            return None;
        }
        self.sent(text, now_ms);
        self.last_received = None;
        Some(text.to_owned())
    }

    /// Something the user explicitly sent (share sheet, a Send button): only the size rules apply.
    pub fn forced(&mut self, text: Option<&str>, now_ms: i64) -> Option<String> {
        let text = text.filter(|t| fits(t))?;
        self.sent(text, now_ms);
        Some(text.to_owned())
    }

    /// Text from the other side to put on this clipboard, or None to ignore it.
    pub fn incoming(&mut self, text: &str) -> Option<String> {
        if !fits(text) {
            return None;
        }
        self.last_received = Some(text.to_owned());
        self.last_sent = None;
        Some(text.to_owned())
    }

    /// The peer was forgotten: nothing it sent counts as an echo any more.
    pub fn reset(&mut self) {
        *self = Self::default();
    }

    fn sent(&mut self, text: &str, now_ms: i64) {
        self.last_sent = Some(text.to_owned());
        self.last_sent_at = Some(now_ms);
    }
}

fn fits(text: &str) -> bool {
    !text.is_empty() && text.len() <= MAX_BYTES
}
