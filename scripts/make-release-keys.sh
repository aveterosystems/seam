#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# One-time, run by the maintainer on their own Mac. It:
#   1. protects releases on GitHub: a `release` environment that only v* tags can use and that
#      waits for your approval, and a tag ruleset so only admins can create, move or delete v* tags;
#   2. makes Seam's signing keys inside an encrypted disk image (you choose its password);
#   3. writes their public fingerprints to release/ (commit those), then stores the keys as
#      `release` environment secrets.
# Back up the disk image offline and keep its password in your password manager, not beside it.
# Losing the Android key means installed copies can't update; losing the Mac certificate means
# one keychain prompt per user.
# Needs: keytool (a JDK), Homebrew openssl (brew install openssl), gh logged in as a repo admin.
set -euo pipefail
repo="aveterosystems/seam"
root="$(cd "$(dirname "$0")/.." && pwd)"
image="${1:-$HOME/Seam release keys.dmg}"
openssl=/opt/homebrew/bin/openssl
[ -x "$openssl" ] || openssl=/usr/local/opt/openssl/bin/openssl
[ -x "$openssl" ] || { echo "Needs Homebrew openssl: brew install openssl" >&2; exit 1; }
[ -e "$image" ] && { echo "$image already exists: not overwriting release keys" >&2; exit 1; }

echo "1/3 Protecting releases on $repo"
me="$(gh api user --jq .id)"
gh api -X PUT "repos/$repo/environments/release" --input - >/dev/null <<JSON
{"reviewers":[{"type":"User","id":$me}],"deployment_branch_policy":{"protected_branches":false,"custom_branch_policies":true}}
JSON
gh api "repos/$repo/environments/release/deployment-branch-policies" --jq '.branch_policies[].name' | grep -qxF 'v*' \
  || gh api -X POST "repos/$repo/environments/release/deployment-branch-policies" -f name='v*' -f type=tag >/dev/null
if ! gh api "repos/$repo/rulesets" --jq '.[].name' | grep -qx 'Release tags'; then
  gh api -X POST "repos/$repo/rulesets" --input - >/dev/null <<'JSON'
{"name":"Release tags","target":"tag","enforcement":"active",
 "conditions":{"ref_name":{"include":["refs/tags/v*"],"exclude":[]}},
 "rules":[{"type":"creation"},{"type":"update"},{"type":"deletion"},{"type":"non_fast_forward"}],
 "bypass_actors":[{"actor_id":5,"actor_type":"RepositoryRole","bypass_mode":"always"}]}
JSON
fi

echo "2/3 Making the keys in an encrypted disk image (choose a strong password when asked)"
read -rsp "Disk image password: " pass; echo
read -rsp "Same again: " again; echo
[ -n "$pass" ] && [ "$pass" = "$again" ] || { echo "Passwords differ or are empty" >&2; exit 1; }
printf '%s' "$pass" | hdiutil create -size 20m -fs APFS -encryption AES-256 -stdinpass -volname "Seam release keys" "$image" >/dev/null
unset pass again
mount="$(hdiutil attach -nobrowse "$image" | sed -n 's|.*\(/Volumes/.*\)$|\1|p' | tail -1)"
trap 'hdiutil detach -quiet "$mount" 2>/dev/null || true' EXIT
dir="$mount"
umask 077
tmutil addexclusion "$image" 2>/dev/null || true

# Android: one key for the life of the app (an APK's updates must be signed by the same key)
"$openssl" rand -hex 24 > "$dir/android-password.txt"
keytool -genkeypair -keystore "$dir/android-release.p12" -storetype PKCS12 -alias seam \
  -keyalg EC -groupname secp384r1 -sigalg SHA384withECDSA -validity 18250 \
  -dname "CN=Seam, O=Avetero" -storepass:file "$dir/android-password.txt" 2>/dev/null

# Mac: a self-signed code-signing certificate, valid 20 years (not notarized: see the release notes)
cnf="$(mktemp)"
cat > "$cnf" <<'CNF'
[req]
distinguished_name=dn
x509_extensions=ext
prompt=no
[dn]
CN=Seam (Avetero)
[ext]
basicConstraints=critical,CA:false
keyUsage=critical,digitalSignature
extendedKeyUsage=critical,codeSigning
CNF
"$openssl" rand -hex 24 > "$dir/mac-p12-password.txt"
# The private key only exists unencrypted in this shell's memory, then inside the P12 (SR-14-13)
key="$("$openssl" genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 2>/dev/null)"
"$openssl" req -x509 -key <(printf '%s\n' "$key") -out "$dir/mac-cert.pem" -days 7300 -config "$cnf"
"$openssl" pkcs12 -export -legacy -inkey <(printf '%s\n' "$key") -in "$dir/mac-cert.pem" -name "Seam (Avetero)" \
  -out "$dir/mac-signing.p12" -passout file:"$dir/mac-p12-password.txt"
unset key
rm -f "$cnf"

echo "3/3 Fingerprints to release/, keys to the release environment"
keytool -exportcert -keystore "$dir/android-release.p12" -alias seam -storepass:file "$dir/android-password.txt" 2>/dev/null \
  | shasum -a 256 | cut -d' ' -f1 > "$root/release/android-certificate.sha256"
"$openssl" x509 -in "$dir/mac-cert.pem" -outform der | shasum -a 256 | cut -d' ' -f1 > "$root/release/mac-certificate.sha256"
# Secrets go to GitHub on stdin, never on a command line
base64 -i "$dir/android-release.p12" | gh secret set ANDROID_KEYSTORE_BASE64 --env release --repo "$repo"
gh secret set ANDROID_KEYSTORE_PASSWORD --env release --repo "$repo" < "$dir/android-password.txt"
base64 -i "$dir/mac-signing.p12" | gh secret set MAC_SIGNING_P12_BASE64 --env release --repo "$repo"
gh secret set MAC_SIGNING_P12_PASSWORD --env release --repo "$repo" < "$dir/mac-p12-password.txt"

echo "Done. Keys are in $image (encrypted, excluded from Time Machine): back it up offline."
echo "Commit release/android-certificate.sha256 and release/mac-certificate.sha256 in a PR."
