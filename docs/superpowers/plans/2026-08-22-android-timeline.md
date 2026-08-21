# Android Timeline (A2) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Open a room and read it — history, arrivals, an agent's live turn, and read receipts.

**Architecture:** `LazyColumn(reverseLayout = true)` fed newest-first, mirroring iOS's inverted `UICollectionView` for the reasons its source records. Rows branch on the core's `ItemView`; nothing is classified in Compose. The animation rule moves to `:kit` as a pure function.

**Tech Stack:** Kotlin, Compose, `androidx.compose.foundation.lazy`, the ported `:kit` stores.

**Spec:** `docs/superpowers/specs/2026-08-22-android-timeline-design.md`

## Global Constraints

- **The app parses nothing and decides nothing.** `ItemView`, `RichBlock`, attribution, muting and ordering are all the core's. A `when` that *renders* a variant is right; a `when` that *decides which variant something is* is the defect this architecture exists to prevent.
- **Row identity is `row.item.id`.** Getting this wrong reproduces iOS's local-echo→confirmed flicker.
- **Consume `TimelineStore.revision`**, never diff rows in the view (rule 3).
- **Assert geometry, not existence.** `ListDetailPaneScaffold` composes hidden panes at degenerate zero-size bounds, and this project once asserted a panel existed while it sat off the side of an iPad.
- **`RootScaffoldTest`'s five tests must pass unmodified.** They call bare `RootScaffold()` and so exercise the *default* placeholder panes — keep every default's `testTag` (`pane-roster`, `pane-timeline`, `pane-info`) exactly as it is.
- **A test that has never failed is not yet a regression test.** Mutate every test until it fails before keeping it.
- **`--tests` is a config-time error** on this AGP. Use `-Pandroid.testInstrumentationRunnerArguments.class=<FQCN>`.
- **Emulators launch only via `scripts/android-emulator.sh`** (headless; `-no-window` is a compositor-leak fix, not a preference).
- Instrumented runs no longer uninstall the app, and the secret store's tests use their own Keystore alias — a signed-in session survives the suite. Do not undo either.

---

### Task 1: `animates`, in `:kit`

**Files:**
- Create: `android/kit/src/main/kotlin/dev/supermessage/kit/TimelineAnimation.kt`
- Test: `android/kit/src/test/kotlin/dev/supermessage/kit/TimelineAnimationTest.kt`

**Interfaces:**
- Produces: `object TimelineAnimation { fun animates(arrived: Int, had: Int, hasApplied: Boolean, wasAway: Boolean): Boolean }`

- [ ] **Step 1: Write the failing tests**

Five cases, one per condition plus the happy path. Each name states the rule it pins.

```kotlin
class TimelineAnimationTest {
    private fun animates(arrived: Int, had: Int = 5, hasApplied: Boolean = true, wasAway: Boolean = false) =
        TimelineAnimation.animates(arrived, had, hasApplied, wasAway)

    /** One to three arrivals into a room already on screen: the case that animates. */
    @Test fun aHandfulArrivingIsAnArrival() {
        assertTrue(animates(arrived = 1))
        assertTrue(animates(arrived = 3))
    }

    /** A room's first fill is the room appearing, not messages arriving. */
    @Test fun theFirstFillIsNotAnArrival() =
        assertFalse(animates(arrived = 3, hasApplied = false))

    /** A reader scrolled away did not watch it happen. */
    @Test fun nothingAnimatesWhileTheReaderIsAway() =
        assertFalse(animates(arrived = 3, wasAway = true))

    /** An empty room gaining rows is a fill. */
    @Test fun anEmptyRoomGainingRowsIsAFill() =
        assertFalse(animates(arrived = 3, had = 0))

    /**
     * More than a handful is a page of history or a resync — "a conversation
     * does not gain eight messages in one moment, so if it looks like it did,
     * this is not an arrival."
     */
    @Test fun aPageOfHistoryIsNotAnArrival() {
        assertFalse(animates(arrived = 4))
        assertFalse(animates(arrived = 20))
    }

    /** Nothing arrived. */
    @Test fun nothingArrivingDoesNotAnimate() = assertFalse(animates(arrived = 0))
}
```

