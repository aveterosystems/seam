#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# One-time, run by the maintainer on their own Mac: makes Seam's release signing keys, stores them
# as GitHub Actions secrets, and writes their public fingerprints to release/ for committing.
#   scripts/make-release-keys.sh [dir]   (default ~/seam-release-keys)
# Back that folder up somewhere safe and offline. Losing the Android key means installed copies
# can't be updated (users must uninstall); losing the Mac certificate means one keychain prompt.
# Needs: keytool (a JDK), Homebrew openssl (brew install openssl), gh logged in with repo admin.
set -euo pipefail
dir="${1:-$HOME/seam-release-keys}"
root="$(cd "$(dirname "$0")/.." && pwd)"
repo="aveterosystems/seam"
openssl=/opt/homebrew/bin/openssl
[ -x "$openssl" ] || openssl=/usr/local/opt/openssl/bin/openssl
[ -x "$openssl" ] || { echo "Needs Homebrew openssl: brew install openssl" >&2; exit 1; }
if [ -e "$dir" ]; then echo "$dir already exists: not overwriting release keys" >&2; exit 1; fi
mkdir -m 700 "$dir"
umask 077

# Android: one key for the life of the app (an APK's updates must be signed by the same key)
"$openssl" rand -hex 24 > "$dir/android-password.txt"
keytool -genkeypair -keystore "$dir/android-release.p12" -storetype PKCS12 -alias seam \
  -keyalg EC -groupname secp384r1 -sigalg SHA384withECDSA -validity 18250 \
  -dname "CN=Seam, O=Avetero" -storepass:file "$dir/android-password.txt" 2>/dev/null

# Mac: a self-signed code-signing certificate, valid 20 years (not notarized: see the release notes)
cat > "$dir/mac-cert.cnf" <<'CNF'
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
"$openssl" req -x509 -newkey rsa:3072 -nodes -keyout "$dir/mac-key.pem" -out "$dir/mac-cert.pem" -days 7300 -config "$dir/mac-cert.cnf" 2>/dev/null
"$openssl" rand -hex 24 > "$dir/mac-p12-password.txt"
"$openssl" pkcs12 -export -legacy -inkey "$dir/mac-key.pem" -in "$dir/mac-cert.pem" -name "Seam (Avetero)" \
  -out "$dir/mac-signing.p12" -passout file:"$dir/mac-p12-password.txt"

# Secrets go to GitHub from files on stdin, never on a command line
base64 -i "$dir/android-release.p12" | gh secret set ANDROID_KEYSTORE_BASE64 --repo "$repo"
gh secret set ANDROID_KEYSTORE_PASSWORD --repo "$repo" < "$dir/android-password.txt"
base64 -i "$dir/mac-signing.p12" | gh secret set MAC_SIGNING_P12_BASE64 --repo "$repo"
gh secret set MAC_SIGNING_P12_PASSWORD --repo "$repo" < "$dir/mac-p12-password.txt"

# Public fingerprints: CI checks the secrets against them, and users can check downloads
keytool -exportcert -keystore "$dir/android-release.p12" -alias seam -storepass:file "$dir/android-password.txt" 2>/dev/null \
  | shasum -a 256 | cut -d' ' -f1 > "$root/release/android-certificate.sha256"
"$openssl" x509 -in "$dir/mac-cert.pem" -outform der | shasum -a 256 | cut -d' ' -f1 > "$root/release/mac-certificate.sha256"
echo "Keys in $dir (back it up offline). Secrets set on $repo."
echo "Commit release/android-certificate.sha256 and release/mac-certificate.sha256."
