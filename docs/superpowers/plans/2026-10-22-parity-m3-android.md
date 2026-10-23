# Parity M3 (Android) — Block and report: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task by task. Steps use
> checkbox (`- [ ]`) syntax for tracking. Each task is a bundle: one implementer dispatch, targeted
> tests while iterating, the full suites once before the commit, one review.

**Goal:** turn the Android safety rows of the parity matrix ✅ — block and unblock, the blocked
list in Account, and report for a message, a room and a person with iOS's reason sheet that hides
the reported message — and align the one room-settings string that still differs from iOS (the
Leave confirm). Every other Android safety, recovery and room-settings row is already ✅ or is
closed by M2 (see "Excluded").

**Architecture:**
- **All work is in `android/`. No FFI change, so the bindings are untouched.** Every call this plan
  makes is already in the generated Kotlin (`android/core/.../supermessage_ffi.kt`):
  `ignoreUser`, `unignoreUser`, `ignoredUsers`, `reportEvent`, `reportRoom`, `reportUser`
  (`CoreInterface`), and the free function `reportReasonRemaining` (`:7213`). `StubCore` already
  overrides them with refusals.
- **Kit** (`android/kit`, JVM-testable, no Compose): `CoreClient` wrappers, a `SafetyStore` (the
  core's blocked list plus this device's hidden messages), `ReportSubject`, and `Session`'s
  `block`/`unblock`/`blockedUsers`/`report`, ported from iOS `SupermessageKit/Session.swift:327-392`
  and `Stores/SafetyStore.swift`. `FfiEvent.IgnoredUsers` stops being a no-op
  (`kit/Session.kt:833-837`).
- **App** (`android/app`): the report sheet, "Report…" in the message menu, "Report room…" on an
  invitation and in room info, member block/unblock/report in room info, and the Blocked section in
  Account.
- What a reader reported is hidden **on this device**, persisted in `SharedPreferences` and cleared
  at sign-out, exactly as iOS keeps it in `UserDefaults` (`SafetyStore.swift:50-55`).

**Tech stack:** Kotlin 2.4.0, Compose (BOM 2026.06.01), AGP 9.3.1, compileSdk 36, minSdk 31.
Tests: JUnit4, compose-ui-test, Roborazzi 1.74.0 on Robolectric 4.15.1.

**Spec:** `docs/research/2026-10-22-ios-parity/README.md` (rows "Block / unblock, blocked list",
"Report message / room / user", "Room info: … block/report", "Message menu: … report", "Account
panel: … blocked list") and `notes/android.md` ("FFI methods with no `kit/CoreClient.kt` wrapper";
"Block / unblock … `kit/Session.kt:805-809` ('iOS-only for now')" — now `:833-837`). Umbrella:
`docs/superpowers/plans/2026-10-22-ios-parity-roadmap.md`, "M3". Format follows
`.superpowers/sdd/planning-m1-m2/2026-10-22-parity-m2-android.md`. iOS paths are relative to `apple/`.

Paths used below:
- `A=android/app/src/main/kotlin/dev/supermessage`
- `K=android/kit/src/main/kotlin/dev/supermessage/kit`
- `AT=android/app/src/androidTest/kotlin/dev/supermessage`
- `UT=android/app/src/test/kotlin/dev/supermessage`
- `KT=android/kit/src/test/kotlin/dev/supermessage/kit`
- `PV=android/app/src/debug/kotlin/dev/supermessage/previews`

**Branch and worktree:** `parity/m3-android`, in `.worktrees/parity-android`, cut from the tip of
`parity/m2-android` after its Task 15 (M2 may not have merged yet; rebase on `main` when it does).

**Preconditions (controller, before Task 1):**
- M2 Android Tasks 1-15 are complete. This plan builds on their output: `RoomPane.kt` and
  `RosterPane.kt` (Tasks 1, 11), `MessageMenu.kt` and `Haptics.kt` (Task 2), the sectioned
  `AccountPanel` with help links (Task 13), the `MentionPicker` "Agent" tag (Task 10).
- The branch contains `origin/main` at or after `2f7ecbe`, with jniLibs and the host
  `target/debug/libsupermessage_ffi.so` rebuilt for it (M2's ledger already does this before Task
  10). M3 itself changes no FFI.

## Global Constraints

**From the roadmap (binding):**
- **Tokens only:** no hex in views. New type roles go through `design/tokens.toml` and then
  `python3 scripts/generate-tokens.py`. Never edit `GeneratedThemeTokens.kt`.
- **Amber (`signal`) means a pending decision and nothing else.** Blocked, reported and failure
  states use `error`/danger and the muted roles.
- **The app parses nothing and decides nothing that the core already decides.** The reason
  counter is the core's `reportReasonRemaining`, never `String.length`. A Matrix id is never split
  in Kotlin.
- **Match iOS's behaviour and copy, not its widgets.** Every string is quoted verbatim with its
  iOS `file:line`. A string iOS lacks is marked *(no iOS copy)*.
- **Bindings:** this track never edits `crates/` or the generated bindings. Do not run
  `build-android-libs.sh`.
- **Testing:** no single-character substring matchers; test shells set the required **width**
  (`Modifier.requiredWidth`), never the height. Every new UI state ships an `internal` `@Preview`
  in `PV/`; a preview with an indeterminate animation joins `PreviewScreenshotTest.NEVER_SETTLES`.
- **Haptics** are asserted through a recording `LocalHapticFeedback`, never a device vibration
  (M2 convention, `A/Haptics.kt`: `Haptic.Success`, `Haptic.Failure`).
- **Falsify every new test** (AGENTS.md): apply the named mutation, watch the named test fail,
  restore, and record the failure line in the report. For a gesture or timing test, run the
  mutated version three times.

**This machine (owner rules, 2026-10-22):**
- Every heavy command takes the shared lock:
  `flock -w 7200 $HOME/.cache/supermessage-heavy.lock <command>`. Heavy: any `./gradlew` run, any
  `cargo build|test|clippy`. Light: git, grep. `export CARGO_BUILD_JOBS=8`. Stop Gradle daemons
  (`./gradlew --stop`) when you finish.
