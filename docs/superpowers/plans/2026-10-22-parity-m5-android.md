# Parity M5 (Android) — Navigation and first run: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task by task. Steps use
> checkbox (`- [ ]`) syntax for tracking. Each task is a bundle: one implementer dispatch, targeted
> tests while iterating, the full suites once before the commit, one review. **An implementer reads
> only its own task, plus Global Constraints and Review Focus.**

**Goal:** turn the Android navigation and onboarding rows of the parity matrix ✅:
- N1, the four destinations (Chats, Needs you, Agents, Search) in a Material 3
  `NavigationSuiteScaffold` — a bottom bar on phones, a rail on tablets (owner gate G1);
- N2, the Needs you inbox, with its badge and "You're all caught up";
- N3, the Agents view, with each agent's status and last activity;
- R3, the All · Unread filter chips over Chats;
- O1, the welcome screen and the local first-run demo, where nothing is sent anywhere;
- the two Android-only rows: `supermessage://open?room=` deep links, and pull to refresh;
- and the M1.6 consumer that has no other milestone: readable space names ("Runtime 9247e5",
  `.local` trimmed) in the spaces menu and the section headers.

**Core prerequisites: none new.** Everything consumed is already exported, or arrives with the M1.6
PR (branch `parity/m1-core-first-run`, which also carries M1b Batch A). See "Interfaces consumed".

**Architecture:**
- **All work is in `android/`**, plus one generator output for the welcome mark (Task 4,
  `scripts/tokens/emit_mark.py`, Decision 5). This track never edits `crates/` or the generated
  bindings, and never runs `build-android-libs.sh`.
- **Kit** (`android/kit`, JVM-testable, no Compose): thin `RosterArrangement` wrappers over the
  core's roster views (as iOS's `RosterArrangement` is), `FirstRunDemo` (step and answer only, no
  client), `AppLink` (the link shape iOS shares with its widgets) and `NavigationRequests` (iOS's
  `NotificationRouter`: one pending room, consumed by the shell). M6's notification taps and M7's
  Glance widgets reuse the last two.
- **App** (`android/app`): `NeedsYouPane`, `AgentsPane`, the destination shell around the existing
  `ListDetailPaneScaffold`, the chips, the empty states, the welcome and the demo.
- **The shell keeps one `ListDetailPaneScaffold`.** The list pane shows the chosen destination; the
  detail pane shows the open room, whichever destination opened it. On a phone that reproduces iOS's
  "each tab owns the room it opened" (`PhoneShell.swift:6-10`): the bar is hidden while a room is
  open, so Back always returns to the destination that opened it (Decision 1).
- **Layout type from measured width, never `WindowSizeClass`** (AGENTS.md, `directiveFor()` in
  `RootScaffold.kt`). `NavigationSuiteScaffold` takes an explicit `layoutType`: `NavigationBar` when
  the shell is narrower than `TwoPaneWidth` (600dp), `NavigationRail` otherwise, and `None` on a
  one-pane shell while the detail pane is current. Pane count is then measured **inside** the
  scaffold's content, after the rail has taken its width.

**Tech stack:** Kotlin 2.4.0, Compose (BOM 2026.06.01), material3-adaptive 1.2.0, AGP 9.3.1,
compileSdk 36, minSdk 31. Tests: JUnit4, compose-ui-test, Roborazzi 1.74.0 on Robolectric 4.15.1.

**Spec:** `docs/research/2026-10-22-ios-parity/README.md` "Navigation and onboarding" (N1–N3, deep
links, O1) and "Room list" (R3); `notes/android.md` rows N1, N2, N3, Deep links, O1, R3 and "Also
iOS-only" (pull to refresh, readable space names). Umbrella:
`docs/superpowers/plans/2026-10-22-ios-parity-roadmap.md` "M5", "Decision gates" G1, "Review Focus"
3 and 5. Gates: `.superpowers/sdd/planning-m1-m2/owner-decisions.md` (G1, O2 "follow iOS exactly",
O4, the pace decision, merge authorization). Format follows
`.superpowers/sdd/planning-m4/2026-10-22-parity-m4-android.md`.

**iOS citations** are to `apple/` at `f970e8f` (the tip of `parity/m1-core-first-run`, which has
the M1.1 and M1.6 switch-overs: `RosterArrangement.needsYouInbox`, `FirstRunDemo` over the core's
script, `spaceDisplayName`). On `origin/main` before that PR merges, `FirstRunDemo.swift` is still the
Swift copy; the copy and timings are identical (the core test `the_script_is_what_ios_says` pins
them).

Paths used below:
- `A=android/app/src/main/kotlin/dev/supermessage`
- `K=android/kit/src/main/kotlin/dev/supermessage/kit`
- `AT=android/app/src/androidTest/kotlin/dev/supermessage`
- `UT=android/app/src/test/kotlin/dev/supermessage`
- `KT=android/kit/src/test/kotlin/dev/supermessage/kit`
- `KF=android/kit/src/testFixtures/kotlin/dev/supermessage/kit`
- `PV=android/app/src/debug/kotlin/dev/supermessage/previews`
- Android line numbers are at `f5d873f` (`parity/m2-android`, after M2 Tasks 11–12). M2 Tasks
  13–15, M3 and M4 move them. Find code by the quoted symbol.

**Branch and worktree:** `parity/m5-android`, in `.worktrees/parity-android`, cut from the tip of
`parity/m4-android` after its Task 5.

**Preconditions (controller, before Task 1):**
- M2 Android Tasks 1–15, M3 Android Tasks 1–4 and M4 Android Tasks 1–5 are complete. M5 reuses
  M4's `RoomAvatar` (`A/RoomAvatar.kt`), `StatusPill` (`A/StatusPill.kt`), the two-line `RoomRow`,
  and `LiveStore.inProgress`; M2's `SpaceMenu`, invitations row, `SwipeActionsRow`, `Haptic` and
  `motion()`/`reducesMotion()`.
- The branch contains `origin/main` with the **M1.6 PR** (`parity/m1-core-first-run`) merged, and
  the jniLibs plus the host `target/debug/libsupermessage_ffi.so` rebuilt for it (the M2 ledger's
  procedure, under the lock). Then `grep -n 'fun \`firstRunScript\`\|fun \`spaceDisplayName\`\|displayName' android/core/src/main/kotlin/uniffi/*/*.kt`
  must find all three.

### Interfaces consumed (Step 0 of each task greps the generated Kotlin)

| Kotlin (generated, `uniffi.supermessage_ffi` / `uniffi.supermessage_core`) | From | May change? |
|---|---|---|
| `rosterNeedsYouInbox(rows, nowMs: ULong): NeedsYouInbox(decisions, invitations, count: UInt, badgeCount: UInt)` | M1a PR3 | no (main) |
| `rosterAgentDirectory(rows, nowMs): List<RosterRow>` | M1a PR3 | no |
| `rosterFilterSections(sections, filter)`, `rosterFilterChips(sections): List<RosterFilterChip(filter, title, count: UInt)>`, `rosterFilterFromKey(key)`, `rosterFilterKey(filter)`, `rosterFilterEmptyState(filter): RosterEmptyState(title, description)`, `RosterFilter.ALL/UNREAD/AGENTS/NEEDS_YOU` | M1a PR3 | no |
| `roomStatus(state, describesAgent, turnInProgress): RoomStatus?`, `roomStatusWord(status)` | M1a PR3 | no |
| `agentAvatarStyle(roomId)`, `displayInitial(name)`, `peopleLabel(ids)` | M1a PR1, main | no |
| `firstRunScript(): FirstRunScript`, `firstRunAdvance(step)`, `firstRunAnswer(step): FirstRunStep?`, `FirstRunStep.GREETING/ASKING/WAITING/APPROVED` | M1.6 | yes (unmerged) |
| `spaceDisplayName(name)`, `SpaceSummary.displayName` | M1.6 | yes |
| `RoomAvatar(…)`, `StatusPill(status, compact)`, `RoomRow(…)` two-line, `LiveStore.inProgress` | M4 Android Tasks 1, 4 | yes (in progress) |

## Global Constraints

**From the roadmap and AGENTS.md (binding):**
- **Tokens only:** no hex in views; colours from `SupermessageTheme.colors`, radii from `Metrics`,
  type from `MaterialTheme.typography` roles mapped in `design/tokens.toml` (meta → `labelMedium`,
  label → `labelSmall`, ui → `bodySmall`). iOS's `title3` maps to `titleLarge` semibold and
  `footnote` to `bodySmall`, as M2/M4 did. Never edit `GeneratedThemeTokens.kt` or any file the
  token generator writes; change the generator and regenerate.
- **Amber (`signal`) means a pending decision and nothing else.** The Needs you **badge is `accent`**
  (`SplitShell.swift:171-178`: `accentContent` on `accent`), not amber. Only a row's own pending
  preview, its Needs you dot and the Needs you status pill are amber.
- **The app parses nothing and decides nothing the core decides.** What is in the inbox, what the
  badge counts, who is an agent, the directory's order, each status and its word, what a chip
  admits and counts, a stored chip read back, what an empty chip says, a space's readable name, and
  every word and pause of the demo are the core's. The host places them.
- **Match iOS behaviour and copy, not its widgets** (O2: where iOS and the roadmap disagree, follow
  iOS). Every string is quoted verbatim with its iOS `file:line`. A string iOS lacks is marked
  *(no iOS copy)*.
- **Reduce Motion:** every animation goes through `motion(reducesMotion(), …)` / `reducesMotion()`
  (`A/Motion.kt`). No UI test reads the device setting; tests provide `LocalReduceMotion`.
