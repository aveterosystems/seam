// SPDX-License-Identifier: AGPL-3.0-only
//! The one-time grant that turns on automatic clipboard from the phone, as on the Mac
//! (apps/mac/Packages/SeamSession/Sources/SeamSession/AdbSetup.swift): Android only lets adb grant
//! log access, so the computer does it over USB or wireless debugging. Only ever runs the adb
//! found in a known place, with fixed arguments.

use std::path::Path;
use std::process::{Command, Stdio};
use std::time::{Duration, Instant};

/// The sideloaded `full` build: the only one that declares READ_LOGS.
pub const PACKAGE: &str = "com.avetero.seam.full";
/// How long one adb call may take before it's stopped (SR-4-11).
pub const STEP_TIMEOUT: Duration = Duration::from_secs(20);

/// Where adb usually lives. A fixed list, never a PATH lookup, so nothing else can stand in for it.
/// Inside Flatpak, the adb Seam ships (decision from the packaging research).
pub fn locate(home: &str, exists: impl Fn(&str) -> bool) -> Option<String> {
    [
        "/app/bin/adb".to_string(),
        "/usr/bin/adb".to_string(),
        "/usr/local/bin/adb".to_string(),
        format!("{home}/Android/Sdk/platform-tools/adb"),
        format!("{home}/.local/share/android-sdk/platform-tools/adb"),
    ]
    .into_iter()
    .find(|p| exists(p))
}

/// Serials from `adb devices` that are connected and authorised.
pub fn devices(output: &str) -> Vec<String> {
    output
        .lines()
        .filter_map(|l| {
            let mut parts = l.split_whitespace();
            let serial = parts.next()?;
            (parts.next()? == "device").then(|| serial.to_string())
        })
        .collect()
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Step {
    pub what: &'static str,
    pub arguments: Vec<String>,
    /// What this changes on the phone, in the words the user agrees to. None for a step that only looks.
    pub grants: Option<&'static str>,
}

/// In order. The first fails if the full build isn't installed. The restart at the end is needed:
/// Android only hands a new log permission to a freshly started process.
pub fn steps(serial: &str) -> Vec<Step> {
    let shell = |rest: &[&str]| -> Vec<String> {
        ["-s", serial, "shell"]
            .iter()
            .chain(rest)
            .map(|s| s.to_string())
            .collect()
    };
    let activity = format!("{PACKAGE}/com.avetero.seam.MainActivity");
    vec![
        Step {
            what: "Find Seam on the phone",
            arguments: shell(&["pm", "path", PACKAGE]),
            grants: None,
        },
        Step {
            what: "Allow reading clipboard events",
            arguments: shell(&["pm", "grant", PACKAGE, "android.permission.READ_LOGS"]),
            grants: Some(
                "Seam may read the phone's system log. It only looks for the line Android writes when something is copied.",
            ),
        },
        Step {
            what: "Allow the invisible focus window",
            arguments: shell(&["appops", "set", PACKAGE, "SYSTEM_ALERT_WINDOW", "allow"]),
            grants: Some(
                "Seam may show a 1-pixel window over other apps for a moment, which Android requires before reading the clipboard.",
            ),
        },
        Step {
            what: "Restart Seam",
            arguments: shell(&["am", "force-stop", PACKAGE]),
            grants: Some("Seam on the phone restarts once to pick these up."),
        },
        Step {
            what: "Open Seam",
            arguments: shell(&["am", "start", "-n", &activity]),
            grants: None,
        },
    ]
}

/// Every change the steps make, listed, so the user agrees to exactly what happens.
pub fn consent() -> String {
    steps("")
        .iter()
        .filter_map(|s| s.grants)
        .map(|g| format!("• {g}"))
        .collect::<Vec<_>>()
        .join("\n")
}

/// How the setup went, in the words the dialog shows.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Outcome {
    pub title: String,
    pub detail: String,
    pub ok: bool,
}

fn outcome(title: &str, detail: &str, ok: bool) -> Outcome {
    Outcome {
        title: title.into(),
        detail: detail.into(),
        ok,
    }
}

/// Runs the grant with `run` (adb's arguments → exit status and output). Blocking.
pub fn grant(run: impl Fn(&[String]) -> Option<(bool, String)>) -> Outcome {
    let Some((true, list)) = run(&["devices".to_string()]) else {
        return outcome(
            "adb Didn't Run",
            "Check that adb works in a terminal, then try again.",
            false,
        );
    };
    let found = devices(&list);
    let [serial] = found.as_slice() else {
        return if found.is_empty() {
            outcome(
                "No Phone Found",
                "Plug the phone in, unlock it and tap Allow on “Allow USB debugging?”, then try again.",
                false,
            )
        } else {
            outcome(
                "More Than One Phone Connected",
                "Leave only the phone you paired connected, then try again.",
                false,
            )
        };
    };
    for (i, step) in steps(serial).into_iter().enumerate() {
        if run(&step.arguments).map(|r| r.0) != Some(true) {
            return if i == 0 {
                outcome(
                    "The Seam Build From GitHub Isn't on This Phone",
                    "Install it, pair it with this computer, then try again.",
                    false,
                )
            } else {
                Outcome {
                    title: format!("Setup Stopped at “{}”", step.what),
                    detail: "Unlock the phone, check USB debugging is still on, then try again."
                        .into(),
                    ok: false,
                }
            };
        }
    }
    outcome(
        "Automatic Clipboard Is On",
        "If the phone asks “Allow access to all device logs?”, tap Allow. It asks again after the phone restarts. You can unplug the phone now.",
        true,
    )
}

