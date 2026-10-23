# Parity M4 (Android) — The agent experience: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task by task. Steps use
> checkbox (`- [ ]`) syntax for tracking. Each task is a bundle: one implementer dispatch, targeted
> tests while iterating, the full suites once before the commit, one review. **An implementer reads
> only its own task, plus Global Constraints and Review Focus.**

**Goal:** turn the Android agent-experience rows of the parity matrix ✅ — A1 (header status pill),
T1 (agent message cards and the serif long read), A2 (one live activity card in the timeline), A3
("Sent to" → "is on it"), T6 (the turn record and "What I did" in the answer row), D6 (the quote
hidden under its parent), D11 (one name per person on the typing line and the live card) and R1
(generated agent tiles, the dot on the avatar, two-line rows, runtime out of the row) — and pass
roadmap Review Focus 1 and 2 in the running app (M4's exit).

**Core prerequisites — read first.** `.superpowers/sdd/planning-m4/core-prereqs.md`:
- **P1** `Core.acknowledgement(roomId, addressee, agentIds, typingAgents, turnInProgress)` — Task 5
  consumes it. If it has not merged when Task 5 starts, Task 5 uses the existing free
  `acknowledgement(rows, …)` (Task 5, Step 0) and the switch is a one-line follow-up.
- **P2** `TurnSummary.agentId` — only if the owner accepts Q1. Task 5 wires it if it is in the
  bindings; otherwise the live card keeps iOS's naming rule (Task 2).

**Architecture:**
- **All work is in `android/`. This track never edits `crates/` or the generated bindings, and
  never runs `build-android-libs.sh`.** Everything consumed is in the generated Kotlin once M1b
  (Batches A and B) and, for Task 5, P1 have merged.
- **Kit** (`android/kit`, JVM-testable, no Compose): `LiveStore` becomes a mirror of the core's turn
  record (`FfiEvent.Turn`, `Core.turn(roomId)`), exactly as iOS's does after M1b C5; `TypingStore`
  recognises the room's cast (D11); `Session` gains `turn` and `acknowledgement`.
- **App** (`android/app`): the activity card, "What I did", the agent card and long read, the
  header status pill, the roster tile and two-line row, and the dock above the composer.
- **M1b names may still change.** This plan uses the interfaces as written in
  `.superpowers/sdd/planning-m1-m2/2026-10-22-parity-m1b-turns-timeline.md` (at planning time only
  C1 is committed). Every task's Step 0 greps the generated Kotlin for the names in "Interfaces
  consumed" below; if one differs, use the merged name and say so in the report.

**Tech stack:** Kotlin 2.4.0, Compose (BOM 2026.06.01), AGP 9.3.1, compileSdk 36, minSdk 31.
Tests: JUnit4, compose-ui-test, Roborazzi 1.74.0 on Robolectric 4.15.1.

**Spec:** `docs/research/2026-10-22-ios-parity/README.md` rows A1, T1, A2, A3, T6, D5, D6, D11, R1
and `notes/android.md` (same IDs). Umbrella: `docs/superpowers/plans/2026-10-22-ios-parity-roadmap.md`
"M4", "Review Focus" 1–2 (the exit), 3 and 5. Format follows
`.superpowers/sdd/planning-m3/2026-10-22-parity-m3-android.md`.

**iOS citations** are to `apple/` at `e6d934b` (`parity/m1-core-first-run`, = `origin/main` plus
M1a PR5 and M1b C1). **M1b C5 rewrites `LiveTurnView.swift:55-211` and `WhatIDidFooter.swift:24-60`
to read `TurnSummary`; C7/C10 change `TimelineRowView.swift`/`TimelineCollectionView.swift` call
sites.** The copy, sizes and timings quoted below do not change in those PRs; find a moved line by
the quoted text.

Paths used below:
- `A=android/app/src/main/kotlin/dev/supermessage`
- `K=android/kit/src/main/kotlin/dev/supermessage/kit`
- `AT=android/app/src/androidTest/kotlin/dev/supermessage`
- `UT=android/app/src/test/kotlin/dev/supermessage`
- `KT=android/kit/src/test/kotlin/dev/supermessage/kit`
- `KF=android/kit/src/testFixtures/kotlin/dev/supermessage/kit`
- `PV=android/app/src/debug/kotlin/dev/supermessage/previews`
- Android line numbers are at `0cd82ac` (`parity/m2-android`); M2 Tasks 10–15 and M3 move them.
  Find code by the quoted symbol.

**Branch and worktree:** `parity/m4-android`, in `.worktrees/parity-android`, cut from the tip of
`parity/m3-android` after its Task 4.

**Preconditions (controller, before Task 1):**
- M2 Android Tasks 1–15 and M3 Android Tasks 1–4 are complete.
- The branch contains `origin/main` with **M1b Batch A (C1–C5) and Batch B (C6–C12)** merged, and
  jniLibs plus the host `target/debug/libsupermessage_ffi.so` rebuilt for it (the M2 ledger's
  procedure). C11 already moved Android's grouping to the core (membership stretch, `continuesRun`,
  `endsRun`, `showsRuntime`), so D5 is done before this plan starts.
- Before Task 5: merge `origin/main` again if P1 (and P2, if accepted) merged, and rebuild the
  libraries.

### Interfaces consumed (names as planned in M1b; Step 0 checks them)

| Kotlin (generated) | From | May change? |
|---|---|---|
| `FfiEvent.Turn(update: TurnUpdate)` | C4 | yes (M1b in progress) |
| `CoreInterface.turn(roomId: String): TurnSnapshot?` | C4 | yes |
| `TurnUpdate(summary: TurnSummary, step: TurnStepChange?)`, `TurnStepChange(index: UInt, step: TurnStep)` | C2 | yes |
| `TurnSummary(roomId, turnId: ULong, revision: ULong, inProgress, endedBy: TurnEnding?, startedAtMs: ULong, endedAtMs: ULong?, lastActivityMs: ULong, answering, hasThought, totalSteps: UInt, completedSteps: UInt, failedStep: String?, currentStepTitle: String, stepIsTool, recordLabel: String?)` | C2 | yes |
| `TurnSnapshot(summary, steps: List<TurnStep>, thought: String?)` | C2 | yes |
| `TurnStep(id, title, kind: String?, status, phase: ToolPhase, statusLabel, locations, input: String?, output: String?, seq: ULong, hasDetail)` | C2 | yes |
| `foldTurn(roomId: String, inputs: List<TurnInput>): TurnSnapshot?`, `elapsedLabel(elapsedMs: Long): String` | C1, C4 | yes |
| `TimelineRow.isAgent`, `.isLongRead`, `.hidesQuote`, `.showsRuntime`, `.continuesRun`, `.endsRun` | C6, C9 | yes |
| `roomStatus(state, describesAgent, turnInProgress): RoomStatus?`, `roomStatusWord(status)`, `rosterRow(rows, roomId, nowMs)`, `RosterRow.avatar: AgentAvatarStyle?`, `AvatarRole` | M1a PR1, PR3 | no (merged) |
| `Core.roomCast(roomId): RoomCast`, `typingView(typers, agentIds, counterpart): TypingView`, `acknowledgement(rows, …)` (free), `Core.hasSent` | M1a PR4 | no (merged) |
| `Core.acknowledgement(roomId, addressee, agentIds, typingAgents, turnInProgress): Acknowledgement?` | P1 | new |
| `TurnSummary.agentId: String?` | P2 (Q1) | new, optional |

## Global Constraints

**From the roadmap and AGENTS.md (binding):**
- **Tokens only:** no hex in views; colours from `SupermessageTheme.colors`, radii from
  `Metrics`, type from `MaterialTheme.typography` roles mapped in `design/tokens.toml` (meta →
  `labelMedium`, label → `labelSmall`, ui → `bodySmall`, longread → serif `bodyLarge`). New type
  roles go through `design/tokens.toml` and `python3 scripts/generate-tokens.py`; never edit
  `GeneratedThemeTokens.kt`.
- **Amber (`signal`) means a pending decision and nothing else.** In this plan only the "Needs you"
  status (pill word, pill ground `signalSoft`, roster dot) and a pending preview line are amber —
  each is a pending decision. Working, Done, failure, the dock and the Agent label never are.
- **The app parses nothing and decides nothing the core decides.** Status, words, step titles,
  counts, the record label, elapsed text, who is an agent, what is a long read, which quote hides,
  the typing line and the dock text are all the core's. The host only places them.
- **Match iOS behaviour and copy, not its widgets.** Every string is quoted verbatim with its iOS
  `file:line`. A string iOS lacks is marked *(no iOS copy)*.
