# Changelog

Changes people will notice, newest first. Add an entry under `## Unreleased` with each such
change; before a tag, a release-prep PR runs `sh .github/release/prep-release.sh vX.Y.Z`, which
stamps the section `## vX.Y.Z — YYYY-MM-DD`, and the desktop release notes quote it. Releases up
to v0.1.2 predate this file.

## Unreleased

## v0.1.3 — 2026-11-23

- A turn that failed because the agent's gateway stopped answering reads "Agent disconnected"
  rather than a plain error (the `harness_disconnected` turn error kind).
- On iOS, a Lock Screen decision for a card waiting on a person (or of a kind this build does not
  know) opens the app instead of offering a permission's buttons.
- A voice note's transcript and an agent's spoken reply from a newer hub (a higher
  `schema_version`) are drawn best-effort rather than shown as the plain message.
- A tool row in a live turn opens onto the files it touched; the Input and Output sections, which
  no agent ever sent, are gone, as is a turn card's unused reasoning field.
- The Live Activity client sends only what the hub accepts: a push token of 32 to 512 hex digits,
  and device and activity ids of 1 to 255 characters.
- A card whose subject carries an expiry that is not a real RFC 3339 date-time (with seconds and
  an offset), or a revision that is not a whole number, is no longer read as bound.
- A v2 card draws up to twenty options, the contract's bound, and a v1 gate card with an empty
  `gate_id` offers no buttons.
- On iOS, Settings → Account → Link to your Super Jackfruit account, shown when the homeserver has
  an Accounts service.
- The client follows the suite's published contracts (Treaty 0.0.17): their data is vendored under
  `vendor/treaty/`, checked by `treaty verify` in CI, and every chat-activity and Live Activity
  case runs through the real readers.

- Desktop releases are published, with `SHA256SUMS` and a build-provenance attestation for each
  installer, from the installers main's CI built and tested for the tagged commit.
- Every release carries the four Android debug APKs again
  (`supermessage_<version>_android-<abi>-debug.apk` for arm64-v8a, armeabi-v7a, x86 and x86_64),
  built by main's CI for the tagged commit beside the desktop installers. A tag whose CI run lacks
  any of them is refused rather than released without it.
