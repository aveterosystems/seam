# Seam for Android

A native Android app in Kotlin and Jetpack Compose (Material 3), for Android 12 (API 31) and later. It mirrors notifications and texts to your Mac, syncs the clipboard, and sends and receives files.

> **Status:** 0.2.1. Pairs with a Mac by scanning its QR code, syncs the clipboard both ways, sends the battery level, mirrors the notifications of the apps you choose (with reply and dismiss from the Mac), and connects only when there's something to move (decision 0005). Tested on a Galaxy S25 Ultra running Android 17. Get it from [Releases](https://github.com/aveterosystems/seam/releases/latest).

## Screens

Built to the design boards: Home (A01), pairing with Seam's own camera view (A02), the set-up checklist after pairing (A03), the Send to Mac share sheet (A04), and Settings with Notifications and About (A06). Teal accents, the same as the Mac, on neutral surfaces, in light and dark. Screens are built from the shared pieces in `Components.kt`.

## Modules

```mermaid
flowchart TB
    app[":app<br/>screens · service · flavours"] --> features[":feature:pairing · :feature:clipboard · :feature:notifications · :feature:status"]
    app --> session
    features --> session[":core:session<br/>relay and Same Wi‑Fi paths · pairing · delivery"]
    session --> crypto[":core:crypto<br/>Kotlin bindings + Rust core .so"]
    session --> protocol[":core:protocol<br/>generated messages"]
    crypto --> rust["core/seam-ffi (Rust)<br/>built per ABI with cargo-ndk"]
    protocol --> proto["protocol/seam/v1/*.proto"]
    logic["build-logic/<br/>convention plugins"] -. "configures" .-> app & features & session & crypto & protocol
```

| Module | What |
|---|---|
| `app` | The application: the screens, `ConnectionService`, the share sheet, the after-restart reminder, and the `play` / `full` flavours |
| `core/session` | The phone's link to its Mac: `Session` (pairing, delivery and acks, the features, and when to connect, decision 0005) over two paths, `RelayPath` (relay login, backoff) and `LanPath` (Same Wi‑Fi discovery and handshake). Which path to use comes from the core (decision 0006) |
| `feature/pairing` | The camera view: CameraX frames decoded on the phone by ZXing (no network code, no Google services) |
| `feature/clipboard` | Clipboard both ways, the share and copy rules, and automatic phone → Mac in the `full` build |
| `feature/notifications` | The notification listener, the per-app settings (every app starts off; the phone filters before anything is sent), and reply, buttons and dismiss from the Mac |
| `feature/status` | The battery level for the Mac |
| `core/crypto` | The Kotlin bindings to the Rust core (`seam_ffi.kt`, generated) and the native library for each ABI |
| `core/protocol` | Message types generated from `protocol/` at build time (protobuf-kotlin lite) |
| `build-logic/convention` | Shared Gradle setup: `seam.android.application`, `seam.android.library`, `seam.android.compose` |
| `gradle/libs.versions.toml` | Every dependency version, in one place |

More `feature/*` modules are added as their milestones start.

## Flavours

| Flavour | Distribution | Differences |
|---|---|---|
| `play` | Google Play | No SMS permission; texts are answered through notification replies |
| `full` | GitHub Releases (sideloaded) | Supports automatic phone → Mac clipboard (and SMS, when texts arrive). This is the build in each release. |

## Building

**Needs:** JDK 17, the Android SDK, NDK `29.0.14206865`, Rust (rustup) with the Android targets, [cargo-ndk](https://github.com/bbqsrc/cargo-ndk) and `protoc`.

```bash
# One-time Rust setup
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
cargo install cargo-ndk --version 4.1.2 --locked

# Build both flavours
cd apps/android
./gradlew assemblePlayDebug assembleFullDebug
```

Gradle builds the Rust core for `arm64-v8a`, `armeabi-v7a` and `x86_64` automatically and packs it into the APK. Install on a phone with USB debugging on:

```bash
adb install -r app/build/outputs/apk/full/debug/app-full-debug.apk
```

Release builds are built and signed by the release workflow, never locally; see [Releasing](../../CONTRIBUTING.md#releasing).

## Testing

```bash
cd apps/android
./gradlew test
```

| Suite | What it proves |
|---|---|
| `SeamCoreTest` | Kotlin can pin, start a session, encrypt and decrypt through the Rust core; impostors fail with `UntrustedIdentity`; unpaired peers fail with `NotPaired`; file chunks reject reordering |
| `ProtocolTest` | The golden vector decodes and re-encodes byte for byte; fields from newer apps don't break parsing |
| `SessionTest` | Pairing, Codes don't match (including just after the Mac accepted), delivery and acks, unpairing, and when the phone connects and goes idle |
| `LanPathTest` | A Same Wi‑Fi handshake that takes too long is given up |
| `ClipRulesTest` | No echoes or repeats, size limits, the share preview shows hidden characters, a Mac copy stops showing after 10 minutes |
| `QrDecoderTest` | A QR code is read from a padded camera frame; an empty frame reads nothing |
| `NoGoogleTelemetryTest` | No ML Kit, Google telemetry transport or Firebase on the app's classpath |

The crypto tests run on the JVM against a host build of the same Rust library, so they exercise the real code, not a mock.

## Generated code

`core/crypto/src/main/kotlin/com/avetero/seam/crypto/seam_ffi.kt` is generated by `scripts/gen-bindings.sh`. Don't edit it by hand; CI fails if it drifts from the Rust code.