- [ ] **Step 2: Run, confirm failure**

Run: `cd android && ./gradlew :kit:testDebugUnitTest --tests '*TimelineAnimationTest*'`
(`--tests` **is** valid for JVM unit tests; only `connectedDebugAndroidTest` rejects it.)
Expected: FAIL — `TimelineAnimation` does not exist.

- [ ] **Step 3: Write it**

```kotlin
package dev.supermessage.kit

/**
 * Whether a timeline change should animate.
 *
 * A port of `TimelineCollectionView.swift:426`, which is a *decision* and so
 * does not belong in a composable — this app's central rule is that the view
 * decides nothing. It lives here rather than in the core only because that
 * would cost a Rust change and a binding rebuild for a rule no other platform
 * is asking to share yet; moving it later is a rename.
 */
object TimelineAnimation {
    /**
     * @param arrived how many rows appeared at the newest end
     * @param had how many rows were there before
     * @param hasApplied whether any snapshot has been applied to this room yet
     * @param wasAway whether the reader was scrolled away from the newest end
     */
    fun animates(arrived: Int, had: Int, hasApplied: Boolean, wasAway: Boolean): Boolean {
        // A room's first fill is the room appearing, not an arrival; a reader
        // who was away did not watch it happen; and an empty room gaining rows
        // is a fill. Any of the three and nothing animates.
        if (!hasApplied || wasAway || had <= 0) return false
        // More than a handful at once is a page of history or a resync.
        return arrived in 1..3
    }
}
```

- [ ] **Step 4: Run, confirm pass**

- [ ] **Step 5: Mutate**

Three, each run for real and reverted:
1. Drop the `!hasApplied` guard → `theFirstFillIsNotAnArrival` fails.
2. Change `arrived in 1..3` to `arrived >= 1` → `aPageOfHistoryIsNotAnArrival` fails.
3. Change `had <= 0` to `had < 0` → `anEmptyRoomGainingRowsIsAFill` fails.

- [ ] **Step 6: Commit**

```bash
git add android/kit/ && git commit -m "kit: whether a timeline change should animate"
```

---

### Task 2: `RichText.kt`

**Files:**
- Create: `android/app/src/main/kotlin/dev/supermessage/RichText.kt`
- Test: `android/app/src/androidTest/kotlin/dev/supermessage/RichTextTest.kt`
- Read first: `apple/Supermessage/Timeline/RichTextView.swift` (113 lines)

**Interfaces:**
- Consumes: `uniffi.supermessage_core.{RichBlock, RichInline, RichListItem, RichTableCell, RichTableRow}`
- Produces: `@Composable fun RichText(blocks: List<RichBlock>, modifier: Modifier = Modifier)`

The core's shapes, verified against the generated bindings:

```
RichBlock  = Paragraph(inlines) | Heading(level: UByte, inlines) | CodeBlock(language: String?, text)
           | BlockQuote(blocks) | ListBlock(ordered: Boolean, start: UInt, items: List<RichListItem>)
           | Table(header: List<RichTableCell>, rows: List<RichTableRow>)
RichInline = Text(text) | Emphasis(inlines) | Strong(inlines) | Code(text) | Link(href, inlines)
```

- [ ] **Step 1: Write the failing tests**

Four, chosen because each is a shape that can silently render as nothing:

```kotlin
/** Nested emphasis renders its innermost text — the case iOS got wrong. */
@Test fun nestedEmphasisKeepsItsText()
/** An ordered list starting at something other than 1 respects `start`. */
@Test fun anOrderedListHonoursItsStart()
/** A code block renders its text verbatim, including leading whitespace. */
@Test fun aCodeBlockKeepsItsWhitespace()
/** A link renders its label, not its href. */
@Test fun aLinkShowsItsLabelRatherThanItsHref()
```

`nestedEmphasisKeepsItsText` is not arbitrary: `RichTextFolding` on iOS was found by this project's own mutation testing to be untested *and wrong* for nested emphasis. Do not let Android repeat it.

- [ ] **Step 2: Run, confirm failure**