- One emulator, never restarted mid-task by you. Emulator logs in `TMPDIR=~/Android/dl`.
- Run `cargo build -p supermessage-ffi` (under the lock) only if the host library `:kit` tests
  need is missing.
- While iterating, run the focused class. Run the full suites **once per task**, before the commit.

**Verification for every task**, from `android/` unless noted, ending in `BUILD SUCCESSFUL` and
0 failures:
- `flock -w 7200 $HOME/.cache/supermessage-heavy.lock ./gradlew :kit:test :app:testDebugUnitTest`;
- from the repo root, `scripts/android-ci-parity.sh` (once per emulator boot);
- `flock -w 7200 $HOME/.cache/supermessage-heavy.lock ./gradlew :app:connectedDebugAndroidTest`
  **at one height, 1080x2400** (owner decision, 2026-10-22).
- **Exception:** a task whose new UI depends on screen height (a bottom sheet with a text field and
  the keyboard) also runs the affected classes at `adb shell wm size 1080x1280`
  (`-Pandroid.testInstrumentationRunnerArguments.class=dev.supermessage.<Class>`), then
  `adb shell wm size 1080x2400`. Task 2 does this for `ReportSheetTest`.
- **Both heights for the whole suite** run once, at the end of the branch (Task 4).
- Single class: `-Pandroid.testInstrumentationRunnerArguments.class=dev.supermessage.<Class>`.

**Roborazzi baselines:** record with
`./gradlew :app:testDebugUnitTest --tests '*PreviewScreenshotTest*' -Proborazzi.test.record=true`
(under the lock), then copy in only the frames this task meant to add or change, after inspecting
each by eye. Never re-record to make the gate pass.

**Live account: READ-ONLY.** On a device signed in to the real account you may open rooms, menus,
sheets and panels, type a reason without sending, and take screenshots. You may **not** block,
unblock, report, leave, decline, accept, send or answer. List every skipped write under "Live
verification pending".

**Serialized files:** `MainActivity.kt` (Tasks 3, 4), `RoomPane.kt` (Tasks 1, 2), `Account.kt`
(Task 4), `RoomInfo.kt` (Task 3). The tasks run in plan order, one implementer at a time.

**Commits** end with the attribution lines in the session's system reminder.

## Review Focus

1. **A report that does not land** (offline, 5xx, a homeserver that refuses person reports). The
   sheet stays open, the reason stays in the field, the refusal is said under it, nothing is
   hidden and nobody is blocked (iOS `ReportSheet.swift:12-18`, `Session.swift:352-380`). Pinned in
   Task 1 by `aFailedReportHidesNothingAndBlocksNoOne` and in Task 2 by
   `aRefusedReportKeepsTheReasonAndSaysWhy`.
2. **A reported message leaves the timeline at once, without a new timeline revision.** `Timeline`
   caches its grouping on `revision` (`A/Timeline.kt:203-271`, `remember(revision)`), and a report
   changes the rows without bumping it, so a naive filter would leave the reported row on screen
   until the next message. Pinned in Task 1 by `aReportedMessageLeavesWithoutANewRevision`.
3. **Sign out and back in as another account** (roadmap RF5). The first account's blocked list and
   hidden messages must not show. Pinned in Task 1 by `signOutForgetsBlockedAndHidden` and
   `persistsUntilSignOut`.
4. **Font scale 2.0 at 411dp** (roadmap RF3): the report sheet's Send, the block toggle and the
   member actions must stay reachable and unclipped. Pinned in Task 2 by
   `reportSheetSendsAtFontScaleTwo` (run at both heights) and in Task 3 by
   `memberActionsFitAtFontScaleTwo`.
5. **An echo that lags, or another device.** The core's list replaces anything optimistic. Pinned
   in Task 1 by `theCoresListWins`.

---

## Task 1: Kit — wrappers, the safety store, block/report on the session; hidden rows leave the timeline — touches `RoomPane.kt`

**iOS behaviour this ports:**
- `SupermessageKit/Stores/SafetyStore.swift:11-37` — `ReportSubject`: `message(roomId, eventId,
  senderId, senderName, isAgent)`, `room(roomId, name)`, `user(userId, name, isAgent)`; `id`
  (`"message:\(roomId):\(eventId)"`, `"room:\(roomId)"`, `"user:\(userId)"`); `blockable` is the
  sender for a message, nobody for a room, the person for a user.
- `SafetyStore.swift:56-115`:
  - `blocked` is replaced wholesale by the core's list (`apply`, `:78-80`);
  - `noteBlocked`/`noteUnblocked` are the only local writes (`:84-88`);
  - `hide(eventId:)` persists under `safety.hiddenEvents` (`:69`, `:91-94`);
  - `visible(_:)` drops rows whose **event** id was hidden; a row with no event id always passes
    (`:96-106`);
  - `clear()` forgets everything and removes the stored key (`:108-114`).
- `SupermessageKit/Session.swift:332-392`:
  - `block`/`unblock` return the presenter's refusal or `nil`, noting the change only on success;
  - `blockedUsers()` is `[]` when the list cannot be read (`:346-350`);
  - `report` sends the report first and alone decides success; then hides a message; then, if
    asked and not already blocked, blocks; a failed block returns
    `"Reported, but blocking didn't work: \(blockRefused)"` (`:376`);
  - a non-core error reads `"That didn't work."` (`:389`).
- `Session.swift:764` — `safety.clear()` at sign-out; `:841-842` — `.ignoredUsers` →
  `safety.apply(ignored:)`.
- `Timeline/TimelineCollectionView.swift:417` — the timeline draws `safety.visible(timeline.items)`.
- `Timeline/TimelineCollectionView.swift:1000-1007` — a message's subject names the sender with
  `row.senderShort`, and needs an event id and a sender.