- **Testing:** no single-character substring matchers (a badge "2" is asserted with
  `assertTextEquals` on its own tag, never `onNodeWithText("2", substring = true)`); test shells set
  the required **width**, never the height. Every new UI state ships an `internal` `@Preview` in
  `PV/`; a preview whose animation never settles renders still or joins
  `PreviewScreenshotTest.NEVER_SETTLES`.
- **Haptics** through a recording `LocalHapticFeedback` (`Haptic.Selection` for a destination or
  chip change, `Haptic.Success` when the demo is approved), never a device vibration.
- **Falsify every new test** (AGENTS.md "A test that has never failed"): apply the named mutation,
  watch the named test fail, restore, record the failure line in the report. Timing or gesture
  tests: run the mutant three times.

**The first-run demo never touches the homeserver.** `FirstRunDemo` and `FirstRunDemoScreen` take
no `Session`, `CoreClient` or `CoreInterface` — the signature is the promise, as on iOS
(`FirstRunDemoView.swift:8-13`). The card is the real `DecisionCard`, handed a local
`onDecide` that only calls `FirstRunDemo.answer`. Task 4 pins it with a recording core that fails
on any call during the demo.

**Tablet (rail) tests — how they run.** Navigation depends on width, so every navigation task tests
three widths:
- **Width override in the test shell** (in the normal suite, any AVD): a phone shell
  `Modifier.requiredWidth(411.dp)`, a tablet-portrait shell and a tablet-landscape shell built with
  `Modifier.fillMaxWidth().wrapContentWidth(Alignment.Start, unbounded = true).requiredWidth(840.dp)`
  (and `1280.dp`), so the rail and the list pane start at x = 0 and stay inside the phone's window.
  A node beyond the window's right edge is clicked with
  `performSemanticsAction(SemanticsActions.OnClick)`, never `performClick()`. This is the
  `RootScaffoldTest` precedent (`requiredSize(1200.dp, …)` on any AVD), with the width-only rule.
