# Research: language and toolkit options for a Linux app

Ticket: #64, part of map #58. Researched 2026-11-01. Each claim gives its source. **Unverified** marks anything I couldn't confirm from a primary source; check those on the Linux test machine before a decision rests on them.

## Answer in short

- **Rust is the natural language.** The core is already Rust, so a Rust app links the core crates directly with no UniFFI layer. Every other option goes through generated bindings.
- **GTK4 + libadwaita (gtk-rs, optionally Relm4)** is the most mature Rust UI route. It looks right on GNOME and has a real spring animation API. On KDE it runs fine but looks like a GNOME app.
- **Qt/QML through cxx-qt** looks right on KDE and has the strongest animation system. But cxx-qt calls itself "early development, API changes frequently", and Qt apps look foreign on GNOME.
- **Kotlin (Compose Desktop)** reuses the Android bindings, but it draws its own widgets, so it looks native on neither desktop. Its Wayland and tray support are weak.
- **Swift on Linux** could reuse most of `SeamSession` (about 2,700 lines), but 4 of its 22 files use Network.framework and 1 uses AppKit. There's no SwiftUI, and the only GUI route is the young Adwaita for Swift (GNOME look).
- **Tray:** every toolkit ends up on StatusNotifierItem (SNI). KDE has it built in; stock GNOME needs the AppIndicator extension.

## What `SeamSession` uses that Linux doesn't have (from this repo)

`apps/mac/Packages/SeamSession/Sources/SeamSession/`, 22 files, about 2,749 lines:

