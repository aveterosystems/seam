#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Sets both apps' version and build number:  scripts/set-version.sh 0.4.0 440213
# Used by scripts/release.sh (stable) and the release workflow's nightly builds.
set -euo pipefail
version="$1" build="$2"
cd "$(dirname "$0")/.."
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-nightly\.[0-9]+)?$ ]] || { echo "Not a version: $version" >&2; exit 1; }
[[ "$build" =~ ^[0-9]+$ ]] || { echo "Not a build number: $build" >&2; exit 1; }
gradle=apps/android/app/build.gradle.kts
yml=apps/mac/project.yml
sed -i.bak -E "s/versionName = \"[^\"]*\"/versionName = \"$version\"/; s/versionCode = [0-9]+\$/versionCode = $build/" "$gradle"
sed -i.bak -E "s/MARKETING_VERSION: \"[^\"]*\"/MARKETING_VERSION: \"$version\"/; s/CURRENT_PROJECT_VERSION: \"[0-9]+\"/CURRENT_PROJECT_VERSION: \"$build\"/" "$yml"
rm -f "$gradle.bak" "$yml.bak"
grep -q "versionName = \"$version\"" "$gradle" && grep -q "versionCode = $build\$" "$gradle" \
  && grep -q "MARKETING_VERSION: \"$version\"" "$yml" && grep -q "CURRENT_PROJECT_VERSION: \"$build\"" "$yml" \
  || { echo "Couldn't set the version in $gradle and $yml" >&2; exit 1; }
