#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# The release workflow's gate: scripts/check-release.sh vX.Y.Z  (prints version=X.Y.Z)
# Fails unless the tagged commit is on main, both apps carry this version, their build numbers went
# up since the last release tag, and the certificate pins are 64 hex characters (SR-14-01, 03, 08, 09).
set -euo pipefail
tag="$1"
version="${tag#v}"
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
fail() { echo "$*" >&2; exit 1; }

[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "Not a version tag: $tag"
git merge-base --is-ancestor HEAD "${MAIN_REF:-origin/main}" || fail "$tag isn't on main"

field() { sed -n "s/.*$2[ =:]*\"\{0,1\}\([0-9.]*\)\"\{0,1\}.*/\1/p" "$1" | head -1; }
android_name="$(field apps/android/app/build.gradle.kts 'versionName')"
android_code="$(field apps/android/app/build.gradle.kts 'versionCode')"
mac_name="$(field apps/mac/project.yml 'MARKETING_VERSION')"
mac_build="$(field apps/mac/project.yml 'CURRENT_PROJECT_VERSION')"
[ "$android_name" = "$version" ] || fail "Android versionName is $android_name, not $version"
[ "$mac_name" = "$version" ] || fail "Mac MARKETING_VERSION is $mac_name, not $version"
[[ "$android_code" =~ ^[0-9]+$ ]] || fail "Android versionCode missing"
[[ "$mac_build" =~ ^[0-9]+$ ]] || fail "Mac CURRENT_PROJECT_VERSION missing"

# Android refuses an update whose versionCode isn't higher
previous="$(git describe --tags --abbrev=0 --match 'v[0-9]*.[0-9]*.[0-9]*' HEAD^ 2>/dev/null || true)"
if [ -n "$previous" ]; then
  prev_code="$(git show "$previous:apps/android/app/build.gradle.kts" | sed -n 's/.*versionCode = \([0-9]*\).*/\1/p')"
  prev_build="$(git show "$previous:apps/mac/project.yml" | sed -n 's/.*CURRENT_PROJECT_VERSION: "\([0-9]*\)".*/\1/p')"
  [ "$android_code" -gt "$prev_code" ] || fail "Android versionCode $android_code isn't above $previous's $prev_code"
  [ "$mac_build" -gt "$prev_build" ] || fail "Mac CURRENT_PROJECT_VERSION $mac_build isn't above $previous's $prev_build"
fi

for pin in release/android-certificate.sha256 release/mac-certificate.sha256; do
  [[ "$(tr -d '[:space:]' < "$pin" 2>/dev/null)" =~ ^[0-9a-f]{64}$ ]] || fail "$pin must be one SHA-256 (64 hex characters)"
done
echo "version=$version"
