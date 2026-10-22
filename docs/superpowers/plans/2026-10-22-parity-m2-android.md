# Parity M2 (Android) — Platform Parity With No New Core: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task by task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** close every Android row the roadmap's "M2 — Android" list names, using only the
existing FFI and Kit, so each row reads ✅ in the exit table at the end of this plan.

**Execution:** one branch, `parity/m2-android-parity`, in worktree `parity-android`, cut from
`main` after PR #127 (M0) merges. Tasks merge in order. One PR per task, or per pair of
adjacent tasks when the reviewer prefers it.

**Cross-plan dependencies (M1a plan, `2026-10-22-parity-m1a-core-rules.md`):**
- **Task 8 waits for M1a PR2** (M1.7, branch `parity/m1-core-send-outcome`, tasks 3-4): it
  consumes the core's `AttachmentSendOutcome` (`Sent | Failed { error, restaged }`) and must not
  port the Swift restage. Start Task 8 only after PR2 has merged and `main` has the
  regenerated Kotlin bindings.
- Tasks 1 and 10 use thin Kotlin copies (`RosterArrangement.rosterRow`, `MentionCast`) that
  stay until M1a PR3 (M1.1, `parity/m1-core-roster-views`) and PR4 (M1.2,
  `parity/m1-core-cast`) merge. They do not wait for them; the switch-over is a later task.
- M1.4 (M1b PR2/PR3, `parity/m1-core-row-facts`, `parity/m1-core-timeline-grouping`): the
  Agent badge, long-read "Read", quote hiding and membership stretches wait for it and are not
  planned here (see "Moved to core").
- Owner decisions applied: O2 (follow iOS: no header avatar, Attach stays visible while
  editing), O4 (one connection line under the roster title, Task 11), R1 ("Not sent. Try
  again." stays).

**Architecture:**
- **All work is in `android/`.** The work is Compose UI in `android/app`. Thin Kit additions
  go in `android/kit`, over `CoreClient` methods that already exist:
  - a `StagedAttachment` port;
  - `SendResult`;
  - `MentionComposing`;
  - `MentionCast`;
  - `RoomsStore.markRead`;
  - `RoomToggles`;
  - `RosterChoice.offered`;
  - `StreamingMarkdown`.
- **No FFI change, so the bindings are untouched.**
- **Task 1 moves the detail pane, and Task 11 the list pane, out of `MainActivity.kt`,** into
  `RoomPane.kt` and `RosterPane.kt`. That way the later tasks stop contending for one 665-line
  file.

**Tech Stack:**
- Kotlin 2.4.0 and Compose, through BOM 2026.06.01 (ui 1.11.4, material3 from the same BOM).
- AGP 9.3.1, compileSdk 36, minSdk 31.
- Tests: JUnit4, compose-ui-test, and Roborazzi 1.74.0 on Robolectric 4.15.1 (API 35).

**Spec:**
- `docs/research/2026-10-22-ios-parity/README.md`, with its evidence in `notes/android.md`.
- The umbrella plan is `docs/superpowers/plans/2026-10-22-ios-parity-roadmap.md`, whose
  "M2 — Android" list is this plan's scope.
- iOS behaviour is quoted from `apple/` at the file:line given in each task.

Paths used below:
- `A=android/app/src/main/kotlin/dev/supermessage`
- `K=android/kit/src/main/kotlin/dev/supermessage/kit`
- `AT=android/app/src/androidTest/kotlin/dev/supermessage`
- `UT=android/app/src/test/kotlin/dev/supermessage`
- `KT=android/kit/src/test/kotlin/dev/supermessage/kit`
- `PV=android/app/src/debug/kotlin/dev/supermessage/previews`

## Global Constraints

**From the roadmap (binding):**
- **Tokens only:** no hex in views. New type roles go through `design/tokens.toml` and then
  `python3 scripts/generate-tokens.py`. Never edit `GeneratedThemeTokens.kt`.
- **Amber (`signal`) means a pending decision and nothing else.** A receipt, a failure, a
  swipe or an unread line is never amber.
- **The app parses nothing and decides nothing that the core already decides.** Anything that
  needs a new core rule goes under "Moved to core" below. It is never approximated in UI
  code.
- **No client-private Matrix event types.**
- **Match iOS's behaviour and copy, not its widgets.** Every string in this plan is quoted
  verbatim from the iOS file:line beside it. A string iOS lacks is marked *(no iOS copy)*; the
  only one is "Not sent. Try again.", which stays (R1). Where iOS and the roadmap disagree on a
  UI detail, follow iOS exactly (O2).
- **Bindings:** the platform track never edits `crates/` or the generated bindings. A needed
  core change is filed to the Core track, not worked around here.
- **Testing:**
  - Run instrumented tests after `scripts/android-ci-parity.sh`, at pixel_6 height **and** at
    `adb shell wm size 1080x1280`.
  - Use no single-character substring matchers.
  - Test shells set the required **width** (`Modifier.requiredWidth`), never the height.
- **Previews and baselines:** every new UI state ships an `@Preview` in `PV/`. Preview
  functions are `internal`, because the scanner cannot see private ones. A preview with an
  indeterminate animation joins `PreviewScreenshotTest.NEVER_SETTLES`.
- **This machine:**
  - One emulator, and one heavy build, at a time.
  - Emulator logs go in `TMPDIR=~/Android/dl`.
  - Do not run `build-android-libs.sh`.
  - Run `cargo build -p supermessage-ffi` only if the host library `:kit` tests need is
    missing.

**Android-specific:**
- **CI parity, animations.** CI's emulator runs with `disable-animations: true`
  (`.github/workflows/ci.yml:709`), so its `animator_duration_scale` is 0. Task 3 makes
  `scripts/android-ci-parity.sh` set all three scales to 0, the same as CI.
  - No UI test may read the device's animation setting. A test provides `LocalReduceMotion`
    explicitly.
  - The one exception is `systemReduceMotionFollowsTheAnimatorScale` (Task 15).
- **Haptics are tested through Compose's own `LocalHapticFeedback`,** provided as a recording
  fake. They are never tested by a device vibration. Android's `performHapticFeedback` already
  honours the system's touch-feedback setting, as iOS's `sensoryFeedback` does
  (`ComposerView.swift:110-113`).
- **Roborazzi baselines:**
  - Record with `./gradlew :app:testDebugUnitTest --tests '*PreviewScreenshotTest*'
    -Proborazzi.test.record=true`, then copy in only the frames this task meant to change.
  - Inspect each frame by eye first, and name it in the report.
  - Never re-record a frame to make the gate pass. A changed frame nobody intended is a
    regression to explain.
- **Verification for every task**, from `android/`, ending with `BUILD SUCCESSFUL` and 0
  failures:
  - `./gradlew :kit:test :app:testDebugUnitTest`;
  - `scripts/android-ci-parity.sh`;
  - `./gradlew :app:connectedDebugAndroidTest` at 1080x2400;
  - `adb shell wm size 1080x1280` and the same run;
  - `adb shell wm size 1080x2400`.

  To run a single test class, add
  `-Pandroid.testInstrumentationRunnerArguments.class=dev.supermessage.<Class>`.
- **Falsify every new test (AGENTS.md, "A test that has never failed").** Each task names the
  mutation. Apply it, watch the named test fail, restore it, and record the failure line in
  the task report. For a gesture or timing test, run the mutated version three times.
- **Serialized files.** These must not have two tasks open at once:
  - `MainActivity.kt`: Tasks 1, 11, 13 and 14;
  - `RoomPane.kt` (after Task 1 creates it): Tasks 2, 3, 9 and 10;
  - `RosterPane.kt` (after Task 11 creates it): Tasks 12 and 13.
- **Commits** end with the attribution lines the session's system reminder gives.

## Review Focus

1. **Accessibility font scale 2.0 at 411dp.** The decision buttons, the capsule composer, the
   room header and the message menu must not clip, overlap or break a word across lines.
   Pinned by:
   - Task 7: `requestChangesStaysOnOneLineAtFontScaleTwo`;
   - Task 9: `sendStaysOnScreenAtFontScaleTwo`;
   - Task 1: `aLongNameEllipsizesAndTheHeaderStaysTappable`.
2. **A decision whose send fails** (offline, 5xx). The card stays answerable and says so, and
   never shows a receipt. This is also M0's deferred DecisionCard test. Pinned in Task 7 by
   `aFailedAnswerKeepsTheCardAnswerableAndSaysSo`.
3. **A mostly vertical or diagonal drag on the timeline.** It scrolls. It neither reveals
   times nor starts a reply. A rightward swipe on a row that cannot be replied to does
   nothing. Pinned in Task 3 by `aVerticalDragRevealsNothing` and
   `swipingAnUnrepliableRowDoesNothing`.
4. **Switching rooms mid-state.** A header member count, a quote highlight, an `@` cast or an
   open message menu from the previous room must not show in the next one. iOS resets these
   on `roomId` (`RoomScreen.swift:35-39`). Pinned by:
   - Task 1: `memberCountResetsWhenTheRoomChanges`;
   - Task 10: `theCastReloadsForTheNewRoom`.
5. **A roster swipe when the core cannot answer** (offline, or `roomInfo` throws). Nothing
   flips, the label stays truthful, and no success haptic fires. Pinned in Task 12 by
   `muteSwipeDoesNothingWhenRoomInfoFails`.

---

## Task 1: Room header, `RoomPane` extraction, `MainActivity` import order — touches `MainActivity.kt`

**iOS:**
- The header is the way into room info (`Navigation/RoomScreen.swift:102-115`).
- `RoomHeader` shows the name on one line, plus, for a room of people only, `"1 member"` or
  `"\(memberCount) members"` (`:199-220`, `:212`).
- Its accessibility label is `"\(name), about this room"` (`:113`).
- The member count is fetched only when `!describesAgent` and the room is not an invitation
  (`:116-123`), and is reset on a room change (`:35-39`).
- iOS draws no avatar in the header, and neither does Android (Decided: O2). Do not add one.
- **No status pill:** that is M4.

**Files:**
- Create:
  - `A/RoomPane.kt`, holding the body of `MainActivity.kt:292-534` moved verbatim;
  - `A/RoomHeader.kt`;
  - `PV/RoomHeaderPreviews.kt`;
  - `AT/RoomHeaderTest.kt`;
  - `UT/RoomHeaderLabelTest.kt`.
- Modify:
  - `A/MainActivity.kt` — `detailPaneContent` becomes one `RoomPane(...)` call. Fix the import
    order at :40-44 so `dev.supermessage.kit.Session` precedes
    `dev.supermessage.kit.stores.ConnectionStore.Connection` (M0 deferred).
  - `A/RootScaffold.kt:135` and `:367`, the `detailPaneContent` signature.
  - `K/RosterArrangement.kt` — add `rosterRow`.
  - `AT/RosterReachabilityTest.kt` — `FakeCore.roomInfo`.
- Test: `KT/RosterArrangementTest.kt`.

**Interfaces:**
- Produces:
  - `RootScaffold(detailPaneContent: @Composable (shellWidth: Dp, openInfo: () -> Unit) -> Unit)`.
  - `@Composable internal fun RoomPane(session: Session, rooms: List<RoomRow>, voice: VoicePlayback, now: Instant, onOpenRoomInfo: (roomId: String) -> Unit, modifier: Modifier = Modifier)`.
  - `@Composable fun RoomHeader(row: RoomRow, memberCount: ULong?, onOpenInfo: () -> Unit, modifier: Modifier = Modifier)`, tagged `room-header`.
  - `internal fun memberCountLabel(count: ULong?): String?`.
  - `fun RosterArrangement.rosterRow(roomId: String, rows: List<RoomRow>, now: Instant): RosterRow?`
    — `sections(listOf(row), RECENT, showsInvitations = true, now).flatMap { it.rows }.firstOrNull()`,
    as in Swift `RosterViews.swift:115-120`.

- [ ] **Step 1: Write the failing tests.**
  - `UT/RoomHeaderLabelTest`:
    - `memberCountLabel(null) == null`;
    - `memberCountLabel(0uL) == null`;
    - `memberCountLabel(1uL) == "1 member"`;
    - `memberCountLabel(4uL) == "4 members"`.
  - `KT/RosterArrangementTest`:
    - `rosterRowFindsTheRoomAndSaysWhetherItIsAnAgents`: two rows, one with a `runtime`.
      `rosterRow("b", rows, now)?.describesAgent == true` for the agent's room, `false` for the
      other, and `null` for `"nope"`.
  - `AT/RoomHeaderTest`, each in `Box(Modifier.requiredWidth(411.dp))`:
    - `headerNamesTheRoomAndCountsPeople`: `memberCount = 4uL`. Assert the text "Design
      review" and the text "4 members" are displayed.
    - `theHeaderDrawsNoAvatar` (O2): no node tagged `room-avatar` exists inside `room-header`.
    - `anAgentsRoomShowsNoCount`: `memberCount = null`. Assert the text "members" with
      `substring = true` does not exist.
    - `tappingTheHeaderOpensInfo`: click `room-header`, and assert the callback ran once. The
      node's `contentDescription` is "Design review, about this room".
    - `aLongNameEllipsizesAndTheHeaderStaysTappable`: an 80-character name at
      `LocalDensity provides Density(density, fontScale = 2f)`. Assert the name's
      `TextLayoutResult.lineCount == 1` (via `SemanticsActions.GetTextLayoutResult`) and that
      the click still reaches `onOpenInfo`.
  - `AT/RosterReachabilityTest`:
    - `theRoomHeaderOpensRoomInfo`: through `AppRoot`, open a room, click `room-header`, and
      assert `pane-info` shows `RoomInfoPanel`'s tag.
    - `memberCountResetsWhenTheRoomChanges`: `FakeCore.roomInfo` returns 4 members for `!a`
      and suspends forever for `!b`. Open `!a`, see "4 members", open `!b`, and assert "4
      members" does not exist.
- [ ] **Step 2: Run the tests and confirm they fail.** The `:kit:test` and
  `:app:testDebugUnitTest` targets fail to compile on the unresolved names, and so does
  `--tests`/`class=` for `RoomHeaderTest` and `RosterReachabilityTest`.
- [ ] **Step 3: Implement.**
  - Move the detail-pane body into `RoomPane` unchanged.
  - Add the header above `Timeline` and `InvitationView` alike. iOS shows the header for an
    invitation too.
  - Compute `describesAgent` from `RosterArrangement.rosterRow(roomId, rooms, now)`.
  - Fetch the member count in `LaunchedEffect(roomId)`. Reset it to `null` on entry, skip it
    when `describesAgent` is true or the row is `RESPOND_TO_INVITATION`, and run
    `runCatching { session.roomInfo(roomId).activeMemberCount }.getOrNull()`.
  - The name uses `titleMedium`, one line, ellipsized. The count uses `labelSmall` in
    `contentMuted`.
  - The whole header is one `clickable(onClickLabel = "About this room")`, with
    `semantics(mergeDescendants = true) { contentDescription = "$name, about this room" }`.
  - `AppRoot` passes `onOpenRoomInfo = { id -> extraPanel = ExtraPanel.RoomInfo(id); openInfo() }`.
  - The header holds the name and the count only; no avatar (O2).
  - Previews:
    - `RoomHeaderPeople`, with 4 members;
    - `RoomHeaderAgent`, with no count;
    - `RoomHeaderLongNameLargeText`, at font scale 2.0.
- [ ] **Step 4: Falsify.** Make `memberCountLabel` return "1 members" for 1:
  `RoomHeaderLabelTest` fails. Then delete the reset line in the `LaunchedEffect`:
  `memberCountResetsWhenTheRoomChanges` fails. Then add a `room-avatar`-tagged node to the
  header: `theHeaderDrawsNoAvatar` fails.
- [ ] **Step 5: Run the full verification** (Global Constraints) at both heights. Record the
  three new previews after inspection.
- [ ] **Step 6: Commit.** Message: `android: room header opens room info; RoomPane out of MainActivity`.

## Task 2: Message menu — react, reply, copy, edit, delete with a confirm; "+" on tap; who reacted

**iOS** (`Timeline/TimelineCollectionView.swift:1039-1150`):
- A long press opens one menu. Its contents depend on the row:
  - When `row.canReplyOrReact`: a strip of `quickReactions` (`TimelineRowView.swift:22`), then
    **"Reply"** (`:1097`).
  - When `item.body` is non-empty: **"Copy"** (`:1103`).
  - When `item.editable`: **"Edit"** (`:1113`).
  - When `item.isOwn && row.canReplyOrReact`: **"Delete"**, destructive (`:1124`).
- Delete confirms (`:975-989`):
  - title **"Delete message?"**;
  - message **"This removes it for everyone in the room."**;
  - actions **"Delete"** (destructive) and **"Cancel"**.
- **No menu** when it would be empty (`:1147`).
- A reaction from the strip is a selection haptic (`:1086-1090`).
- Who reacted: a long press on a chip opens a popover with the `displayKey` and
  `peopleLabel(userIds: reaction.senders)` (`TimelineRowView.swift:575-593`).
  - The chip's accessibility label is `"\(displayKey), \(count)"`, plus `", including yours"`
    when it is yours (`:571-572`).
  - A chip tap is a selection haptic (`:578`).
- **"Report…" is M3** and is not built here.
- **Android's "+"** opens the picker on a **tap** (roadmap). Today it needs a long press
  (`TimelineRow.kt:587-591`).

**Files:**
- Create:
  - `A/MessageMenu.kt`;
  - `A/Haptics.kt`;
  - `PV/MessageMenuPreviews.kt`;
  - `AT/MessageMenuTest.kt`.
- Modify:
  - `A/Timeline.kt:143-159, 306-341` — the class-doc section "Starting a reply or an edit"
    is rewritten for the menu;
  - `A/TimelineRow.kt:505-620` — chips and `AddReactionAffordance`;
  - `A/RoomPane.kt` — the `onRowLongPress` block moved from `MainActivity.kt:443-456`.
- Test:
  - `AT/TimelineRowTest.kt:517-562` — the two long-press tests become taps;
  - `AT/TimelineTest.kt:481-535`.

**Interfaces:**
- Produces:
  - `enum class Haptic { Success, Failure, Selection, Light }`.
  - `fun HapticFeedback.play(haptic: Haptic)`, which maps Success → `HapticFeedbackType.Confirm`,
    Failure → `Reject`, Selection → `SegmentTick` and Light → `ContextClick`.
  - `data class MessageMenuActions(val react: Boolean, val reply: Boolean, val copy: Boolean, val edit: Boolean, val delete: Boolean) { val isEmpty: Boolean }`.
  - `internal fun messageMenuActions(row: TimelineRowDto): MessageMenuActions`.
  - `@Composable fun MessageMenu(row: TimelineRowDto, onReact: (String) -> Unit, onReply: () -> Unit, onCopy: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit)`.
    It is a `ModalBottomSheet` tagged `message-menu`. Its items are tagged `menu-reply`,
    `menu-copy`, `menu-edit` and `menu-delete`, and its strip `quick-reaction-$emoji`.
  - `@Composable fun DeleteConfirm(onConfirm: () -> Unit, onDismiss: () -> Unit)`, tagged
    `delete-confirm` and `delete-confirm-button`.
  - New `Timeline` parameters, replacing `onRowLongPress`:
    - `onReply: (TimelineRowDto) -> Unit = {}`;
    - `onEdit: (TimelineRowDto) -> Unit = {}`;
    - `onDelete: suspend (TimelineRowDto) -> Boolean = { false }`.

    Copy is handled inside `Timeline` through `LocalClipboardManager`.

- [ ] **Step 1: Write the failing tests.**
  - `AT/MessageMenuTest` covers these cases:

    | Test | Row | Expected |
    |---|---|---|
    | `othersReplyableMessageOffersReactReplyCopy` | not own, `canReplyOrReact`, body "hi", `editable = false` | `menu-reply` and `menu-copy` exist; `menu-edit` and `menu-delete` do not; four `quick-reaction-` nodes |
    | `ownMessageOffersEditAndDelete` | own, `canReplyOrReact`, editable | `menu-edit` and `menu-delete` exist |
    | `pendingOwnMessageOffersCopyOnly` | own, `canReplyOrReact = false`, body "hi", `editable = false` | `menu-copy` only; no strip |
    | `nothingToOfferOpensNoMenu` | `canReplyOrReact = false`, body null, not editable | `messageMenuActions(row).isEmpty`; a long press leaves no `message-menu` |

  - `deleteAsksFirst`: click `menu-delete`. Assert the texts "Delete message?" and "This
    removes it for everyone in the room." are displayed and `onDelete` has not been called.
    Click `delete-confirm-button`, and `onDelete` was called once with the row.
  - `cancelDeletesNothing`: click "Cancel", and `onDelete` was never called.
  - `copyPutsTheBodyOnTheClipboard`: click `menu-copy`, then assert
    `LocalClipboardManager.current.getText()?.text == "hi"`. Read it through a test-provided
    `ClipboardManager` fake.
  - `aMenuReactionIsASelectionHaptic`: provide a recording `HapticFeedback`. Click
    `quick-reaction-✅`. Assert `onReact("✅")` ran and the recorded types are
    `[SegmentTick]`.
  - `AT/TimelineRowTest`:
    - `tappingAddReactionOffersExactlyTheQuickSet`: `performClick()` on `add-reaction`, not
      `longClick()`. The picker shows exactly four.
    - `longPressingAChipSaysWhoReacted`: a chip with `senders = ["@ganesha:x", "@priya:x"]`.
      `longClick()` on `reaction-👍` shows `reaction-who`, containing the text
      `peopleLabel(listOf("@ganesha:x", "@priya:x"))`.
    - `aChipNamesItsCountAndWhetherItIsYours`: `byMe = true`, count 2. The
      `contentDescription` is "👍, 2, including yours".
  - `AT/TimelineTest`: `aLongPressOpensTheMenuForThatRow` replaces
    `aLongPressOnAReplyableRowReportsIt`. `aRowWithNothingToOfferGetsNoLongPressGesture` is
    kept as is.
- [ ] **Step 2: Run the tests and confirm they fail** (compile failure on `MessageMenu`), using
  `class=dev.supermessage.MessageMenuTest`.
- [ ] **Step 3: Implement.**
  - `Timeline` holds `var menuFor by remember { mutableStateOf<String?>(null) }`, keyed by
    `item.id`, and `var confirmingDelete`.
  - Both are cleared when `rows` no longer contains the id, so a redacted row's menu closes.
  - The row's long press exists only when `!messageMenuActions(row).isEmpty`.
  - `RoomPane` wires:
    - `onReply` to `session.replies.start(row, currentRoomId)`;
    - `onEdit` to `session.edits.start(row, currentRoomId)`;
    - `onDelete` to `session.delete(row.item.eventId, currentRoomId)`.
  - When `session.delete` returns `false`, `Timeline` shows a `labelSmall` danger line, "Not
    sent. Try again.", under the row for 4 s *(no iOS copy; iOS swallows a failed delete —
    Decided: R1 — the copy stays)*.
  - `AddReactionAffordance` uses `onClick = { pickerOpen = !pickerOpen }`.
  - Chips gain `combinedClickable(onLongClick = { asking = reaction })` and a `Popup` tagged
    `reaction-who`. Tapping a chip plays `Haptic.Selection`.
  - Previews:
    - `MessageMenuOthers`;
    - `MessageMenuOwn`;
    - `DeleteConfirm`;
    - `ReactionWhoReacted`.
