#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Aligns and signs an APK with Seam's key, then checks it's signed by the pinned certificate only.
#   ANDROID_KEYSTORE (path to the PKCS12) and ANDROID_KEYSTORE_PASSWORD must be set;
#   scripts/sign-android-apk.sh unsigned.apk signed.apk
#   ANDROID_CERT_PIN overrides release/android-certificate.sha256 (CI's signing check).
set -euo pipefail
in="$1" out="$2"
: "${ANDROID_KEYSTORE:?}" "${ANDROID_KEYSTORE_PASSWORD:?}" "${ANDROID_HOME:?}"
root="$(cd "$(dirname "$0")/.." && pwd)"
tools="$(find "$ANDROID_HOME/build-tools" -mindepth 1 -maxdepth 1 | sort -V | tail -1)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# apksigner reads one line per use from a password file, so the store and the key each get their own
(umask 077; printf '%s\n' "$ANDROID_KEYSTORE_PASSWORD" > "$work/store"; printf '%s\n' "$ANDROID_KEYSTORE_PASSWORD" > "$work/key")
"$tools/zipalign" -P 16 -f 4 "$in" "$work/aligned.apk"
"$tools/apksigner" sign --ks "$ANDROID_KEYSTORE" --ks-key-alias seam \
  --ks-pass "file:$work/store" --key-pass "file:$work/key" \
  --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true \
  --min-sdk-version 31 --out "$out" "$work/aligned.apk"
"$tools/apksigner" verify --min-sdk-version 31 "$out"
"$tools/apksigner" verify --min-sdk-version 31 --print-certs "$out" > "$work/certs.txt"
# Exactly one signing certificate, and it's the pinned one. apksigner's labels differ between
# versions ("Signer #1", "V3.0 Signer:"), so read every SHA-256 digest it reports.
digests="$(sed -n 's/^.*Signer.* certificate SHA-256 digest: \([0-9a-f]\{64\}\)$/\1/p' "$work/certs.txt" | sort -u)"
count="$(printf '%s' "$digests" | grep -c . || true)"
expected="$(tr -d '[:space:]' < "${ANDROID_CERT_PIN:-$root/release/android-certificate.sha256}")"
if [ "$count" -ne 1 ] || [ "$digests" != "$expected" ]; then
  echo "Expected one signing certificate, the pinned $expected; apksigner reported:" >&2
  cat "$work/certs.txt" >&2
  rm -f "$out" # never leave an APK signed by the wrong key (SR-16-05)
  exit 1
fi
echo "Signed by the pinned certificate $digests"