**Files:**
- Modify: `K/CoreClient.kt` (beside `setRoomPinned`, about `:200`).
- Create: `K/stores/SafetyStore.kt`, `K/ReportSubject.kt`.
- Modify: `K/Session.kt`: constructor (`:167`), the new safety section after `inviter` (about
  `:533`), `signOut` (`:751-772`), `handle` (`:833-837`).
- Create: `A/HiddenEventsPreferences.kt`.
- Modify: `A/SessionViewModel.kt:69` (pass the preferences-backed storage).
- Modify: `A/RoomPane.kt` (the `Timeline(` call, about `:203`) and `A/Timeline.kt` (a new parameter
  and the `remember` keys at `:203-271`).
- Test: `KT/stores/SafetyStoreTest.kt` (new), `KT/SessionTest.kt`, `KT/CoreClientTest.kt`,
  `UT/HiddenEventsPreferencesTest.kt` (new, Robolectric), `AT/TimelineTest.kt`.
- Create: `docs/superpowers/plans/2026-10-22-parity-m3-android.md`, a copy of this plan (R10).

**Interfaces:**
- Produces (`CoreClient`): `suspend fun ignoreUser(userId: String)`, `suspend fun unignoreUser(userId: String)`,
  `suspend fun ignoredUsers(): List<RoomMemberDto>`,
  `suspend fun reportEvent(roomId: String, eventId: String, reason: String)`,
  `suspend fun reportRoom(roomId: String, reason: String)`,
  `suspend fun reportUser(userId: String, reason: String)` — each `run { it.… }`, like the rest.
- Produces (`K/ReportSubject.kt`):
  - `data class Blockable(val userId: String, val name: String, val isAgent: Boolean)`;
  - `sealed class ReportSubject { abstract val id: String; abstract val blockable: Blockable? }`
    with `Message(roomId, eventId, senderId, senderName, isAgent)`, `Room(roomId, name)`,
    `User(userId, name, isAgent)`.
- Produces (`K/stores/SafetyStore.kt`):
  - `interface HiddenEventStorage { fun load(): Set<String>; fun save(ids: Set<String>); fun clear() }`
    and `class InMemoryHiddenEvents : HiddenEventStorage`;
  - `class SafetyStore(private val storage: HiddenEventStorage = InMemoryHiddenEvents())`:
    `val blocked: StateFlow<Set<String>>`, `val hidden: StateFlow<Set<String>>` (loaded from
    `storage` at construction), `fun apply(ignored: List<String>)`, `fun isBlocked(userId): Boolean`,
    `fun noteBlocked(userId)`, `fun noteUnblocked(userId)`, `fun hide(eventId)` (saves),
    `fun visible(rows: List<TimelineRow>): List<TimelineRow>` (keyed on `row.item.eventId`),
    `fun clear()`.
