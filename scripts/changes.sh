#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Prints CHANGELOG.md's section for one release (the lines under "## X.Y.Z"), or fails if it's empty.
#   scripts/changes.sh X.Y.Z
set -euo pipefail
cd "$(dirname "$0")/.."
out="$(awk -v v="## $1" '$0 == v { on = 1; next } /^## / { on = 0 } on' CHANGELOG.md | sed -e '/./,$!d' | sed -e :a -e '/^\n*$/{$d;N;ba' -e '}')"
[ -n "$out" ] || { echo "CHANGELOG.md has no section for $1" >&2; exit 1; }
printf '%s\n' "$out"
