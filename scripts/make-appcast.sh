#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Writes Sparkle's appcast.xml for one release and signs both the update and the feed with Seam's
# update key (EdDSA). Run by the release workflow's Mac signing job.
#   SPARKLE_KEY_FILE (base64 Ed25519 seed) and SIGN_UPDATE (Sparkle's sign_update) must be set;
#   EXPECTED_PUBLIC_KEY, if set, must be the key's public half (the app's SUPublicEDKey);
#   scripts/make-appcast.sh Seam-X.Y.Z-mac.zip X.Y.Z BUILD appcast.xml
set -euo pipefail
zip="$1" version="$2" build="$3" out="$4"
: "${SPARKLE_KEY_FILE:?}" "${SIGN_UPDATE:?}"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "Bad version $version" >&2; exit 1; }
[[ "$build" =~ ^[0-9]+$ ]] || { echo "Bad build $build" >&2; exit 1; }
repo="https://github.com/aveterosystems/seam"

# The key must be the one the app trusts, or every user's Sparkle would refuse this update
if [ -n "${EXPECTED_PUBLIC_KEY:-}" ]; then
  openssl=/opt/homebrew/bin/openssl
  [ -x "$openssl" ] || openssl=/usr/local/opt/openssl/bin/openssl
  [ -x "$openssl" ] || openssl=openssl
  # Ed25519 private key DER = fixed 16-byte PKCS#8 prefix + the 32-byte seed
  public="$({ printf '302e020100300506032b657004220420' | xxd -r -p; base64 -d < "$SPARKLE_KEY_FILE"; } \
    | "$openssl" pkey -inform DER -pubout -outform DER | tail -c 32 | base64)"
  [ "$public" = "$EXPECTED_PUBLIC_KEY" ] || { echo "The update key's public half ($public) isn't the app's SUPublicEDKey ($EXPECTED_PUBLIC_KEY)" >&2; exit 1; }
fi
name="$(basename "$zip")"
# sparkle:edSignature="..." length="..."
attrs="$("$SIGN_UPDATE" --ed-key-file "$SPARKLE_KEY_FILE" "$zip")"
[[ "$attrs" =~ ^sparkle:edSignature=\"[A-Za-z0-9+/=]+\"\ length=\"[0-9]+\"$ ]] || { echo "Unexpected sign_update output" >&2; exit 1; }
cat > "$out" <<XML
<?xml version="1.0" encoding="utf-8"?>
<rss version="2.0" xmlns:sparkle="http://www.andymatuschak.org/xml-namespaces/sparkle">
  <channel>
    <title>Seam</title>
    <link>$repo</link>
    <item>
      <title>Seam $version</title>
      <pubDate>$(LC_ALL=C date -u "+%a, %d %b %Y %H:%M:%S +0000")</pubDate>
      <sparkle:version>$build</sparkle:version>
      <sparkle:shortVersionString>$version</sparkle:shortVersionString>
      <sparkle:minimumSystemVersion>14.0</sparkle:minimumSystemVersion>
      <sparkle:fullReleaseNotesLink>$repo/releases/tag/v$version</sparkle:fullReleaseNotesLink>
      <enclosure url="$repo/releases/download/v$version/$name" type="application/octet-stream" $attrs/>
    </item>
  </channel>
</rss>
XML
# Signs the feed in place (SURequireSignedFeed), then checks both signatures verify
"$SIGN_UPDATE" --ed-key-file "$SPARKLE_KEY_FILE" --disable-signing-warning "$out" >/dev/null
"$SIGN_UPDATE" --verify --ed-key-file "$SPARKLE_KEY_FILE" "$out" >/dev/null
sig="$(sed -n 's/^sparkle:edSignature="\([^"]*\)".*/\1/p' <<< "$attrs")"
"$SIGN_UPDATE" --verify --ed-key-file "$SPARKLE_KEY_FILE" "$zip" "$sig" >/dev/null
echo "Signed $name and $(basename "$out")"
