#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Adds a release to the Linux app's AppStream metainfo, which GNOME Software and Discover show as
# its version history, with CHANGELOG.md's section as the notes:  scripts/add-linux-release.sh X.Y.Z
# Used by scripts/release.sh; scripts/check-release.sh checks the entry is there.
set -euo pipefail
version="$1"
cd "$(dirname "$0")/.."
metainfo=apps/linux/data/com.avetero.Seam.metainfo.xml
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "Not a version: $version" >&2; exit 1; }
grep -q "<release version=\"$version\"" "$metainfo" && { echo "$metainfo already has $version" >&2; exit 1; }
# Each "- line" of the section as a list item, with XML's special characters escaped
items="$(scripts/changes.sh "$version" | sed -n 's/^- //p' \
  | sed -e 's/&/\&amp;/g' -e 's/</\&lt;/g' -e 's/>/\&gt;/g' -e 's|.*|          <li>&</li>|')"
[ -n "$items" ] || { echo "CHANGELOG.md's $version section has no \"- \" lines" >&2; exit 1; }
entry="    <release version=\"$version\" date=\"$(date -u +%Y-%m-%d)\">
      <url type=\"details\">https://github.com/aveterosystems/seam/releases/tag/v$version</url>
      <description>
        <ul>
$items
        </ul>
      </description>
    </release>"
# Newest first, straight after <releases>
entry="$entry" awk '{ print } /^  <releases>$/ { print ENVIRON["entry"] }' "$metainfo" > "$metainfo.new"
mv "$metainfo.new" "$metainfo"
grep -q "<release version=\"$version\"" "$metainfo" || { echo "Couldn't add $version to $metainfo" >&2; exit 1; }
