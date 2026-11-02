// SPDX-License-Identifier: AGPL-3.0-only
//! Who may connect on Same Wi‑Fi, and how waiting connections are bucketed.

use std::net::{IpAddr, Ipv4Addr};

/// Private, link-local or unique-local only: never the internet (a port forward, a VPN) or this
/// computer itself.
pub fn is_local(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(v4) => local_v4(v4),
        IpAddr::V6(v6) => {
            if let Some(v4) = v6.to_ipv4_mapped() {
                return local_v4(v4);
            }
            let b = v6.octets();
            (b[0] == 0xFE && b[1] & 0xC0 == 0x80) || b[0] & 0xFE == 0xFC // fe80::/10, fc00::/7
        }
    }
}

fn local_v4(a: Ipv4Addr) -> bool {
    let b = a.octets();
    b[0] == 10
        || (b[0] == 172 && b[1] & 0xF0 == 16)
        || (b[0] == 192 && b[1] == 168)
        || (b[0] == 169 && b[1] == 254)
}

/// The per-address limit's bucket: the IPv4 address (IPv4-mapped too), the exact address for
/// link-local IPv6 (the whole LAN shares fe80::/64, so the phone would share an attacker's
/// bucket), and the /64 for other IPv6 (one device can hold a whole /64).
pub fn address_key(ip: IpAddr) -> String {
    let v6 = match ip {
        IpAddr::V4(v4) => return v4.to_string(),
        IpAddr::V6(v6) => v6,
    };
    if let Some(v4) = v6.to_ipv4_mapped() {
        return v4.to_string();
    }
    let b = v6.octets();
    let hex = |bytes: &[u8]| bytes.iter().map(|x| format!("{x:02x}")).collect::<String>();
    if b[0] == 0xFE && b[1] & 0xC0 == 0x80 {
        return hex(&b);
    }
    hex(&b[..8]) + "::/64"
}
