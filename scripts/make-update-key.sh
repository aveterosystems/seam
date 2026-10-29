#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# One-time, run by the maintainer on their own Mac, after scripts/make-release-keys.sh. Makes Seam's
# update key (Ed25519, what Sparkle on the Mac checks every update and feed against), keeps it in the
# same encrypted disk image as the signing keys, stores it as a `release` environment secret, and
# writes its public half into apps/mac/Config/Release-Info.plist (commit that).
# Losing this key means Mac users can't be offered updates until they install one by hand.
# Needs: Homebrew openssl, gh logged in as a repo admin.
set -euo pipefail
repo="aveterosystems/seam"
root="$(cd "$(dirname "$0")/.." && pwd)"
image="${1:-$HOME/Seam release keys.dmg}"
plist="$root/apps/mac/Config/Release-Info.plist"
openssl=/opt/homebrew/bin/openssl
[ -x "$openssl" ] || openssl=/usr/local/opt/openssl/bin/openssl
[ -x "$openssl" ] || { echo "Needs Homebrew openssl: brew install openssl" >&2; exit 1; }
[ -f "$image" ] || { echo "No $image: run scripts/make-release-keys.sh first" >&2; exit 1; }

read -rsp "Disk image password: " pass; echo
mount="$(printf '%s' "$pass" | hdiutil attach -stdinpass -nobrowse "$image" | sed -n 's|.*\(/Volumes/.*\)$|\1|p' | tail -1)"
unset pass
[ -n "$mount" ] || { echo "Couldn't open $image" >&2; exit 1; }
trap 'hdiutil detach -quiet "$mount" 2>/dev/null || true' EXIT
key="$mount/sparkle-ed25519.key"
[ -e "$key" ] && { echo "$key already exists: not replacing the update key" >&2; exit 1; }
umask 077

# Sparkle's private key format: the 32-byte Ed25519 seed, base64. The PEM only exists in memory.
pem="$("$openssl" genpkey -algorithm ed25519)"
"$openssl" pkey -outform DER <<< "$pem" | tail -c 32 | base64 > "$key"
public="$("$openssl" pkey -pubout -outform DER <<< "$pem" | tail -c 32 | base64)"
unset pem
[[ "$public" =~ ^[A-Za-z0-9+/]{43}=$ ]] || { echo "Unexpected public key" >&2; exit 1; }

/usr/libexec/PlistBuddy -c "Set :SUPublicEDKey $public" "$plist"
gh secret set SPARKLE_ED_PRIVATE_KEY --env release --repo "$repo" < "$key"
echo "Done. Update key saved in $image; public key written to apps/mac/Config/Release-Info.plist (commit it)."