- [ ] **Step 3: Write it**

Build an `AnnotatedString` per block. Inline rendering recurses over `RichInline`, applying `SpanStyle`s (italic, bold, monospace) and, for `Link`, a `LinkAnnotation.Url`. Block rendering emits a `Text` per paragraph/heading, a surface-tinted `Text` for code, an indented `Column` with a leading rule for quotes, a numbered/bulleted `Column` for lists, and a simple `Column`/`Row` grid for tables inside a horizontally scrollable container.

**Wide content scrolls inside itself.** A table or a long code line must not make the whole timeline scroll sideways — wrap each in `Modifier.horizontalScroll(rememberScrollState())`.

- [ ] **Step 4: Run, confirm pass**

- [ ] **Step 5: Mutate**

Render only the first inline of an `Emphasis` → `nestedEmphasisKeepsItsText` fails. Ignore `start` and always number from 1 → `anOrderedListHonoursItsStart` fails. Render `href` instead of the label → `aLinkShowsItsLabelRatherThanItsHref` fails. Each run for real, then reverted.

- [ ] **Step 6: Commit**

---

### Task 3: `TimelineRow.kt`

**Files:**
- Create: `android/app/src/main/kotlin/dev/supermessage/TimelineRow.kt`
- Test: `android/app/src/androidTest/kotlin/dev/supermessage/TimelineRowTest.kt`
- Read first: `apple/Supermessage/Timeline/TimelineRowView.swift` (475 lines)

**Interfaces:**
- Consumes: `RichText` (Task 2), `uniffi.supermessage_core.{TimelineRow, ItemView, TimelineItemDto}`
- Produces:
```kotlin
@Composable fun TimelineRow(
    row: TimelineRowDto,
    now: Instant,                       // injected clock; DateDivider formats from it
    continuesRun: Boolean = false,      // does the row above already carry this sender's header?
    attribution: String = "",           // chosen by the LIST, which can see every row
    avatarUri: (userId: String) -> String? = { null },
    modifier: Modifier = Modifier,
)
```

`attribution` and `continuesRun` are the list's to decide — from `TimelineRowView.swift:28`:
*"Who to name, already chosen: the full attribution in a room where several agents speak,
the bare name where one does. Chosen by the list, which can see every row; a single row
cannot."* Fall back to `row.senderName` when `attribution` is empty, exactly as iOS does.

**No `onReply` or `onReact` in A2.** iOS's row takes both, and its own doc says `onReply` is
*"nil in contexts with no composer"* — which is precisely this phase. Reactions and replies
arrive with Phase B. Displaying existing reactions and read receipts is in scope; *changing*
them is not.

**Name collision:** the composable and the core's DTO are both `TimelineRow`. Import the DTO and name the composable `TimelineRow` anyway — Kotlin resolves the call by position — but if that proves ambiguous at any call site, alias the import (`import uniffi.supermessage_core.TimelineRow as TimelineRowDto`) rather than renaming the composable, which the plan's later tasks call by name.

`ItemView`'s **ten** variants, read off the Rust source and the generated bindings:

```
// data classes
Bubble(muted: Boolean, blocks: List<RichBlock>)
System(text: String)
Placeholder(text: String)
Image(alt: String, width: ULong?, height: ULong?)
MediaFile(label: MediaFileLabel, filename: String, size: ULong?, mimetype: String?)   // FILE | AUDIO | VIDEO
CustomEvent(view: CustomEventView, label: String, eventType: String)
// objects — no fields, and the half most easily dropped
Emote
UnreadMarker
DateDivider
None
```

**Write the `when` with NO `else` branch.** Kotlin enforces exhaustiveness over a sealed
class, so omitting `else` makes a future core variant a compile error rather than a
silently blank row. That is deliberate: `DateDivider` exists as a variant *because* a host
missed it when it was only a comment — iOS rendered "Unsupported event (dateDivider)" in
the middle of a conversation (`item_view.rs:81`). An `else` branch would reintroduce
exactly that failure mode.

