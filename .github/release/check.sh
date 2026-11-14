#!/bin/sh
# SuperMessage's own release check: the release kit's check hook, run in the tag gate.
#
# The release ships every artifact of main CI's run whose name ends in the tagged commit's sha
# (`artifact` in .github/release-config.json): the desktop installers and the Android debug APKs
# (.github/workflows/ci.yml, jobs desktop and android-apks). A pattern that matches less than
# that still downloads, so a run missing one of them, or whose artifacts have expired, would
# release without it, silently. This refuses the tag instead.
set -eu
: "${CI_RUN_ID:?CI_RUN_ID is not set}" "${RELEASE_SHA:?RELEASE_SHA is not set}"
: "${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is not set}"

names=$(gh api "repos/$GITHUB_REPOSITORY/actions/runs/$CI_RUN_ID/artifacts?per_page=100" \
  -q '.artifacts[] | select(.expired | not) | .name')
missing=
for want in desktop-universal-apple-darwin desktop-x86_64-unknown-linux-gnu \
  desktop-x86_64-pc-windows-msvc android-apks; do
  printf '%s\n' "$names" | grep -qxF "$want-$RELEASE_SHA" || missing="$missing $want-$RELEASE_SHA"
done
if [ -n "$missing" ]; then
  echo "::error::main CI run $CI_RUN_ID has no unexpired artifact named:$missing. Tag a newer main commit, whose run has them."
  exit 1
fi
echo "main CI run $CI_RUN_ID has every release artifact for $RELEASE_SHA"