- Produces (`A/HiddenEventsPreferences.kt`):
  `class HiddenEventsPreferences(context: Context) : HiddenEventStorage`, over
  `context.getSharedPreferences("safety", MODE_PRIVATE)` and the string-set key
  `"safety.hiddenEvents"` (iOS's key). Synchronous on purpose: the store is read once, at `Session`
  construction, before any row is drawn.
- Produces (`Session`):
  - constructor parameter `hiddenEvents: HiddenEventStorage = InMemoryHiddenEvents()`, and
    `val safety = SafetyStore(hiddenEvents)`;
  - `suspend fun block(userId: String): String?`, `suspend fun unblock(userId: String): String?`;
  - `suspend fun blockedUsers(): List<RoomMemberDto>` (failure → `emptyList()`);
  - `suspend fun report(subject: ReportSubject, reason: String, alsoBlock: Boolean): String?`;
  - `suspend fun reportSubjectFor(row: TimelineRow, roomId: String): ReportSubject?` — `null`
    without `row.item.eventId` or `row.item.sender`; `senderName = row.senderShort`; `isAgent`
    per Step 0;
  - a private `safetyRefusal(body)` mirroring iOS `:382-392`: `FfiException` →
    `ErrorPresenter.message(e)`, anything else → `"That didn't work."`, `CancellationException`
    rethrown. Unlike the existing `refusal`, it does **not** reload the roster.
  - `signOut()` calls `safety.clear()`; `handle` maps `FfiEvent.IgnoredUsers` to
    `safety.apply(event.userIds)` (rewrite the comment: the core sends the whole sorted list once
    at session start and after every change from any device, `C/event.rs:62-65`).
- Produces (`Timeline`): a parameter `hiddenEvents: Set<String> = emptySet()`, used **only** as a
  cache key: `continuesRun`, `endsRun`, `facesBySender`, `singleSpeaker`, `displayRows` and
  `idByEventId` become `remember(revision, hiddenEvents)`. `animatesThisUpdate` stays
  `remember(revision)` — a hidden row is not an arrival. The filtering itself is
  `SafetyStore.visible`, done by the caller.
- `RoomPane` passes `rows = session.safety.visible(items)` and `hiddenEvents = hidden` (collected
  from `session.safety.hidden`).

- [ ] **Step 0: Check what the base carries.**
  - If the generated `uniffi.supermessage_core.TimelineRow` has `isAgent` (M1.4 row facts, M1b Task
    C6, merged), `reportSubjectFor` uses `row.isAgent`. Otherwise it reads the member's flag:
    `roomInfo(roomId).members.firstOrNull { it.userId == sender }?.isAgent ?: false`, inside a
    try/catch (failure → `false`). That flag is the same core rule (`agent_name_from_id`,
    `C/room_info.rs:149`). Record which in the report.
- [ ] **Step 1: Write the failing tests.**
  - `KT/stores/SafetyStoreTest.kt`, ported from iOS `SupermessageKitTests/SafetyStoreTests.swift`:
    - `hidesWhatWasReported` (`:24`): hide `$b`; `visible([a, b, c])` is `[a, c]`;
    - `aLocalEchoPasses` (`:32`): a row whose `eventId` is `null` survives;
    - `theCoresListWins` (`:41`): `noteBlocked("@x")`, then `apply(listOf("@y"))` →
      `isBlocked("@x")` false, `isBlocked("@y")` true;
    - `persistsUntilSignOut` (`:51`): over one shared fake storage, hide `$b`; a second store sees
      it; `clear()`; a third sees nothing and the storage was cleared;
    - `whoCanBeBlocked` (`:70`): the three `blockable` answers.
  - `KT/SessionTest.kt`, with `StubCore` fakes that record calls:
    - `ignoredUsersEventReplacesTheList`: deliver `FfiEvent.IgnoredUsers(listOf("@a"))` through the
      pump; `session.safety.blocked.value == setOf("@a")`;
    - `reportHidesOnlyAfterTheHomeserverHasIt`: `reportEvent` succeeds →
      `hidden.value` contains the event id; the fake asserts `hidden` was empty while it ran;
    - `aFailedReportHidesNothingAndBlocksNoOne` (RF1): `reportEvent` throws
      `FfiException.Network("")` → the result is `"Can't reach the homeserver."`, `hidden` is empty,
      `ignoreUser` never ran;
    - `aBlockThatFailsAfterAReportSaysWhichHalf`: report succeeds, `ignoreUser` throws
      `FfiException.Refused("You can't block yourself.")` → exactly
      `"Reported, but blocking didn't work: You can't block yourself."`;
    - `anAlreadyBlockedSenderIsNotBlockedAgain`; `aRoomReportNeverBlocks`;
    - `blockNotesOnlyWhatLanded`: a throwing `ignoreUser` leaves `blocked` empty and returns the
      presenter's text; a succeeding one adds the id;
    - `blockedUsersIsEmptyWhenTheListCannotBeRead`;
    - `signOutForgetsBlockedAndHidden` (RF5): apply, hide, `signOut()` → both empty;
    - `reportSubjectForNeedsAnEventAndASender` (and, without M1.4,
      `reportSubjectForAsksTheRoomWhoIsAnAgent`).
  - `KT/CoreClientTest.kt`: one case that `reportEvent` reaches the core with its three arguments
    (the existing pattern for a blocking call).
  - `UT/HiddenEventsPreferencesTest.kt` (Robolectric): save, a new instance loads the same set,
    `clear` removes it.
  - `AT/TimelineTest.kt`: `aReportedMessageLeavesWithoutANewRevision` (RF2): set content with rows
    `[a, b, c]`, `revision = 1u`, `hiddenEvents = emptySet()`; then rows `[a, c]`, the **same**
    `revision`, `hiddenEvents = setOf(b's eventId)`; b's body text no longer exists.
- [ ] **Step 2: Run them and confirm they fail.** `:kit:test`, `UT` and the one `AT` class.
- [ ] **Step 3: Implement** as specified. `SessionViewModel` builds the session with
  `hiddenEvents = HiddenEventsPreferences(app)`. Update `Session`'s class KDoc store count.
- [ ] **Step 4: Run the full verification** (one height).
  - **Falsify:**
    - `hide` before awaiting the report → `reportHidesOnlyAfterTheHomeserverHasIt` fails;
    - `visible` keyed on `item.id` → `aLocalEchoPasses` fails;
    - `apply` that unions instead of replacing → `theCoresListWins` fails;
    - drop `safety.clear()` from `signOut` → `signOutForgetsBlockedAndHidden` fails;
    - drop `hiddenEvents` from the `displayRows` key → `aReportedMessageLeavesWithoutANewRevision`
      fails.
  - No preview frame may change.
- [ ] **Step 5: Commit.** Message: `kit: block and report on the session; reported messages leave the timeline`.

## Task 2: The report sheet; "Report…" on a message; "Report room…" on an invitation — touches `RoomPane.kt`

**iOS behaviour and copy** (`Supermessage/Panels/ReportSheet.swift`):
- `:184-190` — titles: `"Report message"`, `"Report room"`, `"Report"`.
- `:205-215` — the explanation, first:
  - message: `"Your report goes to your homeserver's administrators, not to \(senderName). The message is hidden from you once the report is sent."`;
  - room: `"Your report about \(name) goes to your homeserver's administrators, not to the people in it."`;
  - user: `"Your report goes to your homeserver's administrators, not to \(name)."`, plus
    `" Reporting an agent doesn't stop it running."` for an agent.
- `:74-94` — the reason: header `"Reason"`, placeholder `"What's wrong? (optional)"`, label
  `"Reason for the report"`, hint `"Optional. Seen only by the homeserver's administrators."`, 4 to
  10 lines. The counter shows only when `remaining < 200`: `"\(remaining) characters left"`
  (muted), or `"\(-remaining) characters over the limit"` (danger). `remaining` is the core's
  `reportReasonRemaining` (`:60-61`).
- `:96-107` — when there is someone to block: a switch `"Also block \(target.name)"`, **on by
  default** (`:39-45`), or `"\(target.name) is blocked"` (muted) when they already are; footer
  (`:178-182`):
  - agent: `"Its messages are hidden everywhere, on all your devices. The agent keeps running and isn't told."`;
  - person: `"You won't see their messages in any room, on any of your devices. They aren't told."`.
- `:109-115` — a refusal under the form, in danger.
- `:122-138` — `"Cancel"` and `"Send"`; Send disabled while over the limit; hint
  `"Asks before sending"`; while sending, progress replaces Send and the sheet cannot be dismissed
  (`:149`).
- `:139-148` — a confirmation: title `"Report this message?"` / `"Report \(name)?"` (`:217-223`);
  message `"The homeserver's administrators will see it. \(subjectName) won't be told."`; actions
  `"Report and block"` when blocking, otherwise `"Report"` (`:225-227`, destructive), and `"Cancel"`.
- `:150-170` — success closes the sheet with the success haptic; a refusal keeps it open, with a
  warning haptic (Android: `Haptic.Failure`).
- `Timeline/TimelineCollectionView.swift:1128-1145` — `"Report…"`, last, destructive, on someone
  else's message the server has acknowledged (`!isOwn && canReplyOrReact` and a subject).
- `Panels/InvitationView.swift:77-87` — a quiet `"Report room…"` in danger beneath Accept/Decline,
  hint `"Reports this invitation to your homeserver's administrators"`, at least 44pt high.

**Files:**
- Create: `A/ReportSheet.kt` (`ReportCopy`, `ReportSheet`).
- Modify: `A/MessageMenu.kt` (`MessageMenuActions`, `messageMenuActions`, `MessageMenuContent`, the
  KDoc that says "Report… is a later milestone").
- Modify: `A/Timeline.kt` (`onReport` parameter, wired into `MessageMenu` at about `:571-590`).
- Modify: `A/RoomPane.kt` (hosts the sheet for a message; passes the report hook to
  `InvitationView`).
- Modify: `A/Invitation.kt` (`InvitationView`).
- Create: `PV/SafetyPreviews.kt`.
- Test: `UT/ReportCopyTest.kt` (new, JVM), `AT/ReportSheetTest.kt` (new), `AT/MessageMenuTest.kt`,
  `AT/InvitationTest.kt`, `AT/RosterReachabilityTest.kt` (its `StubCore` fake, about `:654`).

**Interfaces:**
- Produces (`ReportCopy`, an `internal object`, every string as quoted above):
  `title(subject)`, `explanation(subject)`, `confirmationTitle(subject)`,
  `confirmationMessage(subject)`, `subjectName(subject)`, `sendLabel(subject, alsoBlock)`,
  `blockConsequence(isAgent)`, `alreadyBlocked(name)`, `alsoBlock(name)`,
  `counter(remaining: Long): Pair<String, Boolean>?` (`null` at `>= 200`; the boolean is "over").
- Produces:
  `@Composable fun ReportSheet(subject: ReportSubject, isBlocked: (String) -> Boolean, onSend: suspend (reason: String, alsoBlock: Boolean) -> String?, onClose: () -> Unit, remaining: (String) -> Long = ::reportReasonRemaining)`.
  - A `ModalBottomSheet(skipPartiallyExpanded = true)` tagged `report-sheet` (open question Q13):
    a top row with `report-cancel`, the title, and `report-send`; a `verticalScroll` body with
    `imePadding()`: the explanation, the reason `OutlinedTextField` (`report-reason`, `minLines = 4`,
    `maxLines = 10`, the label as its `contentDescription`), the counter (`report-counter`), the
    block switch (`report-also-block`) or the blocked line (`report-already-blocked`) with its
    footer, and the refusal (`report-failure`).
  - Send opens an `AlertDialog` tagged `report-confirm` with `report-confirm-send` and
    `report-confirm-cancel`. Confirming calls `onSend(reason, alsoBlock && subject.blockable != null)`;
    `null` → `Haptic.Success`, then `onClose()`; a string → `report-failure` shows it and
    `Haptic.Failure`; the reason stays.
  - While sending: a `CircularProgressIndicator` in Send's place; `onDismissRequest` and Cancel do
    nothing (`sheetState` confirmValueChange refuses hiding).
- Produces: `MessageMenuActions` gains `report: Boolean`; `messageMenuActions` sets it to
  `!row.item.isOwn && row.canReplyOrReact && row.item.eventId != null && row.item.sender != null`;
  `isEmpty` includes it. `MessageMenu`/`MessageMenuContent` gain `onReport: () -> Unit`; the item
  "Report…" is last, tagged `menu-report`, destructive, after a `HorizontalDivider`.
- Produces: `Timeline(onReport: (row: TimelineRowDto) -> Unit = {})`.
- Produces: `InvitationView(onReport: () -> Unit = {})` — a `TextButton` "Report room…"
  (`invitation-report`, `bodySmall`, `colorScheme.error`, `heightIn(min = 48.dp)`,
  `onClickLabel` set to the iOS hint).
- `RoomPane` holds `var reporting by remember(currentRoomId) { mutableStateOf<ReportSubject?>(null) }`.
  The message hook launches `session.reportSubjectFor(row, roomId)` and sets it; the invitation hook
  sets `ReportSubject.Room(roomId, roomName)`. One `ReportSheet` is shown when `reporting != null`,
  with `isBlocked = session.safety::isBlocked` and
  `onSend = { reason, block -> session.report(subject, reason, block) }`.

- [ ] **Step 1: Write the failing tests.**
  - `UT/ReportCopyTest.kt`: every string above for each subject kind, exactly, including the agent
    sentence on a user and its absence on a person; `sendLabel(room, true) == "Report"`;
    `counter(200) == null`; `counter(199) == "199 characters left" to false`;
    `counter(-5) == "5 characters over the limit" to true`.
  - `AT/ReportSheetTest.kt` (shell `Modifier.requiredWidth(411.dp)`, a recording haptic):
    - `theToggleIsOnByDefaultForAMessage`;
    - `aRoomHasNoToggle`;
    - `someoneAlreadyBlockedShowsTheLineNotTheToggle`;
    - `theCounterAppearsOnlyNearTheLimit`: `remaining = { 1500L }` → no `report-counter`;
      `{ 150L }` → "150 characters left";
    - `overTheLimitSendIsDisabled`: `{ -3L }` → `report-send` is not enabled;
    - `sendAsksFirst`: click `report-send` → `report-confirm` shows "Report and block";
      `onSend` has not run;
    - `confirmingSendsWithTheBlockChoice`: turn the toggle off, confirm → `onSend("why", false)`;
    - `aRefusedReportKeepsTheReasonAndSaysWhy` (RF1): `onSend` returns
      `"This homeserver doesn't accept person reports. Nothing was sent."` → the text shows in
      `report-failure`, `report-reason` still holds the typed text, haptics `[Reject]`, `onClose`
      not called;
    - `aLandedReportClosesWithSuccess`: haptics `[Confirm]`, `onClose` called once;
    - `reportSheetSendsAtFontScaleTwo` (RF4): `Density(fontScale = 2f)`, a 300-character reason;
      `report-send` is displayed and clickable after `performScrollTo` where needed. **Run this
      class at both heights.**
  - `AT/MessageMenuTest.kt`: `reportOfferedOnSomeoneElsesSentMessage`, `reportNotOfferedOnYourOwn`,
    `reportNotOfferedWhilePending`, `reportAloneMakesAMenu` (no body, not editable, not own, sent →
    the menu exists and shows only the reactions, Reply and Report…).
  - `AT/InvitationTest.kt`: `reportRoomIsOfferedOnAnInvitation`: click `invitation-report` →
    `onReport` ran.
  - `AT/RosterReachabilityTest.kt`: `reportingAMessageHidesIt` — the app root over the existing
    fake core, extended so `reportEvent` records and succeeds: long-press someone else's message,
    `menu-report`, `report-send`, `report-confirm-send`; the message's text no longer exists and
    the fake saw `reportEvent(room, event, "")`.
- [ ] **Step 2: Run them and confirm they fail.**
- [ ] **Step 3: Implement** as specified. Previews (`PV/SafetyPreviews.kt`, `internal`, sheet
  content drawn without the sheet chrome): `ReportMessageAgent` (reason typed, toggle on — the
  agent footer), `ReportPersonRefused`, `ReportRoom`, `ReportOverLimit`, `MessageMenuWithReport`,
  `InvitationWithReport`.
- [ ] **Step 4: Run the full verification** (one height, plus `ReportSheetTest` at 1080x1280).
  - Record the new frames after inspection. Existing frames that change: the invitation preview
    (the new button) and any `MessageMenu` preview showing someone else's sent message (the new
    item). Inspect and name each; no other frame may change.
  - **Falsify:**
    - drop `!isOwn` → `reportNotOfferedOnYourOwn` fails;
    - default the toggle to off → `theToggleIsOnByDefaultForAMessage` fails;
    - clear the reason on a refusal → `aRefusedReportKeepsTheReasonAndSaysWhy` fails;
    - call `onSend` straight from Send → `sendAsksFirst` fails;
    - count with `reason.length` instead of `remaining` (a 1500-char emoji reason) → note the
      result in the report (the counter must come only from the injected `remaining`).
  - On the phone AVD signed in to the live account (read-only): long-press someone else's message,
    open Report…, type a reason, open the confirmation, **Cancel**, Cancel. Repeat at font scale 2.0.
  - Live verification pending: a sent report, report-and-block, a refused report, a reported
    message disappearing on the real account.