- [ ] **Step 4: Falsify.**
  - Drop the `isOwn` guard from `messageMenuActions(...).delete`:
    `othersReplyableMessageOffersReactReplyCopy` fails.
  - Call `onDelete` before the confirm: `deleteAsksFirst` fails.
- [ ] **Step 5: Run the full verification** at both heights. Record the four new previews.
- [ ] **Step 6: Commit.** Message: `android: message menu with copy and confirmed delete; who reacted`.

## Task 3: Swipe to reply and T4 swipe-to-reveal times, with Reduce Motion

**iOS:**
- **A rightward swipe on a row** whose `row.canReplyOrReact` starts a reply. The action is
  labelled **"Reply"** (`TimelineCollectionView.swift:1249-1258`).
- **A leftward, mostly horizontal pan anywhere on the list** slides every message left
  together and shows its time at the trailing edge (`:1203-1247`).
  - The reveal width is `68` (`TimelineChrome.swift:24`).
  - Past that width the pull is rubber-banded:
    `pulled <= width ? pulled : width + (pulled - width) * 0.2` (`:1236-1239`).
  - The rows spring back on release: `spring(response: 0.32, dampingFraction: 0.86)`, or
    `easeOut(0.12)` under Reduce Motion (`TimelineChrome.swift:49-55`).
  - The time fades in, at opacity `min(1, offset / width)` (`:62-63`).
- **`revealedTime(row)`** is `nil` for `dateDivider`, `unreadMarker`, `system` and `none`.
  Those rows stay put. Every other row shows its clock (`TimelineCollectionView.swift:664-669`).
- **Android's Reduce Motion** is the system animator duration scale of 0. It turns every
  animation off (roadmap).

**Files:**
- Create:
  - `A/Motion.kt`;
  - `A/TimelineSwipe.kt`;
  - `UT/TimelineSwipeTest.kt`;
  - `AT/TimelineSwipeTest.kt`.