- **Streaming rules (AGENTS.md, "Reproducing timeline scrolling"):** never animate the whole list
  (no `animateContentSize`, no list-wide `animateItem` placement while a turn streams); a
  streaming cell's resize must not animate; motion is opacity and translation only (`alpha`,
  `graphicsLayer { translationY }`, `fadeIn`/`slideInVertically`), never an animated height.
- **Reduce Motion:** every animation goes through `motion(reducesMotion(), …)` / `reducesMotion()`
  (`A/Motion.kt`). No UI test reads the device setting; tests provide `LocalReduceMotion`.
- **Testing:** no single-character substring matchers; test shells set the required **width**
  (`Modifier.requiredWidth(411.dp)`), never the height. Every new UI state ships an `internal`
  `@Preview` in `PV/`; a preview whose animation never settles renders still (reduce motion on, or
  a `still = true` parameter) or joins `PreviewScreenshotTest.NEVER_SETTLES`.
- **Haptics** through a recording `LocalHapticFeedback`, never a device vibration.
- **Falsify every new test** (AGENTS.md "A test that has never failed"): apply the named mutation,
  watch the named test fail, restore, record the failure line in the report. Timing or gesture
  tests: run the mutant three times.

**This machine (owner rules, 2026-10-22):**
- Every heavy command takes the shared lock:
  `flock -w 7200 $HOME/.cache/supermessage-heavy.lock <command>`. Heavy: any `./gradlew` run, any
  `cargo build|test|clippy`. Light: git, grep. `export CARGO_BUILD_JOBS=8`. `./gradlew --stop`
  when you finish.
- One emulator, never restarted by you mid-task. Emulator logs in `TMPDIR=~/Android/dl`.
- `cargo build -p supermessage-ffi` (under the lock) only if the host library `:kit` tests need is
  missing.
- While iterating, run the focused class. Run the full suites **once per task**, before the commit.

**Verification for every task**, from `android/`, ending in `BUILD SUCCESSFUL` and 0 failures:
- `flock -w 7200 $HOME/.cache/supermessage-heavy.lock ./gradlew :kit:test :app:testDebugUnitTest`;
- from the repo root, `scripts/android-ci-parity.sh` (once per emulator boot);
- `flock -w 7200 $HOME/.cache/supermessage-heavy.lock ./gradlew :app:connectedDebugAndroidTest`
  **at one height, 1080x2400** (owner decision, 2026-10-22).
- **Exception:** a task whose new UI depends on height (a sheet, the dock above the keyboard) also
  runs the affected classes at `adb shell wm size 1080x1280`, then `adb shell wm size 1080x2400`.
  Single class: `-Pandroid.testInstrumentationRunnerArguments.class=dev.supermessage.<Class>`.
- **Both heights for the whole suite** run once, at the end of the branch (Task 5).

**Roborazzi baselines:** record with
`./gradlew :app:testDebugUnitTest --tests '*PreviewScreenshotTest*' -Proborazzi.test.record=true`
(under the lock), then copy in only the frames this task meant to add or change, after inspecting
each by eye, and name them in the report. Never re-record to make the gate pass; a frame nobody
meant to change is a regression to explain.

**Live account: READ-ONLY.** On a device signed in to the real account you may open rooms, menus,
sheets and panels, and take screenshots. You may **not** send, react, answer, edit, delete, block,
report, leave or accept. Anything that needs a write goes under "Live verification pending"; the
owner's live pass (Q2) covers it.

**Serialized files:** `RoomPane.kt` (Tasks 1, 2, 4, 5), `Timeline.kt` (Tasks 2, 3, 5),
`TimelineRow.kt` (Tasks 2, 3), `MainActivity.kt` (Task 4). Tasks run in plan order, one
implementer at a time.

**Commits** end with the attribution lines in the session's system reminder.

## Review Focus

1. **A bridge agent that answers by editing a progress message** (roadmap RF1, M4 exit). With no
   live deltas the core starts no turn, so there is no card and no "Working"; a turn that did start
   ends when the agent's new or edited message lands (M1b C2/C3), and the card gives way to the
   record in the answer row; the dock goes when the agent's message lands, edited or not. Pinned in
   Task 2 by `aBridgeThatOnlyEditsItsProgressMessageShowsNoCard` and
   `aTurnTheCoreEndedByAReplyLeavesTheRecord`, and in Task 5 by
   `aProgressMessageTheAgentEditsEndsTheDock`.
2. **A room with several agents and several humans** (roadmap RF2, M4 exit). No "Sent to" without
   a single addressee; typing agents are all named the core's way; a human typing stays on the
   people's line; the header names the room and its status; the card names the writer (P2, or
   iOS's rule). Pinned in Task 1 by `anAgentTypingIsNamedByTheHeaderAndLeavesThePeoplesLine`, in
   Task 2 by `theCardNamesTheRoomsAgent`, and in Task 5 by `aRoomWithSeveralAgentsNamesThemAll`.
3. **Font scale 2.0 at 411dp** (roadmap RF3). The status pill, the card's header and step lines,
   the Agent label beside a long name, the Read button, the two-line row and the dock must not clip
   or overlap. Pinned per task (`…AtFontScaleTwo`).
4. **Sign out and back in as another account** (roadmap RF5). No turn, record, cast, typing agent
   or dock from the first account. Pinned in Task 1 by `signOutForgetsTurnAndCast` and in Task 5 by
   `signingOutLeavesNoDock`.
5. **A long room while an answer streams.** History rows do not glide or bounce as the card grows;
   only the card's own content changes. Pinned in Task 2 by
   `historyRowsDoNotAnimatePlacementWhileATurnStreams`.

---

## Task 1: Kit — the turn mirror, the cast on the typing line, sign-out — touches `RoomPane.kt`

**iOS behaviour this ports** (iOS after M1b C5 and M1a Task 13; plan text in
`2026-10-22-parity-m1b-turns-timeline.md` C5 "Interfaces"):
- `LiveStore` is a mirror: stored `answer`, `thought`, `tools: [TurnStep]`, `summary: TurnSummary?`;
  computed `finished = summary?.endedBy != nil`, `inProgress = summary?.inProgress ?? false`,
  `isLive = summary != nil || answer != nil`; `apply(update)` for the focused room only, ignoring an
  older `revision`; a new `turnId` drops the last turn's steps, reasoning and answer; a step change
  lands at the core's index (`index == count` appends); `restore(snapshot)` on focus; answer and
  thought text from the `live`/`thought` events, the answer going on `done`; `setAgentName(name, for:)`
  kept per room.
- `Session`: `.turn(update)` → `live.apply`; `.tool` is ignored (steps arrive on `.turn`); `open`
  awaits `live.focus(roomId)`, which resets and restores from `Core.turn`; sign-out clears it.
- `TypingStore` (`apple/SupermessageKit/Stores/TypingStore.swift`, M1a Task 13): typers stored as
  `TypingUserDto`; `recognise(cast, in:)`; `typingAgents` and `line` come from **one**
  `typingView(typers:agentIds:counterpart:)` call; a cast for another room is ignored.
- `TimelineView.swift:188-195` (`loadCast`): the cast loads per room, then
  `typing.recognise(cast, in: roomId)` and `live.setAgentName(cast?.counterpart?.name, for: roomId)`.