| Import | Files | Linux status |
|---|---|---|
| `Foundation` | most | Available: swift-foundation plus swift-corelibs-foundation, "compatibility is best-effort only" ([swift-corelibs-foundation](https://github.com/swiftlang/swift-corelibs-foundation)) |
| `Observation` | ClipboardFeature, NotificationsFeature, StatusFeature | **Available.** It's part of the open-source stdlib, with Glibc/Musl/Android code paths ([source](https://github.com/swiftlang/swift/tree/main/stdlib/public/Observation/Sources/Observation)). `--enable-experimental-observation` defaults to true, and only the minimal/bootstrap presets turn it off ([driver_arguments.py](https://github.com/swiftlang/swift/blob/main/utils/build_swift/build_swift/driver_arguments.py), [build-presets.ini](https://github.com/swiftlang/swift/blob/main/utils/build-presets.ini)) |
| `Network` (`NWListener`, `NWConnection`, `NWParameters`, `NWEndpoint`, `NWProtocolTCP`) | LanAddress, LanServer, Pipe | **Apple-only.** There's no Linux Network.framework. NIO Transport Services "is only functionally useful on" Apple OSes. SwiftNIO (BSD sockets on Linux) is the replacement ([swift-nio-transport-services](https://github.com/apple/swift-nio-transport-services), [swift-nio](https://github.com/apple/swift-nio)) |
| `AppKit` (`NSImage` for notification app icons) | NotificationsFeature | **Apple-only.** It would need a platform image type or plain PNG `Data` |
| `OSLog` (`Logger`) | Session | **Apple-only** (unverified as a documented fact; it's an Apple SDK framework). [swift-log](https://github.com/apple/swift-log) is the portable API |
| `Darwin` | tests only | Use `Glibc` on Linux |

The Mac-specific paths in `AdbSetup.swift` (Homebrew, `~/Library/Android/sdk`) would also need Linux equivalents. `SeamCore` is a binary `.xcframework` (`apps/mac/Packages/SeamCore/Package.swift`), so Linux would need a `.so` plus a modulemap. UniFFI's Swift bindings working with SwiftPM on Linux is **unverified**.

The Swift route doesn't save the network layer: LanServer and Pipe are exactly the code that needs rewriting. Moving the session logic into the shared Rust core (an open question on #58) would make it shared by every option.

## UniFFI language support

First-party: Kotlin, Swift, Python, and Ruby (maintained, few new features). Third-party: JS/React Native, Kotlin Multiplatform (Gobley; Ubique), Go, C#, Dart, Java, Node, Haskell ([UniFFI manual](https://mozilla.github.io/uniffi-rs/latest/), [README](https://github.com/mozilla/uniffi-rs/blob/main/README.md)). It has **no C++ backend**, so Qt reaches Rust through cxx-qt, not UniFFI. Seam pins `uniffi = "0.32"`; the latest is 0.32.2 ([crates.io](https://crates.io/crates/uniffi)).

## The options

### 1. Rust + GTK4 + libadwaita (gtk-rs, optionally Relm4)

- **Maturity:** `gtk4` 0.11.5 (Sep 2026, about 5.3M downloads), `libadwaita` 0.9.2 (Jul 2026), `relm4` 0.11.0 (Apr 2026) ([crates.io](https://crates.io/crates/gtk4)). gtk-rs is part of GNOME Circle, and Relm4 is "an idiomatic GUI library inspired by Elm and based on gtk4-rs" ([gtk-rs.org](https://gtk-rs.org/), [relm4.org](https://relm4.org/)). Relm4's libadwaita coverage isn't stated on its site (**unverified**, but gtk-rs's `libadwaita` crate works underneath either way).
- **GNOME:** It's the platform toolkit, so it's native.
- **KDE:** It runs. libadwaita apps keep the Adwaita look and don't follow Breeze (**unverified** here; it's the libadwaita project's stated no-theming policy as I remember it).
- **Tray:** GTK4 has no tray API. GNOME Discourse says tray icons "have no replacement in GTK4" ([thread](https://discourse.gnome.org/t/what-to-use-instead-of-statusicon-in-gtk4-to-display-the-icon-in-the-system-tray/7175)). The Rust `ksni` crate (0.3.7, Oct 2026) implements SNI over D-Bus ([crates.io](https://crates.io/crates/ksni)). That works on KDE. GNOME Shell needs the AppIndicator extension (v66, Shell 45–51) ([extensions.gnome.org](https://extensions.gnome.org/extension/615/appindicator-support/)). Ubuntu ships that extension by default (**unverified**).
- **Notifications:** Use the xdg-desktop-portal Notification interface (v2): buttons, `default-action`, and inline reply through category `im.received` and button purpose `im.reply-with-text`, with the text returned in `ActionInvoked` ([portal spec](https://flatpak.github.io/xdg-desktop-portal/docs/doc-org.freedesktop.portal.Notification.html)). Whether GNOME's and KDE's portal backends implement v2 reply today is **unverified**; that's the motion.md/decision-0011 fallback question on #58.
- **Animation:** `AdwSpringAnimation` has damping ratio, epsilon, clamp and initial velocity, and you read its velocity to carry momentum ([docs](https://gnome.pages.gitlab.gnome.org/libadwaita/doc/main/class.SpringAnimation.html)). `AdwSpringParams` takes damping ratio, mass and stiffness, so `Motion.css(d)`'s damping 0.632 / response 0.388 × d can be converted directly. That conversion is **unverified**: I haven't checked it frame by frame. Retargeting a running spring isn't documented; you'd rebuild it from the current value and velocity. There's no SwiftUI-style implicit layout transitions, so entries 3, 5 and 6 (value swap, row insert and remove) are hand-built with `GtkRevealer`/`GtkStack` plus `AdwAnimation`.
- **Flatpak:** It fits best: the `org.gnome.Platform` runtime plus `org.freedesktop.Sdk.Extension.rust-stable` (active on Flathub, Oct 2026).

### 2. Rust + Qt 6 / QML (cxx-qt), optionally Kirigami

- **Maturity:** `cxx-qt` 0.10.0 (Aug 2026, about 160k downloads). In its own words: "in early development and the API changes frequently." It supports Qt 5.15 LTS and all Qt 6, with CI on Linux, Windows, macOS and WASM. Rust structs become QObjects with signals, slots and properties, usable from QML ([KDAB/cxx-qt](https://github.com/KDAB/cxx-qt)). KDE's own Rust getting-started page returned 404 when I checked, so KDE's endorsement is **unverified**.
- **KDE:** It's the platform toolkit (Kirigami, Breeze), so it's native.
- **GNOME:** Qt apps don't look like libadwaita apps. It's usable but visibly foreign (my own assessment).
- **Tray:** `QSystemTrayIcon` speaks SNI on "KDE, Gnome, Xfce, LXQt, and DDE". On GNOME Shell ≥ 3.26 "not all activation reasons are supported without shell extensions" ([Qt docs](https://doc.qt.io/qt-6/qsystemtrayicon.html)). The same AppIndicator caveat applies.
- **Notifications:** The same portal, or KNotification on KDE (**unverified** for inline reply).
- **Animation:** It's the strongest of the four. QML `SpringAnimation` (spring, damping, mass, epsilon) plus `Behavior`, states and transitions give implicit, declarative motion close to SwiftUI's model ([Qt docs](https://doc.qt.io/qt-6/qml-qtquick-springanimation.html)). Mid-flight retargeting isn't documented on that page (**unverified**).
- **Flatpak:** `org.kde.Platform` plus the Rust extension. cxx-qt also needs a C++ toolchain and CMake or Cargo build glue.

### 3. Kotlin (JVM desktop, reusing UniFFI's Kotlin bindings)

- **Reuse:** The bindings are first-party ([UniFFI](https://mozilla.github.io/uniffi-rs/latest/)), but the Android app's session code depends on Android services, so the reuse is the bindings, not the app (**unverified** how much of `apps/android` is platform-free).
- **UI:** Compose Multiplatform Desktop draws with Skia, a Material look, so it looks native on neither GNOME nor KDE. The motion would be close to Android's (Compose springs).
- **Wayland:** It runs on the JetBrains Runtime. Native Wayland (WLToolkit) became the IDE default only in 2026.1, and earlier previews drew their own title bars and lacked input methods ([JetBrains blog 2024](https://blog.jetbrains.com/platform/2024/07/wayland-support-preview-in-2024-2/), [2026.1 EAP](https://blog.jetbrains.com/platform/2026/02/wayland-by-default-in-2026-1-eap/)). Whether Compose Desktop inherits this is **unverified**.
- **Tray:** Compose's `Tray` is built on AWT `SystemTray`, which on Linux is XEmbed (**unverified**). XEmbed is gone on Wayland desktops, so an SNI library would be needed.
- **Flatpak:** It's possible with `org.freedesktop.Sdk.Extension.openjdk21` (active on Flathub), but it ships a JVM.
- **Not checked:** GTK bindings for Java (java-gi), which would give a native GNOME look from Kotlin.

### 4. Swift on Linux (reusing `SeamSession`)

- **Toolchain:** Swift 6.4.2 is current ([swift.org/install/linux](https://www.swift.org/install/linux/)).
- **Reuse:** See the import table above. Observation and Foundation come across. Network (LanAddress, LanServer, Pipe), AppKit (NotificationsFeature) and OSLog (Session) need replacing.
- **UI:** There's no SwiftUI on Linux. Adwaita for Swift is a declarative libadwaita framework, written up on the swift.org blog and proposed for GNOME Circle. Its repo moved off GitHub, and the older `AparokshaUI/Libadwaita` wrapper is unmaintained ([swift.org blog](https://swift.org/blog/adwaita-swift/), [Circle issue](https://gitlab.gnome.org/Teams/Circle/-/issues/222)). It gives a GNOME look only, with a small community. Its animation API is **unverified**.
- **Tray and notifications:** Nothing Swift-native; you'd call D-Bus (SNI, portal) yourself.
- **Flatpak:** `org.freedesktop.Sdk.Extension.swift6` exists and is active on Flathub (Oct 2026).

## Comparison

| | Rust + GTK/libadwaita | Rust + Qt/QML (cxx-qt) | Kotlin / Compose | Swift on Linux |
|---|---|---|---|---|
| Calls the core | Directly (Rust) | Directly (Rust) | UniFFI Kotlin | UniFFI Swift (Linux **unverified**) |
| Maturity | High | Bindings "early development" | Good, Wayland recent | Toolchain good, GUI young |
| Looks native on GNOME | Yes | No | No | Yes (Adwaita for Swift) |
| Looks native on KDE | No (Adwaita look) | Yes | No | No |
| Tray | ksni (SNI) | QSystemTrayIcon (SNI) | AWT, weak (**unverified**) | Write your own D-Bus |
| Springs and motion | AdwSpringAnimation, hand-built transitions | QML SpringAnimation + Behavior, best | Compose springs | **Unverified** |
| Flatpak | GNOME runtime + Rust | KDE runtime + Rust + C++ | OpenJDK extension | Swift 6 extension |
| Reuses Mac or Android code | None (but no FFI) | None (but no FFI) | Kotlin bindings | About 80% of SeamSession |

## What this leaves for the grilling ticket

1. One toolkit on both desktops, or GTK on GNOME and Qt on KDE? The second doubles the UI work. Only Rust makes it cheap, since both front ends would share a Rust session crate.
2. Where the session logic lives. Every option except Swift rewrites `SeamSession`, which argues for moving it into the Rust core first.
3. The GNOME tray: require the AppIndicator extension, or design a no-tray mode (window plus background portal).
