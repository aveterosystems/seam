#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# The nightly build's gate:  scripts/nightly-version.sh [force]
# Prints version= (the next patch as a pre-release, 0.3.1-nightly.202611030200), tag=
# (nightly-202611030200: v* tags are stable releases only), build=, and skip=true when main hasn't
# changed since the last nightly (a scheduled run builds only new work; "force" builds anyway).
set -euo pipefail
cd "$(dirname "$0")/.."
git merge-base --is-ancestor HEAD "${MAIN_REF:-origin/main}" || { echo "Nightlies are built from main only" >&2; exit 1; }
current="$(sed -n 's/.*versionName = "\([0-9]*\.[0-9]*\.[0-9]*\)".*/\1/p' apps/android/app/build.gradle.kts)"
[[ "$current" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "main's version isn't X.Y.Z: $current" >&2; exit 1; }
IFS=. read -r major minor patch <<< "$current"
stamp="$(date -u +%Y%m%d%H%M)"
last="$(git tag --list 'nightly-[0-9]*' --sort=-refname | head -1)"
skip=false
# Main unchanged, and that nightly fully published (the Mac feed points at it): nothing to do
if [ "${1:-}" != force ] && [ -n "$last" ] && [ "$(git rev-list -n1 "$last")" = "$(git rev-parse HEAD)" ] \
  && curl -sSfL "https://github.com/aveterosystems/seam/releases/download/nightly/appcast.xml" | grep -qF "/download/$last/"; then
  skip=true
fi
echo "version=$major.$minor.$((patch + 1))-nightly.$stamp"
echo "tag=nightly-$stamp"
echo "build=$(scripts/build-number.sh)"
echo "previous=$last"
echo "skip=$skip"
