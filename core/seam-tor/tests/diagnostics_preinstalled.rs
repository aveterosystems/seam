// SPDX-License-Identifier: AGPL-3.0-only
//! When something else already installed a tracing subscriber, Arti's log isn't recorded, but
//! Arti's safe logging is still locked on and nothing panics. Its own test binary: the
//! subscriber and the lock are process-wide.

#[test]
fn with_another_subscriber_safe_logging_is_still_locked_and_arti_isnt_recorded() {
    tracing::subscriber::set_global_default(tracing_subscriber::registry())
        .expect("the first subscriber");
    seam_tor::record_arti();
    assert!(
        safelog::disable_safe_logging().is_err(),
        "nothing may switch Arti's redaction off"
    );
    tracing::info!(target: "tor_hsservice::status", "Onion service is running");
    let out = seam_diagnostics::recorder().export("");
    assert!(!out.contains("Onion service is running"), "{out}");
}
