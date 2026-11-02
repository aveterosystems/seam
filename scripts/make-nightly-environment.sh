#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# One-time, run by the maintainer on their own Mac, after scripts/make-release-keys.sh and
# scripts/make-update-key.sh. Makes the `nightly` environment the release workflow signs nightly
# builds in: no reviewer (it runs each night), main only. It holds the same keys as `release`,
# copied from the encrypted disk image, because a nightly must be signed by Seam's keys to install
# over Seam and keep its pairing. Stable releases keep the reviewer in `release`.
# Needs: gh logged in as a repo admin.
set -euo pipefail
repo="aveterosystems/seam"
image="${1:-$HOME/Seam release keys.dmg}"
[ -f "$image" ] || { echo "No $image: run scripts/make-release-keys.sh first" >&2; exit 1; }

read -rsp "Disk image password: " pass; echo
mount="$(printf '%s' "$pass" | hdiutil attach -stdinpass -nobrowse "$image" | sed -n 's|.*\(/Volumes/.*\)$|\1|p' | tail -1)"
unset pass
[ -n "$mount" ] || { echo "Couldn't open $image" >&2; exit 1; }
trap 'hdiutil detach -quiet "$mount" 2>/dev/null || true' EXIT
for f in android-release.p12 android-password.txt mac-signing.p12 mac-p12-password.txt sparkle-ed25519.key; do
  [ -s "$mount/$f" ] || { echo "$f is missing from $image" >&2; exit 1; }
done

echo "1/2 The nightly environment on $repo: main only, no reviewer"
gh api -X PUT "repos/$repo/environments/nightly" --input - >/dev/null <<'JSON'
{"deployment_branch_policy":{"protected_branches":false,"custom_branch_policies":true}}
JSON
gh api "repos/$repo/environments/nightly/deployment-branch-policies" --jq '.branch_policies[].name' | grep -qx main \
  || gh api -X POST "repos/$repo/environments/nightly/deployment-branch-policies" -f name=main -f type=branch >/dev/null

echo "2/2 Seam's signing and update keys, as nightly environment secrets"
base64 -i "$mount/android-release.p12" | gh secret set ANDROID_KEYSTORE_BASE64 --env nightly --repo "$repo"
gh secret set ANDROID_KEYSTORE_PASSWORD --env nightly --repo "$repo" < "$mount/android-password.txt"
base64 -i "$mount/mac-signing.p12" | gh secret set MAC_SIGNING_P12_BASE64 --env nightly --repo "$repo"
gh secret set MAC_SIGNING_P12_PASSWORD --env nightly --repo "$repo" < "$mount/mac-p12-password.txt"
gh secret set SPARKLE_ED_PRIVATE_KEY --env nightly --repo "$repo" < "$mount/sparkle-ed25519.key"
echo "Done. The first nightly: gh workflow run release.yml --repo $repo --ref main"
