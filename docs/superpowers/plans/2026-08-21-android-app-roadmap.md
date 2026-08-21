# Android `app` roadmap (companion steps 5–6)

**Status:** roadmap, 21 Aug 2026. **Not an implementation plan** — see below.
**Companion:** `docs/superpowers/specs/2026-08-20-android-app-design.md` decides *what* the views draw. `docs/superpowers/plans/2026-08-21-android-kit.md` is the state layer this consumes and must land first.

## Why this is a roadmap and not a plan

`kit` could be planned to the step because it is a **port**: 2,892 lines of Swift with 2,134 lines of tests that translate almost line for line. The rules are already written down, and a plan for it is mostly a reading order plus the traps.

The views are not a port. `apple/Supermessage` is 4,092 lines of SwiftUI and UIKit, and the spec says plainly: *"New. Compose is not SwiftUI."* The single largest file, `TimelineCollectionView.swift` (674 lines), is a `UICollectionView` with a diffable data source and an inverted layout; the Compose equivalent is `LazyColumn(reverseLayout = true)` with a different measurement model, different animation primitives, and different failure modes.

Writing bite-sized TDD steps with real Kotlin for view code whose layout has not been designed would mean inventing it. That is the failure the planning discipline exists to prevent, so this document does what is honest instead: it decomposes the work, says what is already decided, and names the questions each phase must answer **before** it earns a plan.

Each phase below gets its own brainstorm → spec → plan → implementation cycle.

## What is already decided, and must not be re-litigated

These come from the companion spec and from the scaffold that shipped. A phase's design pass inherits them.

- **The shell exists.** `RootScaffold` on `ListDetailPaneScaffold`, with pane count from `paneCountFor(width)` — a measured width, never `WindowWidthSizeClass`. Phone and tablet both work; three instrumented tests cover the geometry. Real panes replace the placeholders; the rule does not change.
- **The app parses nothing and decides nothing.** Every classification, projection and identity question already has an answer in `supermessage-core`. A view that re-derives one is a bug in waiting, and it is the reason a third client is worth having at all.
- **The four timeline rules**, each of which was a shipped iOS bug:
  1. Open at the newest message, fully visible, not under the composer.
  2. Animate an arrival, and nothing else — not a page of history, not a room's first fill, not more than a handful of rows at once.
  3. Do not rebuild every row on every streaming token. `TimelineStore.revision` answers "did the history actually change" in constant time; the view must consume it rather than diffing rows itself.
  4. The keyboard dismisses on drag. It had no way down for weeks.
- **Assert geometry, not existence.** A test once asserted the room-info panel existed while it was laid out off the side of an iPad. The scaffold's `assertWithinShell` is the pattern; reuse it.
- **Typography is structural**, not decorative: serif for what agents write, sans for what the operator writes, mono for data and sigils, and amber for nothing but a pending decision. That structure survived a typeface swap on iOS and should survive the move to Android's type system.

## The phases

### Phase A — the reading surface (spec step 5)

Roster and timeline. The room list reads correctly, a room opens, history paginates, and an agent's live turn renders. Roughly `RoomListView` + `RoomRowView` + `TimelineView` + `TimelineRowView` + `TimelineCollectionView` + `RichTextView` + `StreamingTextView` + `LiveTurnView` + `DecisionCard`, ~2,000 Swift lines of equivalent.

**This is where the judgement is.** The spec says so, and the reason is that the timeline is what a chat app is judged on.

Questions its design pass must answer:
- Does `LazyColumn(reverseLayout = true)` give an exact "am I at the newest message" the way an inverted `UICollectionView` does, where the answer is `contentOffset.y <= 0`? The Compose equivalent is `firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset == 0` — verify that on a device before designing around it.
- How does rule 2 (animate an arrival, nothing else) express in Compose? `animateItem` applies per item; deciding *which* changes animate needs the revision counter and probably a keyed distinction between "appended" and "filled".
- What is the row identity? iOS used `TimelineItemDto.id` to stop a confirmed message flickering. Compose's `key` in `items()` is the analogue and getting it wrong produces the same flicker.
- Does the decision card — amber, and the one thing that colour means — need a different affordance on Android, where the interaction idiom differs?