- Modify:
  - `A/Timeline.kt` — the `HistoryRow` modifier chain, and the class doc's "No swipe gesture"
    sentence at :133-135;
  - `A/RoomPane.kt` — `onSwipeReply`;
  - `scripts/android-ci-parity.sh` — set all three animation scales to 0.

**Interfaces:**
- Produces:
  - `val LocalReduceMotion = compositionLocalOf<Boolean?> { null }`.
  - `@Composable fun reducesMotion(): Boolean` — `LocalReduceMotion.current ?: systemReducesMotion()`.
    `systemReducesMotion()` reads `Settings.Global.ANIMATOR_DURATION_SCALE == 0f` and re-reads
    it on `ON_RESUME`.
  - `fun <T> motion(reduce: Boolean, full: AnimationSpec<T>): AnimationSpec<T>` — `snap()`
    when `reduce`.
  - `@Stable class TimeReveal { var offset: Float; var tracking: Boolean }`.
  - `internal const val RevealWidthDp = 68`.
  - `internal fun rubberBand(pulled: Float, width: Float): Float`.
  - `internal fun revealedTime(row: TimelineRowDto, clock: (ULong) -> String): String?`.
  - `fun Modifier.timelineSwipe(row: TimelineRowDto, reveal: TimeReveal, revealWidthPx: Float, onSwipeReply: (TimelineRowDto) -> Unit): Modifier`.
    It is one `pointerInput` per row that:
    - claims the drag only when `abs(dx) > abs(dy) * 2` past touch slop;
    - sends a rightward drag to a per-row reply offset, firing at 64dp if
      `row.canReplyOrReact`;
    - sends a leftward drag to the shared `reveal`.
  - New `Timeline` parameter: `onSwipeReply: (TimelineRowDto) -> Unit = {}`.

- [ ] **Step 1: Write the failing tests.**
  - `UT/TimelineSwipeTest`:
    - `rubberBand(30f, 68f) == 30f`;
    - `rubberBand(68f, 68f) == 68f`;
    - `rubberBand(168f, 68f) == 88f`;
    - `revealedTime` is `null` for `DateDivider`, `UnreadMarker`, `System` and `None`, and is
      `clock(ts)` for a `Bubble` with a timestamp.
  - `AT/TimelineSwipeTest`, all with `LocalReduceMotion provides true` and a list of five
    bubble rows:
    - `swipingRightStartsAReply`: `performTouchInput { swipeRight(endX = left + 200f) }` on
      `row-1`. `onSwipeReply` received row 1.
    - `swipingAnUnrepliableRowDoesNothing`: row 1 has `canReplyOrReact = false`. The same
      swipe, and `onSwipeReply` is never called.
    - `swipingLeftRevealsEveryTime`: hold a leftward drag of 120px using
      `down`/`moveBy`/no `up`. Nodes tagged `revealed-time` exist for all five rows. After
      `up()`, none remain.
    - `aVerticalDragRevealsNothing`: `swipe(start = center, end = center + Offset(-30f, -400f))`.
      No `revealed-time` exists, `onSwipeReply` is never called, and
      `listState.firstVisibleItemIndex` or its offset changed. Run it three times, as the
      constraint for gesture tests requires.
  - `AT/MotionTest`: `reduceMotionSnaps`. Under `LocalReduceMotion provides true`,
    `motion(reducesMotion(), tween<Float>(300))` is a `SnapSpec`. Under `false` it is the
    tween.
- [ ] **Step 2: Run the tests and confirm they fail** (unresolved names).
- [ ] **Step 3: Implement.**
  - Each row is offset by `-reveal.offset` (0 for rows whose `revealedTime` is `null`).
  - A trailing `Text`, tagged `revealed-time`, sits at `x = RevealWidth - offset`, with alpha
    `min(1, offset / width)`. It is `clearAndSetSemantics {}`, the iOS `accessibilityHidden`
    (`TimelineChrome.swift:64-67`).
  - On release the offset animates to 0 with
    `motion(reduce, spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow))`.
  - The reply swipe also exposes a `CustomAccessibilityAction("Reply")`.
  - The parity script adds:

    ```
    adb shell settings put global window_animation_scale 0
    adb shell settings put global transition_animation_scale 0
    adb shell settings put global animator_duration_scale 0
    ```

    It echoes them the way it echoes the size.
- [ ] **Step 4: Falsify.**
  - Change the claim test to `abs(dx) > abs(dy)`: `aVerticalDragRevealsNothing` fails on at
    least one of the three runs.
  - Remove the `canReplyOrReact` check: `swipingAnUnrepliableRowDoesNothing` fails.
- [ ] **Step 5: Run the full verification** at both heights, after the updated parity script.
- [ ] **Step 6: Commit.** Message: `android: swipe to reply; swipe left for times; Reduce Motion`.

## Task 4: Row rhythm, reactions on the corner, receipt faces, labelled new-messages line, undecryptable hint

**iOS:**
- **T2 rhythm:** two points inside a run, twelve between turns:
  `.padding(.top, continuesRun ? 2 : 12)` (`TimelineRowView.swift:375`). The same applies to
  the undecryptable row (`:674`). Android has 2/8 (`TimelineRow.kt:329`).
- **T3:**
  - Reactions hang off the bubble's bottom corner with `spacing: -8`, padded 10 horizontally
    (`:319-323`).
  - Each chip has a 2-point ring of `surface` (`:612-614`).
  - Receipts become a `ReaderStack`:
    - up to **3** faces of 14pt, overlapping by 4, each ringed 1.5 in `surface`;
    - then **"+\(n)"** for the rest;
    - the accessibility label is **"Read by \(peopleLabel(...))"** (`TimelineChrome.swift:142-175`).
  - The faces sit on the own-message meta line (`TimelineRowView.swift:340-366`).
- **Who is a reader's face:** the list builds `facesBySender` from rows where
  `item.kind == "message"`, taking the first `senderAvatar` and `senderInitial` per sender. A
  reader with no such row gets no initial and is drawn as a person glyph
  (`TimelineCollectionView.swift:747-779`, `TimelineChrome.swift:97-110`).
- **New-messages line:** a divider in `accent`, then **"New"** in the meta face, in `accent`.
  Its accessibility label is **"New messages"** (`TimelineRowView.swift:136-151`). Android's
  is unlabelled (`TimelineRow.kt:164-174`).
- **Undecryptable** (`Placeholder(UnableToDecrypt, text)`) (`:153-158`, `:637-680`):
  - the sender header (face, name, time) when it is not own and not a continuation;
  - then a lock icon with `text`;
  - then **"Encryption recovery in your account can restore older messages."** in footnote,
    `contentFaint`;
  - all on `surfaceRaised`, with radius 14.

  Android draws a centred `SystemLine` (`TimelineRow.kt:156`).

**Files:**
- Modify:
  - `A/TimelineRow.kt:107-243` — new parameters and the `Placeholder` and `UnreadMarker`
    branches;
  - `A/TimelineRow.kt:309-440` — `MessageBlock`;
  - `A/Timeline.kt:180-200` — the readers map, beside `continuesRun`.
- Create: `PV/TimelinePolishPreviews.kt`.
- Test: `AT/TimelineRowTest.kt` — rewrite `reactionsAndReadReceiptsRenderReadOnly` (:356) and
  `readReceiptsNamePeopleRatherThanIds` (:401) for the faces.

**Interfaces:**
- Produces:
  - `data class ReaderFace(val userId: String, val mxcUri: String?, val initial: String?)`.
  - `internal fun readerFaces(rows: List<TimelineRowDto>): Map<String, ReaderFace>`, keyed by
    sender.
  - `TimelineRow(readers: List<ReaderFace> = emptyList())`.
  - `@Composable internal fun ReaderStack(readers: List<ReaderFace>, faceUri: (String) -> String?, loadFace: suspend (String) -> Unit)`,
    tagged `reader-stack`.

- [ ] **Step 1: Write the failing tests** in `TimelineRowTest`.
  - `continuedRowsSitTwoApartAndNewTurnsTwelve`: two `Bubble` rows in a `Column`, the first
    with `continuesRun = false` and the second with `continuesRun = true`. Assert that the
    second bubble's top minus the first bubble's bottom is in `[2.dp, 4.dp]`. A third row with
    `continuesRun = false` must have a gap of at least 12.dp.
  - `fourReadersShowThreeFacesAndACount`: own message, four readers. The text "+1" is
    displayed, and `reader-stack` has the `contentDescription`
    `"Read by " + peopleLabel(ids)`.
  - `aReaderWithNoMessageGetsNoInitial`: `readerFaces(rows)` has no entry for a reader who
    never sent a `kind == "message"` row.
  - `theUnreadLineSaysNew`: the text "New" is displayed, and `unread-marker` has the
    `contentDescription` "New messages".
  - `anUndecryptableMessageOffersRecovery`:
    `Placeholder(PlaceholderKind.UnableToDecrypt, "Unable to decrypt")`, not own. Assert the
    sender name is displayed and the text "Encryption recovery in your account can restore
    older messages." is displayed.
  - `otherPlaceholdersStayQuietLines`: `Placeholder(PlaceholderKind.Redacted, "Message
    deleted")` draws no recovery text.
- [ ] **Step 2: Run them and confirm they fail.**
- [ ] **Step 3: Implement.**
  - Wrap the bubble and reactions in `Column(verticalArrangement = Arrangement.spacedBy((-8).dp))`.
    That keeps the chips' height in the row.
  - `ReactionsRow` gets `padding(horizontal = 10.dp)`, and each chip
    `padding(2.dp).background(surface, CircleShape)`.
  - `SenderFace` gains a `size: Dp = 18.dp` parameter. With `initial == null` it draws
    `Icons.Filled.Person` tinted `contentFaint`.
- [ ] **Step 4: Falsify.**
  - Set top padding back to 8dp: `continuedRowsSitTwoApartAndNewTurnsTwelve` fails.
  - Drop the `kind == "message"` filter: `aReaderWithNoMessageGetsNoInitial` fails.
- [ ] **Step 5: Run the full verification.** Record these previews after inspection:
  - `RowRhythm`;
  - `ReactionsOnCorner`;
  - `ReaderFaces`;
  - `UnreadNew`;
  - `Undecryptable`.

  Every existing timeline baseline that moved because of the 12dp rhythm or the corner
  reactions is re-recorded only after checking each one against T2 and T3. List them in the
  report.
- [ ] **Step 6: Commit.** Message: `android: 2/12 rhythm, reactions on the corner, receipt faces, New line, recovery hint`.

## Task 5: T5 — tap a quote to jump to it and highlight it

**iOS:**
- **A quote is tappable only when its parent is loaded:**
  `canJump = idByEventId[row.item.replyTo?.eventId] != nil` (`TimelineCollectionView.swift:590-592`).
- **A tap** scrolls the parent to the centre, animated unless Reduce Motion is on. It then
  highlights the parent for **1.4 s** (`:921-935`).
- **The highlight** is a rounded rectangle of radius 12 in `accentSoft`, inset −8
  horizontally. It fades in over 0.15 s and out over 0.8 s (`TimelineRowView.swift:79-88`).
- **Accessibility hint:** **"Shows the original message"** (`:538`).

**Files:**
- Modify:
  - `A/Timeline.kt` — `idByEventId`, `highlightedId` and the jump;
  - `A/TimelineRow.kt:464-503` — `ReplyQuoteBlock(onTap)` and the row highlight.
- Test: `AT/TimelineTest.kt`.

**Interfaces:**
- Consumes:
  - `TimelineItemDto.replyTo: ReplyToDto?` (`.eventId`);
  - `reducesMotion()` and `motion(...)` from Task 3.
- Produces:
  - `TimelineRow(highlighted: Boolean = false, onQuoteTap: (() -> Unit)? = null)`.
  - Test tags: `row-highlight` (present only while highlighted) and `reply-quote`.

- [ ] **Step 1: Write the failing tests** in `TimelineTest`, with 40 rows and
  `LocalReduceMotion provides true`. Row `r39`, the newest, quotes `r2`, the oldest.
  - `tappingAQuoteBringsItsParentIntoView`: click `reply-quote` in `row-r39`. `row-r2` is
    displayed, and `row-highlight` exists inside `row-r2`.
  - `theHighlightFades`: with `mainClock.autoAdvance = false`, after the tap,
    `mainClock.advanceTimeBy(1_500)`. `row-highlight` does not exist.
  - `aQuoteWhoseParentIsNotLoadedOffersNoTap`: `replyTo.eventId = "$missing"`.
    `reply-quote` has no click action (`assertHasNoClickAction()`).
