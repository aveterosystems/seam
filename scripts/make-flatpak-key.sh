#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# One-time, run by the maintainer on their own Mac, after scripts/make-release-keys.sh. Makes the
# key that signs Seam's Flatpak repo (OpenPGP: what Flatpak checks every Linux update against),
# keeps it in the same encrypted disk image as the other keys, stores it as a `release`
# environment secret, writes its public half to apps/linux/flatpak/seam.gpg (commit that), and
# turns on GitHub Pages, which serves the repo.
# Losing this key means Linux users can't be offered updates until they add the repo again.
# Needs: GnuPG (brew install gnupg), gh logged in as a repo admin.
set -euo pipefail
repo="aveterosystems/seam"
root="$(cd "$(dirname "$0")/.." && pwd)"
image="${1:-$HOME/Seam release keys.dmg}"
public="$root/apps/linux/flatpak/seam.gpg"
command -v gpg >/dev/null || { echo "Needs GnuPG: brew install gnupg" >&2; exit 1; }
[ -f "$image" ] || { echo "No $image: run scripts/make-release-keys.sh first" >&2; exit 1; }

read -rsp "Disk image password: " pass; echo
mount="$(printf '%s' "$pass" | hdiutil attach -stdinpass -nobrowse "$image" | sed -n 's|.*\(/Volumes/.*\)$|\1|p' | tail -1)"
unset pass
[ -n "$mount" ] || { echo "Couldn't open $image" >&2; exit 1; }
# Its own keyring, on the encrypted image: never the maintainer's own ~/.gnupg
export GNUPGHOME="$mount/flatpak-gnupg"
trap 'gpgconf --kill gpg-agent 2>/dev/null || true; hdiutil detach -quiet "$mount" 2>/dev/null || true' EXIT
[ -e "$GNUPGHOME" ] && { echo "$GNUPGHOME already exists: not replacing the Flatpak key" >&2; exit 1; }
umask 077
mkdir "$GNUPGHOME"

# No passphrase: the disk image and the GitHub secret are what protect it, as for the other keys
gpg --batch --quiet --gen-key <<'EOF'
%no-protection
Key-Type: RSA
Key-Length: 4096
Key-Usage: sign
Name-Real: Seam Flatpak repo
Expire-Date: 0
%commit
EOF
fpr="$(gpg --list-secret-keys --with-colons | awk -F: '/^fpr/ { print $10; exit }')"
[[ "$fpr" =~ ^[0-9A-F]{40}$ ]] || { echo "Unexpected key fingerprint: $fpr" >&2; exit 1; }

# The secret first: if this fails, nothing in the repo has changed yet
gpg --batch --armor --export-secret-keys "$fpr" | gh secret set FLATPAK_GPG_PRIVATE_KEY --env release --repo "$repo"
gpg --batch --export "$fpr" > "$public"
# Pages, built by the flatpak-repo workflow rather than from a branch
gh api "repos/$repo/pages" >/dev/null 2>&1 || gh api -X POST "repos/$repo/pages" -f build_type=workflow >/dev/null
# Pages' environment only deploys from main by default: the workflow runs on a release's v* tag
gh api -X PUT "repos/$repo/environments/github-pages" --input - >/dev/null <<'JSON'
{"deployment_branch_policy":{"protected_branches":false,"custom_branch_policies":true}}
JSON
for policy in "main branch" "v* tag"; do
  name="${policy% *}" type="${policy#* }"
  gh api "repos/$repo/environments/github-pages/deployment-branch-policies" --jq '.branch_policies[].name' | grep -qxF "$name" \
    || gh api -X POST "repos/$repo/environments/github-pages/deployment-branch-policies" -f name="$name" -f type="$type" >/dev/null
done
echo "Done. Flatpak key $fpr saved in $image; public key written to apps/linux/flatpak/seam.gpg (commit it)."