`DateDivider` carries no text; format the date from `row.item.timestampMs`, because
"formatting reads a clock and a locale and both belong where the rendering is". Take the
clock as a parameter rather than calling `System.currentTimeMillis()` inside the row — the
roster's `RelativeTime` takes an injected clock for the same reason.

Use **relative** day formatting — "Today" and "Yesterday" where they apply — because, per
`TimelineRowView.swift:47`, *"a date is harder to place than a word."*

Per-variant rendering, as iOS does it (`TimelineRowView.swift:58-118`):

| Variant | Renders |
|---|---|
| `Bubble` | the message block; `muted` (`m.notice`) de-emphasised but never suppressed |
| `Emote` | centred italic `"$named ${item.body}"` — prose *about* its sender |
| `System`, `Placeholder` | a system line (both, identically) |
| `DateDivider` | a hairline with the day on it |
| `UnreadMarker` | an accented rule, **no label** |
| `Image` | thumbnail, reserving its box from `width`/`height` before bytes land |
| `MediaFile` | an informative row: label · filename · size |
| `CustomEvent` | `DecisionCard` (Task 5) |
| `None` | **nothing at all** |

- [ ] **Step 1: Write the failing tests**

```kotlin
/**
 * Every one of the ten ItemView variants is HANDLED.
 *
 * Not "renders something visible" — `None` renders deliberately nothing
 * (iOS returns `EmptyView()`), and `UnreadMarker` renders a rule with no
 * label on purpose: "a caption repeated at every scroll position would be
 * chrome pretending to be content." The property under test is that no
 * variant falls through unhandled, which with no `else` branch is largely
 * the compiler's job — so assert the nine visible ones render their
 * distinguishing content, and assert `None` renders nothing.
 */
@Test fun everyVariantIsHandled()
/** A muted bubble (m.notice) is visually distinct but still legible. */
@Test fun aMutedBubbleStillShowsItsText()
/** Attribution comes from senderName; the row derives no names. */
@Test fun attributionComesFromTheRow()
/** An image with no loaded bytes shows its alt text rather than a blank box. */
@Test fun anImageWithoutBytesShowsItsAlt()
```

`everyVariantRendersSomething` is the important one: a `when` that silently misses a variant renders an empty row, which looks like data loss and is invisible to a test that only checks bubbles.

- [ ] **Step 2: Run, confirm failure**
- [ ] **Step 3: Write it** — branch on `row.view`, delegate `Bubble.blocks` to `RichText`, and render nothing of its own that the core did not supply.
- [ ] **Step 4: Run, confirm pass**
- [ ] **Step 5: Mutate** — drop the `Placeholder` branch and confirm `everyVariantRendersSomething` fails. Replace `row.senderName` with a literal and confirm `attributionComesFromTheRow` fails.
- [ ] **Step 6: Commit**

---

### Task 4: `StreamingText.kt` and `LiveTurn.kt`

**Files:**
- Create: `android/app/src/main/kotlin/dev/supermessage/LiveTurn.kt` (includes the streaming text composable)
- Test: `android/app/src/androidTest/kotlin/dev/supermessage/LiveTurnTest.kt`
- Read first: `apple/Supermessage/Timeline/LiveTurnView.swift` (182), `StreamingTextView.swift` (84)

**Interfaces:**
- Consumes: `dev.supermessage.kit.StreamingText` (already ported, `class StreamingText(scope: CoroutineScope)` with `text`, `revealed`, `accept(full)`, `finish(full?)`, `clear()`), `LiveStore`'s `answer`/`thought`/`tools`/`finished`.
- Produces: `@Composable fun LiveTurn(answer: String?, thought: String?, tools: List<LiveStore.ToolCall>, finished: Boolean, modifier: Modifier = Modifier)`

**The view owns the pacer** (spec §5): `remember { StreamingText(scope) }` fed by `LaunchedEffect(answer) { stream.accept(answer) }`. Do not give `LiveStore` a running job.

- [ ] **Step 1: Write the failing tests**

```kotlin
/** The reveal advances over time rather than appearing whole. */
@Test fun theAnswerRevealsProgressively()
/** A finished turn shows its whole answer, with no reveal left pending. */
@Test fun aFinishedTurnIsFullyRevealed()
/** Thought is collapsed by default and expands on tap. */
@Test fun theThoughtStartsCollapsed()
/** Tool calls are listed by name. */
@Test fun toolCallsAreNamed()
```

