#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Generates the Swift and Kotlin bindings for core/seam-ffi with UniFFI (names set in core/seam-ffi/uniffi.toml).
# Output is committed, so the apps build without running this; rerun it whenever seam-ffi's API changes.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
core="$root/core"

swift_src="$root/apps/mac/Packages/SeamCore/Sources/SeamCore"
swift_ffi="$root/apps/mac/Packages/SeamCore/FFI/include"
kotlin_out="$root/apps/android/core/crypto/src/main/kotlin"

cd "$core"
lib="$core/target/debug/libseam_ffi.dylib"
bindgen() { cargo run -q -p seam-ffi --features bindgen --bin uniffi-bindgen -- generate --library "$lib" --no-format "$@"; }

# Swift has the computer side (seam-computer); the phone's Kotlin doesn't
cargo build -p seam-ffi --features computer
tmp="$(mktemp -d)"
bindgen --language swift --out-dir "$tmp"
mkdir -p "$swift_src" "$swift_ffi"
cp "$tmp/SeamCore.swift" "$swift_src/SeamCore.swift"
cp "$tmp/SeamCoreFFI.h" "$swift_ffi/"
# The XCFramework wants the map named module.modulemap (scripts/build-mac-core.sh packs both)
cp "$tmp/SeamCoreFFI.modulemap" "$swift_ffi/module.modulemap"
echo "Swift  → ${swift_src#"$root"/}"

cargo build -p seam-ffi
rm -rf "$kotlin_out/com/avetero/seam/crypto"
bindgen --language kotlin --out-dir "$kotlin_out"
echo "Kotlin → ${kotlin_out#"$root"/}"