- **Device size override** for the classes that compose the real `AppRoot` measure: run them once
  at `adb shell wm size 2205x2400` (840dp wide at pixel_6's 420 dpi: rail, two panes), then
  `adb shell wm size 1080x2400`. If the emulator refuses the size, use `adb shell wm density 210`
  (1080 px → 823dp) and `adb shell wm density reset`.
- **The tablet AVD** (`supermessage-tablet`, 800dp portrait / 1280dp landscape) once, at the branch
  end (Task 5): `NavigationTest` and `DeepLinkTest` plus the read-only device pass, after stopping
  the phone emulator (one emulator at a time).

**This machine (owner rules, 2026-10-22):**
- Every heavy command takes the shared lock:
  `flock -w 7200 $HOME/.cache/supermessage-heavy.lock <command>`. Heavy: any `./gradlew` run, any
  `cargo build|test|clippy`. Light: git, grep, `python3 scripts/generate-tokens.py`,
  `python3 -m unittest discover -s scripts/tests -t .`. `export CARGO_BUILD_JOBS=8`.
  `./gradlew --stop` when you finish.
- One emulator, never restarted by you mid-task. Emulator logs in `TMPDIR=~/Android/dl`.
- `cargo build -p supermessage-ffi` (under the lock) only if the host library `:kit` tests need is
  missing.
- While iterating, run the focused class. Run the full suites **once per task**, before the commit.

**Verification for every task**, from `android/`, ending in `BUILD SUCCESSFUL` and 0 failures:
- `flock -w 7200 $HOME/.cache/supermessage-heavy.lock ./gradlew :kit:test :app:testDebugUnitTest`;
- from the repo root, `scripts/android-ci-parity.sh` (once per emulator boot);
- `flock -w 7200 $HOME/.cache/supermessage-heavy.lock ./gradlew :app:connectedDebugAndroidTest`
  **at one height, 1080x2400** (owner decision, 2026-10-22).
- **Height exception:** Tasks 2 and 4 change height-dependent layout (a bottom bar under the list; a
  full-screen demo and welcome). They also run their named classes at `adb shell wm size 1080x1280`,
  then `adb shell wm size 1080x2400`. Single class:
  `-Pandroid.testInstrumentationRunnerArguments.class=dev.supermessage.<Class>`.
- **Both heights for the whole suite** run once, at the end of the branch (Task 5).

**Roborazzi baselines:** record with
`./gradlew :app:testDebugUnitTest --tests '*PreviewScreenshotTest*' -Proborazzi.test.record=true`
(under the lock), then copy in only the frames this task meant to add or change, after inspecting
each by eye, and name them in the report. Never re-record to make the gate pass; a frame nobody
meant to change is a regression to explain.

**Live account: READ-ONLY.** On a device signed in to the real account you may open rooms, switch
destinations, pull to refresh (a fetch), open a `supermessage://` link to a room you can see, and
take screenshots. You may **not** send, react, answer, edit, delete, block, report, mute, pin,
leave, accept, decline, **sign out or sign in**, or clear the app's data. The welcome screen needs a
signed-out install, so its device check is in the owner's checklist (Task 5); the demo is local and
may be shown on the live device through the debug launch extra Task 4 adds.

**Serialized files:** `MainActivity.kt` (Tasks 2, 4, 5), `RootScaffold.kt` (Task 2), `RosterPane.kt`
(Tasks 2, 3), `Roster.kt` (Task 3), `AndroidManifest.xml` (Task 5). Tasks run in plan order, one
implementer at a time.

**Commits** end with the attribution lines in the session's system reminder.

## Review Focus

1. **Sign out and back in as another account** (roadmap RF5). The destination returns to Chats; no
   badge, inbox row, agent row, chip count or pending deep link from the first account shows; the
   demo's per-install keys are not reset (iOS keys them to the install, `WelcomeView.swift:6-8`).
   Pinned in Task 2 by `signingOutReturnsToChatsWithNoBadge` and in Task 5 by
   `signingOutDropsAPendingLink`.
2. **Font scale 2.0 at 411dp, and the short height** (roadmap RF3). The bar's four labels, the
   badge, the chips (which scroll sideways), the inbox headers, "You're all caught up", the agent
   row's name/role/pill, the welcome and the demo must not clip or overlap; the demo and welcome
   scroll to their buttons at 1080x1280. Pinned per task (`…AtFontScaleTwo`).
3. **A room opened from a destination returns to it.** Back from a room opened in Needs you lands in
   Needs you, from Agents in Agents, from Search in Search with the term still there; a deep link
   always opens in Chats (`PhoneShell.swift:72-80`). Pinned in Task 2 by
   `backFromARoomReturnsToTheDestinationThatOpenedIt` and in Task 5 by
   `aLinkOpensTheRoomInChats`.
4. **The inbox is finishable.** A decision answered anywhere (the core stops saying the room needs
   you) leaves the inbox and the badge in the same update; the last one leaves "You're all caught
   up"; an invitation alone never lights the badge (`roster.rs` `badge_count`). Pinned in Task 1 by
   `aRowThatStopsNeedingYouLeaves` and in Task 2 by `theBadgeCountsDecisionsOnly` and
   `theBadgeGoesWhenTheDecisionIsAnswered`.
5. **The demo never touches the homeserver.** Pinned in Task 4 by
   `theDemoMakesNoCoreCallsFromStartToFinish` and by the composable's signature.

---

## Task 1: Kit roster views; the Needs you and Agents panes (N2 list and caught-up state, N3)

**iOS behaviour and copy:**
- **The inbox** (`Inbox/NeedsYouView.swift:11-57`): `RosterArrangement.needsYouInbox(rows, now)` —
  the core's `NeedsYouInbox`. Two sections, each only when it has rows: **"Waiting on you"** over
  `inbox.decisions`, then **"Invitations"** over `inbox.invitations` (`:22-35`). A section header is
  the title in meta, `contentMuted`, then the count in meta, `contentFaint`, spacing 6, not
  upper-cased (`:74-80`). Rows are the roster's `RoomRowView` with `showsState` from the stored
  `roster.showsState` (`:15`, `:59-72`) — on Android, M4's two-line `RoomRow` with `RoomAvatar`.
  The list animates rows leaving with the default animation (`:48-49`: "A row leaving is the inbox
  being finished; let it be seen to go"). Title **"Needs you"** (`:50`).
- **Caught up** (`:45-47`, `CaughtUpView` `:87-119`), when `inbox.count == 0`: a seal-with-check
  glyph at 56 in `ok`, hidden from accessibility; **"You're all caught up"** in title3 semibold,
  `content`; **"Decisions and invitations that need you land here."** in subheadline,
  `contentMuted`, centred; spacing 12, padding 32, centred in the pane, read as one element. One
  bounce of the glyph 250 ms after it appears; **none** under Reduce Motion. (This is the inbox's
  own copy; the core's `rosterFilterEmptyState(NEEDS_YOU)` is the unoffered chip's and is not used
  here.)
- **The Agents view** (`Agents/AgentsView.swift:11-63`): `RosterArrangement.agentDirectory(rows,
  now)` — the core's directory, recency order, no invitations. Title **"Agents"** (`:49`). Empty:
  **"No agents yet"**, sparkles glyph, **"Rooms with an agent in them appear here."** (`:42-48`).
- **The status** (`:25-27`, `:58-63`): `roomStatus(state: entry.state, describesAgent: true,
  turnInProgress: selectedId == roomId && live.inProgress)`. Only the open room can say Working;
  every other agent's status is the core's from its state.
- **The agent row** (`:67-103`): `RoomAvatar` at **44**, `describesAgent = true`, the entry's state;
  spacing 12; then a column (spacing 2): line 1 the name (name face, one line), a spacer (min 4),
  the relative time (meta, `contentFaint`) when not empty; line 2 (spacing 6) the role
  (subheadline, `contentMuted`, one line) when there is one, then `StatusPill(status, compact =
  true)` when there is a status. Vertical padding 4; the whole row is one element and one tap.
- Previews: iOS "Needs you", "Needs you, dark", "All caught up" (`NeedsYouView.swift:121-142`),
  "Agents", "Agents, dark" (`AgentsView.swift:105-119`), over
  `Previews/NavigationRevampFixtures.swift` `fleetSession()` (`:95`) and `caughtUpSession()`
  (`:109`). Port their rows into `PV/PreviewFixtures.kt` (names, roles, previews, ages) verbatim.

**Files:**
- Modify: `K/RosterArrangement.kt` — `needsYouInbox`, `agentDirectory`.
- Create: `A/NeedsYou.kt` (`NeedsYouPane`, `CaughtUp`), `A/Agents.kt` (`AgentsPane`, `AgentRow`,
  `agentStatus`), `A/ContentUnavailable.kt` (iOS `ContentUnavailableView`'s shape: glyph, title,
  description, optional action — Task 3 reuses it).
- Modify: `A/Roster.kt` only to make the row composable reusable from another list (no visible
  change).
- Create: `PV/NavigationPreviews.kt`; modify `PV/PreviewFixtures.kt`.
- Test: `KT/RosterArrangementTest.kt` (real core, host `.so`), `AT/NeedsYouTest.kt`,
  `AT/AgentsTest.kt`, `UT/AgentStatusTest.kt`.
- Create: `docs/superpowers/plans/2026-10-22-parity-m5-android.md`, a copy of this plan (R10).

**Interfaces:**
- Produces (`RosterArrangement`): `fun needsYouInbox(rows: List<RoomRow>, now: Instant): NeedsYouInbox`,
  `fun agentDirectory(rows: List<RoomRow>, now: Instant): List<RosterRow>` (same `milliseconds(now)`
  clamp as `sections`).
- Produces:
  - `@Composable fun NeedsYouPane(inbox: NeedsYouInbox, now: Instant, showsState: Boolean, avatarUri: (String) -> String?, onLoadAvatar: (String) -> Unit, onOpenRoom: (String) -> Unit, onOpenInfo: (String) -> Unit, modifier: Modifier = Modifier)`;
    tags `needs-you-list`, `needs-you-header-decisions`, `needs-you-count-decisions`,
    `needs-you-header-invitations`, `needs-you-count-invitations`, `caught-up`, `caught-up-seal`.
  - `@Composable fun AgentsPane(agents: List<RosterRow>, now: Instant, openRoomId: String?, openRoomInProgress: Boolean, avatarUri: (String) -> String?, onLoadAvatar: (String) -> Unit, onOpenRoom: (String) -> Unit, modifier: Modifier = Modifier)`;
    tags `agents-list`, `agent-row-${roomId}`, `no-agents`.
  - `internal fun agentStatus(entry: RosterRow, openRoomId: String?, openRoomInProgress: Boolean): RoomStatus?`
    = `roomStatus(entry.state, true, entry.row.room.id == openRoomId && openRoomInProgress)`.
  - `@Composable internal fun ContentUnavailable(title: String, description: String?, icon: ImageVector, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null)`.
  - A custom `SemanticsPropertyKey<Boolean>("Bounces")` on `caught-up-seal`, true when the bounce
    is scheduled (false under Reduce Motion), so a test can read it.

- [ ] **Step 0: Check the base.** Grep the generated Kotlin for `rosterNeedsYouInbox`,
  `rosterAgentDirectory`, `roomStatus`; grep `A/` for M4's `fun RoomAvatar(`, `fun StatusPill(` and
  `LiveStore`'s `inProgress`. Record the actual signatures; if M4 named them differently, use M4's.
- [ ] **Step 1: Write the failing tests.**
  - `KT/RosterArrangementTest.kt` (real core):
    - `theInboxIsTheCores`: rows = two rooms with a pending preview, one invitation, one quiet room
      → `decisions.size == 2`, `invitations.size == 1`, `count == 3u`, `badgeCount == 2u`.
    - `theDirectoryLeavesOutPeopleAndInvitations`: an agent room (`@agent_` counterpart or glyphed
      identity), a people room, an invited agent room → exactly the first.
  - `UT/AgentStatusTest.kt`: `onlyTheOpenRoomCanSayWorking` — two ACTIVE agent entries, `!a` open
    with `openRoomInProgress = true` → `!a` WORKING, `!b` ACTIVE; `!a` open with `false` → ACTIVE.
  - `AT/NeedsYouTest.kt` (each in `Box(Modifier.requiredWidth(411.dp))`):
    - `decisionsAndInvitationsAreSectionedAndCounted`: the core's inbox for the Step 1 rows →
      "Waiting on you" above "Invitations"; `needs-you-count-decisions` `assertTextEquals("2")`,
      `needs-you-count-invitations` `assertTextEquals("1")`; the decision rows lie between the two
      headers.
    - `aSectionWithNoRowsIsLeftOut`: decisions only → no "Invitations" node.
    - `anEmptyInboxIsCaughtUp`: an empty inbox → "You're all caught up" and
      "Decisions and invitations that need you land here." displayed; `needs-you-list` has no rows.
    - `aRowThatStopsNeedingYouLeaves` (RF4): recompose with the inbox the core gives once one room's
      preview is no longer pending → that row's name is gone and the other remains; then with none
      → `caught-up` displayed.
    - `caughtUpBouncesUnlessReduced`: `Bounces` true; with `LocalReduceMotion provides true` false.
    - `tappingARowOpensIt`: click a decision row → `onOpenRoom("!a:x")` ran once.
    - `theInboxFitsAtFontScaleTwo` (RF2): `Density(density, fontScale = 2f)`, a 60-character room
      name → the header's count is displayed and its right edge ≤ the list's; caught-up texts
      wrap without being cut (`lineCount ≥ 2`, no `hasVisualOverflow`).
  - `AT/AgentsTest.kt`:
    - `anAgentRowSaysWhoWhatAndWhen`: an agent "Atlas" with role "Platform", last active 3 minutes
      before `now` → "Atlas", "Platform", "3m" and `status-pill` with "Active" inside
      `agent-row-!a:x`.
    - `theOpenRoomWithALiveTurnSaysWorking` (UI wiring of `agentStatus`): `openRoomId = "!a:x"`,
      `openRoomInProgress = true` → "Working" in `!a`'s row only.
    - `noAgentsYet`: empty list → "No agents yet" and "Rooms with an agent in them appear here."
    - `theFaceIs44`: `avatar` inside the row is 44dp wide.
    - `theRowIsOneTap`: the row's merged semantics have one click action; click → `onOpenRoom`.
    - `agentsFitAtFontScaleTwo` (RF2): an 80-character name and a long role → name `lineCount == 1`,
      the pill displayed with its right edge ≤ the row's.
- [ ] **Step 2: Run them and confirm they fail** (`:kit:test`, `:app:testDebugUnitTest`, the two
  instrumented classes).
- [ ] **Step 3: Implement.**
  - Lists are `LazyColumn`s keyed by room id. Row removal uses
    `Modifier.animateItem(fadeOutSpec = motion(reduce, tween(…)), placementSpec = motion(reduce, spring()), fadeInSpec = motion(reduce, tween(…)))`
    — the M2 Task 15 helper; nothing animates under Reduce Motion.
  - The seal: `Icons.Filled.Verified` if `material-icons-extended` is on the classpath after M4,
    else `Icons.Filled.CheckCircle` (Decision 4). The bounce is a one-shot `Animatable` scale
    1 → 1.15 → 1 started 250 ms after first composition, skipped under Reduce Motion.
  - Previews (`internal`, still): `NeedsYou`, `NeedsYouDark`, `AllCaughtUp`, `Agents`,
    `AgentsDark`, `NoAgentsYet`, `NeedsYouLargeText`, `AgentsLargeText` (font scale 2.0).
- [ ] **Step 4: Run the full verification** (one height).
  - **Falsify:** use `inbox.count` for the decisions header → `decisionsAndInvitationsAreSectionedAndCounted`
    fails; pass `true` for `turnInProgress` on every row → `onlyTheOpenRoomCanSayWorking` fails;
    bounce regardless of the setting → `caughtUpBouncesUnlessReduced` fails; key the inbox
    `remember` on nothing → `aRowThatStopsNeedingYouLeaves` fails.
- [ ] **Step 5: Commit.** `android: the Needs you inbox and the Agents view, from the core`.

## Task 2: The four destinations (N1, the N2 badge) — touches `RootScaffold.kt`, `MainActivity.kt`, `RosterPane.kt`

**iOS behaviour and copy:**
- **The four places** (`Navigation/AppDestination.swift:6-28`): **"Chats"**, **"Needs you"**,
  **"Agents"**, **"Search"**, in that order, the same four on the phone's tab bar and the iPad's
  sidebar. Icons: chats (two bubbles), needs you (tray), agents (sparkles), search (magnifier).
  The selected item is emphasised (`SplitShell.swift:169`, `:186-189`: semibold glyph, `accent`,
  `accentSoft` ground); others `contentMuted`.
- **The badge** (`PhoneShell.swift:23`, `:49-50`; `SplitShell.swift:45`, `:170-180`, `:192-194`):
  `needsYouInbox(rows, now).badgeCount` — **decisions only**; nothing at 0. Drawn `accentContent` on
  an `accent` capsule, caption2 semibold, tabular digits. The item's accessibility label is
  **"Needs you, \(count)"** when the count is above 0, else "Needs you".
- **A destination change** is a selection haptic (`PhoneShell.swift:83`, `SplitShell.swift:199`).
- **The phone** (`PhoneShell.swift:25-70`): a tab bar. **A room is a place, not a tab: the bar is
  hidden while a room is open** (`AppDestination.swift:36-45`). Back returns to the destination that
  opened the room (`PhoneShell.swift:6-10`).
- **The tablet** (`SplitShell.swift:113-148`): the destinations beside the list; the detail column
  is the selected room, whichever destination chose it; switching destination leaves it open
  (`:52-56`). G1 makes this a rail.
- **Search is a destination** (`PhoneShell.swift:62-69`; `SplitShell.swift:121-127`): the list
  pane is the search panel, titled "Search", with **no Done/Cancel** (iOS `SearchPanel(session:
  scope: onOpen:)` has no close). Its scope is the selected room where one is selected (the
  tablet), none otherwise (the phone). Opening a result opens the room in the detail pane.
- **The Chats toolbar** (`AppDestination.swift:48-107`): the account at the leading edge, as the
  account's initial in an `accent` circle, labelled **"Account"** (`:118-134`, `:154-161`: 30 wide,
  `accent` at 18 % ground, initial in subheadline semibold `accent`); compose at the trailing edge,
  labelled **"New conversation"** (`:73-74`). On the phone it is on Chats only (`PhoneShell.swift:30-35`);
  on the iPad on the sidebar whatever its destination (`SplitShell.swift:79-84`). Android today
  has three text buttons "New room / Search / Account" (`A/RosterPane.kt:96-119`); Search leaves
  for the destination and the other two become this toolbar (O2).
- Titles: Chats keeps M2's `SpaceMenu` and the connection line under it (O4; iOS shows the
  connection only there, `SpaceMenu.swift` `status`); Needs you, Agents and Search show their own
  title (`NeedsYouView.swift:50`, `AgentsView.swift:49`, `SearchPanel.swift:75`).