### Phase B — the composer (spec step 6, first half)

Sending, replies, edits, reactions, attachments. `ComposerView.swift` is 274 lines; `ComposerStateTests.swift` (96 lines) already exists in the Kit's suite and is ported by the `kit` plan, so the state rules arrive before the view does.

Questions:
- Rule 4 (keyboard dismisses on drag) is a Compose `nestedScroll` concern, not a view concern — where does it live so the timeline and composer agree?
- Attachment picking is a platform API with no iOS analogue worth copying: Android's photo picker has its own contract.
- How does an edit-in-progress present, given `EditTarget` is already a store?

### Phase C — the panels (spec step 6, second half)

Room info, search, new room, invitations, account. Five panels, ~935 Swift lines, each largely independent of the others — the most parallelisable phase, and the one where a plan can be most mechanical once the first panel establishes the pattern.

The adaptive question is already answered: on a wide layout these are the third pane; on a narrow one they are a bottom sheet. `paneCountFor` decides which.

**The room info panel carries a live bug from the other platform, and this phase is where Android inherits the risk.** The scaffold spec §4.1 cites the iPad incident — a panel laid out at x=850.5 on an 834-point window — as historical rationale. It is not historical: reviving the test that measures it found the fault still present on `iPad Pro 11-inch (M4)`, filed as **#26**. It had been invisible because the test died on a stale selector in #24 and there was no iOS job in CI.

Android is safe from one half of it. iOS computes `isWide` from a `width` that starts at `0` and is filled in by a `GeometryReader`, so there is a window where the branch is taken on a stale value; `BoxWithConstraints` hands `maxWidth` over synchronously and has no initial-zero state.

Android is **exposed to the other half**. `RootScaffold`'s `LaunchedEffect` — the one that collapses an open info pane when the shell narrows past three panes — cannot fire today, because nothing calls `navigateTo(Extra, ...)`. Its own comment says so, and records that deleting it changes no test. **The moment this phase opens the info pane independently of width, that block goes from dead code to load-bearing, with nothing covering it.**

So the requirement, for whichever task first makes the info pane openable:

> The same task adds the test that strands it — open the pane at a wide width, narrow below `ThreePaneWidth`, assert the pane is gone. Then mutate it: delete the `LaunchedEffect`, watch that test fail, restore. Until that mutation has been seen to fail, rule 2 is documentation rather than a guarantee.

This is the one place in the roadmap where a test is specified before the design pass, because the failure it guards has now been observed twice on a sibling platform.

### Phase D — theme and polish

`Theme.swift` is 140 lines. Android has its own type scale, dynamic colour, and a dark mode users expect to work. This is also where the manifest's light-only placeholder theme gets replaced — `AndroidManifest.xml` currently carries a comment saying exactly that, because no dependency-free `DayNight.NoActionBar` exists in this SDK.

## Sequencing

A → B → C, with D folded into A's tail rather than saved for the end: a timeline built against a placeholder theme gets rebuilt when the real one lands.

Each phase ends somewhere you could stop and have something honest to show:
- After A, a room reads correctly on a device.
- After B, you can hold a conversation.
- After C, it is the app the spec describes.

## What is still not covered anywhere

- **The spaces rail.** A fourth surface. `ListDetailPaneScaffold` will need negotiating with when it arrives — the known cost of adopting the component.
- **Push.** `event_id_only` via Sygnal → FCM is described in `AGENTS.md` and built on no platform.
- **Mobile release and signing.** The release workflow builds macOS, Linux and Windows. Neither mobile platform is in it.
- **The desktop's own gaps.** Mute, edit/delete and scoped search have core support and Tauri commands but no Svelte. Android should not wait for them and should not re-derive them.
