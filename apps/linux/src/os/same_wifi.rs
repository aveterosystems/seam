// SPDX-License-Identifier: AGPL-3.0-only
//! Same Wi‑Fi as Linux provides it: the mDNS announcement, and which local addresses are on
//! Wi‑Fi or wired Ethernet. A connection on anything else (a VPN tunnel, mobile data, a bridge,
//! a container network, loopback) is refused, as on the Mac.

use std::net::IpAddr;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use mdns_sd::{ServiceDaemon, ServiceInfo};
use seam_computer::computer::SameWifi;

const TYPE: &str = "_seam._tcp.local.";

pub struct LinuxSameWifi {
    state: Arc<Mutex<State>>,
}

#[derive(Default)]
struct State {
    /// Set while Seam wants to be found: the listener's port.
    port: Option<u16>,
    live: Option<Live>,
}

struct Live {
    daemon: ServiceDaemon,
    full: String,
    addrs: Vec<IpAddr>,
}

/// How often the addresses are looked at again: a computer back on its Wi‑Fi, or given a new
/// address, is announced within this, so the phone finds it in seconds.
const RECHECK: Duration = Duration::from_secs(3);

impl LinuxSameWifi {
    pub fn new() -> Self {
        let state = Arc::new(Mutex::new(State::default()));
        let watched = Arc::downgrade(&state);
        // shortcut: polls getifaddrs; a netlink or NetworkManager watch would react at once
        std::thread::spawn(move || {
            loop {
                std::thread::sleep(RECHECK);
                let Some(state) = watched.upgrade() else {
                    return;
                };
                let mut st = crate::lock(&state);
                let stale = st.live.as_ref().is_none_or(|l| l.addrs != lan_addresses());
                if let (Some(port), true) = (st.port, stale) {
                    stop(&mut st.live);
                    st.live = announce(port);
                }
            }
        });
        Self { state }
    }
}

impl SameWifi for LinuxSameWifi {
    fn announce(&self, port: u16) {
        let mut st = crate::lock(&self.state);
        stop(&mut st.live);
        st.port = Some(port);
        st.live = announce(port);
    }

    fn stop_announcing(&self) {
        let mut st = crate::lock(&self.state);
        st.port = None;
        stop(&mut st.live);
    }

    /// Only when every interface holding the address is Wi‑Fi or wired Ethernet.
    fn is_lan_address(&self, ip: IpAddr) -> bool {
        let holders = interfaces_holding(ip);
        !holders.is_empty() && holders.iter().all(|i| is_lan_interface(i))
    }
}

/// Announces Seam at this computer's Wi‑Fi and Ethernet addresses, as they are now.
fn announce(port: u16) -> Option<Live> {
    let daemon = ServiceDaemon::new().ok()?;
    // Random, so neither says whose computer it is. A fresh name each time also tells the phone
    // something changed (Android reports a name it has seen only once), and two computers on one
    // network never share a name: the phone tells services apart by name
    let tag = rand::random::<u32>();
    let host = format!("seam-{tag:08x}.local.");
    let name = format!("Seam {tag:08x}");
    let addrs = lan_addresses();
    let info = ServiceInfo::new(
        TYPE,
        &name,
        &host,
        &addrs[..],
        port,
        None::<std::collections::HashMap<String, String>>,
    )
    .ok()?;
    let info = if addrs.is_empty() {
        info.enable_addr_auto()
    } else {
        info
    };
    let full = info.get_fullname().to_string();
    daemon.register(info).ok()?;
    Some(Live {
        daemon,
        full,
        addrs,
    })
}

fn stop(live: &mut Option<Live>) {
    if let Some(l) = live.take() {
        let _ = l.daemon.unregister(&l.full);
        let _ = l.daemon.shutdown();
    }
}

/// Wi‑Fi or wired Ethernet, as the kernel describes the interface: an Ethernet-type device
/// (ARPHRD 1) that is real hardware, not a bridge, VPN or virtual link.
fn is_lan_interface(name: &str) -> bool {
    is_lan_interface_in(std::path::Path::new("/sys/class/net"), name)
}