**Files:**
- Create: `A/Destinations.kt` (`Destination`, `ShellNavigation`, `navLayoutFor`, `DestinationIcon`,
  `NeedsYouBadge`, `ChatsToolbar`).
- Modify: `A/RootScaffold.kt` — an optional `navigation: ShellNavigation? = null` parameter; when
  present, `NavigationSuiteScaffold` wraps the `ListDetailPaneScaffold`; `paneCountFor` reads the
  width measured inside it. When `null`, nothing changes (every `RootScaffoldTest` keeps passing
  unmodified).
- Modify: `A/MainActivity.kt` (`AppRoot`) — the destination state (`rememberSaveable`, reset to
  CHATS on sign-out), the inbox and badge (`remember(rooms, now) { RosterArrangement.needsYouInbox(rooms, now) }`),
  the list pane `when (destination)`, the hoisted search term, the haptic; `ExtraPanel.Search` and
  its branch are deleted.
- Modify: `A/RosterPane.kt` — the three `TextButton`s replaced by `ChatsToolbar` (account and new
  conversation; `roster-open-search` deleted).
- Modify: `A/Search.kt` — `onClose: (() -> Unit)?`; `null` hides Cancel and the result tap no
  longer calls it; the term can be hoisted (`term: String`, `onTermChange`).
- Modify: `android/gradle/libs.versions.toml`, `android/app/build.gradle.kts` — add
  `androidx.compose.material3:material3-adaptive-navigation-suite` (BOM-managed) and, if M4 did not,
  `androidx.compose.material:material-icons-extended` (Decision 4).
- Modify: `PV/RosterChromePreviews.kt` (toolbar), create `PV/ShellPreviews.kt`.
- Test: `AT/NavigationTest.kt` (new; AppRoot + `StubCore`), `AT/RosterReachabilityTest.kt`
  (`searchOpenedFromARoomOffersThatRoom` rewritten for the destination; the "Search" and "New room"
  button lookups updated), `AT/SearchTest.kt` (no Cancel when `onClose == null`),
  `UT/PaneLayoutTest.kt` (`navLayoutFor`).

**Interfaces:**
- Produces:
  ```kotlin
  enum class Destination(val title: String) { CHATS("Chats"), NEEDS_YOU("Needs you"), AGENTS("Agents"), SEARCH("Search") }
  internal data class ShellNavigation(
      val selected: Destination,
      val onSelect: (Destination) -> Unit,
      val badgeCount: Int,
  )
  internal fun navLayoutFor(shellWidth: Dp, detailIsCurrentOnOnePane: Boolean): NavigationSuiteType
      // < TwoPaneWidth → NavigationBar; else NavigationRail; detailIsCurrentOnOnePane → None
  @Composable internal fun ChatsToolbar(accountInitial: String, onAccount: () -> Unit, onCompose: () -> Unit)
  ```
  Tags: `nav-bar` / `nav-rail` (on the suite's container, set through the item modifiers'
  ancestors or a wrapping `testTag`), `destination-chats`, `destination-needs-you`,
  `destination-agents`, `destination-search`, `needs-you-badge`, `toolbar-account`,
  `toolbar-new-conversation`.
- Consumes Task 1's `NeedsYouPane`, `AgentsPane`; M2's `SearchPanel`; M4's `LiveStore.inProgress`.

- [ ] **Step 0: Check the dependency.** Under the lock, add the navigation-suite artifact and run
  `./gradlew :app:dependencies --configuration debugRuntimeClasspath | grep -i 'adaptive\|navigation-suite'`.
  If it pulls `androidx.compose.material3.adaptive:*:1.3.x` (which declares `minCompileSdk=37`,
  `libs.versions.toml:10-12`) or any artifact that fails `:app:checkDebugAarMetadata`, **stop and
  report** — the fallback (owner question Q4) is M3 `NavigationBar` / `NavigationRail` chosen by
  the same `navLayoutFor`, which keeps every test below.
