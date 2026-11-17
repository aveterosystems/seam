#!/bin/sh
# SPDX-License-Identifier: AGPL-3.0-only
# Regenerates apps/linux/flatpak/cargo-sources.json from apps/linux/Cargo.lock, so the Flatpak
# builds offline (Flathub's rule). Run after any change to the Linux app's dependencies.
set -eu
root=$(cd "$(dirname "$0")/.." && pwd)
tool=$(mktemp -d)
trap 'rm -rf "$tool"' EXIT
# flatpak-builder-tools, pinned
curl -sSfL -o "$tool/gen.py" https://raw.githubusercontent.com/flatpak/flatpak-builder-tools/41c20aa10819cdb2a4f3ca171758a96d1955c018/cargo/flatpak-cargo-generator.py
python3 "$tool/gen.py" "$root/apps/linux/Cargo.lock" -o "$root/apps/linux/flatpak/cargo-sources.json"
