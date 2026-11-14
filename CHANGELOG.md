# Changelog

Changes people will notice, newest first. Add an entry under `## Unreleased` with each such
change; before a tag, a release-prep PR runs `sh .github/release/prep-release.sh vX.Y.Z`, which
stamps the section `## vX.Y.Z — YYYY-MM-DD`, and the desktop release notes quote it. Releases up
to v0.1.2 predate this file.

## Unreleased

- Desktop releases are published, with `SHA256SUMS` and a build-provenance attestation for each
  installer, from the installers main's CI built and tested for the tagged commit.
- Every release carries the four Android debug APKs again
  (`supermessage_<version>_android-<abi>-debug.apk` for arm64-v8a, armeabi-v7a, x86 and x86_64),
  built by main's CI for the tagged commit beside the desktop installers. A tag whose CI run lacks
  any of them is refused rather than released without it.
