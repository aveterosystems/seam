# Third-party software

Seam is licensed under [AGPL-3.0-only](LICENSE) and builds on the open-source work below. Every licence listed is compatible with the AGPL-3.0.

## Shipped inside the apps

| Component | Used for | Licence |
|---|---|---|
| [libsignal](https://github.com/signalapp/libsignal) (`libsignal-protocol`, `signal-crypto`, `libsignal-core`) | The Signal Protocol: PQXDH, the triple ratchet | AGPL-3.0-only |
| [SparsePostQuantumRatchet](https://github.com/signalapp/SparsePostQuantumRatchet) (`spqr`) | Post-quantum ratchet inside libsignal | AGPL-3.0-only |
| [libcrux](https://github.com/cryspen/libcrux) (`libcrux-ml-kem`) | Formally verified Kyber / ML-KEM | Apache-2.0 |
| [curve25519-dalek](https://github.com/dalek-cryptography/curve25519-dalek) | X25519 and signatures | BSD-3-Clause |
| [snow](https://github.com/mcginty/snow) | The Noise handshake of the direct link | Apache-2.0 OR MIT |
| [RustCrypto `aes-gcm`](https://github.com/RustCrypto/AEADs) | File chunk encryption | Apache-2.0 OR MIT |
| [UniFFI](https://github.com/mozilla/uniffi-rs) | Swift and Kotlin bindings for the Rust core | MPL-2.0 |
| [Arti](https://arti.torproject.org) (`arti-client` and its `tor-*` crates), by the Tor Project | Connecting to the Tor® network: the Mac's private onion service and the phone's connection to it | MIT OR Apache-2.0 |
| [hpke-rs](https://github.com/cryspen/hpke-rs) | Pulled in by libsignal | MPL-2.0 |
| [SwiftProtobuf](https://github.com/apple/swift-protobuf) | Message encoding on the Mac | Apache-2.0 |
| [Sparkle](https://github.com/sparkle-project/Sparkle) | In-app updates on the Mac | MIT |
| [Protocol Buffers for Kotlin/Java (lite)](https://github.com/protocolbuffers/protobuf) | Message encoding on Android | BSD-3-Clause |
| [JNA](https://github.com/java-native-access/jna) | Calls the Rust core from Kotlin | Apache-2.0 OR LGPL-2.1 |
| [AndroidX](https://developer.android.com/jetpack/androidx): Compose, Lifecycle, CameraX | Android UI, lifecycle and the pairing camera view | Apache-2.0 |
| [Kotlin standard library](https://github.com/JetBrains/kotlin) | The Kotlin runtime | Apache-2.0 |
| [ZXing core](https://github.com/zxing/zxing) | Reads the pairing QR code on the phone | Apache-2.0 |
| [OkHttp](https://github.com/square/okhttp) and [Okio](https://github.com/square/okio) | The phone's update check and download from GitHub | Apache-2.0 |
| [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) | The phone's session and timers | Apache-2.0 |

Tor® is a trademark of The Tor Project, Inc.; all rights reserved. Seam isn't endorsed or sponsored by, or affiliated with, the Tor Project. See [torproject.org](https://www.torproject.org).

The Rust core pulls in around 200 crates (the exact count depends on the platform). Most are `MIT OR Apache-2.0`; the rest are Apache-2.0, MIT, BSD, MPL-2.0 (UniFFI, hpke-rs) or AGPL-3.0 (libsignal). To list them yourself:

```bash
cd core && cargo tree -p seam-ffi -e normal --prefix none --format "{p} {l}" | sort -u
```

## Build and test tools (not shipped)

Gradle and the Android Gradle Plugin, Kotlin, Swift and XcodeGen, apksigner and zipalign, the GitHub Actions in `.github/workflows` (pinned to commits), Cargo and cargo-ndk, `protoc` and buf, JUnit and Swift Testing. Each is under its own open-source licence.