/// adb itself, stopped after STEP_TIMEOUT.
pub fn run(adb: &str, args: &[String]) -> Option<(bool, String)> {
    let mut child = Command::new(adb)
        .args(args)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()
        .ok()?;
    let start = Instant::now();
    loop {
        if let Some(status) = child.try_wait().ok()? {
            let mut out = String::new();
            if let Some(mut s) = child.stdout.take() {
                use std::io::Read;
                let _ = s.read_to_string(&mut out);
            }
            return Some((status.success(), out));
        }
        if start.elapsed() > STEP_TIMEOUT {
            let _ = child.kill();
            let _ = child.wait();
            return None;
        }
        std::thread::sleep(Duration::from_millis(50));
    }
}

/// adb on this computer, if it's in one of the known places.
pub fn find() -> Option<String> {
    let home = std::env::var("HOME").unwrap_or_default();
    locate(&home, |p| {
        use std::os::unix::fs::PermissionsExt;
        Path::new(p)
            .metadata()
            .is_ok_and(|m| m.is_file() && m.permissions().mode() & 0o111 != 0)
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    /// SR-4-11: the user agrees to every change the setup makes.
    #[test]
    fn consent_lists_every_change() {
        let c = consent();
        assert!(
            c.contains("system log") && c.contains("over other apps") && c.contains("restarts")
        );
        assert_eq!(steps("x").iter().filter(|s| s.grants.is_some()).count(), 3);
        assert!(
            !steps("x")
                .iter()
                .any(|s| s.arguments.iter().any(|a| a == "deviceidle"))
        );
        assert!(!c.contains("battery"));
        assert!(STEP_TIMEOUT <= Duration::from_secs(30));
    }

    #[test]
    fn finds_adb_only_in_known_places() {
        assert_eq!(
            locate("/home/m", |p| p == "/usr/bin/adb").as_deref(),
            Some("/usr/bin/adb")
        );
        assert_eq!(
            locate("/home/m", |p| p == "/app/bin/adb").as_deref(),
            Some("/app/bin/adb")
        );
        assert!(locate("/home/m", |p| p == "/home/m/Android/Sdk/platform-tools/adb").is_some());
        assert!(
            locate("/home/m", |p| p == "/home/m/bin/adb").is_none(),
            "never from PATH or elsewhere"
        );
        assert!(locate("/home/m", |_| false).is_none());
    }

    #[test]
    fn lists_only_authorised_devices() {
        let out = "List of devices attached\nR5CX123\tdevice\nemulator-5554\toffline\nR5CY999\tunauthorized\n192.168.1.4:5555\tdevice\n\n";
        assert_eq!(devices(out), ["R5CX123", "192.168.1.4:5555"]);
        assert!(devices("List of devices attached\n\n").is_empty());
    }

    #[test]
    fn grants_only_the_full_build_and_restarts_it() {
        let s = steps("R5CX123");
        assert!(s.iter().all(|x| x.arguments.starts_with(&[
            "-s".into(),
            "R5CX123".into(),
            "shell".into()
        ])));
        assert!(s.iter().any(|x| {
            x.arguments
                .ends_with(&[PACKAGE.into(), "android.permission.READ_LOGS".into()])
        }));
        assert!(
            s.last()
                .unwrap()
                .arguments
                .contains(&format!("{PACKAGE}/com.avetero.seam.MainActivity"))
        );
    }

    #[test]
    fn a_whole_run_and_where_it_stops() {
        let ok = |args: &[String]| -> Option<(bool, String)> {
            Some((
                true,
                if args[0] == "devices" {
                    "List\nR5\tdevice\n".into()
                } else {
                    String::new()
                },
            ))
        };
        assert!(grant(ok).ok);
        assert_eq!(
            grant(|_| Some((true, "List\n".into()))).title,
            "No Phone Found"
        );
        assert_eq!(
            grant(|_| Some((true, "List\nA\tdevice\nB\tdevice\n".into()))).title,
            "More Than One Phone Connected"
        );
        let no_app = |args: &[String]| {
            Some((
                args[0] == "devices",
                if args[0] == "devices" {
                    "List\nR5\tdevice\n".into()
                } else {
                    String::new()
                },
            ))
        };
        assert!(grant(no_app).title.contains("Isn't on This Phone"));
        let stops = |args: &[String]| {
            Some((
                !args.iter().any(|a| a == "appops"),
                "List\nR5\tdevice\n".to_string(),
            ))
        };
        assert!(
            grant(stops)
                .title
                .contains("Allow the invisible focus window")
        );
        assert_eq!(grant(|_| None).title, "adb Didn't Run");
    }
}
