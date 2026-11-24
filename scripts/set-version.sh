#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Sets every app's version, and Android's and the Mac's build number:  scripts/set-version.sh 0.4.0 440213
# Used by scripts/release.sh (stable) and the release workflow's nightly builds.
set -euo pipefail
version="$1" build="$2"
cd "$(dirname "$0")/.."
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-nightly\.[0-9]+)?$ ]] || { echo "Not a version: $version" >&2; exit 1; }
[[ "$build" =~ ^[0-9]+$ ]] || { echo "Not a build number: $build" >&2; exit 1; }
gradle=apps/android/app/build.gradle.kts
yml=apps/mac/project.yml
cargo=apps/linux/Cargo.toml lock=apps/linux/Cargo.lock
sed -i.bak -E "s/versionName = \"[^\"]*\"/versionName = \"$version\"/; s/versionCode = [0-9]+\$/versionCode = $build/" "$gradle"
sed -i.bak -E "s/MARKETING_VERSION: \"[^\"]*\"/MARKETING_VERSION: \"$version\"/; s/CURRENT_PROJECT_VERSION: \"[0-9]+\"/CURRENT_PROJECT_VERSION: \"$build\"/" "$yml"
# Linux: the package's own version (the first one in the file) and its Cargo.lock entry
sed -i.bak -E "1,/^version = /s/^version = \"[^\"]*\"/version = \"$version\"/" "$cargo"
sed -i.bak -E "/^name = \"seam\"\$/{n;s/^version = \"[^\"]*\"/version = \"$version\"/;}" "$lock"
rm -f "$gradle.bak" "$yml.bak" "$cargo.bak" "$lock.bak"
grep -q "versionName = \"$version\"" "$gradle" && grep -q "versionCode = $build\$" "$gradle" \
  && grep -q "MARKETING_VERSION: \"$version\"" "$yml" && grep -q "CURRENT_PROJECT_VERSION: \"$build\"" "$yml" \
  && [ "$(sed -n 's/^version = "\(.*\)"$/\1/p' "$cargo" | head -1)" = "$version" ] \
  && grep -A1 -x 'name = "seam"' "$lock" | grep -qx "version = \"$version\"" \
  || { echo "Couldn't set the version in $gradle, $yml, $cargo and $lock" >&2; exit 1; }
