# Looking at the native previews

**Written 2026-09-13.** Follows P2b, which wrote 52 iOS previews and rendered
none of them.

```bash
./scripts/snapshot-previews.sh          # iOS   → .snapshots/index.html
cd android && ./gradlew :app:testDebugUnitTest \
  --tests '*PreviewScreenshotTest*' -Proborazzi.test.record=true
                                        # Android → app/build/outputs/preview-captures/
```

| | Rendered | Of | How |
|---|---|---|---|
| iOS | 40 | 52 | `SnapshotPreviews` on a simulator |
| Android | **48** | 48 | Roborazzi + Robolectric, **no emulator** |

## Android renders all of them, and waits

Roborazzi draws every `@Preview` on the JVM through Robolectric in `NATIVE`
graphics mode, and `ComposablePreviewScanner` finds them by scanning the
classpath — so there is no list to fall behind the previews it describes.
About two minutes for all 48, and no device.

**Robolectric publishes nothing past 4.15.1 and SDK 36 support lands in
4.16, so these render at API 35** — one level below this project's
`compileSdk`. Worth knowing when reading a frame.

Two things had to give way, and both are recorded because they are the sort
of thing that looks arbitrary later:

- **The scanner cannot see `private` previews.** All 48 preview functions are
  `internal` for that reason, not by style preference. With them private it
  found eleven — the ones already opened up for something else — and silently
  reported success.
- **Three previews needed `:kit`'s JNA bootstrap.** `AccountPanel`'s and
  `NewRoomPanel`'s reach a type whose class initialiser loads the core, and on
  a host JVM `Native.load` wants `libjnidispatch.jnilib` as a classpath
  resource — which `:core`'s `jna@aar` does not carry, because an AAR packages
  native code as Android jniLibs. `:kit`'s build file had already hit this and
  says so at length; `:app` now mirrors it.

**Android's frames are more trustworthy than iOS's.** Robolectric drives
pending work to completion before the frame is taken, so `AccountPanel` and
`NewRoomPanel` render their *content* here. The same two screens snapshot as
loading states on iOS.

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

### 1. A `UIViewRepresentable` renders blank — the harness, not the preview

`TimelineView` (both previews) and `TimelineCollectionView` produced an empty
frame, which looked exactly like the outcome P2b's spec called plausible and
undetected: *"a preview that compiles and renders a blank frame would not be
caught."*

**It is not that, and the difference matters.** Rendering one with a red
background produced a *full-screen red* frame: the SwiftUI wrapper is present
and correctly sized, and only the `UICollectionView`'s cells are absent.
`UIKitRenderingStrategy` puts the view in a real `UIWindow` and calls
`drawHierarchy(afterScreenUpdates: true)`, but a diffable data source's
`apply` lands on a later runloop turn, so the shutter opens before a cell
exists. The package offers no readiness hook to wait on.

Xcode's canvas keeps re-rendering a live process and has no such problem. So
these are previews that work where a person looks at them and cannot be
captured here — excluded, so their blank frames stop being reported as a
defect every run.

### 2. `PreviewSeeded` captures the frame before its seed lands

`ComposerView / Attachment staged` is identical to `ComposerView / Empty`,
and `SpacePillStrip / Three spaces` to `No spaces`. Both wrap themselves in
`PreviewSeeded`, whose `.task` has not completed when the shutter opens.

The `SpacePillStrip` preview's own comment predicted the frame — *"the frame
before the seed lands is a strip holding nothing but All"* — and treated it as
a curiosity. It is the only frame that gets captured.

### 3. The sender glyph is drawn twice, in every agent message — fixed

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

**Fixed in the core, not here.** `AGENTS.md`: the app parses nothing and
decides nothing. Stripping a leading glyph in SwiftUI would have put a naming
rule in two hosts and let them disagree, which is the failure the
`RoomIdentity` split exists to prevent. `TimelineRow` now carries
`sender_initial`, and `sender_name`/`sender_short` arrive glyph-free.

Two things fell out of fixing it that were worth more than the fix.

**Android had the same bug and a worse one underneath.** Its face did
`initial.firstOrNull()`, and a Kotlin `Char` is a UTF-16 code unit — so an
astral glyph rendered as half a surrogate pair, which is tofu.
`room_identity.rs`'s own header records Android doing exactly that for an
account avatar once before.

**Three of the roster fixtures were wrong**, in ways that cancelled out to
look plausible: `identity.name` kept its glyph, `initial` was a letter rather
than the glyph, and `matrix-rust-sdk` was shown verbatim when the core
humanises it to `Matrix Rust Sdk`. So the roster preview — the one used to
check the amber rule — was a screen the product does not have. The fixtures
now state what `parse_room_identity` produces, and the core pins those values
in a test.

### 4. Anything that loads in a `.task` snapshots as its loading state

Found while confirming the above, and it bounds what every number on this
page means.

`RoomInfoPanel` renders as a bare spinner. It was **not** reported as a
problem, because duplicate detection can only speak when there are two frames
to compare and this view has one preview. `NewRoomPanel`'s two previews *were*
caught, and only because there are two of them and they are identical.

Every panel that loads through a `.task` — room info, account, search, new
room, the invitation's inviter — is in this category. Some of their frames on
this page are the screen; some are the spinner before it. The index now also
measures how much of each frame is a single flat colour, which is what sorts
one from the other, and says plainly that it is a number to look at rather
than a verdict: a small component on a full-device canvas is legitimately 93%
background.

**So "40 previews rendered" is not "40 screens verified".** It is 40 frames,
of which the ones that matter have been looked at individually.

### 5. The room info panel's `Done` is system blue

Not the token `accent` (`#5b43d4`). A toolbar button taking `UIColor.tintColor`
rather than the palette is exactly the P6 gap P1 recorded in the abstract —
iOS defining colour roles it does not consistently reach for. Recorded in
`docs/platform-parity.md`; not fixed here, because P6 is its own project and
this one is about being able to see.

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