- [ ] **Step 5: Commit.** Message: `android: report a message or an invitation, with iOS's reason sheet`.

## Task 3: Room info — members you can block or report, "Report room…", and iOS's Leave words — touches `MainActivity.kt`

**iOS behaviour and copy** (`Supermessage/Panels/RoomInfoPanel.swift`):
- `:286-308` — every member except this account is a control opening the member actions; hint
  `"Block or report"` / `"Unblock or report"`. Your own row is not a control.
- `:238-259` — the member actions, titled with `displayName ?? userId`:
  - `"Unblock \(name)"` when blocked, otherwise `"Block \(name)"` (destructive);
  - `"Report \(name)…"` (destructive) → the report sheet with a user subject
    (`userId, name, isAgent: member.isAgent`);
  - `"Cancel"`;
  - message: blocked → `"Unblocking shows their messages again, including ones sent while they were blocked."`,
    otherwise the block consequence (Task 2's `ReportCopy.blockConsequence`).
- `:263`, `:333-349` — a landed block or unblock plays the success haptic; a refusal is said under
  the member list in danger (`:310-315`).
- `:317-323` — "blocked" is the core's list when it has spoken, otherwise the member's own
  `isIgnored`, less whatever this panel has just unblocked (`unblockedHere`).
- `:400-436` — the member row: the name (muted when blocked), the `AgentTag` for an agent, and
  `"Blocked"` in danger, semibold, small, when blocked; one element for TalkBack.
- `:200-208` — `"Report room…"` (destructive) beside `"Leave room"`, hint
  `"Reports this room to your homeserver's administrators"`.
- `:187-199` — the Leave confirm: title `"Leave \(info.name ?? "this room")?"`, message
  `"You'll stop receiving its messages."`, actions `"Leave"` (destructive) and Cancel. Android
  today says `"Leave room?"` and `"You will stop receiving messages from this room."`
  (`A/RoomInfo.kt:201-202`); this task makes it iOS's.

**Files:**
- Modify: `A/RoomInfo.kt` (`RoomInfoPanel`, `RoomInfoBody`, `MemberRow`, the leave dialog).
- Create: `A/AgentTag.kt` — lift the "Agent" tag M2 Task 10 drew in the mention picker into
  `@Composable internal fun AgentTag()` (accent text on accent-soft, `labelSmall`, semibold,
  `contentDescription` "Agent"), and switch the picker to it. If Task 10 already made it shared,
  reuse it.
- Modify: `A/MainActivity.kt` (the `RoomInfoPanel(` call, about `:328-344`).
- Modify: `PV/PanelPreviews.kt` (room info previews).
- Test: `AT/RoomInfoTest.kt`.

**Interfaces:**
- Produces: `RoomInfoPanel` gains
  `isBlocked: (String) -> Boolean = { false }`,
  `onBlock: suspend (String) -> String? = { null }`,
  `onUnblock: suspend (String) -> String? = { null }`,
  `onReport: suspend (ReportSubject, String, Boolean) -> String? = { _, _, _ -> null }`.
  It hosts its own `ReportSheet` (room and user subjects) and a member-actions
  `ModalBottomSheet` tagged `member-actions` with the title, the message, and items
  `member-block` / `member-unblock`, `member-report`, `member-actions-cancel`.
- Produces: `MemberRow(member, blocked: Boolean, onClick: (() -> Unit)?)` — clickable with
  `onClickLabel` set to the iOS hint when `onClick` is set; tags `member-${userId}`,
  `member-blocked-${userId}` on the "Blocked" label; `Modifier.semantics(mergeDescendants = true)`.
- Produces: tags `room-info-report-room`, `room-info-safety-failure`.
- `MainActivity` passes `session.safety::isBlocked`, `session::block`, `session::unblock` and
  `session::report`; it collects `session.safety.blocked` so the panel recomposes on the echo.

- [ ] **Step 1: Write the failing tests** in `AT/RoomInfoTest.kt` (extend `sampleInfo` with an
  agent member `@agent_atlas:example.org`, `isAgent = true`):
  - `aMemberOpensBlockOrReport`: click `member-@alice:example.org` → `member-actions` shows
    "Block Alice" and "Report Alice…", and the message is the person consequence;
  - `anAgentsActionsSayItKeepsRunning`: the agent member's message is the agent consequence, and
    its row shows "Agent";
  - `yourOwnRowIsNotAControl`: `accountUserId = "@me:example.org"` and members including it → no
    click action on `member-@me:example.org` (`assertHasNoClickAction`);
  - `blockingSaysBlockedAndPlaysSuccess`: `onBlock` returns `null`; after "Block Alice",
    `isBlocked` is flipped by the fake; `member-blocked-@alice:example.org` shows; haptics `[Confirm]`;
  - `aRefusedBlockIsSaidUnderTheMembers`: `onBlock` returns `"You can't block yourself."` →
    `room-info-safety-failure` shows it; haptics `[]`;
  - `unblockingHereWinsOverAStaleFlag`: a member with `isIgnored = true`, `isBlocked` false;
    "Unblock Alice" succeeds → no "Blocked" label although `isIgnored` is still true;
  - `reportRoomOpensTheSheetForTheRoom`: `room-info-report-room` → `report-sheet` with "Report room";
  - `reportAMemberOpensTheSheetForThem`: "Report Alice…" → `report-sheet` with title "Report";
  - `leaveConfirmIsIosWords`: "Leave Ops Room?" and "You'll stop receiving its messages." exist
    (update the existing leave tests that assert the old words);
  - `memberActionsFitAtFontScaleTwo` (RF4): `fontScale = 2f`, width 411dp; "Report Alice…" and
    `member-actions-cancel` are displayed after `performScrollTo`.
- [ ] **Step 2: Run them and confirm they fail.**
- [ ] **Step 3: Implement.** `unblockedHere` is snapshot state keyed by room
  (`remember(roomId) { mutableStateMapOf<String, Unit>() }`, or `mutableStateSetOf` if the
  runtime has it).
  Block/unblock run in the panel's scope; a refusal sets `safetyFailure` (cleared when an action
  opens, iOS `:298`). "Report room…" sits under "Leave room" in `colorScheme.error`. Previews:
  `RoomInfoWithBlockedMember`, `MemberActionsAgent`, `MemberActionsUnblock`.