**Files:**
- Modify: `K/stores/LiveStore.kt` (rewrite as the mirror; `LiveStore.ToolCall` is deleted in favour
  of the core's `TurnStep`).
- Modify: `K/stores/TypingStore.kt` (`recognise`, `typingAgents`, `line` from one `typingView` call).
- Modify: `K/CoreClient.kt` (`turn` wrapper beside `roomCast`, `:160`).
- Modify: `K/Session.kt` — `open` (`:697-699`), `signOut` (`:765-779`), `handle`
  (`FfiEvent.Live/Thought/Tool/Turn`, `:831-846`; C4 left `is FfiEvent.Turn -> Unit`).
- Modify (compile only, no visible change in this task): `A/LiveTurn.kt`, `A/Timeline.kt`,
  `A/RoomPane.kt` (`LiveStore.ToolCall` → `TurnStep`; the RoomPane cast load — today
  `LaunchedEffect(currentRoomId) { mentionCast.load(currentRoomId, session::roomCast) }` — also
  calls `session.typing.recognise(cast, roomId)` and `session.live.setAgentName(cast.counterpart?.name, roomId)`),
  `PV/*` fixtures that build `ToolCall`.
- Modify: `KF/StubCore.kt` only if C4 did not already add `turn` (Step 0).
- Test: `KT/stores/LiveStoreTest.kt` (rewritten), `KT/stores/TypingStoreTest.kt`, `KT/SessionTest.kt`,
  `KT/CoreClientTest.kt`.
- Create: `docs/superpowers/plans/2026-10-22-parity-m4-android.md`, a copy of this plan (R10).

**Interfaces:**
- Produces (`CoreClient`): `suspend fun turn(roomId: String): TurnSnapshot? = run { it.turn(roomId) }`.
- Produces (`LiveStore`):
  ```kotlin
  class LiveStore {
      val answer: StateFlow<String?>
      val thought: StateFlow<String?>
      val steps: StateFlow<List<TurnStep>>
      val summary: StateFlow<TurnSummary?>
      val finished: StateFlow<Boolean>     // summary?.endedBy != null
      val inProgress: StateFlow<Boolean>   // summary?.inProgress == true
      val isLive: Boolean                  // summary.value != null || answer.value != null
      fun agentName(roomId: String): String?
      fun setAgentName(name: String?, roomId: String)
      fun apply(update: TurnUpdate)
      fun restore(snapshot: TurnSnapshot?)
      suspend fun focus(roomId: String?, fetch: suspend (String) -> TurnSnapshot?)
      fun handleLive(roomId: String, seq: ULong, text: String, done: Boolean)
      fun handleThought(roomId: String, seq: ULong, text: String, done: Boolean)
      fun clear()
  }
  ```
  `focus` resets first, then restores only if the focused room is still `roomId` when `fetch`
  returns. The text-seq rule (`seq < answerSeq` drops) stays for `handleLive`/`handleThought`; a
  new `turnId` resets the seqs.
- Produces (`TypingStore`): `fun recognise(cast: RoomCast, roomId: String)`,
  `val typingAgents: List<String>`, `val line: String?` — both from one
  `typingView(typers, cast.agentIds, cast.counterpart)` call per typer change (cache the
  `TypingView`, as iOS binds it once). `focus` forgets the cast.
- Produces (`Session`): `open` calls `live.focus(roomId, core::turn)` (through `CoreClient`); `handle`
  maps `FfiEvent.Turn` → `live.apply(event.update)` and `FfiEvent.Tool` → `Unit` with the comment
  "steps arrive on `FfiEvent.Turn` (M1b C3: the book emits them, and refuses stale ones)";
  `signOut` calls `live.clear()` (it already does) and `typing.focus(null)` (forgets the cast).

- [ ] **Step 0: Check the base.** ``grep -n 'class TurnSummary\|class TurnUpdate\|class TurnSnapshot\|class Turn(\|turn.*roomId.*TurnSnapshot' android/core/src/main/kotlin/uniffi/*/*.kt``
  — record the actual names. If `StubCore` lacks `turn`, add `override fun turn(roomId: String): TurnSnapshot? = throw NotImplementedError()`.
- [ ] **Step 1: Write the failing tests.**
  - `KT/stores/LiveStoreTest.kt` — fixtures folded with the **real** `foldTurn(roomId, inputs)`
    (needs the host `.so`, the `RosterArrangementTest` precedent), ported from the iOS C5 list:
    - `anUpdateForAnotherRoomIsIgnored`;
    - `anOlderRevisionDoesNotRewindTheCard` (apply revision 5, then 4: the summary stays at 5);
    - `aNewTurnDropsTheLastOnesStepsReasoningAndAnswer`;
    - `aStepChangeLandsAtTheIndexTheCoreGave` (index 0 replaces; index == size appends);
    - `openingARoomRestoresItsTurnFromTheCore` (a finished snapshot → `finished`, thought restored,
      `answer == null`);
    - `aRestoreForARoomLeftMeanwhileIsDropped` (focus `!a`, fetch suspends, focus `!b`, `!a`'s fetch
      returns → nothing of `!a` shows);
    - `theStreamedAnswerComesFromTheLiveChannelAndGoesOnDone`;
    - `theHeadersNameIsKeptPerRoom`.
  - `KT/stores/TypingStoreTest.kt` (ported from iOS "an agent typing is named by the header…",
    built with the real `roomCastFrom(…)` FFI helper or a `RoomCast` constructed directly):
    - `anAgentTypingIsNamedByTheHeaderAndLeavesThePeoplesLine` (RF2): cast agent `@atlas:x`,
      counterpart `("@atlas:x","Atlas")`; typers `atlas-bot`, `Krishna` → `typingAgents == ["Atlas"]`,
      `line == "Krishna is typing…"`;
    - `withNoCastEveryoneTypingIsOnTheLine`: → `"atlas-bot is typing…"`;
    - `aCastForAnotherRoomIsIgnored`;
    - `focusForgetsTheCast`.
  - `KT/SessionTest.kt`: `aTurnEventReachesTheLiveStore`, `aToolEventNoLongerTouchesTheLiveStore`,
    `openRestoresTheRoomsTurn` (StubCore `turn` returns a folded snapshot),
    `signOutForgetsTurnAndCast` (RF5: apply a turn, recognise a cast, `signOut()` → `summary == null`,
    `typingAgents` empty).
  - `KT/CoreClientTest.kt`: `turnReachesTheCoreWithTheRoom`.
- [ ] **Step 2: Run them and confirm they fail** (`:kit:test`).
- [ ] **Step 3: Implement** as specified. Keep `A/` changes to type adaptation and the RoomPane cast
  hand-off; the card's look changes in Task 2.
- [ ] **Step 4: Run the full verification** (one height). No preview frame may change.
  - **Falsify:** drop the revision check → `anOlderRevisionDoesNotRewindTheCard` fails; drop the
    room check in `apply` → `anUpdateForAnotherRoomIsIgnored` fails; skip the stale guard in
    `focus` → `aRestoreForARoomLeftMeanwhileIsDropped` fails (three runs); pass `emptyList()` as
    `agentIds` → `anAgentTypingIsNamedByTheHeaderAndLeavesThePeoplesLine` fails; drop
    `typing.focus(null)` from `signOut` → `signOutForgetsTurnAndCast` fails.
- [ ] **Step 5: Commit.** `kit: the turn is the core's; the typing line knows the room's cast`.

## Task 2: The activity card in the timeline and "What I did" in the answer row (A2, T6, D11) — touches `RoomPane.kt`, `Timeline.kt`, `TimelineRow.kt`

**iOS behaviour and copy:**
- **Where it goes** (`Timeline/TimelineCollectionView.swift:886-913`): a turn **in progress**
  (`isLive && !isFinished`) is one cell at the bottom of the list (index 0 of the inverted list). A
  **finished** turn has **no cell**: its record is drawn inside the answer's own row, between the
  sender's header and the text (`:612-614`, `prelude`). The answer row (`recordAnchorId`, `:812-824`)
  is the **newest non-own row whose view is a Bubble**. Android today keeps a finished turn as a
  separate cell above the newest message (`A/Timeline.kt` "liveAtOne"); that goes.
- **The card** (`Timeline/LiveTurnView.swift:57-138`): column spacing 8; padding 12; ground
  `surfaceRaised`, corner 14, a 1-wide `border` stroke; outer vertical padding 6; opacity 0.85 when
  finished (previews only, since a finished turn has no cell).
- **Header** (`:141-154`): the name (name face, one line), a spacer, then `"Done"` in `ok` when
  finished or `"Working"` in `accent`, then the elapsed time `"· " + elapsedLabel(…)` in meta,
  `contentMuted`, tabular digits. Merged for accessibility.
- **Elapsed** (`:156-174`): ticks once a second from `startedAt` while running; still (previews)
  or finished, measured to `lastActivityAt`. The text is the core's `elapsedLabel(elapsedMs)`
  ("8s", "1m 04s", "1h 02m").
- **Current step** (`:66-68`, `:177-199`): shown when not finished **and** (`summary.stepIsTool` or
  no answer text is showing yet). A small spinner in `accent` — or, under Reduce Motion or a still
  render, a static dotted ring (`circle.dotted`) — then `summary.currentStepTitle` (the core's:
  a tool's title, "Writing the answer", "Thinking", "Starting"), `content`, body-small size, at most
  2 lines. Merged.
- **Step summary** (`:70-72`, `:204-217`), when there are steps: `"1 step done"` /
  `"\(done) steps done"` (`summary.completedSteps`) with a check-circle glyph, meta, `contentMuted`;
  then, if `summary.failedStep` is set, `"\(failed) failed"` with an x-circle glyph, meta, `danger`,
  at most 2 lines. Spacing 3.
- **Reasoning** (`:74-84`): a disclosure labelled `"Reasoning"` (meta, `contentMuted`), collapsed
  by default; open, the thought in footnote size, `contentMuted`, selectable.
- **Steps** (`:86-99`): a disclosure labelled `"1 step"` or `"All \(n) steps"` (meta, `contentMuted`),
  collapsed; open, one row per step (spacing 6, top padding 4).
- **A step row** (`:229-294`): glyph by `phase` — done check-circle, failed x-circle (`danger`),
  running circular arrows (pulsing unless Reduce Motion), queued/unknown clock; the title (meta, one
  line, takes the slack); the core's `statusLabel` (meta, one line, never squeezed; `danger` when
  failed, else `contentFaint`). A step with `hasDetail` opens onto `"Touched"` (the locations joined
  by newlines), `"Input"`, `"Output"` — label meta `contentFaint`, text monospace caption
  `contentMuted`, selectable (`:302-318`).
- **The answer** (`:101-113`): the streaming markdown M2 Task 6 built (`StreamingMarkdown.split`,
  settled blocks through `richBlocksFromMarkdown`, a plain tail), paced by `StreamingText`. Keep it.
- **The name** (`:53-55`, D11): `live.agentName ?? writerName`, where `agentName` is the cast's
  counterpart name set in Task 1 and `writerName` is the newest non-own row's `senderName`, else
  `"Agent"` (`TimelineCollectionView.swift:524`, `:739`). (Task 5 extends this with P2's `agentId`
  if it merged.)
- **"What I did"** (`Timeline/WhatIDidFooter.swift:17-58`): shown when the turn has finished and
  left a record — after C5, `summary.recordLabel != null`. A disclosure whose label is the core's
  `recordLabel` (e.g. `"Thought for 12s · 2 steps"`, `"3 steps · 1m 5s"`) in the ui face,
  `contentMuted`; it opens with a 0.25 s snappy animation unless Reduce Motion
  (`:25-27`); open, the thought (footnote, `contentMuted`, selectable, bottom padding 4 when there
  are steps) then each step (`:61-93`): glyph (`contentFaint`, `danger` when failed), title
  (footnote, `contentMuted`, at most 2 lines), the core's `statusLabel` (meta, fixed size,
  `contentFaint`/`danger`). Padding 12 horizontal, 6 vertical; ground `surfaceSunken` at
  `Metrics.radiusCard`; at most 600 wide (`MessageMeasure.card`, `:96-102`).
- **Streaming must not move the history** (`TimelineCollectionView.swift:161-229`, AGENTS.md): while
  a turn streams, the list lays out without animation. iOS then glides the visible cells together
  (0.32 s); **Android does not port the glide** (Decision 2) — the requirement here is that no
  history row animates its placement while the card grows. The M2 ledger ruling for Task 15 asked
  for exactly this ("force the static item modifier for history rows while a turn is live").

**Files:**
- Modify: `A/LiveTurn.kt` (rewrite into the activity card; keep `StreamingMarkdown`/`StreamingText`
  use and M2 Task 15's `AnimatedVisibility` reasoning toggle).
- Create: `A/WhatIDid.kt`.
- Modify: `A/Timeline.kt` — parameters, placement (card only while in progress, at index 0;
  `liveAtOne` and its jump-offset branch deleted), the anchor, the placement freeze, the
  `HistoryRow` prelude hand-off.
- Modify: `A/TimelineRow.kt` — `TimelineRow`/`MessageBlock` gain `prelude: (@Composable () -> Unit)?`,
  drawn under the header and above the bubble (`TimelineRowView.swift:308-314`).
- Modify: `A/RoomPane.kt` — collect `summary`, `steps`, `thought`, `answer`, `finished` and the name.
- Modify: `PV/TimelinePreviews.kt` (or create `PV/LiveTurnPreviews.kt`).
- Test: `AT/LiveTurnTest.kt` (rewritten), `AT/TimelineTest.kt`, `AT/WhatIDidTest.kt` (new),
  `AT/MotionTest.kt` or the M2 `MotionAndHapticsTest` (the finished haptic still fires once).

**Interfaces:**
- Produces:
  - `@Composable fun LiveTurnCard(summary: TurnSummary?, steps: List<TurnStep>, thought: String?, answer: String?, name: String, still: Boolean = false, nowMs: () -> Long = System::currentTimeMillis, modifier: Modifier = Modifier)`.
    Tags: `live-turn`, `live-turn-state`, `live-elapsed`, `live-current-step`, `live-progress`
    (spinner) / `live-progress-still`, `live-step-summary`, `live-failed-step`, `thought-toggle`,
    `thought-body`, `steps-toggle`, `tool-row`, `tool-detail`, `live-answer`, `live-answer-tail`.
  - `@Composable fun WhatIDid(label: String, thought: String?, steps: List<TurnStep>, modifier: Modifier = Modifier)`;
    tags `what-i-did`, `what-i-did-toggle`, `what-i-did-body`.
  - `internal fun recordAnchorId(rows: List<TimelineRowDto>): String?` — the newest row with
    `!item.isOwn` and `view is ItemView.Bubble`.
  - `internal fun liveWriterName(agentName: String?, rows: List<TimelineRowDto>): String` —
    `agentName ?: rows.lastOrNull { !it.item.isOwn }?.senderName ?: "Agent"`.
  - `Timeline(...)`: `liveAnswer/liveThought/liveTools/liveFinished` are replaced by
    `liveSummary: TurnSummary?`, `liveSteps: List<TurnStep>`, `liveThought: String?`,
    `liveAnswer: String?`, `liveName: String`. `isLive = liveSummary != null || liveAnswer != null`;
    `finished = liveSummary?.endedBy != null`; `streaming = isLive && !finished`.
  - History rows: `itemModifier = if (animatesThisUpdate && !streaming) <M2 Task 15's animateItem(...)> else Modifier`.

- [ ] **Step 0:** confirm Task 1's names and whether M2 Task 15 already added a placement-freeze test
  (`grep -rn 'WhileATurn' AT/`). If it did, keep it and still add the one below for the new card.
- [ ] **Step 1: Write the failing tests.**
  - `AT/LiveTurnTest.kt` (each in `Box(Modifier.requiredWidth(411.dp))`, summaries folded with the
    real `foldTurn` in a host-side helper, or constructed directly):
    - `theCardSaysWhoAndHowLong`: started 64 000 ms before a fixed `nowMs`, in progress, name "Atlas"
      → "Atlas", "Working", "· 1m 04s".
    - `theElapsedTimeTicks`: `mainClock` advanced 2 000 ms → the label moves from "· 8s" to "· 10s".
    - `theCurrentStepIsTheCoresTitle`: `currentStepTitle "Run tests"`, `stepIsTool` → "Run tests"
      shown; then `stepIsTool = false` with answer text showing → `live-current-step` absent.
    - `stepsDoneAndTheFailureAreCounted`: `completedSteps 2`, `failedStep "Lint"` → "2 steps done",
      "Lint failed"; `completedSteps 1` → "1 step done".
    - `theStepsOpenOntoTheirDetail`: three steps → "All 3 steps"; click → three `tool-row`s; a step
      with `input` → click it → "Input" and its text.
    - `oneStepSaysOneStep`: → "1 step".
    - `reduceMotionStillsTheSpinner`: `LocalReduceMotion provides true` → `live-progress-still`
      exists, `live-progress` does not.
    - `theCardFitsAtFontScaleTwo` (RF3): `Density(density, fontScale = 2f)`, a 60-character name,
      failed step "Run the integration suite against staging" → the name's `lineCount == 1`,
      "Working" is displayed and its right edge ≤ the card's, the failed line `lineCount ≤ 2`.
  - `AT/WhatIDidTest.kt`: `theRecordIsTheCoresLabel` ("Thought for 12s · 2 steps"),
    `itOpensOntoTheReasoningAndSteps`, `itOpensWithoutMotionWhenReduced` (`mainClock.autoAdvance = false`;
    one frame after the click the body is at its settled height).
  - `AT/TimelineTest.kt`:
    - `aTurnInProgressIsOneCardAtTheBottom`: in-progress summary → `live-turn` is the bottom-most
      node, below the newest row.
    - `aFinishedTurnHasNoCardAndItsRecordIsInTheAnswer`: `endedBy = DONE`, `recordLabel "2 steps"`;
      rows: older peer bubble, own bubble, newest peer bubble → no `live-turn`; `what-i-did` is a
      descendant of the newest peer row only.
    - `theRecordMovesToTheNewestAnswer`: append a newer peer bubble → the record moves to it.
    - `theCardNamesTheRoomsAgent` (D11, RF2): `liveWriterName("Atlas", rows)` with the newest peer
      `senderName "atlas-bot (claude @ ci)"` → "Atlas"; with `null` → "atlas-bot (claude @ ci)";
      no rows → "Agent" (a JVM unit test in `UT/` for the pure function, plus one UI assertion).
    - `aBridgeThatOnlyEditsItsProgressMessageShowsNoCard` (RF1): no summary, no answer, rows end
      with an `edited` agent message "🔎 Searching files…" → no `live-turn`, no `what-i-did`.
    - `aTurnTheCoreEndedByAReplyLeavesTheRecord` (RF1): recompose from in-progress to
      `endedBy = REPLY`, `recordLabel "1 step"` → `live-turn` gone, `what-i-did` shows "1 step".
    - `historyRowsDoNotAnimatePlacementWhileATurnStreams` (Review Focus 5; the M2 ledger ruling): at the
      newest end, in progress, `animatesThisUpdate` true (a message just arrived);
      `mainClock.autoAdvance = false`; grow `liveAnswer` by three lines;
      `mainClock.advanceTimeByFrame()`; record the newest history row's `positionInRoot().y`; then
      `advanceTimeBy(1_000)`; the position is unchanged (it was already at its final place one frame
      after the growth).
    - `jumpingToAQuoteCountsTheCardOnlyWhileItIsThere`: the M2 Task 5 `jumpTo` index offset is `+1`
      only while streaming (the finished branch's offset is gone).
- [ ] **Step 2: Run them and confirm they fail.**
- [ ] **Step 3: Implement.**
  - `LiveTurnCard` as the iOS card above; colours from `SupermessageTheme.colors`; card shape
    `RoundedCornerShape(14.dp)` (the literal iOS uses, as Android's undecryptable row already does);
    border `1.dp` `colors.border`. The spinner is `CircularProgressIndicator` at 12dp, stroke 1.5dp;
    the still ring is a static `CircularProgressIndicator(progress = { 0.75f })` or a dotted-circle
    icon, tagged `live-progress-still`.
  - The elapsed tick: `produceState` that updates every 1 000 ms only while in progress and not
    `still`; text `"· " + elapsedLabel((nowMs() - startedAtMs).toLong())`; still or finished uses
    `lastActivityMs - startedAtMs`. Use `fontFeatureSettings = "tnum"`.
  - Disclosures: `AnimatedVisibility` with `motion(reducesMotion(), fadeIn(tween(250)))` and
    `fadeOut` — **no** `expandVertically` (a height animation inside the list).
  - `WhatIDid` as above, `widthIn(max = 600.dp)`, `Metrics.radiusCard`.
  - `Timeline`: the card item only when `streaming`; `HistoryRow(prelude = if (row.id == anchor && finished && summary.recordLabel != null) { { WhatIDid(...) } } else null)`.
    The anchor is `remember(revision) { recordAnchorId(rows) }`.
  - `RoomPane`: `name = liveWriterName(session.live.agentName(roomId), rows)`.
  - Delete the old copy `"writing…"` and `"last turn"` (no iOS copy).
  - Previews (render still, reduce motion on): `LiveTurnMidTurn`, `LiveTurnThinkingOnly`,
    `LiveTurnAnsweringOnly`, `LiveTurnActivityCard` (1m 04s, 2 done, 1 failed, 1 running — iOS
    `ComposerRevampFixtures.longTurn()`), `LiveTurnActivityCardFinished`, `WhatIDidInTheAnswer`
    (iOS preview "What I did", `WhatIDidFooter.swift:104-114`), `LiveTurnLargeText` (font scale 2.0).
- [ ] **Step 4: Run the full verification** (one height). The changed `LiveTurnMidTurn`/`LiveTurnThinking`
  frames (in `NEVER_SETTLES` today) are inspected and replaced only for the intended look.
  - **Falsify:** history rows always get `animateItem()` with a placement spring →
    `historyRowsDoNotAnimatePlacementWhileATurnStreams` fails (three runs); keep the finished
    turn's separate cell → `aFinishedTurnHasNoCardAndItsRecordIsInTheAnswer` fails; anchor on the
    newest row of any sender → the same test fails; show the current step whenever not finished →
    `theCurrentStepIsTheCoresTitle` fails; open the record with motion regardless of the setting →
    `itOpensWithoutMotionWhenReduced` fails.
  - **Device pass (read-only, phone AVD):** open an agent room; if the live account has a finished
    turn in a room with a record (a room opened after one), the record shows in the newest agent
    answer. A live streaming answer needs a send — "Live verification pending".
- [ ] **Step 5: Commit.** `android: one activity card in the timeline; the turn's record in its answer`.

## Task 3: Agent cards, the long read, and the quote under its parent (T1, D6) — touches `Timeline.kt`, `TimelineRow.kt`

**iOS behaviour and copy:**
- **Every peer message is a card** (`Timeline/TimelineRowView.swift:263-275`, `:408-465`): padding
  12 horizontal and 8 vertical; ground `surfaceRaised` for a peer, `accent` at 13 % for your own;
  corner 14; at most 600 wide for a peer and 520 for your own (`WhatIDidFooter.swift:96-102`).
  Android today boxes only your own message (radius 12, padding 10, max 320dp,
  `A/TimelineRow.kt:443-462`); a peer's text sits on the page. Decided by owner O2 ("follow iOS
  exactly"): the card is for every peer, and the **Agent** label is what marks an agent.
- **The header** (`:381-406`): the sender's face at **22** (`SenderFace(… size: 22)`; Android uses
  its 18dp default), the name (one line, peer tint), **`AgentLabel()` when the row is an agent's**
  (`row.isAgent`, C6), the time (meta, `contentFaint`), and `"edited"`. The same header, with the
  label, heads a voice note or audio row (`AudioRow`, `:794-808`; Android's `AudioRow` header is the
  name only, `A/VoiceNote.kt:102-104`) and an agent's failed turn (`TurnErrorRow`, `:213-248`,
  with reactions under the card; Android draws the card with no header,
  `A/TimelineRow.kt` `is ItemView.TurnError`).
- **`AgentLabel`** (`Timeline/TimelineChrome.swift:177-190`): `"Agent"`, label face semibold,
  `accent` on `accentSoft`, a capsule, padding 6 horizontal and 1 vertical, never compressed.
- **The long read** (`TimelineRowView.swift:423-457`), when `row.isLongRead` (C6: more than 600
  graphemes, never your own): the text lays out at full height and is **cut** at 320, with a fade
  mask from opaque at 72 % to clear at the bottom; under it a borderless `"Read"` button with a book
  glyph, ui face semibold, `accent`, accessibility hint
  `"Opens the whole message in a reading view"`. It opens the reading view
  (`TimelineChrome.swift:192-225`): titled with the attribution, the whole message through the same
  renderer in the **serif** `ThemeType.longread`, line spacing +4, at most 640 wide, padding 20
  horizontal and 16 vertical, on `surface`, with `"Done"` to close.
- **D6** (`TimelineRowView.swift:410`): the reply quote is drawn only when `!row.hidesQuote` (C9).

**Files:**
- Modify: `A/TimelineRow.kt` — `MessageBlock` chrome; a shared
  `@Composable internal fun PeerHeader(row, named, faceUri, loadFace, showsEdited: Boolean)` used by
  `MessageBlock`, `AudioRow` and the turn-error branch; `AgentLabel`; the long-read cut and button;
  `row.hidesQuote`.
- Modify: `A/VoiceNote.kt` (`AudioRow` uses `PeerHeader`).
- Create: `A/LongRead.kt` (the reading view).
- Modify: `A/Theme.kt` only if `SupermessageTheme.typography` has no accessor for
  `ThemeType.longread` (add `longread: FontFamily = ThemeType.longread` beside `body`).
- Modify: `PV/CardAndTextPreviews.kt` / `PV/TimelinePreviews.kt`.
- Test: `AT/TimelineRowTest.kt`, `AT/LongReadTest.kt` (new).

**Interfaces:**
- Produces: `@Composable internal fun AgentLabel(modifier: Modifier = Modifier)` (tag `agent-label`);
  `PeerHeader` (tag `peer-header`); the bubble tags `peer-card` / `own-bubble`; `long-read-cut`,
  `long-read-button`; `@Composable fun LongReadView(title: String, blocks: List<RichBlock>, onDone: () -> Unit)`
  (tag `long-read-view`), presented as a `ModalBottomSheet` with `skipPartiallyExpanded = true`, a
  top row with the title and "Done" (Decision 4).

- [ ] **Step 0:** `grep -n 'isAgent\|isLongRead\|hidesQuote' android/core/src/main/kotlin/uniffi/supermessage_core/*.kt`.
- [ ] **Step 1: Write the failing tests** (`requiredWidth(411.dp)`):
  - `anAgentsMessageSaysAgent`: `isAgent = true` → `agent-label` inside `peer-header` with text
    "Agent"; `isAgent = false` → none.
  - `anAgentsVoiceNoteAndFailedTurnAreHeadedToo`: an `Audio` row and a `TurnError` row with
    `isAgent` → each has `peer-header` and "Agent".
  - `aPersonsMessageIsACardToo`: a peer row has `peer-card`; an own row has `own-bubble`; the peer
    header's face is 22dp wide.
  - `aLongReadIsCutAndOffersTheReadingView`: `isLongRead`, 40 paragraphs → `long-read-cut` height
    ≤ 320dp; "Read" shown with the hint as its `onClickLabel`; click → `long-read-view` with the
    title and the **last** paragraph's text; "Done" closes it.
  - `aShortMessageHasNoReadButton`.
  - `aReplyUnderItsParentDropsTheQuote`: `hidesQuote = true` → the quote text absent;
    `false` → present.
  - `theAgentLabelFitsAtFontScaleTwo` (RF3): font scale 2, an 80-character agent name → the name's
    `lineCount == 1`; "Agent" displayed with its right edge ≤ the header's.
  - `theReadButtonIsReachableInTheMergedTree`: the row's merged semantics expose the Read action
    (the M2 Task 5 lesson: quote taps were unreachable in the merged tree).
  - `AT/LongReadTest.kt`: `theReadingViewIsSerif` (the text node's `fontFamily` is the longread
    family) and `theReadingViewFitsAtTheShortHeight` (run this class at 1080x1280 as well).
- [ ] **Step 2: Run them and confirm they fail.**
- [ ] **Step 3: Implement.** The cut: `Modifier.heightIn(max = 320.dp).clipToBounds()` over a child
  measured with `wrapContentHeight(unbounded = true)`, plus a `drawWithContent` gradient mask
  (`BlendMode.DstIn`, stops 0.72 → transparent) — no animation. The own bubble moves to radius 14,
  padding 12/8 and max 520dp; peer card max 600dp (visible change, see "Android behaviour changes").
  Previews: `AgentCard`, `PersonCard`, `AgentCardLongRead`, `LongReadView`, `QuoteUnderItsParent`,
  `AgentCardLargeText` (font scale 2.0), and the voice-note and failed-turn headers.
- [ ] **Step 4: Run the full verification** (one height, plus `LongReadTest` at 1080x1280). Every
  timeline preview frame changes (the peer card); inspect each and record only these. List them.
  - **Falsify:** always show the label → `anAgentsMessageSaysAgent` fails; ignore `hidesQuote` →
    `aReplyUnderItsParentDropsTheQuote` fails; cut at 3 200dp → `aLongReadIsCutAndOffersTheReadingView`
    fails; drop `maxLines = 1` on the name → `theAgentLabelFitsAtFontScaleTwo` fails.
  - **Device pass (read-only):** an agent room with a long report shows the cut, "Read" opens the
    serif view; a reply directly under its parent has no quote.
- [ ] **Step 5: Commit.** `android: every peer message is a card; agents say so; long reads open to read`.

## Task 4: The header status pill and the roster's agent tiles (A1, R1) — touches `RoomPane.kt`, `MainActivity.kt`

**iOS behaviour and copy:**
- **Status** (`Navigation/RoomScreen.swift:74-84`): `roomStatus(state: rosterRow.state, describesAgent:
  rosterRow.describesAgent, turnInProgress: live.inProgress)` (the core's precedence: not an agent's
  room → none; Needs you; a live turn → Working; else Active/Idle/Quiet). The header's clock moves
  while the reader stays (`:40-48`, every 60 s; Android's roster clock already ticks every 30 s,
  `A/MainActivity.kt:200-206` — reuse it).
- **Header** (`:194-220`): two lines, spacing 1, at most 260 wide: the name (headline, one line);
  then the **status pill** if there is a status, otherwise `"1 member"` / `"\(n) members"` (M2
  Task 1). The header stays one button labelled `"\(name), about this room"` (`:106-113`).
- **Status pill, compact** (`:222-266`): a dot (6) then the word `roomStatusWord(status)` ("Working",
  "Needs you", "Active", "Idle", "Quiet"), meta face, one line; spacing 4; padding 7 horizontal, 1
  vertical; a capsule on `signalSoft` for Needs you, else `surfaceRaised`. The word is `signal` for
  Needs you, else `contentMuted`. The dot: Working and Active `ok`, Needs you `signal`, Idle
  `contentFaint`, Quiet `border`. **Working pulses** unless Reduce Motion. The pill's accessibility
  label is the word only.
- **The roster's face** (`Rooms/RoomAvatar.swift:12-118`), 40 by default:
  - an **agent's room** (`describesAgent`) is a rounded square, corner `size × 0.28`, filled with
    the core's tile (`RosterRow.avatar`: `ground` and `ink` roles, M1.5), a 1-wide `border` stroke
    when the ground is `surfaceRaised`, the initial at `size × 0.44` semibold in the ink;
  - a **person's or people's room** is a circle on `surfaceRaised` with a 1-wide `border`, the
    initial at `size × 0.42` medium in `contentMuted`;
  - a picture, when the room has one, fills the shape;
  - the **agent badge** at the top-trailing corner, offset (3, −3): a circle of `size × 0.36` in
    `accent`, a sparkle glyph at 0.6 of that in `accentContent`, ringed 1.5 in `surface`;
  - the **state dot** at the bottom-trailing corner, offset (2, 2): `size × 0.3`, ringed 2 in
    `surface`; Needs you `signal`, Active `ok`, Idle `contentFaint`, **Quiet draws nothing**;
  - accessibility: `"Agent"` and the state word, joined by `", "`.
- **The row** (`Rooms/RoomRowView.swift:27-136`): **two lines and only two** — the runtime lives in
  room info, the state is the dot. Spacing 12, vertical padding 4, the lines 2 apart:
  - line 1: the name (name face, one line); `"Invitation"` (meta, `accent`, outlined capsule) for an
    invitation; a spacer; the time (meta; `accent` when unread, else `contentFaint`);
  - line 2: the preview (subheadline size, one line; `signal` when pending, else `contentMuted`), or
    the room's role when it has said nothing, or a blank line; a spacer; the unread badge
    (`"99+"` over 99, `accent` capsule at least 19 square, label `"\(count) unread"`).
  - The dot is drawn when the reader wants it and it means something:
    `showsState && (describesAgent || state == .needsYou)` (`:99-101`).
  - The avatar is the button into room info, labelled `"About \(name)"` (`:31-36`).
  Android today has three lines with the state word, harness and host in a meta line
  (`A/RoomRow.kt` `metaLine`) and the dot beside it; that line and `hidesHost` go.

**Files:**
- Create: `A/StatusPill.kt`, `A/RoomAvatar.kt`.
- Modify: `A/RoomHeader.kt` (`status: RoomStatus?` parameter, pill or count), `A/RoomPane.kt` (the
  status input), `A/RoomRow.kt` (two lines; `metaLine`, `StateDot` and `hidesHost` deleted; the
  avatar is `RoomAvatar`), `A/Roster.kt` (pass `RosterRow.avatar`; drop `hidesHost`),
  `A/MainActivity.kt` (drop `hidesHost = rosterView == RosterChoice.MACHINE`).
- Modify: `PV/RoomHeaderPreviews.kt`, `PV/RosterPreviews.kt`.
- Test: `AT/RoomHeaderTest.kt`, `AT/RoomRowTest.kt`, `AT/RosterReachabilityTest.kt`.

**Interfaces:**
- Produces: `@Composable fun StatusPill(status: RoomStatus, compact: Boolean = true, modifier: Modifier = Modifier)`
  (tags `status-pill`, `status-dot`; a custom `SemanticsPropertyKey<Boolean>("Pulsing")` on the dot
  so a test can read whether it animates); `RoomHeader(row, status: RoomStatus?, memberCount, onOpenInfo, …)`;
  `@Composable fun RoomAvatar(roomId: String, initial: String, avatarUri: String?, describesAgent: Boolean, style: AgentAvatarStyle?, state: AgentState?, size: Dp = 40.dp, onOpenInfo: (() -> Unit)? = null, infoLabel: String? = null)`
  (tags `avatar`, `agent-badge`, `state-dot`); `internal fun avatarColour(role: AvatarRole): Color`.
  M5's Agents view reuses `RoomAvatar`.

- [ ] **Step 1: Write the failing tests.**
  - `AT/RoomHeaderTest.kt`: `anAgentsRoomSaysWhatItIsDoing` (`WORKING` → "Working", no "members"
    text); `aRoomOfPeopleCountsInstead` (no status, 4 → "4 members"); `everyStatusSaysItsWord` (the
    five words); `workingPulsesUnlessReduced` (Pulsing true; with `LocalReduceMotion provides true`
    false; `ACTIVE` false); `theHeaderFitsAtFontScaleTwo` (RF3: a 60-character name and "Needs you"
    → name `lineCount == 1`, the pill inside the header's bounds); `theHeaderIsStillOneButton`
    (merged `contentDescription` "Atlas, about this room").
  - `AT/RosterReachabilityTest.kt` (AppRoot + FakeCore): `theStatusFollowsTheTurn` — an agent's
    room; `FfiEvent.Turn` with `inProgress` → header "Working"; then `endedBy = DONE` → "Active".
  - `AT/RoomRowTest.kt`: `anAgentsRowHasItsTileBadgeAndDot` (`agent-badge` and `state-dot` under
    `avatar`, contentDescription "Agent, active"); `aPersonsRowHasNoBadge`; `theRowIsTwoLines` (no
    "claude-code", no "foundry", no state word text; preview `maxLines == 1`); `quietDrawsNoDot`;
    `aPeopleRoomShowsOnlyANeedsYouDot`; `theStateDotCanBeTurnedOff` (`showsState = false` → no dot,
    except Needs you); `theAvatarOpensInfo` ("About Atlas"); `noPreviewShowsTheRole`;
    `twoLinesFitAtFontScaleTwo` (RF3).
- [ ] **Step 2: Run them and confirm they fail.**
- [ ] **Step 3: Implement.** The pulse is an `infiniteTransition` on the dot's **alpha** (1 → 0.35,
  1 s, reverse) — opacity only — and only when `status == WORKING && !reducesMotion()`. The badge
  glyph is `Icons.Filled.AutoAwesome` (sparkle). `avatarColour` maps the seven `AvatarRole`s to
  `SupermessageTheme.colors` (`accent`, `accentSoft`, `accentContent`, `ok`, `contentMuted`,
  `surface`, `surfaceRaised`). Previews: `HeaderStatuses` and `HeaderStatusesDark` (iOS
  `RoomScreen.swift:268-290`, rendered with reduce motion), `HeaderLargeText`, `Avatars` and
  `AvatarsDark` (iOS `RoomAvatar.swift:120-154`, over `agentAvatarVariants()`), `EveryState` and
  `EveryStateDark` (iOS `RoomRowView.swift` previews), `StateWordHidden`, `RowLargeText`.
- [ ] **Step 4: Run the full verification** (one height). Roster and header frames change;
  inspect and record each.
  - **Falsify:** pass `turnInProgress = false` → `theStatusFollowsTheTurn` fails; pulse whatever
    the motion setting → `workingPulsesUnlessReduced` fails; keep `metaLine` → `theRowIsTwoLines`
    fails; draw a dot for Quiet → `quietDrawsNoDot` fails.
  - **Device pass (read-only):** the roster on the phone AVD and the tablet AVD: agent tiles,
    badges, dots, two lines; open an agent room: the pill; a people room: the count. Font scale 2.0.
- [ ] **Step 5: Commit.** `android: the room says what its agent is doing; agents have their own faces`.

## Task 5: "Sent to" → "is on it", the room with many agents, and the branch-end pass (A3, RF1, RF2) — touches `RoomPane.kt`, `Timeline.kt`

**iOS behaviour and copy:**
- **Placement** (`Timeline/TimelineView.swift:123-146`): above the composer, leading-aligned, a
  column with spacing 4 and horizontal padding 12 (bottom padding 4 only when something shows): the
  **dock**, then the **typing pill**. The dock enters by moving up from the bottom with a fade, the
  pill by a fade; both with a 0.22 s snappy animation, none under Reduce Motion.
- **The facts** (`:177-195`): the focused room; the cast loaded for it (Task 1) — `addressee` and
  `agentIds` only when `cast.roomId == roomId`; `typingAgents` from `TypingStore` (Task 1);
  `turnInProgress = live.inProgress`. The core decides the rest (`acknowledgement`, M1.2), including
  "sent this session".
- **The dock** (`:198-236`): spacing 7; for `Sent`, a check glyph in `ok`; for `OnIt`, an ellipsis
  glyph in `accent` that animates (variable colour) unless Reduce Motion or a still render; the
  core's `text` — `"Sent to \(name)"`, `"\(name) is on it…"`, `"\(names) are on it…"`, `"On it…"` —
  meta face, `content`, one line; padding 12 horizontal, 7 vertical; a floating capsule
  (`surfaceRaised`, 1-wide `border`, a soft `scrim` shadow, `Composer/FloatingSurface.swift:30-46`;
  Android's capsule composer already draws this, `A/Composer.kt:215-226`). Merged, and announced
  as it changes.
- **The typing pill** (`:238-257`): a chat-bubble-with-dots glyph in `contentFaint`, the core's line
  (meta, `contentMuted`, one line), spacing 6, padding 10/5, `surfaceSunken` capsule. It replaces
  Android's plain typing `Text` at the bottom of `Timeline`.
- **RF2** (`acknowledgement` tests in `C/acknowledgement.rs`, M1a Task 9): with several agents and
  no single addressee there is no "Sent to"; working agents are named the core's way
  (`"Atlas, Quill and 1 other are on it…"`); with no known agents, anyone else's reply ends it.

**Files:**
- Modify: `K/CoreClient.kt` (`acknowledgement` wrapper), `K/Session.kt`
  (`suspend fun acknowledgement(roomId: String, addressee: String?, agentIds: List<String>, typingAgents: List<String>, turnInProgress: Boolean): Acknowledgement?`
  — any failure, including `RoomChanged`, is `null`; `CancellationException` rethrown).
- Create: `A/AcknowledgementDock.kt` (the dock and the typing pill).
- Modify: `A/RoomPane.kt` (the facts, the ask, placement above the composer), `A/Timeline.kt`
  (remove the typing `Text`).
- Modify: `A/LiveTurn.kt`/`A/RoomPane.kt` — only if P2 is in the bindings (Step 0): the writer name.
- Modify: `PV/ComposerCapsulePreviews.kt` (or a new `PV/DockPreviews.kt`).
- Modify: `README.md` (the Android cells of the rows this plan turns ✅).
- Create: `.superpowers/sdd/m4-android/m4-android-verification.md` (the run's evidence record).
- Test: `KT/SessionTest.kt`, `KT/CoreClientTest.kt`, `AT/AcknowledgementDockTest.kt` (new),
  `AT/AgentExperienceTest.kt` (new; AppRoot + FakeCore).

**Interfaces:**
- Produces: `@Composable fun AcknowledgementDock(acknowledgement: Acknowledgement, modifier: Modifier = Modifier)`
  (tags `dock`, `dock-sent` / `dock-working`; the working glyph carries the `Pulsing` semantics
  property from Task 4); `@Composable fun TypingPill(line: String, modifier: Modifier = Modifier)`
  (tag `typing-pill`); `@Composable internal fun ComposerAccessories(ack: Acknowledgement?, typingLine: String?)`.
- If P2 merged: `internal fun liveWriterName(agentId: String?, cast: RoomCast?, agentName: String?, rows)`:
  the cast counterpart's name if its `userId == agentId`, else the cast person's name for `agentId`,
  else Task 2's rule — the rule written in `core-prereqs.md` P2, identically.

- [ ] **Step 0: Check the base.** ``grep -n 'acknowledgement.*roomId' android/core/src/main/kotlin/uniffi/supermessage_ffi/supermessage_ffi.kt``.
  - **P1 present** (a `CoreInterface` method with `roomId`): `CoreClient.acknowledgement` calls it.
  - **P1 absent:** `CoreClient.acknowledgement` calls the free
    `acknowledgement(rows = timeline.items.value, addressee, agentIds, sentThisSession = core.hasSent(roomId), typingAgents, turnInProgress)`
    on the core dispatcher, binding the rows once per ask (the M1a PR4 fix), and the report says so;
    the switch to P1 is a one-line follow-up. Both are existing core APIs; nothing is stubbed.
  - `grep -n 'agentId' …/supermessage_core.kt` for P2.
- [ ] **Step 1: Write the failing tests.**
  - `KT/SessionTest.kt`: `acknowledgementAsksTheCoreWithTheRoomsFacts` (StubCore records the five
    arguments); `aRefusedAskIsNoDock` (StubCore throws `FfiException.RoomChanged` → `null`).
  - `AT/AcknowledgementDockTest.kt` (`requiredWidth(411.dp)`): `sentSaysSentToTheAgent`
    ("Sent to Atlas", `dock-sent`); `onItSaysWhoIsOnIt` ("Atlas is on it…", `dock-working`,
    Pulsing true); `onItIsStillUnderReduceMotion`; `theTypingPillSaysWhoIsTyping`;
    `theDockFitsAtFontScaleTwo` (RF3: "Atlas, Quill and 2 others are on it…" → one line, inside the
    screen, above the composer).
  - `AT/AgentExperienceTest.kt` — AppRoot with a FakeCore that serves rows, a cast, typing events,
    `FfiEvent.Turn` updates and an `acknowledgement` answer derived from what it was asked
    (it returns `Sent` while the newest row is own, `null` once an agent row follows — the core's
    rule, pinned in Rust by M1a Task 9; this test pins the host's re-asking):
    - `aProgressMessageTheAgentEditsEndsTheDock` (RF1): open the room → "Sent to Atlas"; deliver
      the agent's "🔎 Searching files…" then a `Set` of it with `edited = true` → the dock is gone;
      no `live-turn` at any point (no deltas).
    - `aTurnThatStartedEndsWhenTheAgentAnswers` (RF1): `FfiEvent.Turn` in progress → the card and
      the header's "Working"; then an update with `endedBy = REPLY` → no card, the header says
      "Active", the record shows in the agent's answer.
    - `aRoomWithSeveralAgentsNamesThemAll` (RF2): cast with `@atlas:x`, `@quill:x` (agents) and
      `@k:x` (person), no counterpart, no addressee; typing `atlas-bot`, `Quill`, `Krishna` → the
      FakeCore was asked with `addressee == null` and `typingAgents == ["atlas-bot", "Quill"]`
      (the names `typingView` gave), the dock says what it returned ("atlas-bot and Quill are on
      it…"), the typing pill says "Krishna is typing…", the header says the room's name and status.
      With P2: the card names the agent whose `agentId` the turn carries.
    - `switchingRoomsDropsTheDock`: an ask for `!a` that resolves after the room changed to `!b`
      shows nothing in `!b`.
    - `signingOutLeavesNoDock` (RF5).
- [ ] **Step 2: Run them and confirm they fail.**
- [ ] **Step 3: Implement.** RoomPane asks in a `LaunchedEffect(roomId, revision, typingAgents, inProgress, cast?.roomId)`
  and assigns only if the room is unchanged. The dock and pill sit in the column above the capsule
  composer; `AnimatedVisibility(enter = motion(reduce, slideInVertically { it } + fadeIn(tween(220))), exit = motion(reduce, fadeOut(tween(220))))`
  — translation and opacity only. The working ellipsis is three dots whose alpha steps in turn
  (`infiniteTransition`), static under Reduce Motion. Previews: `Acknowledgement` (iOS
  `TimelineView.swift:279-291`: the two dock sentences and "Krishna is typing…"), `DockLargeText`.
- [ ] **Step 4: Run the full verification, then the branch-end pass.**
  - Full instrumented suite at **both heights**: 1080x2400, then `adb shell wm size 1080x1280`
    and the same run, then `adb shell wm size 1080x2400`.
  - **Falsify:** drop `revision` from the effect's keys → `aProgressMessageTheAgentEditsEndsTheDock`
    fails; pass `cast.addressee` without the room check → `switchingRoomsDropsTheDock` fails (three
    runs); drop the dock reset in sign-out → `signingOutLeavesNoDock` fails.
  - README: correct the Android cells for A1, T1, A2, A3, T6, D5, D6, D11, R1 against the merged
    code.
  - Fill `m4-android-verification.md` with the exit table below: one line per row, ✅ only with a
    test name or a screenshot, "live pending (owner)" for anything that needs a send.
  - **Device pass** (phone AVD, then once on the tablet AVD; read-only; report each as seen or not
    seen): the roster's tiles, dots and two lines; an agent room's pill; a people room's count; an
    agent's card with "Agent"; a long read and its reading view; "What I did" on an answer (if the
    account has one); font scale 2.0 for each.
  - **Owner live pass (Q2)**, written into the verification file as a checklist for the owner:
    send one message to a bridge agent that edits its progress message (RF1: "Sent to …" → gone, no
    hanging "Working", the record after); send one in a room with two agents and a person (RF2:
    names). The implementer does not send.
- [ ] **Step 5: Commit.** `android: "Sent to" then "is on it"; README after parity M4`.

---

## Excluded, and why

| Item | Why it is not in this plan | Where |
|---|---|---|
| D5 — membership churn across verbs, expandable | Done by M1b C11 (`displayRows` over the core's `membership`, the tappable stretch, `"Shows each change"`). This plan only checks it in the exit table. | M1b PR3 |
| `showsRuntime` attribution on Android | M1b C11 switched `Timeline.kt` to `row.showsRuntime`. | M1b PR3 |
| Markdown while streaming | M2 Task 6 (`StreamingMarkdown`); kept inside the new card. | M2 |
| The `@` picker with agents first, "{harness} on {host}", "Agent" | M2 Task 10, over the core's `roomCast` since the M2 ledger's Task 10 merge. | M2 |
| "Read by {names}" on reader faces | Android already labels `ReaderStack` "Read by {peopleLabel}" (`A/TimelineRow.kt`, `ReaderStack`). Desktop only (M3 Q10). | — |
| The turn-finished success haptic; the reasoning toggle's motion | M2 Task 15. Task 2 keeps both; `aFinishedTurnIsASuccessHapticOnce` must stay green. | M2 |
| The DispatchCard decision hierarchy (G1), receipts | M2 Task 7. | M2 |
| iOS's 0.32 s glide of the history as the answer grows | Not ported (Decision 2). The rule ported is "no animated resize, no animated placement". | Owner Q5 |
| `StreamingMarkdown.split` into the core | Core backlog (drift risk, not a gap). | Core backlog |
| A4 Stop | Blocked until AgentPod defines a cancel event (roadmap). | — |
| N1–N3, R3, O1 (Needs you, Agents view, chips, first run) | M5. The Agents view will reuse Task 4's `RoomAvatar`. | M5 |
| Turn ending for rooms not on screen beyond the 30-minute limit | R7: deferred in the core. | Core |

## Exit: rows this plan turns ✅

| Matrix row | Task | Verified by |
|---|---|---|
| A1 header status pill | 4 | `RoomHeaderTest`, `theStatusFollowsTheTurn`; device pass |
| T1 agent card, long read | 3 | `TimelineRowTest`, `LongReadTest`; device pass |
| A2 one live card in the timeline | 1, 2 | `LiveStoreTest`, `LiveTurnTest`, `TimelineTest`; live pending (owner) |
| A3 "Sent to" → "is on it" | 5 | `AcknowledgementDockTest`, `AgentExperienceTest`; live pending (owner) |
| T6 "What I did", "Thought for 12s · 2 steps" | 1, 2 | `WhatIDidTest`, `TimelineTest` |
| D5 churn across verbs | (M1b C11) | `TimelineDisplayTest`, `aCollapsedStretchOpensOnTap` |
| D6 quote hidden under its parent | 3 | `aReplyUnderItsParentDropsTheQuote` |
| D11 one name per person | 1, 2 | `TypingStoreTest`, `theCardNamesTheRoomsAgent` |
| R1 agent tiles, dot on avatar, two lines, runtime out of the row | 4 | `RoomRowTest`; device pass |
| Review Focus 1 (bridge edits its progress message) | 2, 5 | `aBridgeThatOnlyEditsItsProgressMessageShowsNoCard`, `aTurnTheCoreEndedByAReplyLeavesTheRecord`, `aProgressMessageTheAgentEditsEndsTheDock`; owner live pass |
| Review Focus 2 (several agents, several humans) | 1, 2, 5 | `anAgentTypingIsNamedByTheHeaderAndLeavesThePeoplesLine`, `theCardNamesTheRoomsAgent`, `aRoomWithSeveralAgentsNamesThemAll`; owner live pass |

## Android behaviour changes (visible)

1. A finished turn no longer leaves a card above the newest message; its record is a collapsed
   "What I did" line inside the agent's answer, above the text.
2. The card's words change from "writing…"/"last turn" (no iOS copy) to "Working"/"Done", with the
   elapsed time, the current step, the step counts and the failure.
3. Every peer message is drawn on a card; your own bubble moves from radius 12, padding 10 and
   320dp to radius 14, padding 12/8 and 520dp; a peer's card is up to 600dp.
4. Peer headers show a 22dp face (was 18dp); voice notes and failed turns get the full header.
5. Roster rows are two lines; the state word, harness and host leave the row; the avatar grows from
   34dp to 40dp and an agent's room gets a coloured tile and a badge. The "Machine" arrangement's
   host-hiding is moot and removed.
6. The typing line becomes a pill above the composer.

## Decisions taken in this plan (technical; the controller may overrule)

1. `LiveStore` mirrors only the focused room, as iOS does; other rooms' turns live in the core
   (`Core.turn` on focus).
2. No glide of the history while the answer grows (iOS's 0.32 s transform glide,
   `TimelineCollectionView.swift:211-229`): the reverse-layout list keeps the newest end pinned and
   the history steps up as the card grows, unanimated — the AGENTS.md rule iOS learned. Owner Q5
   can ask for the glide later; it would be a `graphicsLayer` translation on visible rows only.
3. Card and bubble corners use iOS's literal 14dp, as Android's undecryptable row already does;
   "What I did" uses `Metrics.radiusCard`. No new radius token.
4. The long read is a fully expanded `ModalBottomSheet` with a title row and "Done", matching the
   M2 message menu and M3 report sheet (M3 Q13).
5. Task 5 uses the free `acknowledgement(rows, …)` if P1 has not merged, rather than waiting.

Open questions for the owner are in `.superpowers/sdd/planning-m4/open-questions.md`.
