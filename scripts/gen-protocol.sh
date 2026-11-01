#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Regenerates the Protobuf code for the Mac app from protocol/seam/v1.
# Android generates its own at build time (Gradle protobuf plugin), so it isn't done here.
# Uses buf when installed; falls back to plain protoc so a fresh machine can still run it.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
proto="$root/protocol"

swift_out="$root/apps/mac/Packages/SeamProtocol/Sources/SeamProtocol/Generated"

check() { command -v "$1" >/dev/null || { echo "missing: $1 ($2)" >&2; exit 1; }; }

if command -v buf >/dev/null; then
  (cd "$proto" && buf lint)
fi

check protoc "brew install protobuf"
protoc -I "$proto" --descriptor_set_out=/dev/null "$proto"/seam/v1/*.proto

if command -v protoc-gen-swift >/dev/null; then
  rm -rf "$swift_out" && mkdir -p "$swift_out"
  protoc -I "$proto" --swift_out="$swift_out" --swift_opt=Visibility=Public "$proto"/seam/v1/*.proto
  echo "Swift  → ${swift_out#"$root"/}"
else
  echo "skipped Swift: install protoc-gen-swift (brew install swift-protobuf)" >&2
fi
