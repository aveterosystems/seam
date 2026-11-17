// SPDX-License-Identifier: AGPL-3.0-only
//! Arti's log is recorded only with its safe logging locked on, so nothing can switch Arti's own
//! redaction off for as long as the app runs (specs/diagnostics.md). Its own test binary: the
//! tracing subscriber and the lock are process-wide.

#[test]
fn arti_is_recorded_only_with_safe_logging_locked_on() {
    seam_tor::record_arti();
    assert!(
        safelog::disable_safe_logging().is_err(),
        "nothing may switch Arti's redaction off once it's recorded"
    );
    tracing::info!(target: "tor_hsservice::status", "Onion service is running");
    let out = seam_diagnostics::recorder().export("");
    assert!(
        out.contains("arti INFO tor_hsservice::status: Onion service is running"),
        "{out}"
    );
}