- [ ] **Step 4: Run the full verification** (one height). Re-record the room-info frames that
  changed (new rows, Leave words) after inspection; name each.
  - **Falsify:** drop the `unblockedHere` clause → `unblockingHereWinsOverAStaleFlag` fails; make
    your own row clickable → `yourOwnRowIsNotAControl` fails; play the haptic before the call →
    `aRefusedBlockIsSaidUnderTheMembers` fails.
  - On the phone AVD (read-only): open room info in a group room; open a member's actions; Cancel;
    open Report room…; Cancel. At font scale 2.0 as well.
  - Live verification pending: block, unblock, report a member, report a room.
- [ ] **Step 5: Commit.** Message: `android: block or report a member, report a room, iOS's leave words`.

## Task 4: Account — the blocked list and the report footer; README; the branch-end pass — touches `MainActivity.kt`

**iOS behaviour and copy** (`Supermessage/Panels/AccountPanel.swift`):
- `:112-130` — a `"Blocked"` section **only when someone is in it**, after "Chats" and before the
  links. Each row is the member row with an `"Unblock"` button labelled `"Unblock \(name)"`
  (`RoomInfoPanel.swift:438-455`). Footer
  `"Their messages are hidden in every room, on all your devices. A blocked agent keeps running. Block someone from a room's member list."`,
  replaced by the refusal in danger when an unblock fails (`:204-212`). A landed unblock removes
  the row and plays the success haptic (`:181`).