- [ ] **Step 2: Run, confirm failure**
- [ ] **Step 3: Write it**
- [ ] **Step 4: Run, confirm pass**
- [ ] **Step 5: Mutate** — make the composable render `stream.text` instead of the first `stream.revealed` characters, and confirm `theAnswerRevealsProgressively` fails. This is the mutation that matters: `StreamingText`'s pacing loop was never exercised on iOS, and this project's port added `pacesTheRevealOverTicks` precisely because of it.
- [ ] **Step 6: Commit**

---

### Task 5: `DecisionCard.kt`

**Files:**
- Create: `android/app/src/main/kotlin/dev/supermessage/DecisionCard.kt`
- Test: `android/app/src/androidTest/kotlin/dev/supermessage/DecisionCardTest.kt`
- Read first: `apple/Supermessage/Timeline/DecisionCard.swift` (158 lines)

**Interfaces:**
- Consumes: `uniffi.supermessage_core.{CustomEventView, CustomPayload}`
- Produces: `@Composable fun DecisionCard(view: CustomEventView, label: String, eventType: String, modifier: Modifier = Modifier)`

Read `CustomEventView`'s real variants off the generated bindings before writing; do not infer them.

**Amber means one thing.** The spec's typography rule reserves amber for a pending decision and nothing else. A2 has no theme yet, so use a single named constant here and leave a comment tying it to Phase D, exactly as `RoomRow.kt` did for its dot colours.

- [ ] Steps 1–6 as above: failing tests first (a pending decision is distinguishable from a settled one; every `CustomEventView` variant renders something), then implementation, then a mutation that collapses two variants into one.

---

### Task 6: `Timeline.kt` — the container

**Files:**
- Create: `android/app/src/main/kotlin/dev/supermessage/Timeline.kt`
- Test: `android/app/src/androidTest/kotlin/dev/supermessage/TimelineTest.kt`
- Read first: `apple/Supermessage/Timeline/TimelineView.swift` (99), and `TimelineCollectionView.swift`'s header

**Interfaces:**
- Consumes: everything above, plus `TimelineStore` (`items`, `revision`, `roomId`, `isPaginating`, `canPaginate`, `paginateBack(count)`, `markRead()`) and `TypingStore.line`.
- Produces: `@Composable fun Timeline(rows: List<TimelineRowDto>, typingLine: String?, isPaginating: Boolean, canPaginate: Boolean, onPaginate: () -> Unit, onMarkRead: () -> Unit, modifier: Modifier = Modifier)`

- [ ] **Step 1: Write the failing tests**

```kotlin
/**
 * Rule 1: the room opens at its newest message, fully visible.
 *
 * Asserts the newest row's bounds sit ABOVE the container's bottom edge —
 * geometry, not existence. A row hidden under an inset "exists".
 */
@Test fun theNewestMessageIsVisibleOnOpen()

/** Rule 3: a revision bump re-renders; an unrelated recomposition does not rebuild rows. */
@Test fun theListFollowsRevisionRatherThanDiffingRows()

/** Pagination fires when the reader reaches the older end, and not before. */
@Test fun reachingTheOlderEndAsksForMore()

/** Pagination stops asking once the store says there is no more. */
@Test fun nothingIsAskedForWhenThereIsNoMore()

/** The jump-to-newest affordance appears only when away from the newest end. */
@Test fun theWayBackAppearsOnlyWhenAway()

/** The typing line shows what TypingStore said, and nothing when it is null. */
@Test fun theTypingLineComesFromTheStore()
```

- [ ] **Step 2: Run, confirm failure**

- [ ] **Step 3: Write it**

`LazyColumn(reverseLayout = true)`, `items(rows, key = { it.item.id })`. "At newest" is
`listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0`,
derived with `remember { derivedStateOf { … } }` so it does not recompose per scroll pixel.

Pagination triggers off `listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index` approaching `rows.lastIndex`, guarded by `canPaginate && !isPaginating`.

