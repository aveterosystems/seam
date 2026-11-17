// SPDX-License-Identifier: AGPL-3.0-only
//! Diagnostics for the apps (specs/diagnostics.md): what this device did lately, kept in memory
//! only. The phone's session records its decisions here; the user's "Share diagnostics" reads it.

/// One event, from the app (`source`: a short fixed word such as "session"). Filtered and kept
/// in memory with the rest; the oldest go once the recorder is full.
#[uniffi::export]
pub fn diagnostics_record(source: String, event: String) {
    seam_diagnostics::record(&source, &event);
}

/// Everything kept, after `header` (the app, its version, the OS and the network), for the user
/// to share. Nothing is sent by this: the app hands it to the share sheet.
#[uniffi::export]
pub fn diagnostics_export(header: String) -> String {
    seam_diagnostics::recorder().export(&header)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn what_the_app_records_comes_back_filtered_in_the_export() {
        diagnostics_record(
            "session".into(),
            "tor: dialling vww6ybal4bd7szmgncyruucpgfkqahzddi37ktceo3ah7ngmcopnpyyd.onion".into(),
        );
        let out = diagnostics_export("Seam 0.5.1 on Android 15, mobile".into());
        assert!(
            out.starts_with("Seam diagnostics\nSeam 0.5.1 on Android 15, mobile\nexported "),
            "{out}"
        );
        assert!(out.contains(" session tor: dialling [onion]"), "{out}");
        assert!(!out.contains("vww6ybal"), "{out}");
    }
}
