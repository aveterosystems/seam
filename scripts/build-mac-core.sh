#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Builds the Rust core as SeamCoreFFI.xcframework for the Mac app and the SeamCore package.
#   scripts/build-mac-core.sh            host architecture, release (dev)
#   PROFILE=debug scripts/...            debug, sharing gen-bindings.sh's build (CI)
#   ARCHS="arm64 x86_64" scripts/...     universal (release builds)
# Needs Xcode (for xcodebuild and lipo). Output is git-ignored; rerun after changing core/.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
core="$root/core"
pkg="$root/apps/mac/Packages/SeamCore"
out="$pkg/SeamCoreFFI.xcframework"
archs="${ARCHS:-$(uname -m)}"
profile="${PROFILE:-release}"
case "$profile" in
  release) flag=--release ;;
  debug) flag= ;;
  *) echo "unknown profile: $profile" >&2; exit 1 ;;
esac

libs=()
for arch in $archs; do
  case "$arch" in
    arm64) triple=aarch64-apple-darwin ;;
    x86_64) triple=x86_64-apple-darwin ;;
    *) echo "unknown arch: $arch" >&2; exit 1 ;;
  esac
  # The host builds without --target, into the same folder as gen-bindings.sh, so nothing compiles twice
  if [ "$arch" = "$(uname -m)" ]; then
    (cd "$core" && cargo build $flag -p seam-ffi)
    libs+=("$core/target/$profile/libseam_ffi.a")
  else
    (cd "$core" && rustup target add "$triple" >/dev/null && cargo build $flag -p seam-ffi --target "$triple")
    libs+=("$core/target/$triple/$profile/libseam_ffi.a")
  fi
done

work="$(mktemp -d)"
lipo -create "${libs[@]}" -output "$work/libseam_ffi.a"
mkdir -p "$work/include"
cp "$pkg/FFI/include/SeamCoreFFI.h" "$pkg/FFI/include/module.modulemap" "$work/include/"

rm -rf "$out"
xcodebuild -create-xcframework -library "$work/libseam_ffi.a" -headers "$work/include" -output "$out" >/dev/null
echo "Built ${out#"$root"/} ($archs)"
