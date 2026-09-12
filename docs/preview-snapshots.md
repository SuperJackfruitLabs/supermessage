# Looking at the iOS previews

**Written 2026-09-13.** Follows P2b, which wrote 52 iOS previews and rendered
none of them.

```bash
./scripts/snapshot-previews.sh          # → .snapshots/index.html
```

## What this is

`SnapshotPreviews` (MIT, `getsentry/SnapshotPreviews`) enumerates the app's
preview registry at run time and renders each `#Preview` on a simulator.
`SupermessagePreviewTests` is four lines and declares no test functions —
there is deliberately no list of previews to keep in step with the previews.

**A viewer, not a gate.** The images are gitignored. Several previews still
depend on the wall clock through relative-time formatting, so a committed
baseline would diff against itself; making this a regression gate is a
separate decision that needs those fixed first.

`PreviewGallery` — the package's on-device browser — is deliberately not
used. It links to the *app* target, so it would ship in release, and
`scripts/tests/test_ios_preview_leak.sh` exists to say that preview machinery
does not.

## What the first run found

43 of the 52 previews rendered. Three groups came out byte-identical, and
**every one was a real defect rather than a coincidence** — which is why the
index flags identical images rather than deduplicating them.

### 1. A `UIViewRepresentable` renders blank

`TimelineView` (both previews) and `TimelineCollectionView` produce an empty
frame. All three are identical, including "A conversation" against "Empty
room" — so it is not the data. The timeline is a `UICollectionView` behind a
representable, and it never gets the layout pass a snapshot would need.

This is exactly the outcome P2b's spec called plausible and undetected: *"a
preview that compiles and renders a blank frame would not be caught."* It
compiled, it was catalogued, it showed nothing, and nothing knew.

### 2. `PreviewSeeded` captures the frame before its seed lands

`ComposerView / Attachment staged` is identical to `ComposerView / Empty`,
and `SpacePillStrip / Three spaces` to `No spaces`. Both wrap themselves in
`PreviewSeeded`, whose `.task` has not completed when the shutter opens.

The `SpacePillStrip` preview's own comment predicted the frame — *"the frame
before the seed lands is a strip holding nothing but All"* — and treated it as
a curiosity. It is the only frame that gets captured.

### 3. The sender glyph is drawn twice, in every agent message

Not a preview problem. A product defect, visible the moment anyone looked:

    ✳ ✳ Atlas — Platform    11:30 PM

`TimelineRowView` calls `SenderFace(mxcUri:initial: named)`, and `SenderFace`
takes `initial.first`. `named` is the core's `senderName`, which for an agent
room is `"✳ Atlas — Platform"` — so the face renders the glyph and the name
beside it repeats it.

The roster does not have this problem, because `RoomRow` carries
`RoomIdentity.initial` — `"A"` — decided by the core. `TimelineRow` carries no
equivalent, so the view improvised, and improvising is the thing this app is
not allowed to do.

**The fix belongs in the core, not here.** `AGENTS.md`: the app parses
nothing and decides nothing. Stripping a leading glyph in SwiftUI would put a
naming rule in two hosts and let them disagree, which is the failure the
`RoomIdentity` split exists to prevent. The right change is a sender initial
on `TimelineRow`, decided once in Rust.

## What is excluded, and why

`PreviewSnapshotTests.excludedSnapshotPreviews()` skips `RootView`,
`SignedInView` and `RoomListView`.

- **The relative-time ones are a fixture problem.** `PreviewFixtures` uses
  absolute timestamps and the roster's state word is computed against
  `Date()` inside the view, which nothing can inject — so "14m" today is "3d"
  next week. Excluded rather than allowed to cry wolf.
- **`RootView` is a race.** Its `.task` sees `.starting` and calls `start()`;
  the stub answers immediately, so which phase is on screen when the shutter
  opens is not decided by the preview.

## What holds

Worth recording the rules that were checkable by eye for the first time and
passed:

- **Amber means a pending decision and nothing else.** In the five-row roster
  frame exactly one row is amber — its state dot and its preview line — and
  the answered card has none. This was asserted by review and by fixture
  counting before; now it has been seen.
- **The wrap guard holds.** The 104-character unbroken body wraps across four
  lines inside the bubble. The web's equivalent rendered 1147px wide on its
  first attempt while appearing to show the guard holding.
- **Serif for the agent, mono for data.** Sender names render serif, the
  meta line (`needs you · kaambaan · foundry`) renders monospaced, on paper
  rather than white.
- **The palette is the generated one.** The unread badge and the invitation
  chip are `accent` — `#5b43d4`, straight from `design/tokens.toml` — and not
  the system tint, which was the thing worth checking given P6.
