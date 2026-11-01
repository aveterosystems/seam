# Linux: key storage, Same Wi‑Fi discovery, network name, USB

Research for [#62](https://github.com/aveterosystems/seam/issues/62), part of the map [#58](https://github.com/aveterosystems/seam/issues/58).
Checked 2026-11-01 against primary sources. Anything not confirmed from a primary source is marked **(unverified)**.

## The answer

| Mac hook | Linux equivalent | Plain host (deb/rpm) | Flatpak |
|---|---|---|---|
| Keychain (storage key) | Secret Service on the session bus (gnome-keyring on GNOME, ksecretd on Plasma 6). Rust: `oo7` | Works when a provider is running, which GNOME and Plasma both start. No provider = needs a fallback | Secret portal + an encrypted file inside the sandbox. `oo7` does this automatically. No extra permission |
| Bonjour `_seam._tcp` | Avahi over the system bus, or the pure-Rust `mdns-sd` crate | Avahi ships on most desktop distros, not on Arch by default. `mdns-sd` needs nothing | Avahi needs `--system-talk-name=org.freedesktop.Avahi`. `mdns-sd` needs only `--share=network` |
| Wi‑Fi name (Wi‑Fi lock) | NetworkManager D-Bus: primary connection → device → active access point → `Ssid` | Readable by any user, no prompt, no location permission | Needs `--system-talk-name=org.freedesktop.NetworkManager`. The NetworkMonitor portal does **not** give the name |
| adb for the clipboard setup | The user's `adb` (`/usr/bin/adb` from the distro), over wireless debugging first, USB second | USB needs udev rules that tag the phone `uaccess` | Wireless debugging needs only network. USB needs `--device=usb` (Flatpak ≥ 1.15.11) or `--device=all`. The USB portal doesn't help adb |

Recommendation in one line: **`oo7` for the key, `mdns-sd` for Same Wi‑Fi, `zbus` to read NetworkManager, and the wireless-debugging path for adb.** That stack is all Rust, needs no C libraries, and behaves the same on GNOME and KDE, in or out of Flatpak.

Two things worth knowing about today's Mac app, from reading the code:

- The Mac **doesn't bundle adb**. `AdbSetup.locate` checks three fixed paths (`/opt/homebrew/bin/adb`, `/usr/local/bin/adb`, `~/Library/Android/sdk/platform-tools/adb`), in `apps/mac/Packages/SeamSession/Sources/SeamSession/AdbSetup.swift`. Linux can do the same thing with `/usr/bin/adb` and `~/Android/Sdk/platform-tools/adb`.
- The Mac code doesn't read the Wi‑Fi name yet. "Wi‑Fi lock" is in `GLOSSARY.md`, but no Swift code reads an SSID (searched for `ssid`, `CoreWLAN`, `CWWiFi`). So Linux would be building this before the Mac, or alongside it.

---

## 1. Storing the storage key (Mac: Keychain)

### Secret Service (host)

- A freedesktop D-Bus API (`org.freedesktop.Secret.*`: Service, Collection, Item, Session, Prompt). The current spec is "Secret Service 0.2 DRAFT". Secrets are sent either `plain` or encrypted with `dh-ietf1024-sha256-aes128-cbc-pkcs7` ([spec](https://specifications.freedesktop.org/secret-service/latest/), [transfer of secrets](https://specifications.freedesktop.org/secret-service/latest/transfer-secrets.html)).
- Collections can be locked. `Unlock()` may hand back a Prompt the client has to run, so the app must cope with a password dialog or a refusal ([locking and unlocking](https://specifications.freedesktop.org/secret-service/latest/unlocking.html)). The login keyring is normally unlocked by PAM at login **(unverified per distro)**.
- **GNOME:** gnome-keyring provides it.
- **KDE Plasma 6:** the old kwalletd became **ksecretd**, which only offers the Secret Service API and is the default. The KWallet D-Bus API is now a translation layer on top of it. oo7 is being tried as a replacement but is "still at an experimental stage" ([Marco Martin, KDE, 2026-01-30](https://planet.kde.org/marco-martin-2026-01-30-kwallet-secretservice-oo7-the-story-so-far/); split in [kwallet MR 97](https://invent.kde.org/frameworks/kwallet/-/merge_requests/97)). Plasma 6 setups with PAM/activation trouble do show up in the wild ([Arch wiki: KDE Wallet](https://wiki.archlinux.org/title/KDE_Wallet); forum reports only, so **(unverified)** how common it is).
- So **one API covers both desktops; nobody needs to write KWallet code.**
- Outside GNOME and Plasma (a bare tiling WM, say) there may be no provider at all. Seam needs a clear error or a fallback for that. Picking one is a product decision.

### Secret portal + file backend (Flatpak)

- `org.freedesktop.portal.Secret.RetrieveSecret(fd, options)` writes a per-app master secret to a file descriptor. The secret "is unique per application and does not change as long as the application is installed". In a typical backend it "is stored in the user's keyring, under the application ID as a key" ([portal docs](https://flatpak.github.io/xdg-desktop-portal/docs/doc-org.freedesktop.portal.Secret.html), [XML](https://github.com/flatpak/xdg-desktop-portal/blob/main/data/org.freedesktop.portal.Secret.xml)).
- The app then encrypts its own keyring file inside the sandbox with that secret. **No `--talk-name=org.freedesktop.secrets` needed**: the default session-bus policy already lets apps talk to `org.freedesktop.portal.*` ([Flatpak sandbox permissions](https://docs.flatpak.org/en/latest/sandbox-permissions.html)). Flathub wants portals over static permissions ([Flathub requirements](https://docs.flathub.org/docs/for-app-authors/requirements)).
- **libsecret** picks its `file` backend automatically when `/.flatpak-info` exists (or `SNAP_NAME` is set) and the portal is there. Otherwise it uses `SECRET_BACKEND`, which defaults to `service` ([secret-backend.c](https://gitlab.gnome.org/GNOME/libsecret/-/blob/main/libsecret/secret-backend.c)). Latest version is 0.21.8.2. The file backend got locking against concurrent writes in 0.21.8 ([NEWS](https://gitlab.gnome.org/GNOME/libsecret/-/blob/main/NEWS)).
- **GNOME** backend: gnome-keyring. **KDE** backend: KWallet's own `kwallet` portal backend. A bug that made a new secret on every call ([KDE 487348](https://bugs.kde.org/show_bug.cgi?id=487348)) was fixed in Frameworks 6.2.1 (2024). So any Plasma older than that loses Flatpak secrets after a restart.
- The portal is Flatpak/Snap only in practice. A host (deb/rpm) build should use Secret Service directly. Whether the portal can identify a host app at all **(unverified)**.

### Rust

- **[`oo7`](https://docs.rs/oo7/latest/oo7/)** (0.6.0 stable; 0.7.0-beta on crates.io). `Keyring::new()` checks whether it's sandboxed. Inside a sandbox it uses the file backend with the Secret portal and falls back to the host Secret Service if the portal is missing; outside it uses Secret Service over D-Bus ([source](https://github.com/bilelmoussaoui/oo7/blob/main/client/src/keyring.rs)). The file format is "compatible with libsecret", and it has a `migrate` helper to move host secrets into the sandbox. Pure Rust (`native_crypto`, default), tokio by default. **This is the closest match to `Keychain.swift`**: one call, the right backend.
- `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` has no direct equivalent. The nearest is "the login collection, unlocked at login". Secret Service items are readable by **any** unsandboxed process in the user's session. That's weaker than the Mac's per-app Keychain access, and the threat model should say so. Inside Flatpak the file backend avoids this.

## 2. Advertising `_seam._tcp` on Same Wi‑Fi (Mac: Bonjour)

The Mac advertises the fixed name "Seam", type `_seam._tcp`, on a listener with an ephemeral port, on Wi‑Fi and Ethernet only (`LanServer.swift`).

### Avahi

- The Avahi docs recommend the D-Bus API for "software written in any language other than C", recommend avahi-client for C, and warn against embedding avahi-core in desktop apps. The Bonjour compat layer is "incomplete and generally a waste of resources" ([Avahi docs](https://avahi.org/doxygen/html/)).
- Publish with `Server.EntryGroupNew` → `EntryGroup.AddService(interface i, protocol i, flags u, name s, type s, domain s, host s, port q, txt aay)` → `Commit`. Because it takes an interface index, Wi‑Fi/Ethernet-only can be done per interface ([EntryGroup XML](https://github.com/avahi/avahi/blob/master/avahi-daemon/org.freedesktop.Avahi.EntryGroup.xml)). The default D-Bus policy lets any user publish ([avahi-dbus.conf](https://github.com/avahi/avahi/blob/master/avahi-daemon/avahi-dbus.conf.in)).
- Installed and running by default on Fedora Workstation, Ubuntu desktop and openSUSE; on Arch it has to be installed and enabled **(unverified per distro; from general knowledge)**.
- **Flatpak:** the system bus is blocked by default, so this needs `--system-talk-name=org.freedesktop.Avahi` ([sandbox permissions](https://docs.flatpak.org/en/latest/sandbox-permissions.html)). Whether Flathub accepts that name **(unverified)**.

### `mdns-sd` (pure Rust)

- [`mdns-sd`](https://docs.rs/mdns-sd/latest/mdns_sd/) 0.21.6 (2026-10-19). Its own daemon thread, "no async runtime dependency", flume channels. `IfKind` turns interfaces on or off. Multicast only ([crate](https://crates.io/crates/mdns-sd)).
- It binds UDP 5353 with `SO_REUSEADDR` and `SO_REUSEPORT` ([service_daemon.rs](https://github.com/keepsimple1/mdns-sd/blob/main/src/service_daemon.rs)). Avahi also sets SO_REUSEADDR on its sockets "to make sure that the user may run other mDNS implementations if they really want", so both can share the port. Avahi does call two stacks on one host "unreliable and … not recommended" ([avahi-core/socket.c](https://github.com/avahi/avahi/blob/master/avahi-core/socket.c)). How well they share in practice needs testing **(unverified)**. Same goes for systemd-resolved with `MulticastDNS=yes` **(unverified)**.
- **Flatpak:** only `--share=network`, which gives the host network ([sandbox permissions](https://docs.flatpak.org/en/latest/sandbox-permissions.html)), so binding 5353 should work **(inference; check in the prototype)**. Works on Arch without Avahi.
- **Pick:** `mdns-sd`. It needs no system-bus hole in Flatpak and doesn't depend on Avahi being there. Fall back to Avahi only if testing next to a running avahi-daemon shows it's unreliable.

### Firewalls (both choices)

The phone has to reach UDP 5353 and Seam's ephemeral TCP port on the computer.
- Fedora Workstation's zone opens UDP/TCP 1025–65535, so it works out of the box ([FedoraWorkstation.xml](https://src.fedoraproject.org/rpms/firewalld/blob/rawhide/f/FedoraWorkstation.xml)).
- firewalld's stock `public` zone allows only ssh and dhcpv6-client, so **both** mDNS and Seam's port are blocked ([public.xml](https://github.com/firewalld/firewalld/blob/main/config/zones/public.xml)). Which distros use `public` by default **(unverified)**.
- Ubuntu ships ufw turned off **(unverified)**.
- A fixed port, or a firewalld service file shipped with the package, may be needed. That's a spec decision.

## 3. Reading the Wi‑Fi name (Wi‑Fi lock)

- NetworkManager (system bus, `org.freedesktop.NetworkManager`). Both GNOME and Plasma use it for their network settings. Read path:
  1. `PrimaryConnection`, "the 'primary' active connection being used to access the network" ([NM API](https://networkmanager.dev/docs/api/latest/gdbus-org.freedesktop.NetworkManager.html))
  2. its `Devices`
  3. `Device.Wireless.ActiveAccessPoint`
  4. `AccessPoint.Ssid` (`ay`, raw bytes, not UTF-8 text) and `HwAddress` (the BSSID) ([AccessPoint API](https://networkmanager.dev/docs/api/latest/gdbus-org.freedesktop.NetworkManager.AccessPoint.html))
- Watch `PropertiesChanged` to see network switches. A wired primary connection has no SSID: decide what Wi‑Fi lock does on Ethernet.
- **Permissions:** NM's D-Bus policy lets any user call `Properties`/`ObjectManager` and the read-only `AccessPoint` interface, with no polkit prompt ([org.freedesktop.NetworkManager.conf](https://gitlab.freedesktop.org/NetworkManager/NetworkManager/-/blob/main/src/core/org.freedesktop.NetworkManager.conf)). Unlike macOS, there's no location-permission gate.
- **Flatpak:** needs `--system-talk-name=org.freedesktop.NetworkManager`. The NetworkMonitor portal only offers available / metered / connectivity / CanReach, **no network name** ([NetworkMonitor portal](https://flatpak.github.io/xdg-desktop-portal/docs/doc-org.freedesktop.portal.NetworkMonitor.html)). No portal gives the SSID, so a Flatpak build either asks for that permission or shows Wi‑Fi lock as "Not available on this computer" (decision 0011).
- **Without NetworkManager** (iwd-only, systemd-networkd, ConnMan) none of this exists. Seam has to handle "can't tell the network", most simply by treating it as "not a chosen network" so Same Wi‑Fi stays hidden.
- Rust: `zbus` 6.x speaks D-Bus directly. No C library needed.

## 4. adb for the automatic-clipboard setup

- **Wireless debugging needs nothing special on Linux:** Android 11+, `adb pair ip:port` plus the pairing code, then adb finds the phone itself over mDNS (`_adb-tls-connect._tcp`, the `LIBADBMDNS` backend in adb 37) ([adb docs](https://developer.android.com/tools/adb)). No udev, no groups. In Flatpak it only needs `--share=network`. **Make this the first path on Linux.**
- **USB** needs the device node to be writable by the user:
  - Google's instructions (Ubuntu): join `plugdev` and install `android-sdk-platform-tools-common` for the udev rules ([Run apps on a hardware device](https://developer.android.com/studio/run/device)).
  - The community rules set `MODE="0660", GROUP="adbusers", TAG+="uaccess"`. `uaccess` gives the logged-in user access with no group change ([51-android.rules](https://github.com/M0Rf30/android-udev-rules/blob/main/51-android.rules)).
  - systemd's own `70-uaccess.rules` already tags PTP/MTP devices and `ID_MEDIA_PLAYER` USB devices `uaccess` ([70-uaccess.rules.in](https://github.com/systemd/systemd/blob/main/rules.d/70-uaccess.rules.in)). So a phone in file-transfer mode is often reachable even without Android rules **(inference; check on hardware)**.
  - When access is denied, adb lists the phone as `no permissions`. The setup dialog should spot this and explain it **(unverified exact string)**.
- **Flatpak USB:**
  - `--device=usb` exposes raw `/dev/bus/usb` (Flatpak ≥ 1.15.11). Older Flatpak needs `--device=all` ([sandbox permissions](https://docs.flatpak.org/en/latest/sandbox-permissions.html)). The host's udev permissions still apply **(inference)**.
  - The USB portal hands out fds meant for `libusb_wrap_sys_device()` ([USB portal](https://flatpak.github.io/xdg-desktop-portal/docs/doc-org.freedesktop.portal.Usb.html)). Stock adb opens devices itself and can't take an fd, so the portal doesn't help **(unverified from adb source)**.
- **Getting adb:** distros package it from AOSP source (Debian/Ubuntu `adb`, Fedora/Arch `android-tools`) at `/usr/bin/adb` **(unverified paths per distro)**. Like the Mac, look in fixed paths and never search `PATH`. Inside Flatpak the host's `/usr/bin/adb` can't be seen, so the Flatpak would have to bundle adb built from AOSP (Apache-2.0). Google's prebuilt platform-tools zip is under the Android SDK License, so redistributing it is **(unverified)**.

## Open questions for the spec

1. A deb/rpm build, a Flatpak, or both? Flatpak pushes NM toward a system-bus permission (or no Wi‑Fi lock), and adb toward bundling.
2. What happens with no Secret Service provider: refuse to start, or something else?
3. Ship a firewalld service and/or a fixed port?
4. Wi‑Fi lock on Ethernet, and when NetworkManager isn't there.
5. A prototype on the test machine: `mdns-sd` next to a running avahi-daemon, seen by the Android `NsdManager`.
