// SPDX-License-Identifier: AGPL-3.0-only
//! The computer itself: its name for pairing, and waking from sleep.

use futures_util::StreamExt;

/// The pretty name hostnamed gave at launch (inside Flatpak, /etc is the runtime's own).
static PRETTY: std::sync::OnceLock<String> = std::sync::OnceLock::new();

/// The distribution and its version (`PRETTY_NAME` in os-release), for the diagnostics export.
pub fn os_name() -> String {
    // Inside Flatpak /etc is the runtime's; the host's own is at /run/host
    let release = [
        "/run/host/os-release",
        "/etc/os-release",
        "/usr/lib/os-release",
    ]
    .iter()
    .find_map(|p| std::fs::read_to_string(p).ok())
    .unwrap_or_default();
    release
        .lines()
        .find_map(|l| l.strip_prefix("PRETTY_NAME="))
        .map(|v| v.trim_matches('"').to_owned())
        .unwrap_or_else(|| "Linux".into())
}

/// The name the phone shows for this computer: the one set in Settings (hostnamed's pretty name,
/// or /etc/machine-info), else the host name, else "Linux computer".
pub fn computer_name() -> String {
    PRETTY
        .get()
        .cloned()
        .or_else(|| {
            std::fs::read_to_string("/etc/machine-info")
                .ok()
                .and_then(|info| pretty_name(&info))
        })
        .or_else(host_name)
        .unwrap_or_else(|| "Linux computer".into())
}

/// Asks hostnamed for the computer's pretty name once, at launch.
pub async fn learn_name() {
    let Ok(conn) = zbus::Connection::system().await else {
        return;
    };
    let Ok(builder) = zbus::fdo::PropertiesProxy::builder(&conn)
        .destination("org.freedesktop.hostname1")
        .and_then(|b| b.path("/org/freedesktop/hostname1"))
    else {
        return;
    };
    let Ok(p) = builder.build().await else { return };
    let Ok(iface) = zbus::names::InterfaceName::try_from("org.freedesktop.hostname1") else {
        return;
    };
    if let Ok(v) = p.get(iface, "PrettyHostname").await
        && let Ok(name) = String::try_from(v)
        && let Some(name) = clean(&name)
    {
        let _ = PRETTY.set(name);
    }
}

/// PRETTY_HOSTNAME from machine-info, unquoted.
pub fn pretty_name(info: &str) -> Option<String> {
    let v = info
        .lines()
        .find_map(|l| l.trim().strip_prefix("PRETTY_HOSTNAME="))?;
    let v = v.trim().trim_matches('"').trim_matches('\'');
    clean(&v.replace("\\ ", " "))
}

fn host_name() -> Option<String> {
    clean(gtk::glib::host_name().as_str())
}

/// A name worth showing: not empty, not a placeholder.
pub fn clean(name: &str) -> Option<String> {
    let n = name.trim();
    (!n.is_empty()
        && !["localhost", "localhost.localdomain", "fedora", "ubuntu"]
            .contains(&n.to_lowercase().as_str()))
    .then(|| n.to_string())
}

/// Calls `woke` each time the computer resumes from sleep (logind's PrepareForSleep(false)).
pub async fn watch_wake(woke: impl Fn() + Send + 'static) {
    let Ok(conn) = zbus::Connection::system().await else {
        return;
    };
    let Ok(rule) = zbus::MatchRule::builder()
        .msg_type(zbus::message::Type::Signal)
        .interface("org.freedesktop.login1.Manager")
        .and_then(|b| b.member("PrepareForSleep"))
        .map(|b| b.build())
    else {
        return;
    };
    let Ok(mut stream) = zbus::MessageStream::for_match_rule(rule, &conn, None).await else {
        return;
    };
    while let Some(Ok(msg)) = stream.next().await {
        if let Ok(false) = msg.body().deserialize::<bool>() {
            woke();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::clean;

    #[test]
    fn placeholder_names_are_skipped() {
        assert_eq!(clean("  Ana's ThinkPad "), Some("Ana's ThinkPad".into()));
        assert_eq!(clean("fedora"), None);
        assert_eq!(clean("localhost"), None);
        assert_eq!(clean(""), None);
    }

    #[test]
    fn the_pretty_name_from_machine_info() {
        use super::pretty_name;
        assert_eq!(
            pretty_name("PRETTY_HOSTNAME=\"Ana's ThinkPad\"\nICON_NAME=computer-laptop\n"),
            Some("Ana's ThinkPad".into())
        );
        assert_eq!(pretty_name("CHASSIS=laptop\n"), None);
        assert_eq!(pretty_name("PRETTY_HOSTNAME=\n"), None);
    }
}
