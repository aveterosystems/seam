// SPDX-License-Identifier: AGPL-3.0-only
//! Same Wi‑Fi as Linux provides it: the mDNS announcement, and which local addresses are on
//! Wi‑Fi or wired Ethernet. A connection on anything else (a VPN tunnel, mobile data, a bridge,
//! a container network, loopback) is refused, as on the Mac.

use std::net::IpAddr;
use std::sync::Mutex;

use mdns_sd::{ServiceDaemon, ServiceInfo};
use seam_computer::computer::SameWifi;

/// Fixed, so the announced name doesn't say whose computer it is (the Mac's are the same).
const NAME: &str = "Seam";
const TYPE: &str = "_seam._tcp.local.";

pub struct LinuxSameWifi {
    daemon: Mutex<Option<(ServiceDaemon, String)>>,
}

impl LinuxSameWifi {
    pub fn new() -> Self {
        Self {
            daemon: Mutex::new(None),
        }
    }
}

impl SameWifi for LinuxSameWifi {
    fn announce(&self, port: u16) {
        self.stop_announcing();
        let Ok(daemon) = ServiceDaemon::new() else {
            return;
        };
        let host = format!("seam-{:08x}.local.", rand::random::<u32>()); // not the computer's own name
        let addrs: Vec<IpAddr> = lan_addresses();
        let Ok(info) = ServiceInfo::new(
            TYPE,
            NAME,
            &host,
            &addrs[..],
            port,
            None::<std::collections::HashMap<String, String>>,
        ) else {
            return;
        };
        let info = if addrs.is_empty() {
            info.enable_addr_auto()
        } else {
            info
        };
        let full = info.get_fullname().to_string();
        if daemon.register(info).is_ok() {
            *crate::lock(&self.daemon) = Some((daemon, full));
        }
    }

    fn stop_announcing(&self) {
        if let Some((daemon, full)) = crate::lock(&self.daemon).take() {
            let _ = daemon.unregister(&full);
            let _ = daemon.shutdown();
        }
    }

    /// Only when every interface holding the address is Wi‑Fi or wired Ethernet.
    fn is_lan_address(&self, ip: IpAddr) -> bool {
        let holders = interfaces_holding(ip);
        !holders.is_empty() && holders.iter().all(|i| is_lan_interface(i))
    }
}

/// Wi‑Fi or wired Ethernet, as the kernel describes the interface: an Ethernet-type device
/// (ARPHRD 1) that is real hardware, not a bridge, VPN or virtual link.
fn is_lan_interface(name: &str) -> bool {
    let dir = std::path::Path::new("/sys/class/net").join(name);
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