- [ ] **Step 1: Write the failing tests.**
  - `UT/PaneLayoutTest.kt`: `navLayoutFor(411.dp, false) == NavigationBar`,
    `navLayoutFor(599.dp, false) == NavigationBar`, `navLayoutFor(600.dp, false) == NavigationRail`,
    `navLayoutFor(1280.dp, false) == NavigationRail`, `navLayoutFor(411.dp, true) == None`.
  - `AT/NavigationTest.kt` (AppRoot over a `StubCore` serving a fleet of rows; shells as in Global
    Constraints "Tablet tests"):
    - `aPhoneHasABottomBarWithTheFourDestinations` (411dp): `nav-bar` exists, `nav-rail` does not;
      the four items say "Chats", "Needs you", "Agents", "Search" in that order (left bounds
      increasing); the bar's top ≥ the roster's bottom.
    - `aTabletHasARail` (840dp shell): `nav-rail` exists, `nav-bar` does not; `pane-roster`'s left
      bound ≥ the rail's right bound; `pane-timeline` exists (two panes after the rail).
    - `aWideTabletKeepsThreePanes` (1280dp shell): the rail, then `pane-roster`, `pane-timeline`;
      opening room info shows `pane-info` within the shell.
    - `theBarGoesInARoom` (411dp): open a room → `nav-bar` gone; system back → `nav-bar` back,
      Chats selected.
    - `backFromARoomReturnsToTheDestinationThatOpenedIt` (RF3, 411dp): select Needs you, open its
      decision row, back → Needs you is selected and its list shows; the same for Agents.
    - `aTabletKeepsTheRoomWhenTheDestinationChanges` (840dp): open `!a` from Chats, select Agents
      → the detail pane still shows `!a`'s header.
    - `theBadgeCountsDecisionsOnly` (RF4): two pending rooms + one invitation → `needs-you-badge`
      `assertTextEquals("2")`; `destination-needs-you` has `contentDescription` "Needs you, 2".
    - `anInvitationAloneLightsNoBadge`: one invitation, no decisions → no `needs-you-badge`;
      contentDescription "Needs you".
    - `theBadgeGoesWhenTheDecisionIsAnswered` (RF4): deliver a rooms diff in which the pending
      preview is replaced by an ordinary message → the badge goes in that update.
    - `aDestinationChangeIsASelectionHaptic`: recording `LocalHapticFeedback`; click Agents →
      `[Haptic.Selection]`; click Agents again → still one.
    - `searchIsADestinationWithNoCancel` (411dp): click Search → the search field is displayed,
      `search-cancel` does not exist; the scope row offers no room.
    - `searchOnATabletOffersTheOpenRoom` (840dp; replaces M2's `searchOpenedFromARoomOffersThatRoom`):
      open "Atlas", select Search → `search-scope-room` says "Atlas" and is selected.
    - `backFromASearchResultKeepsTheTerm` (RF3, 411dp): type "deploy", open a result, back → Search
      is selected and the field reads "deploy".
    - `theChatsToolbarHasAccountAndNewConversation` (411dp): `toolbar-account` (contentDescription
      "Account", text the account's initial from `displayInitial(peopleLabel(listOf(userId)))`) opens
      the account panel; `toolbar-new-conversation` ("New conversation") opens the new-room panel;
      on Needs you neither exists on a phone; on the 840dp rail both exist on every destination.
    - `signingOutReturnsToChatsWithNoBadge` (RF1): select Needs you with a badge, sign out, sign in
      over a `StubCore` with no pending rows → Chats is selected and there is no `needs-you-badge`.
    - `theBarFitsAtFontScaleTwo` (RF2, 411dp, font scale 2): each label `lineCount == 1`, no two
      item bounds overlap, the badge is displayed.
- [ ] **Step 2: Run them and confirm they fail.**
- [ ] **Step 3: Implement.**
  - `RootScaffold`'s `SignedIn` wraps the `ListDetailPaneScaffold` in
    `NavigationSuiteScaffold(layoutType = navLayoutFor(outerWidth, panes == 1 && detail is current), navigationSuiteItems = { Destination.entries.forEach { item(selected = …, onClick = …, icon = …, label = { Text(it.title, maxLines = 1) }, modifier = Modifier.testTag("destination-…").semantics { … }) } })`,
    then measures again with `BoxWithConstraints` inside it for `paneCountFor`. Colours from
    `SupermessageTheme.colors` through `NavigationSuiteDefaults.itemColors(…)` (selected `accent`
    on `accentSoft`, unselected `contentMuted`); the container on `surface`.
  - The badge: `BadgedBox(badge = { if (count > 0) Badge(containerColor = colors.accent, contentColor = colors.accentContent, modifier = Modifier.testTag("needs-you-badge")) { Text("$count", style = labelSmall + tnum) } })`.
  - Icons (Decision 4): Chats `Forum`, Needs you `Inbox`, Agents `AutoAwesome`, Search `Search`;
    outlined when unselected, filled when selected.
  - The destination is `rememberSaveable(session) { mutableStateOf(Destination.CHATS) }`; a phase
    change to `SIGNED_OUT` sets CHATS.
  - The Agents pane's `openRoomId` is `session.rooms.selectedId`; `openRoomInProgress` is
    `session.live.inProgress` (M4).
  - The search term is hoisted to `AppRoot` (`rememberSaveable(session)`), so the list pane's
    recomposition after Back keeps it.
  - On a one-pane shell, system back on Needs you, Agents or Search with no room open selects Chats
    first (Decision 2, owner Q5); on Chats it is not intercepted.
  - Previews: `ShellPhone` (bar, Chats, badge 2), `ShellPhoneDark`, `ShellTabletRail` (840dp),
    `ShellTabletRailDark`, `ShellLargeText` (font scale 2), `ChatsToolbar` — iOS "Tabs, dark"
    and "iPad fleet" (`RootView.swift:268-278`).
- [ ] **Step 4: Run the full verification** (1080x2400), then `NavigationTest` and
  `RosterReachabilityTest` at `adb shell wm size 1080x1280`, and `NavigationTest` at
  `adb shell wm size 2205x2400` (the device-width override: AppRoot's real measure picks the rail),
  then `adb shell wm size 1080x2400`.
  - **Falsify:** pick the layout from `currentWindowAdaptiveInfo()` → `aTabletHasARail` fails in the
    width-override shell; badge from `inbox.count` → `anInvitationAloneLightsNoBadge` fails; keep
    the bar while a room is open → `theBarGoesInARoom` fails; keep the term local to `SearchPanel`
    → `backFromASearchResultKeepsTheTerm` fails; skip the reset on sign-out →
    `signingOutReturnsToChatsWithNoBadge` fails.
  - **Device pass (read-only, phone AVD):** the bar with its badge; Needs you, Agents and Search;
    open a room from Needs you and come back; font scale 2.0.
- [ ] **Step 5: Commit.** `android: Chats, Needs you, Agents and Search — a bar on phones, a rail on tablets`.

## Task 3: Chats — filter chips, empty states, readable space names, pull to refresh (R3, M1.6, PTR) — touches `RosterPane.kt`, `Roster.kt`

**iOS behaviour and copy:**
- **The chips** (`Rooms/RosterFilterChips.swift:14-57`): **"All"**, **"Unread"** — the core's
  `rosterFilterChips(arranged)` (only these two are offered: `OFFERED_FILTERS`, `roster.rs`). A
  horizontally scrolling row, spacing 8, padding 16 horizontal and 6 vertical, not clipped at the
  edges. A chip: the title, then — for a chip other than All whose count is above 0 — the count in
  tabular digits (`accentContent` when selected, `contentMuted` otherwise); footnote, semibold when
  selected; text `accentContent` selected, `content` otherwise; padding 12/6; a capsule on `accent`
  when selected, `surfaceRaised` with a 1-wide `border` stroke otherwise. Accessibility label
  **"\(title), \(count)"**, the selected trait. A change is a selection haptic (`:30`).
- **Where** (`Rooms/RoomListView.swift:101-120`): at the top of the list's scroll content (it
  scrolls away), above the invitations row, and **only when the account has rooms** (`:111-113`).
- **What it does** (`:53-60`, `:82-91`): the chip in force is `rosterFilterFromKey(stored)` (an
  unknown or unoffered key reads as All), stored under **`roster.filter`** as
  `rosterFilterKey(filter)` (`:32`); the list is `rosterFilterSections(arranged, filter)` — it
  narrows, never re-orders; the counts come from the unfiltered arrangement.
- **Empty states** (`:179-199`), in this order:
  - no rooms at all: **"No rooms yet"**, tray glyph, **"Rooms appear here as they sync."**;
  - the chip left nothing (filter not All): `rosterFilterEmptyState(filter).title` and
    `.description` (for Unread: "Nothing unread", "Every room is read."), a filter glyph, and a
    **"Show all"** button that stores All;
  - every room was filtered away and the invitations row is closed: **"Nothing but invitations"**,
    envelope glyph, **"\(hiddenInvitations) waiting — open Invitations above."**
- **Readable space names** (M1.6): the spaces menu title and each item use the core's readable name
  (`Rooms/SpaceMenu.swift:19-20`, `:55`: `spaceDisplayName(name:)`; on Android
  `SpaceSummary.displayName`); every roster section title goes through `spaceDisplayName`
  (`RoomListView.swift:136-139`) — a Machine section named `Rakeshs-MacBook-Pro.local` reads
  "Rakeshs-MacBook-Pro", a generated id "Runtime 9247e5".
- **Pull to refresh** (`RoomListView.swift:175-178`, `NeedsYouView.swift:53-56`,
  `AgentsView.swift:52-55`): on Chats, Needs you and Agents, `now = Date()` then
  `await session.rooms.seed()`; the indicator stays until the seed returns.

**Files:**
- Modify: `K/RosterArrangement.kt` — `filterSections`, `filterChips`, `filterFromKey`, `filterKey`,
  `filterEmptyState` (one-line wrappers).
- Modify: `A/RosterPreferences.kt` — `val filter: Flow<RosterFilter>` (read through
  `RosterArrangement.filterFromKey`), `suspend fun setFilter(RosterFilter)`, key
  `stringPreferencesKey("roster.filter")`.
- Create: `A/FilterChips.kt`.
- Modify: `A/RosterPane.kt` (chips, the filtered sections, the empty states, `PullToRefreshBox`),
  `A/Roster.kt` (the chips and the invitations row as the list's first items; section titles
  through `spaceDisplayName`), `A/SpaceMenu.kt` (`displayName`), `A/MainActivity.kt` only to pass
  the refresh to the Needs you and Agents panes (or wrap them in `A/NeedsYou.kt` / `A/Agents.kt`).
- Modify: `PV/RosterChromePreviews.kt`, `PV/RosterPreviews.kt`.
- Test: `KT/RosterArrangementTest.kt`, `UT/RosterPreferencesTest.kt`, `AT/FilterChipsTest.kt` (new),
  `AT/RosterTest.kt`, `AT/SpaceMenuTest.kt`, `AT/PullToRefreshTest.kt` (new; AppRoot + a counting
  `StubCore`).

**Interfaces:**
- Produces: `@Composable fun FilterChips(chips: List<RosterFilterChip>, selected: RosterFilter, onSelect: (RosterFilter) -> Unit, modifier: Modifier = Modifier)`
  (tags `filter-chips`, `chip-${rosterFilterKey(filter)}`, `chip-count-${key}`); the empty states
  tagged `roster-empty-none`, `roster-empty-filter`, `roster-empty-invitations`; the lists tagged
  `roster-list`, `needs-you-list`, `agents-list` keep their tags under the refresh box.

- [ ] **Step 0:** grep the bindings for `rosterFilterChips`, `spaceDisplayName`, `displayName`;
  grep `A/` for `PullToRefreshBox` availability (`androidx.compose.material3.pulltorefresh`).
- [ ] **Step 1: Write the failing tests.**
  - `KT/RosterArrangementTest.kt` (real core): `theChipsAreAllAndUnreadWithTheirCounts` (5 rows, 2
    unread → `[All 5, Unread 2]`); `anUnofferedKeyReadsAsAll` (`filterFromKey("agents") == ALL`,
    `filterFromKey("") == ALL`); `unreadNarrowsWithoutReordering` (the kept ids are in the
    arrangement's order).
  - `UT/RosterPreferencesTest.kt`: `theChipIsRemembered` (set UNREAD → reads UNREAD through a
    fresh instance over the same store); `aStoredAgentsChipReadsAsAll` (raw "agents" → ALL).
  - `AT/FilterChipsTest.kt` (`requiredWidth(411.dp)`):
    - `twoChipsTheSecondCounted`: chips `[All 5, Unread 2]` → "All" has no count node,
      `chip-count-unread` `assertTextEquals("2")`; `chip-all` `assertIsSelected()`.
    - `aZeroCountIsNotShown`: `[All 5, Unread 0]` → no `chip-count-unread`.
    - `theLabelSaysTheCount`: `chip-unread` contentDescription "Unread, 2".
    - `choosingAChipIsASelectionHaptic`: click Unread → `onSelect(UNREAD)`, haptics `[Selection]`.
    - `theRowScrollsAtFontScaleTwo` (RF2): font scale 2 at 411dp → both chips reachable with
      `performScrollTo`, each `lineCount == 1`.
  - `AT/RosterTest.kt`:
    - `unreadNarrowsTheRoster`: select Unread → only the two unread rooms' names remain, in the
      arrangement's order.
    - `theChipsAreAboveTheInvitationsRow`: `filter-chips` bottom ≤ `invitations-row` top.
    - `noRoomsShowsNoChipsAndSaysSo`: empty rows → no `filter-chips`; "No rooms yet" and
      "Rooms appear here as they sync."
    - `aChipThatLeavesNothingOffersShowAll`: no unread rooms, Unread selected → "Nothing unread",
      "Every room is read."; click "Show all" → All is selected and the rooms show.
    - `onlyInvitationsSaysSo`: two invitations and nothing else, row closed → "Nothing but
      invitations", "2 waiting — open Invitations above."; open the row → that state goes.
    - `sectionTitlesAreReadable`: Machine arrangement over a host named
      `Rakeshs-MacBook-Pro.local` → the header says "Rakeshs-MacBook-Pro".
  - `AT/SpaceMenuTest.kt`: `spacesAreNamedReadably` — a space named `9247e5c1-…` → the item
    "Runtime 9247e5 · 3" and, selected, contentDescription "Space: Runtime 9247e5".
  - `AT/PullToRefreshTest.kt` (AppRoot + a `StubCore` that counts `roomsSnapshot()` calls and can
    hold one open):
    - `pullingChatsReseedsTheRoster`: `roster-list` `performTouchInput { swipeDown() }` → the count
      rose by exactly 1; while the stub holds the call the indicator is shown; released → gone.
    - `pullingNeedsYouAndAgentsReseedsToo`: the same on `needs-you-list` and `agents-list`.
- [ ] **Step 2: Run them and confirm they fail.**
- [ ] **Step 3: Implement.** The chips and the invitations row are the first `LazyColumn` items, so
  they scroll away as iOS's do. The stored chip is collected with `initialValue = ALL`. The section
  title map is `remember(sections) { sections.associate { it.id to it.title?.let(::spaceDisplayName) } }`.
  `PullToRefreshBox(isRefreshing, onRefresh = { scope.launch { refreshing = true; now = Instant.now(); try { session.rooms.seed() } finally { refreshing = false } } })`
  — `now` is the `AppRoot` clock (make it settable). No amber anywhere in the chips. Previews:
  `FilterChips` (iOS `RosterFilterChips.swift:60-71`: All 5, Unread 2), `FilterChipsUnreadSelected`,
  `FilterChipsLargeText`, `RosterNoRooms`, `RosterNothingUnread`, `RosterOnlyInvitations`,
  `SpaceMenuReadableNames`.
- [ ] **Step 4: Run the full verification** (one height). Roster frames with the new chip row
  change; inspect and record each.
  - **Falsify:** count from the filtered sections → `twoChipsTheSecondCounted` fails (Unread would
    count itself after selection); read the stored key without `filterFromKey` →
    `aStoredAgentsChipReadsAsAll` fails; show the chips with no rooms →
    `noRoomsShowsNoChipsAndSaysSo` fails; drop `spaceDisplayName` on section titles →
    `sectionTitlesAreReadable` fails; `onRefresh` that does not call `seed` →
    `pullingChatsReseedsTheRoster` fails (three runs: gesture).
  - **Device pass (read-only):** the chips over the live roster; Unread; pull to refresh on each of
    the three lists; the spaces menu's names.
- [ ] **Step 5: Commit.** `android: All and Unread chips, the roster's empty states, readable space names, pull to refresh`.

## Task 4: The welcome screen and the first-run demo (O1) — touches `MainActivity.kt`

**iOS behaviour and copy:**
- **Keys, per install, not per account** (`Onboarding/WelcomeView.swift:4-17`):
  **`onboarding.welcomeSeen`** (the welcome has been passed), **`onboarding.demoPending`** (an
  interactive sign-in just succeeded and the demo has not run), **`onboarding.demoSeen`** (shown —
  finished or skipped — once, ever).
- **Signed out** (`:19-32`): the welcome until it is passed, the sign-in form after it.
- **The welcome** (`:35-83`): top to bottom, spacer (min 24); the mark at **132** wide, labelled
  **"supermessage"**, scaling 0.94 → 1 on a 0.6 s spring with bounce 0.3 as it appears — **scale
  only, never opacity**, and no animation under Reduce Motion; **"supermessage"** in large title
  bold, top padding 20; **"Chat with your team and your agents — and approve their work without
  leaving the conversation."** in body, `contentMuted`, centred, top padding 10, horizontal 8;
  spacer (min 24); **"Sign in"** — headline, `accentContent` on `accent`, full width, large;
  padding 28, at most 460 wide, centred, on `surface`.
- **When the demo is queued** (`LoginView.swift:115-126`): only after an **interactive** sign-in
  that reached signed-in; a restored session never queues it.
- **When it shows** (`RootView.swift:89-139`): over the signed-in shell, full screen, when
  `demoPending && !demoSeen && newRecoveryKey == null` — **it waits for the first sign-in's recovery
  key**, and the key can still be shown over it (`:124-133`). Finishing or skipping sets
  `demoSeen = true`, `demoPending = false` (`:136-139`).
- **The demo** (`Onboarding/FirstRunDemoView.swift:17-147`, `SupermessageKit/FirstRunDemo.swift:15-39`),
  every word and pause from the core's `firstRunScript()`:
  - a top bar titled `agentName` ("Atlas") with `skipLabel` ("Skip") at the trailing end on every
    step **except approved**;
  - a scrolling column, spacing 16, padding 16, at most 560 wide, centred, on `surface`;
  - `banner` ("A sample conversation — nothing here is sent anywhere.") in meta, `contentFaint`,
    centred, bottom padding 4;
  - on **greeting**: the typing bubble — the avatar (`RoomAvatar(roomId = script.roomId, initial =
    script.initial, describesAgent = true, state = ACTIVE, size = 32)`), then an ellipsis glyph in
    title3 bold `contentMuted`, animating unless Reduce Motion, padding 14/10, `surfaceRaised`,
    corner 14, labelled `typingLabel` ("Atlas is typing"); from **asking** on, the message instead:
    the avatar, then a column (spacing 4) of the name (name face) with `agentTag` ("Agent", caption2
    semibold, `accent` on `accentSoft`, capsule, padding 6/1), and `greeting` in the body face,
    padding 12, `surfaceRaised`, corner 14;
  - on **waiting** and **approved**: the real decision card — `DecisionCard(view = script.card,
    label = script.cardLabel, eventType = script.cardEventType, senderName = script.agentName,
    onDecide = { demo.answer(it.optionId) })`;
  - on **approved**: a check-circle glyph at 40 in `ok` (one bounce, hidden from accessibility),
    `approvedTitle` ("That's it — approvals from chat.") title3 semibold centred, `approvedBody`
    ("When an agent needs you, it asks in its room, and the Needs you tab keeps the list.")
    subheadline `contentMuted` centred, and `finishLabel` ("Get started") — headline,
    `accentContent` on `accent`, full width, large, top padding 4; the group spacing 14, top
    padding 12;
  - pacing: after each of `stepDelaysMs` (1100, then 900) the step advances
    (`firstRunAdvance`); it stops at the card; **under Reduce Motion the pace stays** (reading
    time, not animation, `:94-101`);
  - arrivals move up from the bottom with a fade on a 0.45 s spring (bounce 0.2); under Reduce
    Motion a fade only (`:80`, `:104-106`);
  - a success haptic when the step becomes approved (`:93`).
  - Any answer advances (`firstRunAnswer(WAITING) == APPROVED`, whichever option), as on iOS.

**Files:**
- Create: `K/FirstRunDemo.kt` (step and answer as `StateFlow`s over the free FFI functions; no
  client), `A/Welcome.kt` (`SignedOutContent`, `WelcomeScreen`), `A/FirstRunDemo.kt`
  (`FirstRunDemoScreen`), `A/FirstRunPreferences.kt` (the three keys, on the app's one DataStore —
  `MainActivity.kt`'s `rosterDataStore`; one DataStore per file).
- Modify: `A/MainActivity.kt` — `signedOutContent` becomes `SignedOutContent`; the sign-in lambda
  sets `demoPending` when `session.phase` is `SIGNED_IN` after `signIn` returns; the demo composed
  **before** `NewRecoveryKeyDialog` so the key dialog draws above it; `AppRoot` takes
  `firstRun: FirstRunPreferences`.
- Create (debug source set): `android/app/src/debug/kotlin/dev/supermessage/DebugLaunch.kt` — the
  extra `dev.supermessage.debug.firstRun` (boolean) sets `demoPending = true, demoSeen = false`
  once, so the demo can be shown on a signed-in device without signing in (iOS's `-openAccount`
  precedent, `RootView.swift:103-112`). The release build has no such path.
- Modify: `scripts/tokens/emit_mark.py`, `scripts/generate-tokens.py`, `scripts/tests/test_emit.py`
  — a new output `android/app/src/main/res/drawable/mark.xml`: the mark alone, its viewport cropped
  to the mark (the launcher foreground carries the adaptive-icon safe zone). Then
  `python3 scripts/generate-tokens.py` and the token unittests.
- Create: `PV/FirstRunPreviews.kt`.
- Test: `KT/FirstRunDemoTest.kt` (real FFI, host `.so`), `UT/FirstRunPreferencesTest.kt`,
  `AT/FirstRunTest.kt` (new), `AT/PhaseGateTest.kt` / `AT/LoginScreenTest.kt` (the welcome before
  the form).

**Interfaces:**
- Produces (Kit):
  ```kotlin
  class FirstRunDemo {                       // no CoreClient, no Session: nothing to send through
      val script: FirstRunScript             // firstRunScript()
      val step: StateFlow<FirstRunStep>      // GREETING
      val answer: StateFlow<String?>
      fun advance()                          // step = firstRunAdvance(step)
      fun answer(optionId: String): Boolean  // firstRunAnswer(step) ?: return false
  }
  ```
- Produces (app): `@Composable fun WelcomeScreen(onSignIn: () -> Unit)` (tags `welcome`,
  `welcome-mark`, `welcome-sign-in`; a `SemanticsPropertyKey<Boolean>("Arriving")` on the mark);
  `@Composable fun FirstRunDemoScreen(onFinish: () -> Unit, demo: FirstRunDemo = remember { FirstRunDemo() }, delay: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) })`
  (tags `first-run`, `first-run-banner`, `first-run-typing`, `first-run-message`, `first-run-card`,
  `first-run-approved`, `first-run-skip`, `first-run-finish`) — **no session parameter**;
  `class FirstRunPreferences(store)` with `welcomeSeen`, `demoPending`, `demoSeen` flows and setters.

- [ ] **Step 0:** grep the bindings for `firstRunScript`, `FirstRunStep`; read
  `crates/supermessage-core/src/first_run.rs` `script()` for the exact field names.
- [ ] **Step 1: Write the failing tests.**
  - `KT/FirstRunDemoTest.kt` (real core): `itOnlyGetsPastTheCardOnTheReadersAnswer` (port of the
    core's test through the Kit: `answer("approve")` on GREETING is `false`; two `advance()` → WAITING;
    a third stays WAITING; `answer("approve")` → APPROVED, `answer.value == "approve"`; a second
    answer is `false`); `theScriptIsTheCores` (`script.banner == "A sample conversation — nothing here is sent anywhere."`,
    `script.stepDelaysMs == listOf(1100uL, 900uL)`); `theDemoHasNoClient` (reflection: no
    constructor parameter and no field of type `CoreClient`, `Session` or `CoreInterface`).
  - `UT/FirstRunPreferencesTest.kt`: the three keys' exact names; defaults false.
  - `AT/FirstRunTest.kt`:
    - `aFirstLaunchSignedOutWelcomes`: `SIGNED_OUT`, fresh prefs → `welcome` with "supermessage",
      the tagline and "Sign in"; no Homeserver field. Click "Sign in" → the form; recompose from the
      same prefs → still the form (`welcomeSeen` stored).
    - `theMarkScalesButNeverFades`: `mainClock.autoAdvance = false`; at the first frame the mark's
      `alpha` (read from its `graphicsLayer`) is 1 and `Arriving` is true; under Reduce Motion
      `Arriving` is false.
    - `anInteractiveSignInQueuesTheDemo`: sign in through the form (StubCore accepts) → `first-run`
      shown with the banner; a `start()` that restores the session (StubCore `restoreSession` true)
      with fresh prefs → no `first-run`.
    - `theDemoWaitsForTheRecoveryKey`: sign-in with `newRecoveryKey` set → the key dialog shows and
      `first-run` does not; dismiss the key → `first-run` shows.
    - `theDemoIsPacedAndStopsAtTheCard`: an injected `delay` that records its arguments → after
      composition the step is GREETING with "Atlas is typing" as the typing bubble's label;
      the recorded delays are `[1100, 900]`; then "Hi — I'm Atlas, a sample agent. I'd like to tidy
      the release notes before today's build.", "Agent" and the card's "Approve" are displayed; no
      approved texts.
    - `theReduceMotionPaceIsTheSame`: with `LocalReduceMotion provides true` the recorded delays are
      still `[1100, 900]`.
    - `approvingFinishesTheDemo`: click "Approve" → "That's it — approvals from chat.", the body, and
      "Get started"; `first-run-skip` gone; haptics include `Success` once; click "Get started" →
      `first-run` gone; `demoSeen` true, `demoPending` false; recompose → it does not return.
    - `skipIsThereUntilApproved`: on GREETING and on WAITING `first-run-skip` ("Skip") is displayed;
      clicking it finishes as above.
    - `theDemoMakesNoCoreCallsFromStartToFinish` (RF5): AppRoot over a recording `StubCore`; after
      sign-in, clear the recording; run the demo to "Get started" → the recording holds no call at
      all (in particular no `sendGateDecision`, `sendPermissionAnswer`, `send`, `sendMessage`).
    - `theDemoFitsAtFontScaleTwo` (RF2): font scale 2 at 411dp → "Get started" reachable by
      `performScrollTo` and displayed; the greeting bubble not clipped.
    - `theWelcomeFitsAtTheShortHeight` (run at 1080x1280 too): "Sign in" displayed without overlap
      with the tagline.
- [ ] **Step 2: Run them and confirm they fail.**
- [ ] **Step 3: Implement.** The demo is a full-screen `Dialog(properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false))`
  (iOS `fullScreenCover`) over the signed-in shell, dismissed only by Skip or Get started (system
  back is Skip). The pacing is a `LaunchedEffect(Unit) { for (d in script.stepDelaysMs) { delay(d.toLong()); demo.advance() } }`.
  The typing ellipsis is three dots stepping alpha (as M4's dock), still under Reduce Motion.
  Arrivals: `AnimatedVisibility(enter = motion(reduce, slideInVertically { it / 2 } + fadeIn(spring(dampingRatio = 0.6f, stiffness = …))), …)`
  — translation and opacity only. Previews: `Welcome`, `WelcomeDark` (iOS `WelcomeView.swift:86-96`),
  `FirstRunDemoGreeting`, `FirstRunDemoWaiting`, `FirstRunDemoApproved`, `FirstRunDemoDark`
  (iOS `FirstRunDemoView.swift:150-160`), `FirstRunDemoLargeText`; each renders still at a fixed step.
- [ ] **Step 4: Run the full verification** (1080x2400), then `FirstRunTest` and `PhaseGateTest` at
  `adb shell wm size 1080x1280`, then `adb shell wm size 1080x2400`. Then the token checks:
  `python3 scripts/generate-tokens.py && git diff --exit-code` (no drift after regenerating) and
  `python3 -m unittest discover -s scripts/tests -t .`.
  - **Falsify:** queue the demo in `start()` too → `anInteractiveSignInQueuesTheDemo` fails; drop
    `newRecoveryKey == null` → `theDemoWaitsForTheRecoveryKey` fails; animate the mark's alpha →
    `theMarkScalesButNeverFades` fails; skip the delays under Reduce Motion →
    `theReduceMotionPaceIsTheSame` fails; hand the card `session::sendGateDecision` →
    `theDemoMakesNoCoreCallsFromStartToFinish` fails (and the reflection test, if a field is added).
  - **Device pass (read-only, phone AVD):** `adb shell am start -n dev.supermessage/.MainActivity --ez dev.supermessage.debug.firstRun true`
    → the demo over the live shell; wait for the card; Approve; Get started. Nothing is sent: check
    `adb logcat` shows no send in the core's tracing for the run. The welcome's device check goes to
    the owner's checklist (it needs a signed-out install).
- [ ] **Step 5: Commit.** `android: a welcome before sign-in, and a first-run demo where nothing is sent`.

## Task 5: `supermessage://` deep links, and the branch-end pass — touches `MainActivity.kt`, `AndroidManifest.xml`

**iOS behaviour and copy:**
- **The link** (`SupermessageKit/Notifications/WidgetSnapshot.swift:326-365`, `AppLink`):
  `supermessage://open?room=<id>[&event=<id>]`. The room is a **query item**, because a Matrix id
  carries `!` and `:`. `roomId(in:)` accepts only scheme `supermessage`, host `open`, and a
  non-empty `room` (percent-decoded); anything else is not a link. `event` names the card the link
  is about and does not change where it opens.
- **Registered** for the scheme (`Platform/Info.plist:67-78`).
- **Handled** (`Platform/AppLinks.swift:6-12`): a link becomes the same request a tapped
  notification makes — `NotificationRouter.request(roomId:)`
  (`SupermessageKit/Notifications/NotificationRouter.swift:17-29`: one pending room, `consume()`
  clears it).
- **Opened** (`PhoneShell.swift:72-80`, `SplitShell.swift:89-93`): **always in Chats**, so Back lands
  somewhere that lists it; a request made **before sign-in is honoured once the shell exists**
  (`initial: true`). On the tablet it selects the room in the detail pane.

**Files:**
- Create: `K/AppLink.kt` (`object AppLink { const val SCHEME = "supermessage"; fun room(roomId: String): String; fun decision(roomId: String, eventId: String): String; fun roomId(uri: String): String? }`
  over `java.net.URI` and `URLDecoder` — the iOS shape, so M7's Glance widgets build links from the
  same file), `K/NavigationRequests.kt` (`request(roomId)`, `pending: StateFlow<String?>`,
  `consume(): String?`, `clear()`).
- Modify: `android/app/src/main/AndroidManifest.xml` — on `.MainActivity`,
  `android:launchMode="singleTop"` and an intent filter: `VIEW`; `DEFAULT`, `BROWSABLE`;
  `<data android:scheme="supermessage" android:host="open" />`.
- Modify: `A/MainActivity.kt` — `onCreate` and `onNewIntent` hand `intent.data` to
  `AppLink.roomId` → `requests.request(id)`; `AppRoot(…, requests: NavigationRequests)` consumes when
  `phase == SIGNED_IN`: destination = CHATS, `session.rooms.select(id)`, `session.open(id)`, open
  the detail pane; sign-out clears the request.
- Modify: `README.md` (the Android cells of N1, N2, N3, R3, O1, deep links; pull to refresh and
  readable space names in their rows), against the merged code.
- Create: `.superpowers/sdd/m5-android/m5-android-verification.md` (the run's evidence record).
- Test: `KT/AppLinkTest.kt`, `KT/NavigationRequestsTest.kt`, `AT/ManifestTest.kt`,
  `AT/DeepLinkTest.kt` (new; AppRoot + `StubCore`).

- [ ] **Step 1: Write the failing tests.**
  - `KT/AppLinkTest.kt`: `roundTrip` (`roomId(room("!a:x")) == "!a:x"`, and with
    `decision("!a:x", "$e")`); `percentEncodingIsDecoded` (`supermessage://open?room=%21a%3Ax` →
    `!a:x`); `notOursIsNull` (other scheme, host `rooms`, no `room`, `room=` empty,
    `https://matrix.to/#/!a:x` → each `null`).
  - `KT/NavigationRequestsTest.kt`: `consumeReturnsThePendingRoomOnce`; `aLaterRequestWins`.
  - `AT/ManifestTest.kt`: `theLinkResolvesToTheApp` — `packageManager.queryIntentActivities(Intent(ACTION_VIEW, Uri.parse("supermessage://open?room=%21a%3Ax")).addCategory(CATEGORY_BROWSABLE), 0)`
    contains `dev.supermessage.MainActivity`.
  - `AT/DeepLinkTest.kt`:
    - `aLinkOpensTheRoomInChats` (RF3, 411dp): Needs you selected; `requests.request("!a:x")` →
      Chats is selected, the detail pane shows `!a`'s header, StubCore saw `open("!a:x")`; back →
      the Chats roster.
    - `aLinkBeforeSignInOpensAfterIt`: request while `SIGNED_OUT`, then sign in → the room opens.
    - `aLinkOnATabletSelectsTheRoom` (840dp shell): the detail pane shows it; the rail says Chats.
    - `signingOutDropsAPendingLink` (RF1): request while signing out, sign in as another account →
      no room opens, `pending.value == null`.
    - `aRoomTheRosterDoesNotHaveIsStillAsked` — the core decides: StubCore's `open` throws
      `RoomChanged` for an unknown id → the shell stays on Chats with no detail pane and the
      failure line the existing `open` path shows (no crash).
- [ ] **Step 2: Run them and confirm they fail.**
- [ ] **Step 3: Implement** as above. `onNewIntent` calls `setIntent(intent)` first.
- [ ] **Step 4: Run the full verification, then the branch-end pass.**
  - Full instrumented suite at **both heights**: 1080x2400, then `adb shell wm size 1080x1280` and
    the same run, then `adb shell wm size 1080x2400`.
  - `NavigationTest` and `DeepLinkTest` at `adb shell wm size 2205x2400`; then stop the phone
    emulator, boot `supermessage-tablet`, run `scripts/android-ci-parity.sh`'s timezone step only
    (keep the tablet's own size) and those two classes once, portrait and landscape.
  - **Falsify:** drop the intent filter → `theLinkResolvesToTheApp` fails; open the link in the
    current destination → `aLinkOpensTheRoomInChats` fails; consume while signed out →
    `aLinkBeforeSignInOpensAfterIt` fails; skip the clear on sign-out →
    `signingOutDropsAPendingLink` fails.
  - README: correct the Android cells against the merged code.
  - Fill `m5-android-verification.md` with the exit table below: one line per row, ✅ only with a
    test name or a screenshot.
  - **Device pass** (phone AVD, then the tablet AVD; read-only; report each as seen or not seen):
    the bar and the rail with the badge; Needs you (or "You're all caught up"); Agents with
    statuses; Search from the destination; the chips and Unread; pull to refresh on the three
    lists; readable space names; `adb shell am start -a android.intent.action.VIEW -d 'supermessage://open?room=<an id from the roster>'`
    with the app on Needs you → the room in Chats; the debug demo extra; font scale 2.0 for each.
  - **Owner checklist**, written into the verification file (the implementer does not do these):
    on a fresh install, the welcome then the form; sign in → the recovery key, then the demo; skip
    or finish; reopen → no demo; a widget- or notification-free link from another app (e.g. a
    `supermessage://` link in a note) opens the room.
- [ ] **Step 5: Commit.** `android: supermessage:// links open the room; README after parity M5`.

---

## Excluded, and why

| Item | Why it is not in this plan | Where |
|---|---|---|
| Search scope (this room / everywhere) | Done in M2 Task 11; Task 2 only moves the panel into a destination. | M2 |
| The spaces menu and the invitations row | M2 Task 11; Task 3 only makes the names readable (M1.6). | M2 |
| `RoomAvatar`, `StatusPill`, two-line rows | M4 Task 4; reused here. | M4 |
| Agents and Needs you as chips; a Mentions chip | Not offered by the core (`OFFERED_FILTERS`, `roster.rs`); iOS dropped them because they are destinations (`RosterFilterChips.swift:7-9`); Mentions "joins when the core exposes it". | Core backlog |
| Notification taps and widget links into `NavigationRequests` | No notifications or widgets yet; Task 5's Kit types are what they will call. | M6, M7 |
| `https://matrix.to` app links | iOS registers only `supermessage://`; a verified https link needs a domain this app does not control. | — |
| Per-tab navigation stacks | One `ListDetailPaneScaffold` gives the same Back behaviour (Decision 1). | — |
| Account → Chats arrangement and the agent-state toggle | M2 Task 13. | M2 |
| A fleet-wide inbox and badge (ignoring the selected space) | iOS's inbox follows the space the roster is narrowed to; changing it is owner Q1 and a core change for all three hosts. | Owner Q1 |
| A4 Stop | Blocked on AgentPod's cancel event. | — |

## Exit: rows this plan turns ✅

| Matrix row | Task | Verified by |
|---|---|---|
| N1 four destinations (bar on phones, rail on tablets) | 2 | `NavigationTest`, `PaneLayoutTest`; device pass (phone, tablet) |
| N2 Needs you inbox, badge, "You're all caught up" | 1, 2 | `NeedsYouTest`, `RosterArrangementTest`, `theBadgeCountsDecisionsOnly`; device pass |
| N3 Agents view with status and last activity | 1 | `AgentsTest`, `AgentStatusTest`; device pass |
| R3 filters (All, Unread) | 3 | `FilterChipsTest`, `RosterTest`, `RosterPreferencesTest` |
| O1 welcome and local first-run demo | 4 | `FirstRunTest`, `FirstRunDemoTest`; debug-extra device pass; owner checklist (welcome) |
| Deep links (`supermessage://open?room=`) | 5 | `AppLinkTest`, `ManifestTest`, `DeepLinkTest`; `am start` device pass |
| Pull to refresh | 3 | `PullToRefreshTest`; device pass |
| Readable space names | 3 | `sectionTitlesAreReadable`, `spacesAreNamedReadably` |
| Review Focus 1–5 (this plan's) | 2, 3, 4, 5 | the tests named in Review Focus |

## Android behaviour changes (visible)

1. A bottom bar (phones) or a rail (tablets, and phones in landscape) with Chats, Needs you, Agents
   and Search; the bar hides inside a room.
2. "Search" is no longer a button above the roster; on a phone it searches everywhere unless a room
   is open on a tablet beside it.
3. "New room" and "Account" become an icon toolbar: "New conversation" and your initial.
4. Chips "All · Unread" above the roster; the roster says when it is empty and why.
5. Space names read as people say them ("Runtime 9247e5", no `.local`).
6. Pull down to refresh Chats, Needs you and Agents.
7. A welcome before the first sign-in form; a one-time demo after the first interactive sign-in.
8. `supermessage://open?room=` opens the room in Chats.

## Decisions taken in this plan (technical; the controller may overrule)

1. **One list/detail scaffold, not four stacks.** iOS's per-tab `open` state matters only because a
   tab could keep a room open behind another tab; with the bar hidden in a room that cannot happen
   on a phone, and the iPad shares one detail column. The destination is the list pane's content.
2. **System back from Needs you, Agents or Search selects Chats** before leaving the app (Android's
   convention for a bottom bar; iOS has no system back). Owner Q5 may reverse it.
3. **Layout type from measured width** (bar under 600dp, rail from 600dp), passed to
   `NavigationSuiteScaffold` explicitly — the AGENTS.md rule; the default would read
   `WindowSizeClass`.
4. **Icons from `material-icons-extended`** (Apache-2.0, BOM-managed; R8 drops the unused ones):
   `Forum`, `Inbox`, `AutoAwesome`, `Search`, `Verified`. `material-icons-core` has no tray or
   sparkle. If M4 already added it, nothing changes.
5. **The welcome mark is a new generator output** (`res/drawable/mark.xml`), not the launcher
   foreground scaled past its safe zone; the mark stays drawn by `emit_mark.py`, as every other copy
   is.
6. **`AppLink` and `NavigationRequests` live in `:kit`** — the link is a contract between the app
   and its future widgets, as iOS keeps it in the shared Kit file, and no core rule is involved
   (the room id is opaque; the core decides what opening it means).
7. **The demo is shown on a debug build through a launch extra** so a read-only device pass can see
   it without signing out the live account.

Open questions for the owner are in `.superpowers/sdd/planning-m5/open-questions.md`.