- `:177-180` — the list loads once when the panel opens; a failure shows no section.
- `:142-144` — the footer under the help links, verbatim (Android has long-press):
  `"To report abuse, long-press a message or open a room's info. Reports go to your homeserver's administrators."`
  M2 Task 13 omitted both this footer and the section until report existed.

**Files:**
- Modify: `A/Account.kt` (the M2 Task 13 sections).
- Modify: `A/MainActivity.kt` (the `AccountPanel(` call).
- Modify: `PV/PanelPreviews.kt` (account previews).
- Test: `AT/AccountTest.kt`.
- Modify: `README.md:39` (Android cell) and any other Android cell this milestone changed.
- Create: `.superpowers/sdd/m3-android/m3-android-verification.md` (the run's evidence record).

**Interfaces:**
- Produces: `AccountPanel` gains `loadBlocked: suspend () -> List<RoomMemberDto> = { emptyList() }`
  and `onUnblock: suspend (String) -> String? = { null }`. Tags: `account-blocked`,
  `account-unblock-${userId}`, `account-blocked-footer`, `account-blocked-failure`,
  `account-report-footer`.
- `MainActivity` passes `session::blockedUsers` and `session::unblock`.

- [ ] **Step 1: Write the failing tests** in `AT/AccountTest.kt`:
  - `noBlockedSectionWhenNobodyIsBlocked`;
  - `theBlockedListShowsAgentsAndPeople`: two members, one an agent → both rows, the agent tag, and
    the footer text exactly;
  - `unblockingRemovesTheRowWithSuccess`: haptics `[Confirm]`, the row is gone;
  - `aRefusedUnblockReplacesTheFooter`: `onUnblock` returns `"Can't reach the homeserver."` →
    `account-blocked-failure` shows it, the row stays, the footer is gone;
  - `theReportFooterIsIosWords`: after `performScrollTo`, the text exists exactly;
  - `unblockIsReachableAtTheShortHeight`: three blocked members, `requiredWidth(411.dp)`;
    `performScrollTo` then click the last `account-unblock-…`. **Run `AccountTest` at both heights.**
- [ ] **Step 2: Run them and confirm they fail.**
- [ ] **Step 3: Implement** in iOS order (identity, appearance, chats, **Blocked**, links + report
  footer, recovery, sign out). Previews: `AccountBlockedUsers`, `AccountUnblockFailed`. The
  existing `Account` preview is in `NEVER_SETTLES`; add the new ones there too if they load
  asynchronously.
- [ ] **Step 4: Run the full verification, then the branch-end pass.**
  - The full instrumented suite at **both heights**: 1080x2400, then `adb shell wm size 1080x1280`
    and the same run, then `adb shell wm size 1080x2400`.
  - **Falsify:** show the section for an empty list → `noBlockedSectionWhenNobodyIsBlocked` fails;
    remove the row whatever the answer → `aRefusedUnblockReplacesTheFooter` fails.
  - README: correct the Android cells against the merged code — row 39 becomes "Implemented".
  - Fill `m3-android-verification.md` with the exit table below; one line per row, ✅ only with a
    test name or a screenshot, "live pending (user)" for any write.
  - **Device pass** (phone AVD, then once on the tablet AVD), read-only on the live account; report
    each as seen or not seen:
    1. Long-press someone else's message: Report… is last and red; open it; type; confirm dialog;
       Cancel; Cancel.
    2. Room info in a group room: Agent tags; a member's actions; Cancel; Report room…; Cancel.
    3. The Leave confirm reads "Leave {room}?"; Cancel.
    4. An invitation (if any): "from …" and Report room…; Cancel.
    5. Account: the Blocked section shows only if the account has blocked someone; the report
       footer under the links.
    6. Font scale 2.0: steps 1 and 2 again.
- [ ] **Step 5: Commit.** Message: `android: the blocked list in Account; README after parity M3`.

---

## Excluded, and why

| Item (roadmap or matrix) | Why it is not in this plan | Where |
|---|---|---|
| R2 swipes: mark read, mute, pin, decline | M2 Android Task 12. **Heads-up:** its "Mark read" uses `CoreClient.markRoomRead`, which reaches only the focused timeline (FFI `mark_room_read` → `focused_timeline().mark_read`, `crates/supermessage-ffi/src/lib.rs:254-257`), so the swipe on any room that is not open reports `false`. iOS's swipe has the same defect. | Core track: `Session::mark_room_read(room_id)` (ruling R3). Open question Q2. |
| Room info mute, notification mode, pin | Already in `A/RoomInfo.kt:285-317, 435-470`, iOS words. | — |
| Inviter on invitations | Already in `A/Invitation.kt` (`InvitationView`, "from …"). | — |
| New room / DM with known people first | Already ✅ (`A/NewRoom.kt`, `Session.openConversation`). | — |
| Permission answers | M0 (B1): `CoreClient.sendPermissionAnswer`, `Session.answerPermission`. | — |
| Account identity, appearance, chats, help links | M2 Android Tasks 13-14 (identity existed before). | — |
| Recovery | Already ✅ (`A/Recovery.kt`, `NewRecoveryKey.kt`, `kit/Session.kt` `ensureRecovery`). | — |
| Other unwrapped FFI (`registerPusher`, `unregisterPusher`, `notificationFor`, `attachmentMarkVoice`, `suspend`, `syncPause`, `syncResume`, `agentAvatar`, `sendGateDecisionTo`) | Not safety, recovery or room settings. Push, notifications and voice are M6; widgets M7. | M6/M7 |
| Showing every member (including yourself) in room info, as iOS does when there are two or more others | Android lists the others only (`A/RoomInfo.kt:239-242`); a members-list presentation choice from before M2, not a safety or settings row. | Note for M8 verification |

## Exit: rows this plan turns ✅

| Matrix row | Task | Verified by |
|---|---|---|
| Block / unblock, blocked list | 1, 3, 4 | `SafetyStoreTest`, `SessionTest`, `RoomInfoTest`, `AccountTest`; live pending (user) |
| Report message / room / user, reason sheet, reported message hidden | 1, 2, 3 | `SessionTest`, `ReportCopyTest`, `ReportSheetTest`, `MessageMenuTest`, `InvitationTest`, `RoomInfoTest`, `RosterReachabilityTest.reportingAMessageHidesIt`, `TimelineTest.aReportedMessageLeavesWithoutANewRevision`; live pending (user) |
| Message menu: … report | 2 | `MessageMenuTest` |
| Room info: members, runtime, mute/pin, Leave with confirm, block/report | 3 (mute/pin/runtime existed; Leave words) | `RoomInfoTest` |
| Account panel: blocked list | 4 | `AccountTest` |
| Review Focus 5 (sign out, sign in as another) | 1 | `signOutForgetsBlockedAndHidden`, `persistsUntilSignOut` |

## Decisions taken in this plan (technical; the controller may overrule)

1. Hidden messages persist through a `HiddenEventStorage` seam: `SharedPreferences` in the app, in
   memory in tests. Synchronous, because the store is read once at construction.
2. `Timeline` takes the hidden set only as a cache key; `SafetyStore.visible` is the one filter.
3. A message report's `isAgent` is `TimelineRow.isAgent` once M1.4 lands, otherwise the member's
   `isAgent` from `roomInfo` (the same core rule). A sender who has left reads as a person.
4. The report sheet is a `ModalBottomSheet`, matching iOS's sheet and M2's message menu (Q13).

Open questions for the owner are in `.superpowers/sdd/planning-m3/open-questions.md`.
