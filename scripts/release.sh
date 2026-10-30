#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# One command for a release:  scripts/release.sh patch|minor|major|X.Y.Z
# From a clean, up-to-date main: bumps both apps' versions and build numbers and the README status
# lines, drafts CHANGELOG.md's section from the PR titles and opens it in $EDITOR, opens the release
# PR, waits for its checks, squash-merges it, and pushes the vX.Y.Z tag on the merge commit. The tag starts the release workflow, which still needs the `release`
# environment's approval and leaves a draft release for you to publish.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
fail() { echo "$*" >&2; exit 1; }
gradle=apps/android/app/build.gradle.kts
yml=apps/mac/project.yml

[ $# -eq 1 ] || fail "Usage: scripts/release.sh patch|minor|major|X.Y.Z"
[ "$(git branch --show-current)" = main ] || fail "Run this on main"
[ -z "$(git status --porcelain --untracked-files=no)" ] || fail "main has uncommitted changes"
git fetch -q origin main --tags
[ "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)" ] || fail "main isn't up to date with origin: git pull"

current="$(sed -n 's/.*versionName = "\([0-9.]*\)".*/\1/p' "$gradle")"
IFS=. read -r major minor patch <<< "$current"
case "$1" in
  patch) version="$major.$minor.$((patch + 1))" ;;
  minor) version="$major.$((minor + 1)).0" ;;
  major) version="$((major + 1)).0.0" ;;
  *) version="$1" ;;
esac
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "Not a version: $version"
git rev-parse -q --verify "refs/tags/v$version" >/dev/null && fail "v$version is already tagged"
code="$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' "$gradle")"
build="$(sed -n 's/.*CURRENT_PROJECT_VERSION: "\([0-9]*\)".*/\1/p' "$yml")"

echo "Releasing $current → $version (Android versionCode $code → $((code + 1)), Mac build $build → $((build + 1)))"
read -r -p "Go ahead? [y/N] " ok
[ "$ok" = y ] || exit 1

git switch -q -c "release-$version"
sed -i '' -e "s/versionName = \"$current\"/versionName = \"$version\"/" -e "s/versionCode = $code\$/versionCode = $((code + 1))/" "$gradle"
sed -i '' -e "s/MARKETING_VERSION: \"$current\"/MARKETING_VERSION: \"$version\"/" -e "s/CURRENT_PROJECT_VERSION: \"$build\"/CURRENT_PROJECT_VERSION: \"$((build + 1))\"/" "$yml"
# What changed: drafted from the PR titles since the last release, then edited by you
if ! scripts/changes.sh "$version" >/dev/null 2>&1; then
  previous="$(git describe --tags --abbrev=0 --match 'v[0-9]*.[0-9]*.[0-9]*' 2>/dev/null || true)"
  draft="$(git log --format='- %s' ${previous:+"$previous"..}HEAD | grep -v '^- Release [0-9]' | sed 's/ (#[0-9]*)$//' || true)"
  head="## $version" body="$draft" awk '!done && /^## / { print ENVIRON["head"] "\n\n" ENVIRON["body"] "\n"; done = 1 } 1' CHANGELOG.md > CHANGELOG.md.new
  mv CHANGELOG.md.new CHANGELOG.md
fi
echo "Opening CHANGELOG.md: write what changed in $version for people using Seam, then save and close."
"${VISUAL:-${EDITOR:-vi}}" CHANGELOG.md
scripts/changes.sh "$version" >/dev/null
# Only the status lines; "In 0.2.0" in feature tables stays as history
sed -i '' "s/Status: $current\./Status: $version./; s/\*\*Status:\*\* $current\./**Status:** $version./" README.md apps/android/README.md apps/mac/README.md
MAIN_REF=HEAD scripts/check-release.sh "v$version" >/dev/null

git commit -q -am "Release $version"
git push -q -u origin "release-$version"
pr="$(gh pr create --title "Release $version" --body "Bumps both apps to $version: Android versionCode $code → $((code + 1)), Mac build $build → $((build + 1)). Made by \`scripts/release.sh\`; after it merges, the script tags \`v$version\` on main.")"
echo "Opened $pr. Waiting for checks…"
sleep 10 # give GitHub a moment to register the checks
gh pr checks "$pr" --watch --interval 20 >/dev/null || fail "Checks failed on $pr: nothing merged or tagged"
gh pr merge "$pr" --squash --delete-branch
git switch -q main
git pull -q --ff-only origin main
sha="$(gh pr view "$pr" --json mergeCommit --jq .mergeCommit.oid)"
[ "$(git rev-parse HEAD)" = "$sha" ] || fail "main moved past the release commit $sha: tag it by hand (git tag -a v$version $sha)"
git tag -a "v$version" -m "Seam $version" "$sha"
git push -q origin "v$version"
echo "Tagged v$version. The release workflow: $(gh repo view --json url --jq .url)/actions/workflows/release.yml"
echo "Approve its signing jobs, then publish the draft release."
