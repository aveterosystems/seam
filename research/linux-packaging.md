# How to ship Seam on Linux: Flatpak, distro packages or AppImage

Research for [#63](https://github.com/aveterosystems/seam/issues/63), part of the map [#58](https://github.com/aveterosystems/seam/issues/58). Sources read on 2026-11-01. Anything marked **(unverified)** I didn't confirm in a primary source.

## Answer

**Ship one first-party Flatpak on Flathub, built from source. Skip AppImage. Keep a signed .deb/.rpm repo as a fallback for later, not for v1.**

- Flathub accepts AGPL apps (Signal Desktop is on Flathub as `AGPL-3.0-only`), and it builds everything offline from source. Our Rust core already builds on `ubuntu-latest` in CI, so the work is turning `core/Cargo.lock` into Flatpak sources, not porting anything.
- Every Seam capability either has a portal or a D-Bus name Flathub allows: tray, notifications (inline reply exists in the portal spec), autostart and running in the background, network, USB.
- `adb` can't go on Flathub as Google's prebuilt binary (the SDK licence forbids redistribution). It has to be built from AOSP source as a manifest module, the way distros do it.
- Updates come from the user's software centre (GNOME Software, Discover) or `flatpak update`. That's our version of Sparkle, and we don't write any updater code.
- **The real risk is the clipboard, and packaging doesn't fix it.** GNOME (Mutter) has no protocol that lets a background app read the clipboard. KDE Connect and GSConnect both get around this from *outside* a sandbox: KDE Connect uses KWin's data-control protocol, and GSConnect runs inside GNOME Shell as an extension. A Shell extension can't ship in a Flatpak. Whatever the clipboard ticket decides, the GNOME part may need a small companion extension shipped through extensions.gnome.org, whichever format the app itself uses.

## The three options side by side

| | Flatpak (Flathub) | Own .deb/.rpm repo | AppImage |
|---|---|---|---|
| Clipboard in background | Portal only through a RemoteDesktop/InputCapture session ¹ | Same Wayland limits, but no sandbox in the way of KWin data-control | Same as distro packages |
| Tray (StatusNotifierItem) | `--talk-name=org.kde.StatusNotifierWatcher` (Signal does this) | Works | Works |
| Notifications with reply | Notification portal v2, `im.reply-with-text` ² | `org.freedesktop.Notifications` directly | Same as distro packages |
| D-Bus | Filtered: talk to named services only, never the whole bus | Unrestricted | Unrestricted |
| USB / adb | `--device=usb` (Flatpak ≥ 1.15.11) or `--device=all`. Can't install udev rules | Can ship or depend on udev rules | Can't install udev rules |
| Network, mDNS, Tor | `--share=network` | Works | Works |
| Starting at login | Background portal `autostart: true` | XDG autostart `.desktop` file | App writes its own autostart file |
| Updates | Software centre / `flatpak update`, published 1–2 h after merge | `apt`/`dnf` from our signed repo | AppImageUpdate (zsync), **no signature check**, opt-in |
| Reach | One build for every distro | One build per format, kept up to date as distros move | One file, needs FUSE 2 (not installed by default everywhere) |
| Our ongoing cost | Manifest + `cargo-sources.json` per release | Repo hosting, signing keys, distro version matrix | Updater integration, FUSE support questions |

¹ ² Details below.

## Flatpak and Flathub

### Policy

- **Build from source, no binaries in the submission.** "Binary or precompiled files must not be present in the submission pull request" and "All source available submissions must be built entirely from source code." Exceptions are for "well-known vendors on a case-by-case basis". [Flathub requirements](https://docs.flathub.org/docs/for-app-authors/requirements)
- **No network during the build.** Every dependency is a declared source with a public URL. [Flathub requirements](https://docs.flathub.org/docs/for-app-authors/requirements)
- **Licence:** content must "allow legal redistribution" and the licence must be declared correctly in MetaInfo. AGPL isn't mentioned. [Flathub requirements](https://docs.flathub.org/docs/for-app-authors/requirements). Precedent: Flathub's `org.signal.Signal` declares `<project_license>AGPL-3.0-only</project_license>` ([metainfo](https://github.com/flathub/org.signal.Signal/blob/master/org.signal.Signal.metainfo.xml)). That package repackages Signal's prebuilt .deb and calls itself "an **unofficial** redistribution" ([README](https://github.com/flathub/org.signal.Signal)), so it's the vendor exception at work, not a route open to us.
- **Permissions:** "Static permissions must be kept to an absolute minimum". Where a portal exists, "using the portal becomes mandatory instead of static permissions". [Flathub requirements](https://docs.flathub.org/docs/for-app-authors/requirements)
- **Never granted:** `--socket=session-bus` / `--socket=system-bus` (whole-bus access) and `--own-name=<app id>` (it's redundant, the app already owns its own namespace). [Flathub linter](https://docs.flathub.org/docs/for-app-authors/linter). Seam's own D-Bus names have to live under its app ID, e.g. `com.avetero.Seam.*`. [Flatpak sandbox permissions](https://docs.flatpak.org/en/latest/sandbox-permissions.html)
- **Verification:** a token at `https://<domain>/.well-known/org.flathub.VerifiedApps.txt` for our app-ID domain. [Flathub requirements](https://docs.flathub.org/docs/for-app-authors/requirements)
- **Self-updaters:** Flathub's docs don't contain a written "no in-app updater" rule. Projects say reviewers reject them **(unverified)**. It doesn't matter for us: the Flatpak build just leaves out any Sparkle-style updater.

### Updates

You open a PR on the `flathub/<app-id>` repo. It gets a test build, and once merged it is "usually [published] within 1-2 hours". The External Data Checker can open those PRs automatically when we tag a release. Auto-merging them is discouraged and only allowed for verified apps. A `beta` branch gives a beta channel. [Flathub maintenance](https://docs.flathub.org/docs/for-app-authors/maintenance)

### Capabilities, one by one

- **Clipboard:** the Clipboard portal "doesn't create sessions of its own". It extends a session from another portal, and "Currently, the Remote Desktop and Input Capture portals support clipboard integration". [Clipboard portal](https://flatpak.github.io/xdg-desktop-portal/docs/doc-org.freedesktop.portal.Clipboard.html). So background clipboard in a Flatpak means asking the user for a remote-desktop session. Whether GNOME and KDE keep such a session alive across logins without asking again is **(unverified)**, and it belongs to the clipboard ticket.
  - Outside a sandbox: `ext-data-control-v1` lets "a privileged client… take the role of a clipboard manager". KWin supports it, **Mutter (GNOME) does not**. [wayland.app: ext-data-control-v1](https://wayland.app/protocols/ext-data-control-v1). KDE Connect's Wayland clipboard uses the wlr data-control protocol ([KDE bug 390933](https://bugs.kde.org/show_bug.cgi?id=390933)). Whether KWin offers data-control to Flatpak'd clients is **(unverified)**.
  - GNOME: GSConnect is installed "from the GNOME Shell Extensions website" ([GSConnect wiki](https://github.com/GSConnect/gnome-shell-extension-gsconnect/wiki/Installation)). Valent, the GTK KDE Connect client, ships as a nightly Flatpak with a separate [GNOME Shell extension](https://github.com/andyholmes/gnome-shell-extension-valent) companion ([Valent](https://github.com/andyholmes/valent)). Neither page says *why* clipboard needs the shell; that it's the reason is my inference.
- **Tray:** there's no tray portal. Flathub's Signal uses `--talk-name=org.kde.StatusNotifierWatcher` ([manifest](https://github.com/flathub/org.signal.Signal/blob/master/org.signal.Signal.yaml)), and the linter doesn't flag it ([linter](https://docs.flathub.org/docs/for-app-authors/linter)). Stock GNOME shows no StatusNotifierItem icons without the [AppIndicator extension](https://github.com/ubuntu/gnome-shell-extension-appindicator). Whether Ubuntu enables it by default is **(unverified)** here (the repo doesn't say). This is the same for every packaging format.
- **Notifications with reply:** Notification portal v2 has categories (`im.received`) and button purposes, including `im.reply-with-text`: "Inline replies for instant messaging. The user-provided text will be added to the response." Dismiss and actions are standard. [Notification portal](https://flatpak.github.io/xdg-desktop-portal/docs/doc-org.freedesktop.portal.Notification.html). Which GNOME and KDE portal backends actually draw a reply field is **(unverified)**. Servers report what they handle through `SupportedOptions`, so Seam can check that at runtime and fall back (map #58 already lists "reply where the desktop can't").
- **Autostart and background:** `RequestBackground` with `autostart: true` ("wants to be started automatically at login"), optional `commandline`, `dbus-activatable`. [Background portal](https://flatpak.github.io/xdg-desktop-portal/docs/doc-org.freedesktop.portal.Background.html)
- **Network:** off by default. `--share=network` turns it on. It also exposes host abstract sockets. [Sandbox permissions](https://docs.flatpak.org/en/latest/sandbox-permissions.html). mDNS and Arti need nothing more **(unverified for mDNS multicast through the sandbox, which is expected to work because the app shares the host network namespace)**.
- **USB:** `--device=usb` ("Since Flatpak 1.15.11") gives raw `/dev/bus/usb`. The USB portal gives per-device file descriptors after the user agrees. Conditional `--device-if=all:!has-usb-device` covers older Flatpak versions. [Sandbox permissions §USB](https://docs.flatpak.org/en/latest/sandbox-permissions.html#usb-portal), [USB portal](https://flatpak.github.io/xdg-desktop-portal/docs/doc-org.freedesktop.portal.Usb.html). Stock adb opens usbfs itself rather than accepting portal fds **(unverified)**, so expect `--device=usb`, not the portal.
  - A Flatpak **can't install udev rules**. Android's own Linux setup needs the user in `plugdev` *and* udev rules from `android-sdk-platform-tools-common`. [Android: run on a hardware device](https://developer.android.com/studio/run/device). Seam's grant also works over **wireless debugging** (`apps/mac/Packages/SeamSession/Sources/SeamSession/AdbSetup.swift`; the Mac dialog already leads with it, #53). That path needs no USB permission or udev at all, so it should be the main path on Linux.

### Bundling adb

- The Mac **doesn't bundle adb** today. It looks for one in fixed places (`AdbSetup.locate`). A Flatpak can't see the host's `/usr/bin/adb` without host filesystem access, so on Linux we'd bundle it.
- Google's platform-tools download falls under the Android SDK License: "you may not copy … redistribute … the SDK", though open-source components are "governed solely by the terms of that open source software license". [platform-tools](https://developer.android.com/tools/releases/platform-tools). So: build from source, never ship Google's zip.
- [nmeum/android-tools](https://github.com/nmeum/android-tools) (Apache-2.0) is a CMake tarball of adb and fastboot made for packagers. Arch, Alpine and Void use it. It fits as one Flatpak module (or a `.deb`/`.rpm` dependency). Size and build time **(unverified)**.
- Port clash: adb runs a server on localhost:5037 **(unverified, from memory)**. A bundled adb and a host adb would fight over it, whatever the format. The Linux spec should start adb on its own port (`ADB_SERVER_SOCKET`) or kill it after the grant.

### Building the core for Flatpak

- What we have: `core/Cargo.lock` has 624 packages. libsignal and SparsePostQuantumRatchet are **git** dependencies (tag-pinned). Arti uses `rustls` + `ring` + `static-sqlite`, with no OpenSSL or BoringSSL. The build needs `protoc` (`.github/workflows/core.yml`). It already builds on `ubuntu-latest` in CI.
- Rust comes from `org.freedesktop.Sdk.Extension.rust-stable`, on the same branch as the runtime. `flatpak-cargo-generator.py Cargo.lock -o cargo-sources.json` vendors every crate as a source. With git dependencies, copy the generated config to `.cargo/config.toml`, or the offline build fails with "you are in the offline mode". [flatpak-builder-tools/cargo](https://github.com/flatpak/flatpak-builder-tools/tree/master/cargo)
- `protoc`: whether the freedesktop SDK includes it is **(unverified)**. If not, it's one more module built from source.
- Per release that's: regenerate `cargo-sources.json`, bump the tag, open the Flathub PR. It can be scripted from `scripts/release.sh`.

## Distro packages

- **Official Debian/Fedora repos: not realistic for us.** Debian's Rust team packages every crate separately as `librust-*-dev`, and "builds must use the packaged versions of crate sources" ([Debian Rust policy](https://wiki.debian.org/Teams/RustPackaging/Policy)). Fedora allows vendoring only as "a last resort", with `Provides: bundled(crate(...))` for every crate ([Fedora Rust guidelines](https://docs.fedoraproject.org/en-US/packaging-guidelines/Rust/), read via search results because the page was bot-blocked). With 624 crates, exact Arti pins and a git libsignal, that's a maintainer's job, and it would be community work, not first-party.
- **Our own signed repo (the Signal model) is doable.** Signal Desktop's official Linux install is its own APT repo: dearmored key in `/usr/share/keyrings/`, a `.sources` file in `/etc/apt/sources.list.d/`, "only work for 64-bit Debian-based" distros. [signal.org/download/linux](https://signal.org/download/linux/). Fedora/RPM would be a second repo. Benefits: no sandbox (KWin data-control, host adb, udev rules as a dependency), updates through `apt`/`dnf`. Costs: a build per distro family, repo hosting, a second signing key to protect, and testing across distro versions.
- KDE Connect itself is "available on all major distributions", packaged by the distros ([kdeconnect.kde.org](https://kdeconnect.kde.org/download.html)). There's no `flathub/org.kde.kdeconnect` repo (checked with `gh`, 404).
- Autostart outside a sandbox: an XDG autostart `.desktop` file in `$XDG_CONFIG_HOME/autostart` ([Desktop Application Autostart spec](https://specifications.freedesktop.org/autostart/latest/), not re-read this session).

## AppImage

- Updates: update information is embedded with `appimagetool -u`, plus a `.zsync` file. AppImageUpdate / libappimageupdate apply it. **"Signature validation is not yet part of the process."** The rules say never to download updates without the user's explicit consent. [AppImage updates](https://docs.appimage.org/packaging-guide/optional/updates.html). For an end-to-end-encrypted app whose Mac updates are signed (Sparkle EdDSA), an unsigned update channel is a step backwards.
- Needs FUSE 2 (`libfuse2`, renamed `libfuse2t64` on Ubuntu 24.04) that "some [distros]" don't ship. [AppImage FUSE](https://docs.appimage.org/user-guide/troubleshooting/fuse.html)
- No sandbox, but also no autostart, menu entry or tray integration unless the app adds them itself.
- Signal now offers an AppImage too, signed with GPG and verified by hand, with "Auto-updates can be configured in settings" ([signal.org/download/linux](https://signal.org/download/linux/)). That's their own updater, not AppImageUpdate.

## What this means for the Linux spec

1. Format: Flatpak, app ID under our domain, on Flathub, verified. Release CI builds the same manifest with `flatpak-builder` as the `linux` check.
2. Updates: none in-app on Flatpak. The software centre handles them. No Sparkle equivalent to build.
3. adb: built from source in the manifest. Wireless debugging is the main path. USB via `--device=usb` is best-effort, and on failure the app says the computer needs Android udev rules.
4. The clipboard ticket has to settle GNOME: a RemoteDesktop-portal clipboard session, or a companion Shell extension shipped separately through extensions.gnome.org. A signed .deb/.rpm repo only becomes worth it if that ticket shows the sandbox blocks a must-have (e.g. KWin data-control).
5. Snap wasn't in the question and wasn't researched.