- [ ] **Step 4: Run the whole instrumented suite** — the new tests plus all 33 existing, `RootScaffoldTest`'s five unmodified.

- [ ] **Step 5: Mutate**

Set `reverseLayout = false` and confirm `theNewestMessageIsVisibleOnOpen` fails. Remove the `canPaginate` guard and confirm `nothingIsAskedForWhenThereIsNoMore` fails. Each for real, then reverted.

- [ ] **Step 6: Commit**

---

### Task 7: Into the detail pane, and onto a device

**Files:**
- Modify: `android/app/src/main/kotlin/dev/supermessage/RootScaffold.kt` (add a `detailPaneContent` slot)
- Modify: `android/app/src/main/kotlin/dev/supermessage/MainActivity.kt`

- [ ] **Step 1: Add the slot**

Exactly as `listPaneContent` was added:

```kotlin
detailPaneContent: @Composable (shellWidth: Dp) -> Unit = { shellWidth ->
    Pane("pane-timeline", "Timeline", shellWidth)
},
```

**Keep the default's `testTag("pane-timeline")`.** Five existing tests call bare `RootScaffold()` and assert that tag; they must keep passing unmodified.

- [ ] **Step 2: Wire it in `MainActivity`**

Collect `timeline.items`, `revision`, `isPaginating`, `canPaginate` and `session.typing.line` with `collectAsStateWithLifecycle`, and pass them to `Timeline`. Mark read on `roomId` change, and again on `(roomId, revision, isAway)` when not away — both rules from spec §6.

- [ ] **Step 3: Run the whole instrumented suite**

- [ ] **Step 4: On a device**

```bash
scripts/android-emulator.sh supermessage-phone
cd android && ./gradlew :app:installDebug
adb shell am start -n dev.supermessage/.MainActivity
```

A signed-in session is already on this AVD. Open a real room. Confirm: the newest message is visible without scrolling; scrolling up loads history without the view jumping; the typing line appears when someone types; the room stops being bold in the roster after reading.

Capture `adb exec-out screencap -p > /tmp/timeline.png` — this works headless.

**Report what you saw, and say plainly what you could not check.**

- [ ] **Step 5: Commit**

---

## Self-Review

**Spec coverage:**

| Spec section | Task |
|---|---|
| §2 inverted container | 6 |
| §3 rule 1 (open at newest, visible) | 6 step 1 |
| §3 rule 2 (`animates`) | 1 |
| §3 rule 3 (revision, not diffing) | 6 step 1 |
| §3 rule 4 (keyboard on drag) | **not covered — see below** |
| §4 rows branch on `ItemView` | 3 |
| §5 live turn, view owns the pacer | 4 |
| §6 marking read, both triggers | 7 step 2 |
| §7 pagination and the way back | 6 |
| §8 structure | 1–6 map 1:1 |
| §9 what must be proven | each task's tests; device check in 7 |

**Placeholder scan:** clean, with one deliberate deferral — Task 5 says to read `CustomEventView`'s variants off the generated bindings rather than transcribing them here, because I have not verified them and inventing variant names is exactly the gloss that cost five tasks on the roster plan.

**Type consistency:** `TimelineAnimation.animates` (Task 1) is consumed in 6. `RichText` (2) in 3. `TimelineRow` composable (3) in 6. `LiveTurn` (4) in 6. `DecisionCard` (5) in 3's `CustomEvent` branch. `Timeline` (6) in 7. The DTO/composable name collision on `TimelineRow` is called out in Task 3 with a resolution.

**Known gap, stated rather than hidden:** **rule 4 (the keyboard dismisses on drag) is not implemented by this plan.** It needs an IME to dismiss, and A2 builds no composer — there is no text field on the reading surface to raise the keyboard in the first place. Installing a `nestedScroll` connection now would be untestable and would be exactly the "dead code that becomes load-bearing later" that the roadmap warns about for `RootScaffold`'s stranding effect. It belongs to Phase B's first task, and Phase B must own it explicitly rather than inheriting it as an assumption. The spec lists it under §3 because it is a timeline-shaped rule; this plan declines to build it blind.
