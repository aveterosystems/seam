#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Signs Seam.app with Seam's own (self-signed) certificate, in a throwaway keychain.
#   MAC_SIGNING_P12 (path) and MAC_SIGNING_P12_PASSWORD must be set; scripts/sign-mac-app.sh path/to/Seam.app
# The certificate must be the one pinned in release/mac-certificate.sha256: a swapped secret fails
# here instead of shipping an app whose updates lose access to the user's keychain.
set -euo pipefail
app="$1"
: "${MAC_SIGNING_P12:?}" "${MAC_SIGNING_P12_PASSWORD:?}"
root="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"
keychain="$work/signing.keychain-db"
pass="$(openssl rand -hex 24)"
cleanup() { security delete-keychain "$keychain" 2>/dev/null || true; rm -rf "$work"; }
trap cleanup EXIT

# The leaf certificate's SHA-256 must match the pinned one
openssl pkcs12 -in "$MAC_SIGNING_P12" -passin env:MAC_SIGNING_P12_PASSWORD -nokeys -clcerts 2>/dev/null \
  | openssl x509 -outform der > "$work/cert.der" \
  || openssl pkcs12 -legacy -in "$MAC_SIGNING_P12" -passin env:MAC_SIGNING_P12_PASSWORD -nokeys -clcerts | openssl x509 -outform der > "$work/cert.der"
actual="$(shasum -a 256 "$work/cert.der" | cut -d' ' -f1)"
expected="$(tr -d '[:space:]' < "$root/release/mac-certificate.sha256")"
if [ "$actual" != "$expected" ]; then
  echo "Signing certificate $actual isn't the pinned one ($expected)" >&2
  exit 1
fi
sha1="$(shasum -a 1 "$work/cert.der" | cut -d' ' -f1 | tr a-f A-F)"

security create-keychain -p "$pass" "$keychain"
security set-keychain-settings -lut 600 "$keychain"
security unlock-keychain -p "$pass" "$keychain"
security import "$MAC_SIGNING_P12" -k "$keychain" -P "$MAC_SIGNING_P12_PASSWORD" -T /usr/bin/codesign >/dev/null
security set-key-partition-list -S apple-tool:,apple:,codesign: -s -k "$pass" "$keychain" >/dev/null

# Hardened runtime, no entitlements. No timestamp: Apple's timestamp server only serves Developer ID.
codesign --force --options runtime --timestamp=none --keychain "$keychain" --sign "$sha1" "$app"
codesign --verify --strict --verbose=2 "$app"
# Updates keep keychain access only while this stays the same: identifier + this certificate
codesign -d -r- "$app" 2>&1 | grep -q "identifier \"com.avetero.seam\" and certificate leaf = H\"$(echo "$sha1" | tr A-F a-f)\"" \
  || { echo "Unexpected designated requirement" >&2; codesign -d -r- "$app"; exit 1; }
