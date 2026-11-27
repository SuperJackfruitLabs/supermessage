# Changelog

Changes people will notice, newest first. Add an entry under `## Unreleased` with each such
change; before a tag, a release-prep PR runs `sh .github/release/prep-release.sh vX.Y.Z`, which
stamps the section `## vX.Y.Z — YYYY-MM-DD`, and the desktop release notes quote it. Releases up
to v0.1.2 predate this file.

## Unreleased

- On macOS, pressing Call no longer quits the app. v0.1.4's macOS build was linked without
  libwebrtc's Objective-C categories, so the call died ("unrecognized selector") as it set up its
  media. CI now checks the built macOS app for them.

## v0.1.4 — 2026-11-27

- On desktop, voice calls with an agent. Account → Calls ("Voice calls with agents") is off by
  default and kept per device. With it on, an agent's room has a Call button beside Info, and a
  call bar above the room pane shows the agent's name, the time in the call, the agent's own state
  (Connecting…, Listening, Thinking…, Speaking), Mute and Hang up.
  - What you say in a call is posted to the room as your message, marked as spoken.
  - The call uses MatrixRTC membership and LiveKit, end to end encrypted, through
    [superdial](https://github.com/SuperJackfruitLabs/superdial) v0.0.1.
  - If the agent cannot take calls, pressing Call reads "Calls are not available for this agent
    yet".
  - A new call waits until a hung-up one has left, and a leave that never returns is given up
    after 45 s.
  - iOS and Android build no calls.
- When one message in a room fails to send, the messages after it go out once it is dropped or
  sent, where before the room's queue stopped for good.
  - A message is dropped, and disappears from the timeline, when the homeserver refuses it (a 4xx
    other than 401, 408 or 429), or when its attachment is missing or of a type that cannot be
    sent.
  - Anything else (a network error, a rate limit, a store error) is retried, waiting from 2 s and
    doubling up to a minute.
- matrix-sdk 0.18.0 → 0.19.1.
- Linking a Super Jackfruit account follows treaty's URL shapes for the Accounts link page and its
  `supermessage://linked` callback (`accounts.matrix-link`, treaty 0.0.19): every vendored case
  runs through the address this client builds and the callback it reads. The callback is now
  refused when it has a path (`supermessage://linked/done?mxid=…`), a second `mxid`, or a user
  name, where before only its scheme and host were checked.
- A decision card the board cancelled — its card was moved by hand or archived before anyone
  decided — closes as "Cancelled — the card was moved or archived before anyone decided, so this
  gate can no longer be answered here." on every client, where it read "Decided". A decision this
  build does not know still closes the card and reads "Decided".

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
