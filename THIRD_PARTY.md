# Third-party software

Seam is licensed under [AGPL-3.0-only](LICENSE) and builds on the open-source work below. Every licence listed is compatible with the AGPL-3.0.

## Shipped inside the apps

| Component | Used for | Licence |
|---|---|---|
| [libsignal](https://github.com/signalapp/libsignal) (`libsignal-protocol`, `signal-crypto`, `libsignal-core`) | The Signal Protocol: PQXDH, the triple ratchet | AGPL-3.0-only |
| [SparsePostQuantumRatchet](https://github.com/signalapp/SparsePostQuantumRatchet) (`spqr`) | Post-quantum ratchet inside libsignal | AGPL-3.0-only |
| [libcrux](https://github.com/cryspen/libcrux) (`libcrux-ml-kem`) | Formally verified Kyber / ML-KEM | Apache-2.0 |
| [curve25519-dalek](https://github.com/dalek-cryptography/curve25519-dalek) | X25519 and signatures | BSD-3-Clause |
| [RustCrypto `aes-gcm`](https://github.com/RustCrypto/AEADs) | File chunk encryption | Apache-2.0 OR MIT |
| [UniFFI](https://github.com/mozilla/uniffi-rs) | Swift and Kotlin bindings for the Rust core | MPL-2.0 |
| [hpke-rs](https://github.com/cryspen/hpke-rs) | Pulled in by libsignal | MPL-2.0 |
| [SwiftProtobuf](https://github.com/apple/swift-protobuf) | Message encoding on the Mac | Apache-2.0 |
| [Protocol Buffers for Kotlin/Java (lite)](https://github.com/protocolbuffers/protobuf) | Message encoding on Android | BSD-3-Clause |
| [JNA](https://github.com/java-native-access/jna) | Calls the Rust core from Kotlin | Apache-2.0 OR LGPL-2.1 |
| [AndroidX and Jetpack Compose](https://developer.android.com/jetpack/androidx), including CameraX | Android UI and the pairing camera view | Apache-2.0 |
| [ZXing core](https://github.com/zxing/zxing) | Reads the pairing QR code on the phone | Apache-2.0 |
| [OkHttp](https://github.com/square/okhttp) | The phone's WebSocket to the relay | Apache-2.0 |
| [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) | The phone's session and timers | Apache-2.0 |

The Rust core pulls in around 200 crates (the exact count depends on the platform). Most are `MIT OR Apache-2.0`; the rest are Apache-2.0, MIT, BSD, MPL-2.0 (UniFFI, hpke-rs) or AGPL-3.0 (libsignal). To list them yourself:

```bash
cd core && cargo tree -p seam-ffi -e normal --prefix none --format "{p} {l}" | sort -u
```

## Used by the relay

| Component | Used for | Licence |
|---|---|---|
| [@bufbuild/protobuf](https://github.com/bufbuild/protobuf-es) | Message encoding | Apache-2.0 |

## Build and test tools (not shipped)

Gradle and the Android Gradle Plugin, Kotlin, Swift and XcodeGen, apksigner and zipalign, the GitHub Actions in `.github/workflows` (pinned to commits), Cargo and cargo-ndk, `protoc` and buf, protoc-gen-es, wrangler, TypeScript and vitest, JUnit and Swift Testing. Each is under its own open-source licence.
