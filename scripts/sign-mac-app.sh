#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Signs Seam.app with Seam's own (self-signed) certificate, in a throwaway keychain.
#   MAC_SIGNING_P12 (path) and MAC_SIGNING_P12_PASSWORD must be set; scripts/sign-mac-app.sh path/to/Seam.app
#   MAC_CERT_PIN overrides the pin file (CI's signing check uses a throwaway certificate).
# The certificate must be the one pinned in release/mac-certificate.sha256: a swapped secret fails
# here instead of shipping an app whose updates lose access to the user's keychain.
set -euo pipefail
app="$1"
: "${MAC_SIGNING_P12:?}" "${MAC_SIGNING_P12_PASSWORD:?}"
root="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"
keychain="$work/signing.keychain-db"
pass="$(openssl rand -hex 24)"
# The user keychain search list, one quoted path per line, kept as an array so paths with spaces
# survive (bash 3.2 on macOS: no mapfile) (SR-16-01)
searchlist=()
while IFS= read -r line; do
  line="${line#"${line%%[![:space:]]*}"}"
  line="${line%\"}"; line="${line#\"}"
  [ -n "$line" ] && searchlist+=("$line")
done < <(security list-keychains -d user)
[ "${#searchlist[@]}" -gt 0 ] || { echo "Couldn't read the keychain search list" >&2; exit 1; }
cleanup() {
  security list-keychains -d user -s "${searchlist[@]}" \
    || echo "Warning: couldn't restore the keychain search list; it was: ${searchlist[*]}" >&2
  security delete-keychain "$keychain" 2>/dev/null || true
  rm -rf "$work"
}
# shortcut: the P12 password reaches `security import -P` on its command line (no stdin option);
# acceptable on an ephemeral hosted runner where nothing else runs in the signing job (SR-14-12).
trap cleanup EXIT

security create-keychain -p "$pass" "$keychain"
security set-keychain-settings -lut 600 "$keychain"
security unlock-keychain -p "$pass" "$keychain"
security import "$MAC_SIGNING_P12" -k "$keychain" -P "$MAC_SIGNING_P12_PASSWORD" -T /usr/bin/codesign >/dev/null
security set-key-partition-list -S apple-tool:,apple:,codesign: -s -k "$pass" "$keychain" >/dev/null
# codesign finds the private key only in a keychain on the search list (macOS 26 runners), so add
# this one in front for the signing, and put the old list back on exit
security list-keychains -d user -s "$keychain" "${searchlist[@]}"

# The imported certificate must be the pinned one (read from the keychain: one path, errors shown)
found="$(security find-certificate -a -Z "$keychain")"
certs="$(grep -c '^SHA-256 hash:' <<< "$found" || true)"
[ "$certs" -eq 1 ] || { echo "Expected one certificate in the P12, found $certs" >&2; exit 1; }
security find-certificate -a -p "$keychain" | openssl x509 -outform der > "$work/cert.der"
actual="$(shasum -a 256 "$work/cert.der" | cut -d' ' -f1)"
expected="$(tr -d '[:space:]' < "${MAC_CERT_PIN:-$root/release/mac-certificate.sha256}")"
if [ "$actual" != "$expected" ]; then
  echo "Signing certificate $actual isn't the pinned one ($expected)" >&2
  exit 1
fi
sha1="$(shasum -a 1 "$work/cert.der" | cut -d' ' -f1 | tr a-f A-F)"
# The identity (certificate + its private key) must be there, valid or not for Gatekeeper
# Output captured before grepping: `grep -q` in a pipe can SIGPIPE the writer under pipefail (SR-16-02)
identities="$(security find-identity -p codesigning "$keychain")"
grep -q "$sha1" <<< "$identities" \
  || { echo "The P12 has the certificate but no matching private key" >&2; echo "$identities" >&2; exit 1; }

# Hardened runtime, no entitlements. No timestamp: Apple's timestamp server only serves Developer ID.
codesign --force --options runtime --timestamp=none --keychain "$keychain" --sign "$sha1" "$app"
codesign --verify --deep --strict --verbose=2 "$app"
# Updates keep keychain access only while this stays the same: identifier + this certificate
requirement="$(codesign -d -r- "$app" 2>&1)"
grep -q "identifier \"com.avetero.seam\" and certificate leaf = H\"$(echo "$sha1" | tr A-F a-f)\"" <<< "$requirement" \
  || { echo "Unexpected designated requirement: $requirement" >&2; exit 1; }
