#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Builds Seam's Flatpak repo, the site GitHub Pages serves, from a release's Linux bundles:
#   FLATPAK_GPG_KEY_FILE=key.asc scripts/make-flatpak-repo.sh <site> <bundle>...
# Signs every commit and the summary with the key, after checking it's the one whose public half
# is in apps/linux/flatpak/seam.gpg. Writes <site>/repo (the OSTree repo), <site>/seam.flatpakrepo
# (the remote) and <site>/com.avetero.Seam.flatpakref (one-click install). The repo holds only
# this release: Flatpak updates to a newer commit without needing the older ones.
# Needs: flatpak, ostree, gpg.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
site="$1"; shift
[ $# -gt 0 ] || { echo "Usage: scripts/make-flatpak-repo.sh <site> <bundle>..." >&2; exit 1; }
url="${SEAM_FLATPAK_URL:-https://aveterosystems.github.io/seam}"
pinned="$root/apps/linux/flatpak/seam.gpg"

GNUPGHOME="$(mktemp -d)"
export GNUPGHOME
trap 'gpgconf --kill gpg-agent 2>/dev/null || true; rm -rf "$GNUPGHOME"' EXIT
gpg --batch --quiet --import < "${FLATPAK_GPG_KEY_FILE:?the signing key}"
key="$(gpg --list-secret-keys --with-colons | awk -F: '/^fpr/ { print $10; exit }')"
want="$(gpg --with-colons --show-keys < "$pinned" | awk -F: '/^fpr/ { print $10; exit }')"
[ -n "$key" ] && [ "$key" = "$want" ] || { echo "The signing key ($key) isn't the pinned one in $pinned ($want)" >&2; exit 1; }

rm -rf "$site" && mkdir -p "$site"
ostree init --mode=archive-z2 --repo="$site/repo"
for bundle; do
  flatpak build-import-bundle --gpg-sign="$key" --gpg-homedir="$GNUPGHOME" "$site/repo" "$bundle"
done
flatpak build-update-repo --generate-static-deltas --prune --default-branch=stable --title=Seam \
  --homepage=https://github.com/aveterosystems/seam --gpg-sign="$key" --gpg-homedir="$GNUPGHOME" "$site/repo"

gpgkey="$(base64 < "$pinned" | tr -d '\n')"
cat > "$site/seam.flatpakrepo" <<EOF
[Flatpak Repo]
Title=Seam
Url=$url/repo/
Homepage=https://github.com/aveterosystems/seam
Comment=Seam: your Android phone's notifications and clipboard on this computer
GPGKey=$gpgkey
EOF
cat > "$site/com.avetero.Seam.flatpakref" <<EOF
[Flatpak Ref]
Name=com.avetero.Seam
Branch=stable
Title=Seam
Url=$url/repo/
SuggestRemoteName=seam
Homepage=https://github.com/aveterosystems/seam
IsRuntime=false
GPGKey=$gpgkey
RuntimeRepo=https://dl.flathub.org/repo/flathub.flatpakrepo
EOF
# Checked as a user's Flatpak sees it, trusting only the pinned key: the summary's signature, then
# each app's commits (an install verifies them)
check="$(mktemp -d)"
export FLATPAK_USER_DIR="$check"
flatpak remote-add --user --gpg-import="$pinned" check "file://$(cd "$site/repo" && pwd)"
refs="$(flatpak remote-ls --user check --app --all --columns=ref)"
[ "$(printf '%s\n' "$refs" | grep -cE '^(app/)?com\.avetero\.Seam/[^/]+/stable$')" = $# ] \
  || { echo "Expected $# stable builds of com.avetero.Seam, found: $refs" >&2; exit 1; }
flatpak install --user --noninteractive --no-deps check com.avetero.Seam//stable >/dev/null
unset FLATPAK_USER_DIR
rm -rf "$check"
echo "Built $site with: $refs"