/// The same, under `root` (tests pass a made-up tree).
fn is_lan_interface_in(root: &std::path::Path, name: &str) -> bool {
    if name.contains('/') || name.starts_with('.') {
        return false;
    }
    let dir = root.join(name);
    let kind = std::fs::read_to_string(dir.join("type")).unwrap_or_default();
    kind.trim() == "1" && dir.join("device").exists() && !dir.join("bridge").exists()
}

/// Every interface this address is on (usually one).
fn interfaces_holding(ip: IpAddr) -> Vec<String> {
    addresses()
        .into_iter()
        .filter(|(_, a)| *a == ip)
        .map(|(i, _)| i)
        .collect()
}

/// The addresses on Wi‑Fi and Ethernet, to announce.
fn lan_addresses() -> Vec<IpAddr> {
    addresses()
        .into_iter()
        .filter(|(i, a)| is_lan_interface(i) && !a.is_loopback())
        .map(|(_, a)| a)
        .collect()
}

fn addresses() -> Vec<(String, IpAddr)> {
    let mut out = Vec::new();
    let mut list: *mut libc::ifaddrs = std::ptr::null_mut();
    // SAFETY: getifaddrs fills a list we walk read-only and free once
    unsafe {
        if libc::getifaddrs(&mut list) != 0 {
            return out;
        }
        let mut p = list;
        while !p.is_null() {
            let a = &*p;
            if !a.ifa_addr.is_null() {
                let name = std::ffi::CStr::from_ptr(a.ifa_name)
                    .to_string_lossy()
                    .into_owned();
                match (*a.ifa_addr).sa_family as i32 {
                    libc::AF_INET => {
                        let sin = &*(a.ifa_addr as *const libc::sockaddr_in);
                        out.push((
                            name,
                            IpAddr::from(u32::from_be(sin.sin_addr.s_addr).to_be_bytes()),
                        ));
                    }
                    libc::AF_INET6 => {
                        let sin6 = &*(a.ifa_addr as *const libc::sockaddr_in6);
                        out.push((name, IpAddr::from(sin6.sin6_addr.s6_addr)));
                    }
                    _ => {}
                }
            }
            p = a.ifa_next;
        }
        libc::freeifaddrs(list);
    }
    out
}

#[cfg(test)]
mod tests {
    use std::fs;

    use super::is_lan_interface_in;

    fn iface(root: &std::path::Path, name: &str, kind: &str, device: bool, bridge: bool) {
        let d = root.join(name);
        fs::create_dir_all(&d).unwrap();
        fs::write(d.join("type"), kind).unwrap();
        if device {
            fs::create_dir_all(d.join("device")).unwrap();
        }
        if bridge {
            fs::create_dir_all(d.join("bridge")).unwrap();
        }
    }

    /// Same Wi‑Fi only on real Wi‑Fi or wired Ethernet: never a VPN, bridge, container link or loopback.
    #[test]
    fn only_real_wifi_and_ethernet_count() {
        let t = tempfile::tempdir().unwrap();
        iface(t.path(), "wlp2s0", "1\n", true, false);
        iface(t.path(), "enp0s1", "1\n", true, false);
        iface(t.path(), "wg0", "65534\n", false, false); // WireGuard
        iface(t.path(), "tun0", "65534\n", false, false); // OpenVPN
        iface(t.path(), "docker0", "1\n", false, true); // a bridge
        iface(t.path(), "veth1a", "1\n", false, false); // a container's virtual Ethernet
        iface(t.path(), "lo", "772\n", false, false);
        for yes in ["wlp2s0", "enp0s1"] {
            assert!(is_lan_interface_in(t.path(), yes), "{yes}");
        }
        for no in [
            "wg0",
            "tun0",
            "docker0",
            "veth1a",
            "lo",
            "missing",
            "../wlp2s0",
        ] {
            assert!(!is_lan_interface_in(t.path(), no), "{no}");
        }
    }
}