- [ ] **Step 2: Run them and confirm they fail.**
- [ ] **Step 3: Implement.**
  - The index comes from `newestFirst.indexOfFirst { it.id == id }`, plus the live-turn
    offset (Timeline's own item order at :394-414). Scroll with `animateScrollToItem`, or
    `scrollToItem` when `reducesMotion()`.
  - `highlightedId` is cleared by a `LaunchedEffect(highlightedId) { delay(1_400); highlightedId = null }`.
  - The fades use `motion(reduce, tween(150))` in and `motion(reduce, tween(800))` out.
- [ ] **Step 4: Falsify.** Remove the "parent loaded" guard:
  `aQuoteWhoseParentIsNotLoadedOffersNoTap` fails.
- [ ] **Step 5: Run the full verification.** Record a `QuoteHighlighted` preview.
- [ ] **Step 6: Commit.** Message: `android: tap a quote to jump to its message (T5)`.

## Task 6: Markdown while streaming

**iOS** (`LiveTurnView.swift:100-111`, `:387-488`):
- Completed lines render through the core's `richBlocksFromMarkdown`, the parser the landed
  message uses. Only the line still being written stays plain.
- `split(text, arriving)` splits at the last newline **before** the arriving chunk.
- A tail containing block markup settles as soon as it is whole: a line starting with any of
  `#`, `- `, `* `, `+ `, `>`, ` ``` `, `|`, or `digits + ". "`, after leading spaces.
- The tail's trailing newlines are trimmed.
- Blocks are parsed only when the settled prefix changes (`MarkdownBlockCache`).
- Android renders the whole answer as one plain `Text` (`LiveTurn.kt` :140-146 in the code
  listing).

**Files:**
- Create: `K/StreamingMarkdown.kt`, `KT/StreamingMarkdownTest.kt`.
- Modify: `A/LiveTurn.kt`, in the `if (answer != null)` block.
- Test: `AT/LiveTurnTest.kt` — `answerText()` now reads `live-answer-tail` plus the settled
  text.

**Interfaces:**
- Produces:
  - `object StreamingMarkdown { fun split(text: String, arriving: Int): Pair<String, String>; fun blockMarkup(text: String): Boolean }`.
  - Tags: `live-answer`, now on the container, and `live-answer-tail`.
- Consumes:
  - `uniffi.supermessage_ffi.richBlocksFromMarkdown(source: String): List<RichBlock>`;
  - `RichText(blocks, fontFamily)`;
  - `StreamingText.revealed`.

- [ ] **Step 1: Write the failing Kit tests.**

  | Test | `split(text, arriving)` | Result |
  |---|---|---|
  | `noNewlineIsAllTail` | `("Hello wor", 3)` | `("", "Hello wor")` |
  | `splitsBeforeTheArrivingChunk` | `("First line.\nSecond li", 2)` | `("First line.", "Second li")` |
  | `aLineEndingInTheChunkStillFades` | `("Done.\n", 6)` | `("", "Done.")` |
  | `headingSettlesWhenWhole` | `("# Title\nBody", 4)` | `("# Title", "Body")` |
  | `listItemSettlesWhenWhole` | `("Intro\n- one\n- tw", 2)` | `("Intro\n- one", "- tw")` |

  Also:
  - `blockMarkup("12. step") == true`;
  - `blockMarkup("12 apples") == false`;
  - `blockMarkup("  > quote") == true`.
- [ ] **Step 2: Write the failing UI test.**
  - `aBoldLineIsBoldWhileStreaming`: `LiveTurn(answer = "**Observed:** ok\nnext",
    finished = true)`. The text "Observed:" is displayed and "**" with `substring = true` does
    not exist.
  - Keep `theAnswerRevealsProgressively`, adapted to the tail.
- [ ] **Step 3: Run both and confirm they fail.**
- [ ] **Step 4: Implement.**
  - `val (settled, tail) = StreamingMarkdown.split(revealedAnswer, stream.revealed)`.
  - `val blocks = remember(settled) { if (settled.isEmpty()) emptyList() else richBlocksFromMarkdown(settled) }`.
  - Render `RichText(blocks)` above a plain tail `Text`, tagged `live-answer-tail`.
- [ ] **Step 5: Falsify.**
  - Split at the last newline instead of before the chunk: `aLineEndingInTheChunkStillFades`
    fails.
  - Drop the digits rule: the `"12. step"` assertion fails.
- [ ] **Step 6: Run the full verification.** The `LiveTurnMidTurn` and `LiveTurnThinking`
  frames in `NEVER_SETTLES` may change. Inspect them and re-record only if the change is the
  markdown.
- [ ] **Step 7: Commit.** Message: `android: format a streaming answer as it arrives`.

## Task 7: G1 decision layout, D7 quiet metadata, failure feedback (and the M0-deferred failed-answer test)

**iOS** (`Timeline/DecisionCard.swift`, `DecisionReceipt.swift`):
- **D7:** the header is `label` only. `eventType` goes into the accessibility label
  `"\(label), \(eventType)"` (`:197-214`). The badge is **"Newer version"** (`:212`).
  Android prints the raw type (`DecisionCard.kt:166-177`) and says "newer version".
- **Pending card** (`:216-262`):
  - the prompt in `headline`, wrapping;
  - **"Asked by \(senderName)"** (`:243`);
  - the digest summary, one line (four at accessibility sizes);
  - **"Details"**, a disclosure with the remaining fields (`:340-352`);
  - **"Reasoning"**;
  - **"Open the card"**;
  - then the buttons.
- **`FieldDigest`** (`:443-476`):
  - It skips fields whose non-empty value the prompt already contains.
  - The summary is the first remaining value, plus the second when there is more than one,
    the second is 32 characters or fewer and the first is 40 or fewer, joined with `" · "`.
  - The rest goes to Details.
- **`DecisionButtons`** (`:481-600`):
  - The primary is the first option whose role is not reject. It is full width, filled
    `signal`, with its label in `signalSoft` and `headline` weight.
  - Secondaries are bordered, in `content`.
  - Rejections come **last**, as text buttons in `danger`, with a minimum height of 44.
  - The secondary and reject buttons sit in a row when they fit, and in a column otherwise.
  - A disabled label is drawn in `content` at 0.45 opacity.
  - `DecisionOptionRole` is decided by id: `approve`, `request_changes`, `reject`, or other.
- **Receipt** (`DecisionReceipt.swift:20-29`, `:53-104`):
  - a check in `ok`;
  - then **"Approved by you"**, **"Changes requested by you"**, **"Rejected by you"** or
    **"You chose “\(label)”"**;
  - then **" · just now"** for the first 60 s, and a relative time after that;
  - then **"Waiting for the board to confirm"** (`DecisionCard.swift:294`).

  Android draws "✓ {label}" in `signal`, which is amber on a receipt (`DecisionCard.kt:388-394`).
- **Failure:**
  - The error haptic fires once per failed send (`:561`).
  - The buttons stay enabled.
  - iOS has no visible copy. Android adds **"Not sent. Try again."**, desktop D2's copy *(no
    iOS copy — Decided: R1, the copy stays)*, as a `danger` `labelSmall` line in a polite live region.
- **The success haptic** fires once, when the answer lands (`:175-177`).
- **Pending → receipt** is a crossfade with scale 0.97 from the top, or the fade alone under
  Reduce Motion (`:192-194`). Android turns both off under scale 0.

**Files:**
- Modify:
  - `A/DecisionCard.kt:122-494`;
  - `A/TimelineRow.kt:217-224`, which passes `senderName = named`.
- Create: `A/DecisionDigest.kt`, `UT/DecisionDigestTest.kt`.
- Test: `AT/DecisionCardTest.kt` — rewrite `eventTypeRendersUntouchedByThisLayer` (:313) as
  `eventTypeIsSpokenNotShown`, and update `newerVersionIsFlaggedOnlyWhenTrue` for "Newer
  version".
- Previews: re-record `CardPending` and `CardHostileEventType`; add `CardPendingDetailsOpen`,
  `CardSendFailed`, `CardJustAnswered` and `CardLargeTextNarrow`.

**Interfaces:**
- Produces:
  - `DecisionCard(..., senderName: String, ...)`.
  - `internal data class FieldDigest(val summary: String?, val rest: List<CustomEventField>)`.
  - `internal fun fieldDigest(fields: List<CustomEventField>, prompt: String): FieldDigest`.
  - `internal enum class DecisionOptionRole { APPROVE, REQUEST_CHANGES, REJECT, OTHER; companion object { fun of(option: CustomEventDecisionOption): DecisionOptionRole } }`.
  - `internal fun receiptOutcome(option: CustomEventDecisionOption): String`.
  - `internal fun receiptWhen(answeredAt: Instant, now: Instant): String`.
  - Tags: `decision-primary`, `decision-secondary`, `decision-reject`, `decision-details`,
    `decision-failed` and `decision-receipt`.

- [ ] **Step 1: Write the failing JVM tests** in `DecisionDigestTest`.
  - `thePromptsOwnWordsAreSkipped`: fields `Wants to = "restart gateway"` and `Host = "ci-1"`,
    prompt "Allow restart gateway?". The summary is "ci-1" and the rest is empty.
  - `twoShortValuesShareTheLine`: `Card = "Launch post"` and `Board = "Marketing"`. The summary
    is "Launch post · Marketing".
  - `aLongSecondValueGoesToDetails`: the second value is 33 characters. It goes to `rest`.
  - `rolesAreReadFromIds`: `"approve"`, `"request_changes"`, `"reject"` and `"later"` map to the
    four roles.
  - `receiptsSayWhoChose`: `receiptOutcome` for approve is "Approved by you", and for `later`
    with label "Later" it is "You chose “Later”".
  - `receiptWhen(t, t + 59s) == "just now"`.
- [ ] **Step 2: Write the failing UI tests** in `DecisionCardTest`.
  - `rejectComesLastAndApproveLeads`: options `[reject, approve, request_changes]`.
    - `decision-primary` has the text "Approve" and spans at least 90% of the card's width.
    - `decision-reject` ("Reject") has a top bound at or below every `decision-secondary`'s
      top.
    - It also has a left bound at or after their left, so it is last in reading order.
  - `detailsAreClosedUntilAsked`: a third field "Repository" does not exist until
    `decision-details` is clicked.
  - `askedByNamesTheSender`: `senderName = "Superpipeline — Delivery"`. The text "Asked by
    Superpipeline — Delivery" is displayed.
  - `eventTypeIsSpokenNotShown`:
    - The text `safeEventType` does not exist.
    - `decision-card` has a `contentDescription` containing `safeEventType`.
    - The hostile-RTL concern no longer applies on screen. Note it in the KDoc.
  - `aFailedAnswerKeepsTheCardAnswerableAndSaysSo`: `onDecide` returns `false`, with a
    recording `HapticFeedback`. Click "Approve", and assert:
    - `decision-failed` has the text "Not sent. Try again.";
    - "Approve" `assertIsEnabled()`;
    - `decision-receipt` does not exist;
    - the haptics are `[Reject]`.

    Click again: the haptics are `[Reject, Reject]`.
  - `aLandedAnswerBecomesAReceipt`: `onDecide` returns `true`. Assert:
    - `decision-receipt` contains "Approved by you · just now";
    - the text "Waiting for the board to confirm" is displayed;
    - `decision-primary` does not exist;
    - the haptics are `[Confirm]`.

    The receipt's colour (`ok`, never `signal`) is checked on the inspected
    `CardJustAnswered` frame.
  - `requestChangesStaysOnOneLineAtFontScaleTwo`: in `requiredWidth(411.dp)` at
    `fontScale = 2f`, the "Request changes" `TextLayoutResult.lineCount == 1`. The buttons
    stack when they must.
- [ ] **Step 3: Run them and confirm they fail.**
- [ ] **Step 4: Implement** as quoted above.
  - Disclosures and the receipt transition use `AnimatedVisibility` and `AnimatedContent` with
    `motion(reducesMotion(), ...)`.
  - The row-or-column choice is a `Layout` that measures each button at its max intrinsic
    width and places them in a row only if the sum plus 8dp spacing fits. No font-scale
    threshold, as iOS's `ViewThatFits` (`:521-534`).
  - The summary is `maxLines = if (LocalDensity.current.fontScale >= 1.3f) 4 else 1`.
- [ ] **Step 5: Falsify.**
  - Make `send` set `answered` before awaiting: `aFailedAnswerKeepsTheCardAnswerableAndSaysSo`
    fails.
  - Order the options as given: `rejectComesLastAndApproveLeads` fails.
- [ ] **Step 6: Run the full verification** at both heights. Inspect and record the six card
  previews.
- [ ] **Step 7: Commit.** Message: `android: decision card hierarchy, quiet type, receipt and failure (G1, D7)`.

## Task 8: Kit composer foundations — attachments that remember failure, send results, mentions

**iOS:**
- **`StagedAttachment`** (`SupermessageKit/Stores/StagedAttachment.swift:36-199`) keeps:
  - `file`, `path`, `roomId` and `failure: Failure(filename, message)`;
  - `isPresent(in:) = roomId == id && (file != nil || failure != nil)`;
  - `blocksSend(in:) = roomId == id && file == nil && failure != nil`;
  - `refuse(filename:message:in:)`.
- **A failed `send`** re-stages the same path to get a fresh token, then sets `failure` and
  returns the message (`:143-178`). **Android does not port this.** The core now does it
  (M1.7, M1a PR2): `attachmentSend` returns `Sent | Failed { error, restaged }`, and `restaged`
  is the fresh `StagedFile` (or null when the file is gone). Task 8 consumes that outcome.
  **This task waits for M1a PR2 (`parity/m1-core-send-outcome`) to merge.**
- **Copy:** "Couldn't attach that file." (`:111`) and "Couldn't send that file." (`:154`).
- **`SendResult`** is `sent`, `nothingToSend`, `attachmentFailed(String)` or
  `textFailed(String)` (`Outbox.swift:5-19`). `Outbox.send` takes `mentioning: [Mentionable]`
  for `collectMentions` (`:39-71`).
- **`MentionComposing`** (`SupermessageKit/MentionComposing.swift:11-66`):
  - `activeQuery(text)`: the last `@`, at the start or after whitespace, with no whitespace
    after it and at most 40 characters (`longestQuery`).
  - `insert(person, query, text) = text[..<at] + "@" + person.name + " "`.
  - `label(person) = person.name`.
- **The cast join** (`Stores/RoomCast.swift:43-62`, `:103-114`):
  - `knownPeople`, filtered to the room's member ids, or everyone when they cannot be read;
  - `candidates = peopleMatching(people, query).take(6)`;
  - `mentionables = people.map { Mentionable(userId, displayName = name) }`.
- **Counterpart and addressee are M1.2,** and are not ported.

**Files:**
- Modify:
  - `K/stores/StagedAttachment.kt` — already adapted by M1a PR2 to discard `restaged?.token`
    on `Failed`; this task makes it keep `restaged` as the chip's file instead;
  - `K/Session.kt:283-324` — `send`.
- Create:
  - `K/SendResult.kt`;
  - `K/MentionComposing.kt`;
  - `K/MentionCast.kt`.
- Test:
  - `KT/stores/StagedAttachmentTest.kt`;
  - `KT/SessionTest.kt`;
  - `KT/MentionComposingTest.kt`;
  - `KT/MentionCastTest.kt`.
- Modify the callers: `A/RoomPane.kt` (`onSend`, `onAttach`). This is a minimal compile fix
  here. The UI is Task 9.

**Interfaces:**
- Produces:
  - In `StagedAttachment`:
    - `data class Failure(val filename: String, val message: String)`;
    - the flows `path: StateFlow<String?>`, `failure: StateFlow<Failure?>` and
      `roomId: StateFlow<String?>`;
    - `fun isPresent(roomId: String): Boolean`;
    - `fun blocksSend(roomId: String): Boolean`;
    - `suspend fun refuse(filename: String, message: String, roomId: String)`.
  - `stage(path, roomId)` records `failure` on refusal, and still returns the message.
  - `StagedAttachment.send` reads the core's `AttachmentSendOutcome` (generated type from
    M1a PR2): `Sent` clears the chip; `Failed(error, restaged)` sets `file = restaged`, sets
    `failure` from `ErrorPresenter.message(error)` and returns that message. No Kotlin
    re-stage call exists. A thrown refusal (`UnknownAttachment` clears the file, otherwise it
    is kept) behaves as M1a PR4's iOS tests describe.
  - `sealed class SendResult { object Sent; object NothingToSend; data class AttachmentFailed(val message: String); data class TextFailed(val message: String); val delivered: Boolean }`.
  - `suspend fun Session.send(text: String, roomId: String, mentioning: List<Mentionable> = emptyList()): SendResult`.
  - `object MentionComposing { data class Query(val text: String, val atOffset: Int); const val LONGEST_QUERY = 40; fun activeQuery(text: String): Query?; fun label(person: PersonDto): String; fun insert(person: PersonDto, query: Query, text: String): String }`.
  - `class MentionCast { val people: StateFlow<List<PersonDto>>; suspend fun load(roomId: String, allPeople: suspend () -> List<PersonDto>, memberIds: suspend () -> List<String>?); fun candidates(query: String, limit: Int = 6): List<PersonDto>; val mentionables: List<Mentionable> }`.

- [ ] **Step 1: Write the failing tests.**
  - `StagedAttachmentTest`:
    - `aRefusedStageLeavesAFailureOnTheChip`: the fake's `attachmentStagePath` throws
      `FfiException.Store("too large")`. `failure.value?.filename` is the path's last
      component, and `isPresent("!r") == true` and `blocksSend("!r") == true`.
    - `aFailedSendKeepsTheFileTheCoreRestaged`: the fake's `attachmentSend` returns
      `Failed(FfiException.Network("upload failed"), StagedFile("tok-2", …))`. Afterwards
      `file` is non-null with token `tok-2`, `failure.value?.message` is non-null,
      `attachmentDiscard` was never called, and `blocksSend` is false. (This replaces M1a
      PR2's `failedOutcomeReturnsTheMessageAndReleasesTheFreshToken`; delete that test.)
    - `whenTheCoreCannotRestageTheChipIsFailed`: `Failed(…, restaged = null)`. `file` is null,
      `failure` is set, and `blocksSend` is true.
    - `isPresentIsPerRoom`: staged in `!a`, so `isPresent("!b") == false`.
  - `SessionTest`:
    - `sendReturnsSentAndCollectsMentions`: text "hi @Atlas", with `mentioning =
      [Mentionable("@atlas:x", "Atlas")]`. The fake's `sendMessage` mentions are
      `["@atlas:x"]`, and the result is `SendResult.Sent`.
    - `aFailedAttachmentHoldsTheTextBack`: staged plus text. `attachmentSend` returns `Failed`, so the
      result is `AttachmentFailed` and `sendMessage` was never called.
    - `blankWithNothingStagedIsNothingToSend`.
  - `MentionComposingTest`:

    | Text | `activeQuery` |
    |---|---|
    | `"@"` | `Query("", 0)` |
    | `"hi @at"` | `Query("at", 3)` |
    | `"ana@example.org"` | `null` |
    | `"@atlas "` | `null` |
    | `"@" + "a".repeat(41)` | `null` |

    Also: `insert(atlas, Query("at", 3), "hi @at") == "hi @Atlas "`, and an astral character
    before the `@` keeps the UTF-16 offset correct: `"🧠 @a"` gives `atOffset == 3`.
  - `MentionCastTest`:
    - `membersFilterKnownPeople`: known `[atlas, priya, kai]`, members `[atlas, kai]`. The
      people are `[atlas, kai]`, in the core's order.
    - `unreadableMembersFallBackToEveryone`.
    - `candidatesAreCappedAtSix`.
- [ ] **Step 2: Run them and confirm they fail.**
  `./gradlew :kit:test --tests '*StagedAttachmentTest*' --tests '*SessionTest*' --tests '*Mention*'`.
- [ ] **Step 3: Implement** as quoted.
  - `Session.send` takes the shape of `Outbox.send`: `hasAttachment = staged.isPresent(roomId)`,
    and a failure returns `AttachmentFailed` before any text is sent.
  - `RoomPane` maps the results:
    - `Sent` → clear the text;
    - `AttachmentFailed` → `composerFailure = null`, because the reason is on the chip;
    - `TextFailed(m)` → `composerFailure = m`.
  - The edit-failure copy becomes iOS's **"Could not save the edit."**
    (`ComposerView.swift:277`), replacing "Couldn't save that edit."
- [ ] **Step 4: Falsify.**
  - Ignore `restaged` (leave `file` as it was): `aFailedSendKeepsTheFileTheCoreRestaged` fails.
  - Drop the whitespace-before-`@` rule: the `"ana@example.org"` row fails.
- [ ] **Step 5: Run the full verification.** The instrumented suite must stay green with the
  new result type.
- [ ] **Step 6: Commit.** Message: `kit: attachments remember failure; send results; mention composing and cast`.

## Task 9: C1 capsule composer, any-file attach, C3 thumbnail and errors on the chip

**iOS** (`Composer/ComposerView.swift`, `FloatingSurface.swift`):
- **One capsule** (`floatingSurface(cornerRadius: 22)`: `surfaceRaised` with a 1pt `border`
  and a `scrim` shadow at 10%, radius 10, y 3) holds, top to bottom:
  - the edit or reply strip;
  - the attachment chip;
  - the field row: "+", the field, and the trailing control (`:57-107`).
- **The failure line** sits below the capsule, in `danger` meta (`:87-93`).
- **The ground behind it** is a gradient from transparent `surface` to `surface` (`:100-107`).
- **"+" menu:** **"Photo"** and **"File"**. Its accessibility label is **"Attach"**
  (`:169-181`).
- **The field:** **"Message"**, or **"Edit message"** while editing, at 1–6 lines (`:146-149`).
- **The trailing control:** a send arrow labelled **"Send"**, or a check labelled **"Save
  edit"** while editing. It is disabled `if sending || !canSend` (`:219-237`).
- **`canSend`** (`:241-249`):
  - while editing, there must be text;
  - `blocksSend` → false;
  - otherwise text, or a present attachment.
- **The mic (C2) is M6.** Until then the send control always shows, disabled when there is
  nothing to send.
- **Strips** (`:551-617`):
  - **"Editing message"** with a pencil, and **"Replying to \(sender)"** plus an excerpt;
  - each has an xmark labelled **"Cancel"** with a 44 target.
- **Chip** (`:625-735`):
  - a 44×44 thumbnail, radius 9, for `image/*`, decoded downsampled;
  - otherwise an icon by MIME;
  - the filename, middle-truncated;
  - then the failure, as a danger label up to 3 lines, or the size;
  - a remove button labelled **"Remove attachment"**;
  - a `danger` border on failure.
- **Picking failures:** **"Couldn't read that photo."** (`:340`) and **"Couldn't read that
  file."** (`:373`), through `staged.refuse`.
- **The send haptic** is light, and fires only for a send that reached the room (`:110-113`,
  `:291`).
- **Attach stays visible while editing** (Decided: O2 — follow iOS, `ComposerView.swift:137-163`).
  The M0-deferred "hide while editing" item is dropped: do not hide "+" or the chip.

**Files:**
- Modify:
  - `A/Composer.kt` — rewritten around the capsule;
  - `A/RoomPane.kt` — the composer call;
  - `AT/ComposerTest.kt`.
- Create:
  - `A/Thumbnail.kt` (`BitmapFactory` with `inSampleSize` to at most 44dp×density, decoded
    on `Dispatchers.IO`);
  - `PV/ComposerCapsulePreviews.kt`.

**Interfaces:**
- Consumes the Task 8 `StagedAttachment` flows and `SendResult`.
- Produces:
  - `data class ChipState(val file: StagedFile?, val path: String?, val failure: StagedAttachment.Failure?)`.
  - `Composer(attachment: ChipState?, onAttach: (path: String) -> Unit, onRefuse: (filename: String, message: String) -> Unit, ...)`,
    replacing `attachment: StagedFile?`. `RoomPane` wires `onRefuse` to
    `session.staged.refuse(filename, message, currentRoomId)`.
  - Tags kept: `composer-text`, `composer-send`, `composer-attach`,
    `composer-attachment-chip`, `composer-attachment-name`, `composer-discard-attachment`,
    `composer-*-banner`, `composer-cancel-*` and `composer-failure`.
  - Tags added: `composer-capsule`, `composer-attach-photo`, `composer-attach-file`,
    `composer-chip-thumbnail` and `composer-chip-failure`.

- [ ] **Step 1: Write the failing tests** in `ComposerTest`.
  - `theCapsuleHoldsTheStripChipAndField`: a reply plus a staged file.
    `composer-reply-banner`, `composer-attachment-chip` and `composer-text` all have bounds
    inside `composer-capsule`.
  - `attachOffersPhotoAndFile`: click `composer-attach`. `composer-attach-photo` ("Photo")
    and `composer-attach-file` ("File") are displayed.
  - `anImageChipShowsItsThumbnail`: a `ChipState` with `mime = "image/png"` and a real 2×2 PNG
    written to `context.cacheDir`. `composer-chip-thumbnail` exists.
  - `aFailedChipSaysWhyAndHoldsSendBack`: `ChipState(file = null, path = null, failure =
    Failure("big.mov", "That file is too large to send."))`, with text "hi".
    `composer-chip-failure` has that text, and `composer-send` `assertIsNotEnabled()`.
  - `attachStaysVisibleWhileEditing` (O2): `editing` set and a chip present.
    `composer-attach` and `composer-attachment-chip` are both displayed.
  - `sendStaysOnScreenAtFontScaleTwo`: `requiredWidth(411.dp)` at `fontScale = 2f`, with
    text 200 characters long. `composer-send` `assertIsDisplayed()`, its right bound is at
    most the root's, and `composer-text`'s width is at least 120.dp.
  - `aDeliveredSendIsALightHaptic`: `AT/RosterReachabilityTest` through `AppRoot`, with a
    recording `HapticFeedback` and a fake `sendMessage` that succeeds. Type, then send: the
    haptics are `[ContextClick]`. With `sendMessage` throwing, they are `[]`.
  - Update `theCancelButtonStaysOnOneLineWithALongSenderName` (:209) to measure against
    `composer-cancel-reply`'s own one-line height. Keep its assertion.
- [ ] **Step 2: Run them and confirm they fail.**
- [ ] **Step 3: Implement.**
  - "File" uses `ActivityResultContracts.OpenDocument()` with `arrayOf("*/*")`.
  - "Photo" keeps `PickVisualMedia(ImageAndVideo)`.
  - Both go through `resolvePickedUri`. A `null` path calls `onRefuse(name, "Couldn't read
    that photo.")` for a photo, or `"Couldn't read that file."` for a file.
  - Icons come from `material-icons-core`:
    - `Icons.Filled.Add` for "+";
    - `Icons.AutoMirrored.Filled.Send` for send;
    - `Icons.Filled.Check` for save;
    - `Icons.Filled.Close` for cancel and remove;
    - `Icons.Filled.Edit` for the edit strip.
- [ ] **Step 4: Falsify.**
  - Remove the `blocksSend` clause: `aFailedChipSaysWhyAndHoldsSendBack` fails.
  - Hide `composer-attach` while editing: `attachStaysVisibleWhileEditing` fails.
- [ ] **Step 5: Run the full verification** at both heights.
  - `EdgeToEdgeTest` and `KeyboardDismissTest` must stay green: they read `composer-text`.
  - Re-record the `Composer*` frames after inspection.
  - Add these previews:
    - `ComposerCapsuleEmpty`;
    - `ComposerReplyingWithChip`;
    - `ComposerImageChip` (a fixture PNG under `PV` resources);
    - `ComposerChipFailed`;
    - `ComposerEditing`.
- [ ] **Step 6: Commit.** Message: `android: capsule composer, any-file attach, chip thumbnail and errors (C1, C3)`.

## Task 10: C4 — the `@` picker

**iOS:**
- **The picker shows** when the field is focused and not recording, and `activeQuery` is
  non-nil and has candidates. It sits above the capsule, on `floatingSurface(cornerRadius:
  16)` (`ComposerView.swift:59-67`, `:253-256`).
- **Each row** (`:508-549`):
  - the initial in an `accentSoft` circle of 30, drawn in `accent`;
  - the name;
  - for an agent, **"\(harness) on \(host)"** in meta;
  - **"Agent"** as a tag (`FloatingSurface.swift:54-63`);
  - a minimum height of 44;
  - an accessibility label of **"\(name), agent"** for an agent, or the name otherwise.
- **A pick** replaces the query via `insert`.
- **The cast loads** for the room on `roomId` (`:114-117`, `:258-264`), from `session.people()`
  and `roomInfo(roomId).members`.
- **The send** passes `cast.mentionables` (`:286`).

**Files:**
- Modify:
  - `A/Composer.kt` — `MentionPicker` and the focus state;
  - `A/RoomPane.kt` — the cast per room, and sending with `mentionables`.
- Create: `AT/MentionPickerTest.kt`, and `PV` `MentionPicker`.

**Interfaces:**
- Consumes `MentionComposing`, `MentionCast` and `Session.send(..., mentioning)` (Task 8).
- Produces:
  - `Composer(mentionCandidates: (query: String) -> List<PersonDto> = { emptyList() })`.
  - `@Composable internal fun MentionPicker(people: List<PersonDto>, onPick: (PersonDto) -> Unit)`,
    tagged `mention-picker`, with rows tagged `mention-${userId}`.

- [ ] **Step 1: Write the failing tests.**
  - `typingAtOffersTheRoomsPeopleAgentsFirst`: candidates `[atlas (runtime harness "claude",
    host "ci"), priya]`. Focus the field and type "@a". `mention-picker` is displayed, with the
    text "claude on ci" and the text "Agent". `mention-@atlas:x` has the `contentDescription`
    "Atlas, agent".
  - `pickingInsertsTheNameAndCloses`: click `mention-@atlas:x`. The field text is "@Atlas ",
    and `mention-picker` does not exist.
  - `anEmailAddressOpensNothing`: type "ana@ex". There is no `mention-picker`.
  - `theCastReloadsForTheNewRoom` (Review Focus 4): through `AppRoot` with `FakeCore`.
    - Room `!a` has members `[atlas]`; room `!b` has `[priya]`.
    - Open `!a`, type "@", and see `mention-@atlas:x`.
    - Open `!b`, type "@": `mention-@atlas:x` does not exist and `mention-@priya:x` does.
  - `aPickedMentionIsSent`: through `AppRoot`, pick Atlas and send. `FakeCore.sendMessage`
    received `mentions == ["@atlas:x"]`.
- [ ] **Step 2: Run them and confirm they fail.**
- [ ] **Step 3: Implement.**
  - `RoomPane` holds `remember(currentRoomId) { MentionCast() }`.
  - It loads the cast in `LaunchedEffect(currentRoomId)` with `session::people` and
    `{ runCatching { session.roomInfo(id).members.map { it.userId } }.getOrNull() }`.
  - The picker animates with `motion(reducesMotion(), tween(200))`.
- [ ] **Step 4: Falsify.**
  - Key `MentionCast` on `Unit` instead of the room: `theCastReloadsForTheNewRoom` fails.
  - Pass `emptyList()` as `mentioning`: `aPickedMentionIsSent` fails.
- [ ] **Step 5: Run the full verification.** Record `MentionPicker` after inspection.
- [ ] **Step 6: Commit.** Message: `android: @ picker over the room's people (C4)`.

## Task 11: Roster chrome — spaces menu, expandable invitations row, search scope — touches `MainActivity.kt`

**iOS:**
- **`SpaceMenu`** (`Rooms/SpaceMenu.swift:12-91`):
  - With no spaces, the title is **"Chats"**.
  - Otherwise a menu offers **"All spaces · \(allCount)"**, then per space **"\(name) ·
    \(childCount)"**, or **"\(name) · Invitation"** for an invited space, with no count.
  - The title is the selected space's name, or "Chats", with a chevron.
  - Its accessibility label is **"Space: \(title)"** and its hint **"Shows the rooms of one
    space"**.
  - `allCount = session.rooms.rooms.count` (`RoomListView.swift:175-177`).
  - **Readable names (`SpaceNames.display`) are M1.6.** This task shows
    `space.identity.name` as it is.
- **The invitations row** (`RoomListView.swift:122-136`, `:355-390`):
  - **"1 invitation"** or **"\(count) invitations"**, with an envelope icon in `accent` and a
    chevron that rotates 180° when open;
  - hint **"Hides them"** or **"Shows them"**;
  - the open state is **not stored** (`:27-30`);
  - when open, the invitation rows are drawn directly under it (`:69-82`);
  - the core's sections are always built with `showsInvitations: false`.

  Android has the text "N invitations hidden" (`Roster.kt:82-98`) and a stored preference.
- **Search scope:** opened with a room selected, search offers that room, narrowed by
  default (`SplitShell.swift:121-127`, `SearchPanel.swift:42-70`). Android always passes
  `scope = null` (`MainActivity.kt:579`).

**Files:**
- Create:
  - `A/RosterPane.kt`, holding the body of `MainActivity.kt:219-290` moved;
  - `A/SpaceMenu.kt`;
  - `AT/SpaceMenuTest.kt`;
  - `PV/RosterChromePreviews.kt`.
- Modify:
  - `A/MainActivity.kt` — `listPaneContent`, the `SearchPanel` scope, and the `ConnectionBar`
    call (moved under the roster title);
  - `AT/ConnectionBarTest.kt`, `PV/ConnectionBarPreviews.kt` — only if they assume the old
    position;
  - `A/Roster.kt:57-99` — `invitations: List<RosterRow>`, `invitationsOpen` and
    `onToggleInvitations`, replacing `hiddenInvitations` and `onRevealInvitations`;
  - `A/RosterPreferences.kt` — remove `showsInvitations`, `setShowsInvitations` and the key;
  - `AT/RosterTest.kt:121-176`;
  - `AT/RosterReachabilityTest.kt:73-76, 145-213` — the invitation tests open the row instead
    of seeding the preference;
  - `PV/RosterPreviews.kt:97-135`.

**Interfaces:**
- Consumes `SpacesStore.spaces`, `selectedId`, `select(id)` and `isInvitation(space)`
  (`K/stores/SpacesStore.kt`).
- Produces:
  - `@Composable internal fun RosterPane(session: Session, prefs: RosterPreferences, rooms: List<RoomRow>, avatarCache: Map<String, String>, now: Instant, openDetail: () -> Unit, openPanel: (ExtraPanel) -> Unit)`.
  - `@Composable fun SpaceMenu(spaces: List<SpaceSummary>, selectedId: String?, allCount: Int, isInvitation: (SpaceSummary) -> Boolean, onSelect: (String?) -> Unit)`,
    tagged `space-menu`, with items tagged `space-all` and `space-${id}`.
  - `internal fun invitationsLabel(count: Int): String`.
  - `Roster(invitations: List<RosterRow>, invitationsOpen: Boolean, onToggleInvitations: () -> Unit, ...)`,
    with the row tagged `invitations-row`.

- [ ] **Step 1: Write the failing tests.**
  - `SpaceMenuTest`:
    - `noSpacesIsAPlainTitle`: the text "Chats" is displayed, with no click action.
    - `spacesOfferAllAndEach`: spaces `[Ops (3 children), Lab (invited)]`, `allCount = 5`.
      Click `space-menu`, and assert "All spaces · 5", "Ops · 3" and "Lab · Invitation" are
      displayed.
    - `choosingASpaceSelectsIt`: click `space-${opsId}`. `onSelect(opsId)` ran.
    - `theTitleNamesTheSelectedSpace`: `selectedId = opsId`. `space-menu` has the
      `contentDescription` "Space: Ops".
  - `RosterTest`:
    - `invitationsAreARowThatOpensInPlace`: two invitation rows, closed. The text "2
      invitations" is displayed and no invitation room name exists. Click `invitations-row`:
      both names are displayed, and their bounds lie below `invitations-row` and above the
      first section header.
    - `oneInvitationIsSingular`: `invitationsLabel(1) == "1 invitation"`.
  - `RosterReachabilityTest`:
    - `searchOpenedFromARoomOffersThatRoom`: open room "Atlas", then click
      `roster-open-search`. `search-scope-room` has the text "Atlas" and is selected.
    - `theConnectionLineSitsUnderTheRosterTitle` (O4): with `Connection.Offline`,
      `connection-bar` shows "Offline" and its top bound is at or below the title's bottom
      bound; with `Connection.Live` no `connection-bar` text exists.
    - `invitationsOpenAfterRestartAreClosed`: open the row, recompose `AppRoot` from a fresh
      `RosterPreferences` over the same store, and assert it is closed.
- [ ] **Step 2: Run them and confirm they fail.**
- [ ] **Step 3: Implement.**
  - The title row sits above the existing "New room / Search / Account" buttons. The M0
    connection strip moves **under the title** (Decided: O4 — one line, like iOS,
    `SpaceMenu.swift:28-39`): `ConnectionBar` is composed directly beneath the title (or
    `SpaceMenu`), with "Connecting…", "Offline", "Reconnecting…" and nothing when live. Remove
    it from its old place above the roster in `MainActivity.kt`.
  - `invitationsOpen` is `rememberSaveable { mutableStateOf(false) }` in `RosterPane`.
  - `sections` uses `showsInvitations = false`. `invitations` is
    `RosterArrangement.sections(rooms, view, true, now).flatMap { it.rows }.filter { it.row.affordance == RESPOND_TO_INVITATION }`.
- [ ] **Step 4: Falsify.**
  - Build the sections with `showsInvitations = true`: the bounds assertion in
    `invitationsAreARowThatOpensInPlace` fails.
  - Pass `scope = null` again: `searchOpenedFromARoomOffersThatRoom` fails.
  - Compose `ConnectionBar` above the title again: `theConnectionLineSitsUnderTheRosterTitle`
    fails.
- [ ] **Step 5: Run the full verification.** Record these previews:
  - `SpaceMenuThreeSpaces`;
  - `SpaceMenuNone`;
  - `InvitationsRowClosed`;
  - `InvitationsRowOpen`.

  Re-record `RosterPreviews` frames only where the invitation line changed.
- [ ] **Step 6: Commit.** Message: `android: spaces menu, invitations row, search scope; RosterPane out of MainActivity`.

## Task 12: R2 — roster swipes: mark read, mute, pin, decline

**iOS** (`Rooms/RoomListView.swift:260-327`):
- **Leading, full swipe allowed, when `affordance == .compose`:** **"Mark read"**, tinted
  `accent`. It calls `session.rooms.markRead(id)`, which returns `Bool`.
- **Trailing, no full swipe:**
  - For an invitation: **"Decline"** (destructive), which is `leaveRoom`.
  - For `.compose`:
    - **"Mute"** or **"Unmute"**, tinted `contentMuted`;
    - **"Pin"** or **"Unpin"**, tinted `ok`.
- **The label** reads what the last swipe learned (`KnownSettings`), never a guess.
- **A toggle** first asks the core: `roomInfo(id)`, then
  `RoomToggles.nextNotificationMode(from: info.notifications)`. That is `muted → default`,
  otherwise `muted` (`SupermessageKit/RosterViews.swift:173-180`). Then `setNotifications` or
  `setPinned`.
- **A landed swipe** fires the success haptic (`:170`, `:302-327`).
- **Invitation rows swipe the same way** (`:239-242`).

**Files:**
- Modify:
  - `K/stores/RoomsStore.kt`, beside `row(roomId)` at :99;
  - `A/Roster.kt:109-135` — the row wrapper;
  - `A/RosterPane.kt` — the toggles and the `KnownSettings` map, keyed per session.
- Create:
  - `K/RoomToggles.kt`;
  - `A/SwipeActions.kt`;
  - `AT/RosterSwipeTest.kt`;
  - `KT/RoomTogglesTest.kt`.
- Test: `KT/stores/RoomsStoreTest.kt`.

**Interfaces:**
- Produces:
  - `suspend fun RoomsStore.markRead(roomId: String): Boolean`, over the existing
    `CoreClient.markRoomRead`.
  - `object RoomToggles { fun nextNotificationMode(current: NotificationMode): NotificationMode }`.
  - `data class SwipeAction(val label: String, val tint: Color, val destructive: Boolean = false, val run: suspend () -> Boolean)`.
  - `@Composable fun SwipeActionsRow(leading: SwipeAction?, trailing: List<SwipeAction>, content: @Composable () -> Unit)`.
    - It is built on `AnchoredDraggableState`, with anchors Closed = 0 and Trailing =
      −72dp × n.
    - A leading pull past 50% of the width runs `leading` and settles closed.
    - Action buttons are tagged `swipe-${label}`.
    - Each action is also exposed as a `CustomAccessibilityAction(label)`.
    - A `run()` that returns `true` plays `Haptic.Success`.

- [ ] **Step 1: Write the failing tests.**
  - `KT`:
    - `nextNotificationMode(MUTED) == DEFAULT`;
    - `nextNotificationMode(DEFAULT) == MUTED`;
    - `nextNotificationMode(ALL) == MUTED`;
    - `markReadReportsWhetherItLanded`: the fake throws, so the result is `false`.
  - `AT/RosterSwipeTest`, with a recording `HapticFeedback`:
    - `swipingRightMarksRead`: `swipeRight()` on a compose row. `markRead("!a")` ran, and the
      haptics are `[Confirm]`.
    - `swipingLeftOffersMuteAndPin`: `swipeLeft(endX = right - 160dp)`. `swipe-Mute` and
      `swipe-Pin` are displayed.
    - `muteAsksTheCoreFirstThenSaysUnmute`: `roomInfo` returns `notifications = DEFAULT`.
      Click `swipe-Mute`, and `setNotifications(MUTED, "!a")` ran. Swipe again: `swipe-Unmute`
      is displayed.
    - `muteSwipeDoesNothingWhenRoomInfoFails` (Review Focus 5): `roomInfo` throws. Click
      `swipe-Mute`. `setNotifications` was never called, the haptics are `[]`, and swiping
      again shows `swipe-Mute`.
    - `anInvitationSwipesToDecline`: an invitation row. `swipeLeft()` shows `swipe-Decline`,
      and a click calls `leaveRoom("!inv")`.
    - `swipeActionsAreReachableWithoutAGesture`: `performSemanticsAction(CustomActions)` on
      the row's "Pin" action calls `setPinned(true, "!a")`.
- [ ] **Step 2: Run them and confirm they fail.**
- [ ] **Step 3: Implement** as quoted.
  - The `KnownSettings` map is `remember(session) { mutableStateMapOf<String, KnownSettings>() }`,
    so a new sign-in starts empty.
  - The tints use the role tokens named above. None is amber.
- [ ] **Step 4: Falsify.**
  - Toggle from `KnownSettings` without asking `roomInfo`: `muteAsksTheCoreFirstThenSaysUnmute`
    passes but `muteSwipeDoesNothingWhenRoomInfoFails` fails.
  - Play the haptic before `run()`: the haptic assertion fails.
- [ ] **Step 5: Run the full verification** at both heights. Record `RosterRowSwipedTrailing`
  and `RosterRowSwipedLeading`, as previews with a fixed anchor.
- [ ] **Step 6: Commit.** Message: `android: swipe a room to mark read, mute, pin or decline (R2)`.

## Task 13: Account panel — sections, Chats settings, help links — touches `MainActivity.kt`

**iOS** (`Panels/AccountPanel.swift:51-203`):
- **Sections in order:**
  1. **"Signed in as"**: a face, the name, the user id, and **"Homeserver"**.
  2. **"Appearance"**: Task 14.
  3. **"Chats"**:
     - **"Order rooms by"**, with **"Waiting first"** or **"Most recent"**, over
       `RosterChoice.offered = [.recent, .waiting]`;
     - **"Agent state"** as a toggle;
     - footer **"Waiting first puts what needs an answer at the top. Agent state is the dot
       and word beside an agent's name."**
  4. **"Help & support"**, **"Terms of use"** and **"Privacy"**, linking to
     `https://supermessage.dev/support/`, `/terms/` and `/privacy/` (`:196-202`).
  5. **"Encryption recovery"**.
  6. **"Sign out"**, with the footer **"Signing out removes this account and its messages from
     this device."**
- **A stored arrangement that is no longer offered** (Machine) reads as Waiting
  (`RoomListView.swift:46-52`).
- **Omitted until M3:**
  - the Blocked section;
  - the help footer **"To report abuse, long-press a message or open a room's info. Reports go
    to your homeserver's administrators."** (`:143`), because it describes report, which
    Android does not have yet.

**Files:**
- Modify:
  - `A/Account.kt:69-221` — sections and a `verticalScroll`;
  - `A/MainActivity.kt:592-604` — new parameters;
  - `A/RosterPane.kt` — reads the mapped view;
  - `K/RosterArrangement.kt:20-32`.
- Test:
  - `AT/AccountTest.kt`;
  - `KT/RosterArrangementTest.kt`;
  - `UT/RosterPreferencesTest.kt`.

**Interfaces:**
- Produces:
  - `RosterChoice.Companion.offered: List<RosterChoice> = listOf(RECENT, WAITING)`.
  - `fun RosterChoice.Companion.offeredOrDefault(stored: RosterChoice): RosterChoice`.
  - `AccountPanel(rosterView: RosterChoice, onRosterView: suspend (RosterChoice) -> Unit, showsState: Boolean, onShowsState: suspend (Boolean) -> Unit, appearance: @Composable () -> Unit = {}, ...)`.
  - Tags: `account-order-waiting`, `account-order-recent`, `account-agent-state` and
    `account-link-support|terms|privacy`.

- [ ] **Step 1: Write the failing tests.**
  - `KT`: `offeredOrDefault(MACHINE) == WAITING`, and `offeredOrDefault(RECENT) == RECENT`.
  - `AccountTest`:
    - `chatsSettingsChangeTheRoster`: click `account-order-recent`. `onRosterView(RECENT)`
      ran. Toggle `account-agent-state` off: `onShowsState(false)`.
    - `helpLinksOpenTheSite`: with a recording `UriHandler` provided through
      `LocalUriHandler`, `performScrollTo()` and then click `account-link-privacy`. The
      handler received "https://supermessage.dev/privacy/".
    - `signOutIsReachableAtTheShortHeight`: in `requiredWidth(411.dp)`, `performScrollTo()`
      on `account-sign-out`, then click it. `account-sign-out-confirm` is displayed. This must
      pass at 1080x1280.
    - `theFootersAreIosWords`: the text "Waiting first puts what needs an answer at the top.
      Agent state is the dot and word beside an agent's name." exists.
  - `RosterReachabilityTest`: `aStoredMachineArrangementReadsAsWaiting`. Seed `setView(MACHINE)`.
    The roster shows the Waiting sections, and `account-order-waiting` is selected.
- [ ] **Step 2: Run them and confirm they fail.**
- [ ] **Step 3: Implement.**
  - "Order rooms by" is two `RadioButton` rows, in `Modifier.selectableGroup()`. "Agent
    state" is a `Switch` row.
  - Section headers use `labelMedium` in `contentMuted`. Footers use `bodySmall` in
    `contentFaint`.
  - `hidesHost = view == MACHINE` in `RosterPane` stays true to the mapped view, so it is now
    always `false`. Delete it, with its `Roster` parameter, if nothing else sets it, and say so
    in the report.
- [ ] **Step 4: Falsify.** Make `offeredOrDefault` return its argument:
  `aStoredMachineArrangementReadsAsWaiting` fails.
- [ ] **Step 5: Run the full verification** at both heights. Re-record the `Account` frame
  (in `NEVER_SETTLES`) after inspection.
- [ ] **Step 6: Commit.** Message: `android: account panel with chats settings and help links`.

## Task 14: Theme chooser — System/Light/Dark, Tinted/Black, accents — touches `MainActivity.kt`

**iOS** (`Appearance.swift:11-116`, `AccountPanel.swift:82-96`, `:221-262`):
- **The modes** are **"System"**, **"Light"** and **"Dark"**, as a segmented control
  labelled **"Appearance"**.
- **Dark style:** **"Tinted"** or **"Black"**.
- **Accents** are a row of swatches. The house violet comes first, as `""`, labelled
  **"Violet"**; the rest are the `ThemeAccents.names`, capitalized.
  - Each swatch is 30, in a 44 minimum target, drawn in its own colour for the current
    appearance.
  - The selection ring is `content` at 2pt.
  - The row's accessibility label is **"Accent"**.
- **The footer:** **"Black is true black in dark mode, for OLED screens. The accent colours
  buttons, links and your own highlights."**
- **The palette** (`ThemeChoice.palette`, `Appearance.swift:61-66`):
  - light uses `accent?.paper ?: paper`;
  - dark with Black uses `accent?.black ?: black`;
  - dark otherwise uses `accent?.dark ?: dark`.
- **Stored keys:** `appearance.mode`, `appearance.darkStyle` and `appearance.accent`, with
  `""` for violet (`:111-116`).
- **The first frame** is drawn in the stored choice (`:79-94`).
- **Android today** binds light to `paper` and dark to `dark`, and offers no choice
  (`Theme.kt:117-121`). `GeneratedAccents` (`GeneratedThemeTokens.kt:146-519`) and
  `GeneratedThemeTokens.black` (`:121`) are generated but unused.

**Files:**
- Create:
  - `A/AppearancePreferences.kt` (a DataStore named `"appearance"`);
  - `A/AppearanceSettings.kt` (the section composable);
  - `UT/AppearancePreferencesTest.kt`;
  - `UT/ThemePaletteTest.kt`.
- Modify:
  - `A/Theme.kt:116-211`;
  - `A/MainActivity.kt:62-81` — read the preferences before `SupermessageTheme`, and set the
    system-bar style for the chosen scheme;
  - `A/Account.kt` — the `appearance` slot;
  - `AT/ThemeTest.kt`.
- Previews: add `AccountAppearance`, `ThemeBlackDark` and `ThemeBlueLight`.

**Interfaces:**
- Produces:
  - `enum class AppearanceMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }`.
  - `enum class DarkStyle(val label: String) { TINTED("Tinted"), BLACK("Black") }`.
  - `data class ThemeChoice(val mode: AppearanceMode, val darkStyle: DarkStyle, val accent: String?)`.
  - `fun ThemeChoice.palette(dark: Boolean): SupermessageColorRoles`.
  - `fun ThemeChoice.peers(dark: Boolean): List<Color>` — Black uses `GeneratedPeers.black`.
  - `internal fun accentSet(name: String): Map<String, SupermessageColorRoles>?`, over
    `GeneratedAccents.blue`, `graphite`, `green`, `pink` and `teal`.
  - `class AppearancePreferences(store: DataStore<Preferences>) { val choice: Flow<ThemeChoice>; suspend fun setMode(AppearanceMode); suspend fun setDarkStyle(DarkStyle); suspend fun setAccent(String?) }`.
    Unknown stored values fall back to `SYSTEM`, `TINTED` and `null`.
  - `SupermessageTheme(choice: ThemeChoice = ThemeChoice(SYSTEM, TINTED, null), content)`
    replaces the `darkTheme` parameter. A `darkTheme: Boolean` overload is kept for the
    existing tests and previews.
  - Tags: `appearance-mode-system|light|dark`, `appearance-dark-tinted|black` and
    `accent-<name|violet>`.

- [ ] **Step 1: Write the failing tests.**
  - `ThemePaletteTest`:
    - `ThemeChoice(LIGHT, BLACK, null).palette(false) == GeneratedThemeTokens.paper`;
    - `(DARK, BLACK, null)` → `black`;
    - `(DARK, TINTED, "blue")` → `GeneratedAccents.blue["dark"]`;
    - `(DARK, BLACK, "teal")` → `GeneratedAccents.teal["black"]`;
    - an unknown accent `"mauve"` falls back to the house palette.
  - `AppearancePreferencesTest`:
    - a round trip of each setter;
    - `anUnknownModeFallsBackToSystem`;
    - `emptyAccentIsViolet`, where the stored `""` reads as `null`.
  - `ThemeTest`:
    - `choosingDarkPaintsTheSurfaceDarkOnALightPhone`: `SupermessageTheme(choice =
      ThemeChoice(DARK, TINTED, null))`, read `SupermessageTheme.colors.surface`, and assert it
      equals `GeneratedThemeTokens.dark.surface`.
    - `blackIsTrueBlack`: the surface is `Color.Black`.
    - `theAccentReachesMaterialPrimary`: `MaterialTheme.colorScheme.primary ==
      GeneratedAccents.green["paper"]!!.accent`.
  - `AccountTest`: `choosingAnAccentStoresIt`. Click `accent-pink`, and `setAccent("pink")`
    ran. `accent-violet` has the `contentDescription` "Violet".
- [ ] **Step 2: Run them and confirm they fail.**
- [ ] **Step 3: Implement.**
  - `onCreate` collects `choice` with `collectAsStateWithLifecycle(initialValue = null)` and
    draws nothing until it is non-null. That is the iOS "first frame is the stored choice".
  - `enableEdgeToEdge(SystemBarStyle.auto(...))` is re-applied in a `DisposableEffect(dark)`
    with `SystemBarStyle.dark` or `SystemBarStyle.light` as the choice requires.
- [ ] **Step 4: Falsify.**
  - Swap `black` and `dark` in `palette`: `blackIsTrueBlack` fails.
  - Ignore `mode` and keep `isSystemInDarkTheme()`:
    `choosingDarkPaintsTheSurfaceDarkOnALightPhone` fails.
- [ ] **Step 5: Run the full verification.** Record the three new previews after inspection.
  No existing frame may change, because the default choice is the old palette. Any frame that
  does change is a bug.
- [ ] **Step 6: Commit.** Message: `android: appearance, dark style and accent chooser`.

## Task 15: M1 motion and M2 haptics — the remaining sites, and the system setting itself

**iOS:**
- **Arrival:** a new message rises 16pt and fades in on a spring, 0.38 s with damping 0.78.
  Under Reduce Motion it fades in over 0.15 s with no rise (`TimelineCollectionView.swift:1167-1199`).
  Android uses `Modifier.animateItem()` with default specs (`Timeline.kt:404-412`). Under
  scale 0 every spec is `null`, so the item snaps.
- **The live turn finishing** is a success haptic, `!was && now`
  (`Timeline/TimelineView.swift:143`).
- **A voice note's play toggle** is a selection haptic (`VoiceNoteView.swift:168`). On Android
  that is `VoiceNote.kt:113`, `:143`.
- **Cards expand:**
  - the live card's "Reasoning" disclosure;
  - the decision card's Details and Reasoning (done in Task 7).

  Android's `LiveTurn` reasoning toggle (`LiveTurn.kt`, `thought-toggle`) is an instant
  `if`. It becomes `AnimatedVisibility` with `motion(...)`.
- **Already covered elsewhere:** Tasks 2, 7, 9 and 12 wired the reaction, decision, send and
  swipe haptics. Tasks 3, 5, 7 and 10 wired their own motion.

**Files:**
- Modify:
  - `A/Timeline.kt:398-414`;
  - `A/LiveTurn.kt`;
  - `A/VoiceNote.kt:113`, `:143`.
- Modify `A/RoomPane.kt` only if the finished-turn haptic needs `liveFinished`; it already
  collects it at the old `MainActivity.kt:367`.
- Create `AT/MotionAndHapticsTest.kt`.

**Interfaces:**
- Consumes `reducesMotion()`, `motion()`, `Haptic` and `HapticFeedback.play` (Tasks 2 and 3).

- [ ] **Step 1: Write the failing tests** in `MotionAndHapticsTest`.
  - `aFinishedTurnIsASuccessHapticOnce`: `Timeline` with `liveFinished` going `false` →
    `true` → `true` (a recomposition). The haptics are `[Confirm]`. A turn that starts
    finished, as when a room is opened, records `[]`.
  - `playingAVoiceNoteIsASelection`: click the note's play control. The haptics include
    `SegmentTick`.
  - `reasoningExpandsWithoutMotionWhenReduced`: `LocalReduceMotion provides true`,
    `mainClock.autoAdvance = false`. Click `thought-toggle`, then
    `mainClock.advanceTimeByFrame()`. The height of `thought-body` equals its height after
    `mainClock.advanceTimeBy(1_000)`.
  - `reasoningAnimatesWhenNotReduced`: the same with `false`. After one frame the height is
    below its settled height.
  - `systemReduceMotionFollowsTheAnimatorScale` is the one test that reads the device.
    1. Through `InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand`,
       read the current `animator_duration_scale`.
    2. Set it to `1`. Compose `Text(reducesMotion().toString())` and assert "false".
    3. Set it to `0`. Trigger an `ON_RESUME` via `ActivityScenario.moveToState(RESUMED)`, and
       assert "true".
    4. Restore the value read in step 1 in `@After`.
- [ ] **Step 2: Run them and confirm they fail.**
- [ ] **Step 3: Implement.**
  - `animateItem(fadeInSpec = motion(reduce, spring(dampingRatio = 0.78f)), placementSpec = if (reduce) null else spring(), fadeOutSpec = motion(reduce, tween(150)))`.
  - The finished-turn haptic is a `LaunchedEffect(liveFinished)` that remembers the previous
    value and fires only on `false → true`.
- [ ] **Step 4: Falsify.**
  - Fire on any `true`: the "starts finished" half of `aFinishedTurnIsASuccessHapticOnce`
    fails.
  - Make `motion()` return `full` always: `reasoningExpandsWithoutMotionWhenReduced` fails.
- [ ] **Step 5: Run the full verification** at both heights. CI-parity scales are 0, so the
  suite runs in the same mode as CI. Then run `MotionAndHapticsTest` once more with
  `adb shell settings put global animator_duration_scale 1`, to prove the reduce branch is
  not the only one exercised. Restore the scale to 0 afterwards.
- [ ] **Step 6: Commit.** Message: `android: motion honours Reduce Motion; turn-finished and voice haptics`.

---

## Moved to core (M1/M3) — not planned here

| Item | Why it is not M2 | Where it goes |
|---|---|---|
| Header status pill (Working / Needs you / Active / Idle / Quiet) | `RoomStatus` is Swift Kit (`RosterViews.swift`). The roadmap excludes it. | M1.1, then M4 |
| Readable space names ("Runtime 9247e5", `.local` trimmed) in the spaces menu and title | `SpaceNames.display` is a Swift Kit rule (`SupermessageKit/SpaceNames.swift:15-42`). Task 11 shows `identity.name` as it is. | M1.6 |
| The `@` cast's counterpart, addressee and typing cast (one name per person in the picker and typing line) | `RoomCast.counterpart` and `addressee` (`RoomCast.swift:64-101`) are moving to core. Task 8 ports only the member-filtered join and `peopleMatching`, to be replaced by the core cast. | M1.2 |
| "Agent" badge on sender headers, the undecryptable header and the menu preview; long-read "Read" | `TimelineGrouping.isAgent` and `isLongRead` are Swift Kit (`TimelineGrouping.swift:261,277`). | M1.4, then M4 (T1) |
| Hiding a reply quote that repeats the row above (D6) | `quoteRepeatsPrevious` is Swift Kit. | M1.4 |
| An expandable membership stretch (tap to show each change) | Cross-verb churn collapse (D5) is three copies today. | M1.4 |
| `RosterArrangement.rosterRow` (Task 1) | A thin call over the existing core `rosterSections`, so no new rule. It lives in Swift `RosterViews.swift:115`, which M1.1 moves. Replace it with the core version when M1.1 lands. | M1.1 (switch-over) |
| "Report…" in the message menu, the Account help footer about reporting, and the Blocked section | They need `CoreClient` wrappers (`reportEvent`, `ignoreUser`, …). The roadmap assigns them to M3. | M3 |
| The mic, and send ⇄ mic in the capsule | C2 voice recording. | M6 |

No item in the M2 Android list needs a new core rule or a new FFI export. Everything above is
either explicitly later in the roadmap, or a part of an M2 item that iOS draws from a Swift Kit
rule M1 is moving.

## M2 Android exit

| Row | Task | Verified by |
|---|---|---|
| Room header (name, member count, tap → info; no avatar, O2) | 1 | `RoomHeaderTest`, `theRoomHeaderOpensRoomInfo`; a device pass on the phone AVD |
| Message menu (react, reply, copy, edit, delete with a confirm), "+" on tap, who reacted | 2 | `MessageMenuTest`, `TimelineRowTest` |
| Swipe to reply; T4 times | 3 | `TimelineSwipeTest` ×3 runs |
| T2, T3, New line, undecryptable hint | 4 | `TimelineRowTest` |
| T5 | 5 | `TimelineTest` |
| Markdown while streaming | 6 | `StreamingMarkdownTest`, `LiveTurnTest` |
| G1, D7, failure | 7 | `DecisionCardTest`, `DecisionDigestTest` |
| C1, any-file, C3, attach visible while editing (O2) | 8, 9 | `StagedAttachmentTest`, `ComposerTest` |
| C4 | 10 | `MentionPickerTest`, `MentionComposingTest` |
| Spaces menu, invitations row, search scope, connection line under the title (O4) | 11 | `SpaceMenuTest`, `RosterTest`, `RosterReachabilityTest` |
| R2 | 12 | `RosterSwipeTest` |
| Account → Chats, help links | 13 | `AccountTest` |
| Theme chooser | 14 | `ThemePaletteTest`, `ThemeTest` |
| M1 motion with Reduce Motion; M2 haptics | 2, 3, 5, 7, 9, 12, 15 | `MotionAndHapticsTest` and the per-task haptic assertions |
| M0 deferred: DecisionCard failed answer; `MainActivity` import order (the "hide Attach while editing" item is dropped, O2) | 7, 1 | `aFailedAnswerKeepsTheCardAnswerableAndSaysSo`, the Task 1 diff |

**After the last task, on the phone AVD and once on the tablet AVD:**
1. Open a room and tap the header.
2. Long-press a message and delete it with the confirm.
3. Swipe a message right, then the list left.
4. Tap a quote.
5. Answer a gate offline (expect "Not sent. Try again."), then online (expect "Approved by
   you · just now").
6. Attach a PDF and an image; start an edit and check Attach is still there.
7. `@`-mention an agent.
8. Switch space.
9. Open the invitations row.
10. Swipe-mute a room.
11. Switch to Black and an accent.
12. Set "Remove animations" and repeat step 4.

Report each step as seen or not seen. The device pass is reported separately from CI.

## Decisions (formerly open questions)

1. Header avatar. Decided: O2 — follow iOS; the Android room header has no avatar.
2. Attach while editing. Decided: O2 — Attach and the chip stay visible while editing on all
   platforms; the M0-deferred "hide while editing" item is dropped.
3. Connection status placement. Decided: O4 — one line under the roster title, like iOS; Task 11
   moves the M0 strip there.
4. Visible failure copy with no iOS source. Decided: R1 — "Not sent. Try again." stays on
   Android and desktop; iOS is unchanged (it uses an error haptic).
5. Retrying a failed attachment. Decided: M1.7 (ruling 11) — iOS re-stages (the M0 correction was
   wrong); the core now does it and returns `restaged`; Task 8 consumes the outcome and waits
   for M1a PR2. Desktop does the same in its Task 5a.
