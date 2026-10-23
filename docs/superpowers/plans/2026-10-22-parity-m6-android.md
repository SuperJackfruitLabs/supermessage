# Parity M6 (Android): notifications and voice. Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task by task. Steps use
> checkbox (`- [ ]`) syntax for tracking. Each task is a bundle: one implementer dispatch, targeted
> tests while iterating, the full suites once before the commit, one review. **An implementer reads
> only its own task, plus Global Constraints and Review Focus.**

**Goal:** turn these Android rows of the parity matrix ✅:
- **P2:** local notifications worded by the core, with Allow once / Reject and Approve / Request
  changes / Reject. They answer with no room open and require device unlock, as iOS does.
- **P1:** background push over UnifiedPush (gate G3, resolved), on the same `event_id_only`
  pusher and `Core::notification_for`.
- **C2:** voice message recording.

**Architecture:**
- **All work is in `android/`.** This track never edits `crates/`, never edits the generated
  bindings, and never runs `build-android-libs.sh`.
- **One `Core` per process.** Today `SessionViewModel` builds the Rust core, so nothing outside an
  Activity can reach it. A notification action, and a push that arrives while the app is closed,
  run with no Activity. Task 1 moves construction into a process-wide `AppCore` holder. The
  `Session` stays ViewModel-scoped, as its KDoc requires. Only the core object is shared. That
  matches iOS, where `CoreClient.shared` serves both the app and a background action
  (`Supermessage/Platform/AppDelegate.swift:172-180`).
- **Kit** (`android/kit`, JVM-testable) ports iOS's host logic that the core does not own: the
  notification coordinator's lifecycle (catch-up, mode cache, notified set), the bounded answerer,
  and the recorder's state machine.
- **App** (`android/app`) holds the Android surfaces: channels, `NotificationCompat` posting, the
  action receiver, the UnifiedPush `SupermessagePushService`, `MediaRecorder`, and the composer UI.
- **The core decides what a notification says, which actions it has, and what an action means**
  (prerequisite CP1). It also gives the recorder's numbers and the waveform (CP2). Kotlin places
  their outputs and decides nothing else.

**Tech stack:** Kotlin 2.4.0, Compose (BOM 2026.06.01), AGP 9.3.1, compileSdk 36, minSdk 31,
targetSdk 36. Tests use JUnit4, compose-ui-test, Robolectric 4.15.1 (API 35 via `@Config`) and
Roborazzi 1.74.0. New dependency: `org.unifiedpush.android:connector:3.3.5` (Task 4).

**Spec:** `docs/research/2026-10-22-ios-parity/README.md` rows P1, P2 and C2 (`:151`, `:162-163`),
and `notes/android.md`. Umbrella: `docs/superpowers/plans/2026-10-22-ios-parity-roadmap.md` "M6"
and Review Focus 2–5. Gate decisions: `.superpowers/sdd/planning-m1-m2/owner-decisions.md`
(G3 resolved; the Pace decision). Questions and prerequisites:
`.superpowers/sdd/planning-m6-m7/open-questions.md`. Format follows
`.superpowers/sdd/planning-m4/2026-10-22-parity-m4-android.md`.

**iOS citations** are to `apple/` at `origin/main` `2f7ecbe`. M1b may move lines in the files it
touches, so find a moved line by the quoted text. Core paths are `C=crates/supermessage-core/src`.

Paths used below:
- `A=android/app/src/main/kotlin/dev/supermessage`
- `K=android/kit/src/main/kotlin/dev/supermessage/kit`
- `AT=android/app/src/androidTest/kotlin/dev/supermessage`
- `UT=android/app/src/test/kotlin/dev/supermessage`
- `KT=android/kit/src/test/kotlin/dev/supermessage/kit`
- `KF=android/kit/src/testFixtures/kotlin/dev/supermessage/kit` (`StubCore`, an `open class` whose every method throws `NotImplementedError`; tests subclass it)
- `PV=android/app/src/debug/kotlin/dev/supermessage/previews`

Android line numbers are at `f5d873f` (`parity/m2-android`). Find code by the quoted symbol.

**Branch and worktree:** `parity/m6-android`, in `.worktrees/parity-android`, cut from the tip of
`parity/m5-android` after its last task.

**Preconditions (controller, before Task 1):**
- M2–M5 Android are complete. M5's deep link `supermessage://open?room=<id>[&event=<id>]` opens a
  room from an `Intent` (roadmap M5, "Android: deep links"). Tasks 2–4 point every notification
  tap at it.
- The branch contains `origin/main` with M1a and M1b merged, and the libraries are rebuilt.
- **Before Task 2:** CP1 has merged and the libraries are rebuilt. Task 1 needs no prerequisite.
- **Before Task 5:** CP2 has merged.

### Interfaces consumed

| Kotlin (generated) | From | State |
|---|---|---|
| `CoreInterface.restoreSessionQuietly(): Boolean`, `notificationFor(roomId, eventId): NotificationDto`, `registerPusher(PushRegistration)`, `unregisterPusher()`, `sendGateDecisionTo(roomId, gateId, optionId, comment: String?, inReplyTo, prompt)`, `sendPermissionAnswer(roomId, optionId)`, `attachmentMarkVoice(roomId, token, durationMs: ULong, waveform: List<Float>)`, `attachmentStagePath(roomId, path)` | `crates/supermessage-ffi/src/lib.rs:348-361, 656, 709, 820-870` | merged |
| `PushRegistration(pushkey, appId, appDisplayName, deviceDisplayName, lang, gatewayUrl)` | `C/push.rs:26-41` | merged |
| `NotificationDto` (`suppress`, `threadId`, …), `NotificationCategory` | `C/notification.rs:27-142` | merged |
| `NotificationContext`, `PlannedNotification`, `plannedFromDecided`, `notificationsForRoster`, `notificationsForTimeline`, `notificationDelivers`, `notificationPresentsRemote`, `notificationActions(category): List<NotificationActionSpec>`, `notificationUserInfo`, `notificationResponse(actionId, userInfo, userText): NotificationResponse` (`Open`/`Answer`/`Ignore`), `NotificationAnswer` (`Permission`/`Gate`), `answerFailedNotification(roomId)`, `genericNotification(roomId, eventId, title)` | **CP1** | new |
| `voiceRecordingSpec(): VoiceRecordingSpec`, `voiceWaveform(decibels: List<Float>, bins: UInt): List<Float>`, `voiceLongEnough(durationMs: ULong): Boolean` | **CP2** | new |
| `elapsedLabel(elapsedMs: Long): String` | M1b C1 | merged by precondition |

Every task's Step 0 greps `android/core/src/main/kotlin/uniffi/` for the names it uses. A
renamed item is followed, not shimmed, and noted in the report. If something is missing, the task
stops; it never re-implements a CP rule in Kotlin.

## Global Constraints

**From the roadmap and AGENTS.md (binding):**
- **Tokens only:** no hex in views. Colours come from `SupermessageTheme.colors`; a notification's
  accent colour comes from the generated light-theme token in `GeneratedThemeTokens.kt`, never a
  literal. New type roles go through `design/tokens.toml` and `python3 scripts/generate-tokens.py`.
- **Amber (`signal`) means a pending decision and nothing else.** Only a decision notification
  (PERMISSION, GATE, DECISION) may use `signal` as its accent. Messages, the recording mic
  (`danger`) and failures never use it.
- **The app parses nothing the core parses, and decides nothing the core decides.** The core
  decides a notification's title, subtitle, body, category, actions, action copy and answer, and
  whether it is posted at all. It also decides the recorder's numbers and the waveform. The host
  parses only its own transport, the Matrix push gateway JSON, as iOS parses its APNs payload
  (`SupermessageKit/Notifications/RemotePush.swift`).
- **Match iOS behaviour and copy, not its widgets.** Every string is quoted verbatim with its iOS
  `file:line`. A string iOS lacks is marked *(no iOS copy)*.
- **Reduce Motion:** every animation goes through `motion(reducesMotion(), …)` (`A/Motion.kt`).
  Tests provide `LocalReduceMotion`.
- **Testing:** no single-character substring matchers. Test shells set the required **width**
  (`Modifier.requiredWidth(411.dp)`), never the height. Every new UI state ships an `internal`
  `@Preview` in `PV/`. System notifications are not Compose UI and get no preview; their tests read
  `NotificationManager` (Robolectric `shadowOf` in `UT`, `activeNotifications` in `AT`).
- **Haptics** go through a recording `LocalHapticFeedback`, never a device vibration.
- **Falsify every new test** (AGENTS.md, "A test that has never failed"): apply the named mutation,
  watch the named test fail, restore it, and record the failure line in the report. For timing,
  gesture or async tests, run the mutant three times.

**Live account: READ-ONLY.** You may open the app, post **local** notifications, tap a
notification's body to open a room, record audio on the emulator, and stage, mark and discard a
recording. You may **not** register a pusher, unregister one, send an answer from an action or a
card, or send a voice note or any message. Prove each of those with `StubCore` fakes. List every
skipped write under "Live verification pending" in the task report.

**This machine (owner rules, 2026-10-22):**
- Every heavy command takes the shared lock:
  `flock -w 7200 $HOME/.cache/supermessage-heavy.lock <command>`. Heavy means any `./gradlew` run
  and any `cargo build|test|clippy`. Light means git and grep. `export CARGO_BUILD_JOBS=8`. Run
  `./gradlew --stop` when you finish.
- One emulator, `supermessage-phone` (API 36, `google_apis`, no Play Store), which you never
  restart mid-task. Emulator logs go in `TMPDIR=~/Android/dl`. Environment:
  `.superpowers/sdd/m2-android/env-android.md`.
- While iterating, run the focused class. Run the full suites **once per task**, before the commit.

**Verification for every task**, from `android/`, ending in `BUILD SUCCESSFUL` and 0 failures:
- `flock -w 7200 $HOME/.cache/supermessage-heavy.lock ./gradlew :kit:test :app:testDebugUnitTest`.
- From the repo root, `scripts/android-ci-parity.sh` (once per emulator boot).
- `flock -w 7200 $HOME/.cache/supermessage-heavy.lock ./gradlew :app:connectedDebugAndroidTest`
  **at one height, 1080x2400** (owner decision, 2026-10-22). For a single class, use
  `-Pandroid.testInstrumentationRunnerArguments.class=dev.supermessage.<Class>`.
- A task whose new UI depends on height also runs the affected classes at
  `adb shell wm size 1080x1280`, then restores `adb shell wm size 1080x2400`. None of this plan's
  UI is height-dependent except Task 5's pill, which sits above the keyboard.
- **Both heights for the whole suite** run once, at the end of the branch (Task 5).

**Commits** end with the attribution lines in the session's system reminder.

## Review Focus

1. **An answer from a notification whose send fails** (roadmap RF4): offline, 5xx, signed out, or
   past the budget. The reader gets "Your answer wasn't sent" / "Open the conversation to answer
   it there." (`Supermessage/Platform/LocalNotifier.swift:93-99`). Nothing ever says it was sent,
   and the card in the room stays answerable. Pinned in Task 3 by
   `aRefusedAnswerPostsYourAnswerWasntSent` and `aSendThatHangsIsGivenUpOnWithinTheBudget`.
2. **A tap that does something other than its label.** "Allow always" is never taken for "Allow
   once". An empty "Request changes" sends nothing. An option the gate does not offer is never
   sent (`SupermessageKit/Notifications/NotificationRouter.swift:113-117`). On Android an "open
   instead" outcome from a receiver **re-posts** the notification, because Android 12+ forbids
   starting an Activity from a receiver that a notification launched (the notification trampoline
   restriction). Pinned in Task 3.
3. **Sign out and back in as another account** (RF5). Every notification is withdrawn. The
   notified set, the roster baseline and the mode cache are forgotten. The pusher goes (the core's
   logout removes it first) and UnifiedPush is unregistered. Pinned in Tasks 1, 2 and 4.
4. **The same news twice.** A local and a remote notification for one event share a tag (the event
   id), so one replaces the other. A push for the room on screen, or for an event already posted,
   is not shown (`NotificationComposer.swift:285-292`). Pinned in Task 4.
5. **No Activity at all.** A notification action, and a push into a swiped-away app, must work
   with no UI, on the one process-wide core. Pinned in Task 1 by `aReceiverAndTheViewModelShareOneCore`,
   and in Tasks 3–4.
6. **Several agents and humans** (RF2). A message notification is titled with the sender the core
   named (`TimelineRow.sender_name`), and a decision with the room, never "the room" as the
   speaker (`C/notification.rs:303-309`). Pinned in Task 2.
7. **Font scale 2.0 at 411dp** (RF3): the Account → Notifications row and the recording pill must
   not clip. Checked in Tasks 4–5.

---

### Task 1: One core per process, notification channels, the permission ask, and the core wrappers **[session]**

No prerequisite. Every later task needs the core outside an Activity, the channels, and the
wrappers.

**iOS behaviour this ports:**
- `Supermessage/Platform/AppDelegate.swift:172-180`: an action is answered "through the app's one
  core directly". A background process has no scene and no `Session`.
- `Supermessage/Platform/PlatformCoordinator.swift:124-131` and `:161-173`: the permission is asked
  **after a fresh sign-in, never at launch**. A restored session never prompts (`justSignedIn =
  lastPhase == .signedOut`).
- `PlatformCoordinator.swift:143-155` and `LocalNotifier.swift:101-107`: on sign-out, every
  delivered notification is removed, and the notified set and roster baseline are forgotten.
- `LocalNotifier.swift:71-72`: one conversation per room. On Android that is `setGroup(roomId)`.

**Files:**
- Create: `A/AppCore.kt`, which holds the process-wide core, an application `CoroutineScope`
  (`SupervisorJob() + Dispatchers.Default`), and a test seam.
- Modify: `A/SessionViewModel.kt`. It takes its core from `AppCore` instead of constructing one.
  Update its KDoc: the core is process-wide so that a receiver can reach it, and the `Session`
  stays ViewModel-scoped for the reasons already given.
- Create: `A/notify/Channels.kt`, `A/notify/PlatformCoordinator.kt` (Android's
  `PlatformCoordinator`; Tasks 2–4 extend it).
- Create: `K/notify/PermissionAsk.kt`.
- Modify: `K/CoreClient.kt`. Add one-line wrappers: `restoreSessionQuietly()`,
  `notificationFor(roomId, eventId)`, `registerPusher(registration)`, `unregisterPusher()`,
  `sendGateDecisionTo(roomId, gateId, optionId, comment, inReplyTo, prompt)` and
  `attachmentMarkVoice(roomId, token, durationMs, waveform)`.
- Modify: `A/MainActivity.kt`. Register `ActivityResultContracts.RequestPermission` and start the
  coordinator.
- Modify: `android/app/src/main/AndroidManifest.xml`. Add
  `<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />`, with a comment
  citing this task.
- Create: `android/app/src/main/res/drawable/ic_stat_supermessage.xml`, a monochrome vector small
  icon traced from the launcher glyph's foreground (`res/mipmap-anydpi-v26/ic_launcher.xml`).
- Create: `docs/superpowers/plans/2026-10-22-parity-m6-android.md`, a copy of this plan (R10).
- Tests: `KT/notify/PermissionAskTest.kt`, `KT/CoreClientNotificationWrappersTest.kt`,
  `UT/notify/ChannelsTest.kt`, `UT/AppCoreTest.kt`, `AT/PlatformNotificationsTest.kt`, and
  `AT/ManifestTest.kt` (modify).

**Interfaces:**
- `object AppCore { fun core(context: Context): CoreInterface; val scope: CoroutineScope; @VisibleForTesting fun installForTest(core: CoreInterface?) }`.
  It builds `Core.withSecretStore(dataDir = context.applicationContext.filesDir.path, store =
  AndroidSecretStore(context.applicationContext))` lazily, once, under a lock.
- `fun shouldAskForNotifications(previous: Session.Phase?, now: Session.Phase, sdkInt: Int, granted: Boolean): Boolean`
  is true only for `SIGNED_OUT → SIGNED_IN` with `sdkInt >= 33` and permission not granted. On 31
  and 32, notifications are allowed without a runtime permission.
- Channels *(no iOS copy; iOS has no channels)*: `messages`, "Messages",
  `IMPORTANCE_DEFAULT`; `decisions`, "Decisions", `IMPORTANCE_HIGH`. `ensureChannels(context)` is
  idempotent.
- `class PlatformCoordinator(context, session, permissionLauncher: () -> Unit)` observes
  `session.phase`. On a fresh sign-in it calls the launcher. On `SIGNED_IN → SIGNED_OUT` it calls
  `NotificationManagerCompat.from(context).cancelAll()`.

- [ ] **Step 0: Check the base.** Confirm that `SessionViewModel` is the only `Core.withSecretStore`
  call (`grep -rn "withSecretStore" android/app/src`). Confirm that the generated `CoreInterface`
  has the six methods above. Record the answers.
- [ ] **Step 1: Write the failing tests.**
  - `PermissionAskTest`: `asksAfterAFreshSignIn`; `neverAsksOnARestore` (`STARTING → SIGNED_IN`);
    `neverAsksBelowApi33` (`sdkInt = 32`); `neverAsksWhenAlreadyGranted`.
  - `CoreClientNotificationWrappersTest`, with a recording `StubCore` subclass:
    `eachWrapperForwardsItsArgumentsUnchanged`, one assertion per wrapper, including
    `comment = null` and the `ULong` duration.
  - `ChannelsTest` (Robolectric): `decisionsInterruptAndMessagesDoNot`. Assert `decisions` is
    `IMPORTANCE_HIGH` and `messages` is `IMPORTANCE_DEFAULT`. `ensureChannelsTwiceChangesNothing`.
  - `AppCoreTest`: `aReceiverAndTheViewModelShareOneCore`. With `installForTest(fake)`,
    `AppCore.core(ctx)` and `SessionViewModel.forTest`'s path reach the same instance. A
    second call returns the same object.
  - `PlatformNotificationsTest` (instrumented): post two notifications through
    `NotificationManagerCompat`, drive a fake phase flow `SIGNED_IN → SIGNED_OUT`, and expect
    `activeNotifications` to be empty: `signingOutWithdrawsEveryNotification`.
  - `ManifestTest`: `declaresPostNotifications`.
- [ ] **Step 2: Run them and confirm they fail.**
- [ ] **Step 3: Implement.** No notification is posted by app code in this task, except in tests.
- [ ] **Step 4: Run the checks** (the full set, at one height).
  - **Falsify:**
    - make `shouldAskForNotifications` true for `STARTING` → `neverAsksOnARestore` fails;
    - give `decisions` `IMPORTANCE_DEFAULT` → `decisionsInterruptAndMessagesDoNot` fails;
    - build a fresh core in `SessionViewModel` again → `aReceiverAndTheViewModelShareOneCore` fails;
    - drop `cancelAll()` → `signingOutWithdrawsEveryNotification` fails.
  - Live: sign-in already exists, so the permission ask cannot be seen without signing out. Do
    not sign out. Live verification pending: the prompt after a fresh sign-in.
- [ ] **Step 5: Commit.** `android: one core per process, notification channels and permission ask`.

### Task 2: Local notifications, worded by the core **[session]**

Needs CP1. This is P2 without the actions, which Task 3 adds.

**iOS behaviour this ports** (the rules are now CP1's; the lifecycle stays host):
- `Supermessage/Platform/PlatformCoordinator.swift:93-117`: becoming active starts a **5-second
  catch-up** in which roster changes are not news (`:95`, `:226`). The timeline's "since" moves to
  now while catching up (`:239`).
- `:214-220`: the context is the open room, whether the app is active, the subscribed timeline
  room, and whether a pusher is registered.
- `:222-250`: roster → `forRoster` (now `notificationsForRoster`). Timeline → `forTimeline` (now
  `notificationsForTimeline`), with "since" reset when the subscribed room changes (`:235-238`).
  Every event id returned is added to the notified set **whether or not it is delivered**
  (`:245-248`).
- `:252-269`: each note passes `delivers(note, mode)` (now `notificationDelivers`). The room's mode
  is cached for **60 seconds** (`:264-265`).
- `LocalNotifier.swift:65-81`: a notification has the title, the subtitle when there is one, the
  body, the category, the thread per room, and the default sound. Its identifier is the planned
  `id`, so posting the same thing twice replaces it (`NotificationComposer.swift:54-56`).
- `NotificationRouter.swift:4-31`: a tap opens the room through the router. On Android that is the
  M5 deep link.

**Files:**
- Create: `K/notify/NotificationCoordinator.kt`. It holds the host-owned state (`previousRooms`,
  `timelineRoomId`, `timelineSinceMs`, `notified: MutableSet<String>`, `catchUpUntilMs`,
  `modes: Map<String, Pair<NotificationMode?, Long>>`, `remotePush`), with an injected clock
  `() -> Long`, a `modeOf: suspend (String) -> NotificationMode?` and a `Poster`.
- Create: `K/notify/Poster.kt`: `fun interface Poster { fun post(note: PlannedNotification) }`.
- Create: `A/notify/AndroidPoster.kt`, built on `NotificationCompat.Builder`:
  - the channel is `messages` for `MESSAGE` and `decisions` for anything else;
  - `setSmallIcon(R.drawable.ic_stat_supermessage)`, `setContentTitle(title)`,
    `setContentText(body)` with `BigTextStyle(body)`, and `setSubText(subtitle)` when there is a
    subtitle;
  - `setGroup(roomId)`, `setAutoCancel(true)`;
  - `setColor(<light signal token>)` for decisions only;
  - the content intent is the M5 deep link `supermessage://open?room=<roomId>`, as a
    `PendingIntent.getActivity` with `FLAG_IMMUTABLE`;
  - posted with `notify(tag = note.id, id = 1, …)`.
- Modify: `A/notify/PlatformCoordinator.kt`. Feed the coordinator from `session.rooms`,
  `session.timeline`, the room on screen, and `ScenePhaseObserver` (`A/AppLifecycle.kt`). On
  sign-out, clear it.
- Modify: `K/Session.kt` only if it lacks a room's notification mode. Then add
  `suspend fun notificationMode(roomId): NotificationMode?` over `client.roomInfo(roomId)`,
  returning `null` on failure, as iOS's `Session.notificationMode(of:)` does.
- Tests: `KT/notify/NotificationCoordinatorTest.kt`, `UT/notify/AndroidPosterTest.kt`,
  `AT/LocalNotificationTest.kt`.

**Interfaces:**
- `class NotificationCoordinator(poster: Poster, modeOf: suspend (String) -> NotificationMode?, now: () -> Long)`.
  Methods: `fun activeChanged(active: Boolean)`; `suspend fun roomsChanged(rows: List<RoomRow>)`;
  `suspend fun timelineChanged(roomId: String?, roomName: String, rows: List<TimelineRow>)`;
  `fun openRoomChanged(roomId: String?)`; `fun setRemotePush(on: Boolean)`; `fun signedOut()`;
  `val context: NotificationContext`; `val notified: Set<String>`.
- `const val CATCH_UP_MS = 5_000L` cites `PlatformCoordinator.swift:95`, and
  `const val MODE_CACHE_MS = 60_000L` cites `:265`.

- [ ] **Step 0:** grep the CP1 names. Find where the open room id lives (the RootScaffold/RoomPane
  state that `key(currentRoomId)` wraps, `A/RoomPane.kt`) and how the timeline's subscribed room id
  is exposed. Confirm the light `signal` constant's name in `GeneratedThemeTokens.kt`.
- [ ] **Step 1: Write the failing tests.**
  - `NotificationCoordinatorTest`, with the real core functions (the host `.so`), a recording
    poster and a fake clock:
    - `theFirstRosterIsABaseline` (`NotificationComposerTests.swift:63-68`);
    - `aNewMessageInAnotherRoomPostsTheCoresPreview`;
    - `comingBackToTheForegroundIsNotABurst`: `activeChanged(true)`, then a roster with a raised
      unread inside 5 s → nothing; the same after 5 s → one note;
    - `aMutedRoomHoldsAMessageButNeverADecision` (`:330-345`, through `notificationDelivers`);
    - `theModeIsAskedOncePerMinute`: two notes 30 s apart → `modeOf` called once; 61 s apart →
      twice;
    - `aRowIsRecordedAsNotifiedEvenWhenHeldBack`: muted room, a message row → nothing posted, but
      `notified` contains the event id;
    - `aRoomSwitchStartsANewSince`: rows older than the switch never post;
    - `signingOutForgetsEverything` (RF5): after `signedOut()`, the next roster is a baseline and
      `notified` is empty;
    - `aMessageIsTitledWithItsSenderNotTheRoom` (RF2): a timeline row from `@lyra` in a room named
      "Atlas" → title "Lyra", subtitle "Atlas".
  - `AndroidPosterTest` (Robolectric):
    - `aMessageGoesToMessagesAndADecisionToDecisions`;
    - `postingTheSameIdReplaces` (one active notification after two posts with one tag);
    - `theTapOpensTheRoom` (the content intent's data is `supermessage://open?room=%21r%3Ax` for
      `!r:x`);
    - `onlyADecisionIsAmber` (`color` equals the signal token for a gate and is unset for a
      message);
    - `theSubtitleIsTheCores`.
  - `LocalNotificationTest` (instrumented, `StubCore`-backed `Session` via
    `SessionViewModel.forTest`): `aRaisedUnreadInAnotherRoomPostsANotification`. Drive two roster
    snapshots through the fake and expect `activeNotifications` to have one item with the core's
    title.
- [ ] **Step 2: Run them and confirm they fail.**
- [ ] **Step 3: Implement.** The coordinator calls the core and the poster, and keeps only the
  state above. `PlatformCoordinator` launches on `AppCore.scope`, so a roster change after the
  Activity stops still posts. The ViewModel's `Session` is the producer, so posts stop when it is
  cleared.
- [ ] **Step 4: Run the checks.**
  - **Falsify:**
    - set `CATCH_UP_MS = 0` → `comingBackToTheForegroundIsNotABurst` fails;
    - insert into `notified` only when delivered → `aRowIsRecordedAsNotifiedEvenWhenHeldBack` fails;
    - post with `tag = null` → `postingTheSameIdReplaces` fails;
    - colour every note → `onlyADecisionIsAmber` fails;
    - skip `signedOut()`'s reset → `signingOutForgetsEverything` fails.
  - Live (read-only): leave the app on the roster for a minute and watch real local notifications
    arrive. Posting is allowed. Tapping one opens its room. Screenshot one.
- [ ] **Step 5: Commit.** `android: local notifications worded by the core`.

### Task 3: Answer from the notification, with device unlock **[session]**

Needs CP1 and Task 2. Answering a gate from an action is proven with fakes here: on the JVM, and on
the device through the real `PendingIntent`.

**iOS behaviour and copy:**
- `Supermessage/Platform/LocalNotifier.swift:18-21`: every answer is `.authenticationRequired` and
  none is `.foreground`. The device must be unlocked to answer, and answering does not open the
  app.
- `:23-61` (now `notificationActions`):
  - "Allow once" and "Reject" (destructive) on PERMISSION;
  - "Approve", "Request changes" (text input: button "Send", placeholder "What should change?")
    and "Reject" (destructive) on GATE, plus "Open";
  - "Open" alone on DECISION.
- `SupermessageKit/Notifications/NotificationRouter.swift:113-145` (now `notificationResponse`):
  anything that cannot be answered exactly as labelled opens instead. That covers a missing
  option, a gate that does not offer the option, and "Request changes" with nothing typed
  (`:133-139`).
- `SupermessageKit/Notifications/NotificationAnswerer.swift:19-84`:
  - restore the stored session **without sync**, then send straight to the room;
  - a missing session means "not sent";
  - **bounded**, because the core call cannot be cancelled, so it is stopped being waited for
    ("Not a task group", `:55-58`).
- `Supermessage/Platform/AppDelegate.swift:172-180` and `LocalNotifier.swift:87-99`: an answer that
  did not land posts "Your answer wasn't sent" / "Open the conversation to answer it there."
  (now `answerFailedNotification`).

**Android differences (state them in the report):**
- **At most three action buttons.** Android shows three. GATE's fourth action, "Open", becomes the
  body tap, which iOS also treats as open (`NotificationRouter.swift:142-143`). DECISION keeps its
  "Open" button.
- **Unlock** is `NotificationCompat.Action.Builder.setAuthenticationRequired(true)` (platform
  API 31). minSdk is 31, so there is no version branch and no fallback. Every answering action sets
  it; "Open" does not, since launching an Activity already needs the device unlocked.
- The RemoteInput's label is the placeholder "What should change?". Android has no separate send
  title, so "Send" is not shown.
- **The receiver cannot start an Activity** (Android 12+ notification trampoline restriction). A
  `NotificationResponse.Open` decided inside the receiver re-posts the notification unchanged, so
  the reader can tap its body. Every answer action cancels its notification at once, which RemoteInput needs.
- **Budget: 9 s**, inside a `BroadcastReceiver.goAsync()`'s 10-second limit. It is not iOS's 15 s,
  which is the iOS system's 30 s budget halved.

**Files:**
- Create: `K/notify/NotificationAnswerer.kt`, with
  `interface NotificationAnswering { suspend fun restoreSessionQuietly(): Boolean; suspend fun sendPermissionAnswer(roomId: String, optionId: String); suspend fun sendGateDecisionTo(roomId: String, gateId: String, optionId: String, comment: String?, inReplyTo: String, prompt: String) }`
  (implemented by `CoreClient`) and
  `object NotificationAnswerer { suspend fun send(answer: NotificationAnswer, client: NotificationAnswering, budgetMs: Long, scope: CoroutineScope): Boolean }`.
  The work runs in `scope.async`, outside the caller's job, and the caller waits on
  `withTimeoutOrNull(budgetMs) { deferred.await() } ?: false`.
- Create: `A/notify/NotificationActionReceiver.kt` (`exported="false"` in the manifest).
- Modify: `A/notify/AndroidPoster.kt`. Add actions from `notificationActions(category)`. Each
  answering action is a broadcast `PendingIntent` to the receiver. Its extras are
  `notificationUserInfo(note)`, the action id and the tag. It uses a request code unique per
  (tag, action id), `FLAG_UPDATE_CURRENT`, and `FLAG_MUTABLE` only on the RemoteInput action
  (`FLAG_IMMUTABLE` otherwise). "Open" is an Activity `PendingIntent` to the deep link.
- Modify: `android/app/src/main/AndroidManifest.xml` (the receiver).
- Tests: `KT/notify/NotificationAnswererTest.kt`, `UT/notify/AndroidPosterActionsTest.kt`,
  `UT/notify/NotificationActionReceiverTest.kt`, `AT/NotificationActionTest.kt`.

- [ ] **Step 0:** grep the CP1 names (`notificationActions`, `notificationResponse`,
  `NotificationResponse.Answer`, `NotificationAnswer.Gate`). Confirm
  `androidx.core:core-ktx:1.15.0` has `NotificationCompat.Action.Builder#setAuthenticationRequired`.
- [ ] **Step 1: Write the failing tests.**
  - `NotificationAnswererTest`, ported from `SupermessageKitTests/NotificationAnswererTests.swift:58-127`,
    with a recording fake `NotificationAnswering`:
    - `aPermissionAnswerRestoresQuietlyThenSendsTheOption`: the call order is restore, then send;
    - `aGateAnswerSendsTheDecisionReferencingTheGateEvent`: `inReplyTo` is the gate event id and
      `prompt` is passed through;
    - `signedOutNothingIsSent` (restore → false);
    - `aRefusedSendIsNotSent`;
    - `aSendThatHangsIsGivenUpOnWithinTheBudget`: the fake blocks on a latch, the budget is
      200 ms, the result is false within 400 ms, and the latch is released after;
    - `aQuickAnswerIsNotHeldUntilTheDeadline`.
  - `AndroidPosterActionsTest` (Robolectric, reading `Notification.actions`):
    - `aPermissionOffersAllowOnceAndReject` (titles verbatim, in order);
    - `aGateOffersApproveRequestChangesRejectAndTheTapOpens`: three actions, with the content
      intent being the deep link;
    - `aDecisionOffersOnlyOpen`; `aMessageHasNoButtons`;
    - `everyAnswerRequiresUnlock`: `isAuthenticationRequired` is true for all five answering
      actions and false for "Open";
    - `requestChangesTakesTextLabelledWhatShouldChange`.
  - `NotificationActionReceiverTest` (Robolectric). It installs a recording `StubCore` subclass
    in `AppCore` and builds each Intent from the poster's own `PendingIntent`
    (`shadowOf(pendingIntent).savedIntent`):
    - `allowOnceSendsThePermissionOptionToItsRoom`;
    - `approveSendsTheGateDecisionReferencingTheGateEvent`;
    - `requestChangesSendsWhatWasTyped` (`RemoteInput.addResultsToIntent`, text " needs tests ",
      giving comment "needs tests");
    - `requestChangesWithNothingTypedSendsNothingAndRepostsTheNotification`;
    - `anOptionTheGateDoesNotOfferSendsNothing` (user info whose `sm.gateOptions` lacks `reject`);
    - `theNotificationIsWithdrawnTheMomentItIsAnswered`;
    - `aRefusedAnswerPostsYourAnswerWasntSent`: the tag is `"{room}#answer-failed"`, the title
      and body are verbatim, and no other notification says the answer was sent (RF4);
    - `anAnswerNeedsNoActivity`: `shadowOf(application).nextStartedActivity` is null.
  - `NotificationActionTest` (instrumented, one height):
    `approvingFromTheNotificationShadeSendsTheGateDecision`. Post a real GATE notification through
    `AndroidPoster`, with a recording fake core in `AppCore`. Find it in `activeNotifications` and
    `send()` its first action's `actionIntent`. Expect the fake to record `sendGateDecisionTo`
    with the gate's ids. Then `requestChangesFromTheShadeCarriesTheText`: `send(context, 0, fillIn)`
    with RemoteInput results.
- [ ] **Step 2: Run them and confirm they fail.**
- [ ] **Step 3: Implement.** The receiver:
  1. `goAsync()`;
  2. cancel by tag;
  3. `notificationResponse(actionId, userInfo, typed)`;
  4. on `Answer`, `NotificationAnswerer.send(…, budgetMs = 9_000, scope = AppCore.scope)`, then on
     false post `answerFailedNotification(roomId)`;
  5. on `Open`, re-post the original planned notification, which is kept in the extras as its user
     info;
  6. `finish()` in `finally`.
- [ ] **Step 4: Run the checks.**
  - **Falsify:**
    - drop `setAuthenticationRequired` → `everyAnswerRequiresUnlock` fails;
    - wait inside the caller's own job instead of `scope.async` →
      `aSendThatHangsIsGivenUpOnWithinTheBudget` fails (three runs);
    - send the comment untrimmed → `requestChangesSendsWhatWasTyped` fails;
    - skip the failure post → `aRefusedAnswerPostsYourAnswerWasntSent` fails;
    - offer "Open" as a fourth GATE button → `aGateOffersApproveRequestChangesRejectAndTheTapOpens`
      fails.
  - The emulator has no lock screen, so unlock is asserted by flag, not exercised. Live
    verification pending (owner): set a PIN, lock, Approve a gate from the lock screen, see the
    unlock prompt, and see the decision land in the room.
- [ ] **Step 5: Commit.** `android: answer permissions and gates from the notification`.

### Task 4: Background push over UnifiedPush **[session]**

Needs CP1 and Tasks 1–3. G3 resolved: the hub's gateway is APNs-only
(`agentpod/apps/hub/src/services/push/gateway.ts:283-286`), so Android uses UnifiedPush. The
distributor's Matrix gateway is the pusher URL, and the pusher is the same `event_id_only` one
(`C/push.rs:47-60`). Owner questions Q1–Q3 set the defaults below.

**iOS behaviour this ports:**
- `SupermessageKit/Notifications/PushConfiguration.swift:22-34`: no gateway means no pusher, so
  local notifications are the only kind. Anything not an absolute `https` URL counts as none.
- `:95-103`: the registration's display name is "supermessage", plus the device's name and
  language.
- `Supermessage/Platform/PlatformCoordinator.swift:188-205`: register only when signed in. Never
  register the same token twice. Remember that a pusher exists, which sets
  `NotificationContext.remotePush` (`:219`).
- `SupermessageNotificationService/NotificationService.swift:104-116` and `:150-181`:
  - restore quietly;
  - call `notificationFor(roomId, eventId)`;
  - a suppressed event is **dropped** where the platform allows it. Android always does: an app
    that receives a push may post nothing, which is iOS's `canFilter` branch
    (`NotificationComposer.swift:344-347`);
  - otherwise show what the core decided;
  - any failure falls back to a generic notification that opens the room (`:17-19`).
- `Supermessage/Platform/AppDelegate.swift:95-108` (now `notificationPresentsRemote`): a push is
  not shown for the room on screen in the foreground, or for an event the app already posted.

**Transport (the host's own parsing, as `RemotePush.swift` is iOS's):**
- The distributor delivers the Matrix push gateway body unchanged:
  `{"notification": {"event_id", "room_id", "counts": {"unread"}, "devices": [...]}}`. With
  `event_id_only` that is all it carries (Matrix Push Gateway API). `PushMessage.content` is the
  bytes, and `decrypted` is false on this path. Do not require it.
- Gateway discovery (unifiedpush.org/users/gateway): replace the endpoint's whole path and query
  with `/_matrix/push/v1/notify` and `GET` it. A Matrix gateway answers
  `{"unifiedpush":{"gateway":"matrix"}}`. **No fallback gateway** (Q2).
- `org.unifiedpush.android:connector:3.3.5` (Maven Central, 2026-08-22). Subclass
  `org.unifiedpush.android.connector.PushService` and implement
  `onNewEndpoint(endpoint: PushEndpoint, instance: String)`,
  `onMessage(message: PushMessage, instance: String)`,
  `onRegistrationFailed(reason: FailedReason, instance: String)` and
  `onUnregistered(instance: String)`. Declare it `exported="false"` with intent action
  `org.unifiedpush.android.connector.PUSH_EVENT`. Register through
  `UnifiedPush.tryUseCurrentOrDefaultDistributor(activity) { ok -> … UnifiedPush.register(context, INSTANCE_DEFAULT, null, null) }`.

**Files:**
- Modify: `android/gradle/libs.versions.toml` (`unifiedpush = "3.3.5"`, plus the `connector`
  library) and `android/app/build.gradle.kts`.
- Create: `A/notify/MatrixPush.kt`. `data class MatrixPush(roomId, eventId, unread: Int?)` and
  `fun parse(bytes: ByteArray): MatrixPush?` (null unless both ids are present, with `org.json`).
- Create: `A/notify/PushGateway.kt`. `fun discoveryUrl(endpoint: String): String?` (https only),
  `fun isMatrixGateway(body: String): Boolean`, and
  `fun interface GatewayProbe { suspend fun get(url: String): String? }`, whose real
  implementation is `HttpURLConnection` on `Dispatchers.IO` with a 10 s timeout.
- Create: `A/notify/SupermessagePushService.kt` (the connector subclass, named so it does not shadow the connector's `PushService`). Its work runs on `AppCore.scope`,
  bounded at 20 s per message.
- Create: `A/notify/PushRegistrar.kt`. It turns endpoint → discovery → `PushRegistration` →
  `client.registerPusher`. It keeps `registeredEndpoint` in memory and calls
  `coordinator.setRemotePush(true/false)`.
- Modify: `A/notify/PlatformCoordinator.kt`. After `SIGNED_IN` with the permission granted and the
  app active, ask the connector. On sign-out, `UnifiedPush.unregister(context, INSTANCE_DEFAULT)`;
  the core's logout already removed the pusher (`C/session.rs:297`, `remove_pusher` `:1490`).
- Modify: `A/Account.kt`. Add the Notifications status row (Q1/Q2) *(no iOS copy)*:
  - "On, through {distributor app label}";
  - "Off: install a UnifiedPush app such as ntfy to get notifications while supermessage is closed";
  - "Off: your UnifiedPush server can't take Matrix notifications";
  - "Off: notifications are turned off for supermessage" (permission denied).
- Modify: `android/app/src/main/AndroidManifest.xml` (the service).
- Modify: `docs/tech-stack.md:109`. One line: push goes through the AgentPod hub's own gateway to
  APNs on iOS, and UnifiedPush on Android (G3).
- Tests: `UT/notify/MatrixPushTest.kt`, `UT/notify/PushGatewayTest.kt`,
  `UT/notify/SupermessagePushServiceTest.kt`, `UT/notify/PushRegistrarTest.kt`,
  `AT/FakeDistributorTest.kt` (with `android/app/src/androidTest/AndroidManifest.xml`), the
  `AT/AccountTest.kt` additions, and `AT/ManifestTest.kt`.

**Interfaces:**
- `PushRegistration(pushkey = endpoint.url, appId = "dev.supermessage" (the applicationId), appDisplayName = "supermessage", deviceDisplayName = Build.MODEL, lang = Locale.getDefault().toLanguageTag(), gatewayUrl = <discovered>)`.

- [ ] **Step 0: Check the dependency.** Add the connector, then
  `flock … ./gradlew :app:checkDebugAarMetadata`. If any artifact declares `minCompileSdk > 36`,
  stop and report BLOCKED with the artifact name. Do not raise compileSdk.
- [ ] **Step 1: Write the failing tests.**
  - `MatrixPushTest`, ported from `RemotePushTests.swift:13-31`: `readsRoomEventAndUnread`,
    `needsBothIds`, and `ignoresAnythingThatIsNotAMatrixNotification`.
  - `PushGatewayTest`:
    - `discoveryReplacesThePathAndQuery` (`https://ntfy.sh/upAbC?up=1` →
      `https://ntfy.sh/_matrix/push/v1/notify`);
    - `onlyHttpsIsAGateway` (`PushConfiguration.swift:27-34`);
    - `recognisesAMatrixGateway`;
    - `anythingElseIsNotAGateway`.
  - `PushRegistrarTest`, with a fake probe and a recording core:
    - `aNewEndpointRegistersAnEventIdOnlyPusherAtTheDiscoveredGateway`, asserting every
      `PushRegistration` field;
    - `noMatrixGatewayRegistersNothing` (Q2), where the row state is "can't take Matrix";
    - `theSameEndpointIsNotRegisteredTwice` (`PlatformCoordinator.swift:193`);
    - `aRegisteredPusherTurnsTheRosterPathOff`: `context.remotePush` is true, and
      `notificationsForRoster` then returns nothing;
    - `signingOutUnregistersAndForgets` (RF5).
  - `SupermessagePushServiceTest` (Robolectric, `Robolectric.buildService`, with `onMessage` called
    directly):
    - `aPushIsFetchedDecidedAndPosted`, with the tag equal to the event id;
    - `aSuppressedPushPostsNothing`;
    - `aPushForTheOpenRoomInFrontPostsNothing`;
    - `aPushAlreadyPostedLocallyIsNotShownAgain` (RF focus 4);
    - `aPushThatCannotBeFetchedSaysNewMessageAndOpensItsRoom`, through `genericNotification`
      with title "supermessage" *(no iOS copy: iOS keeps the gateway's own alert title)*;
    - `aPushRestoresQuietlyFirst`.
  - `FakeDistributorTest` (instrumented). The test APK's manifest declares a receiver for
    `org.unifiedpush.android.distributor.REGISTER` and answers per the UnifiedPush Android spec
    (unifiedpush.org/developers/spec/android) with `NEW_ENDPOINT`, then `MESSAGE`. Tests:
    `aFakeDistributorsEndpointBecomesThePusher` and `aFakeDistributorsMessagePostsTheNotification`.
    **If the connector rejects the fake handshake after one fix attempt**, replace the handshake
    with direct `SupermessagePushService` calls in the same test class. Record why, with the connector's log
    line. Do not loop.
  - `AccountTest`: one test per row state, set by a fake registrar state, with copy verbatim:
    `theNotificationsRowSaysWhyPushIsOff`. Also `theNotificationsRowFitsAtFontScaleTwo` (411dp,
    `fontScale = 2f`, every line displayed, nothing ellipsised).
- [ ] **Step 2: Run them and confirm they fail.**
- [ ] **Step 3: Implement.** Context for `notificationPresentsRemote` comes from the live
  coordinator (process-wide, via `AppCore`). With no coordinator (a cold process), the push
  presents. iOS does the same: `platform?.presentsRemote(...) ?? true` (`AppDelegate.swift:111`).
  Leave a `onDecided(note: NotificationDto)` hook on the push path. M7 Task 1 feeds the widgets
  from it.
- [ ] **Step 4: Run the checks.**
  - **Falsify:**
    - accept `http` gateways → `onlyHttpsIsAGateway` fails;
    - post suppressed pushes → `aSuppressedPushPostsNothing` fails;
    - skip `presentsRemote` → `aPushForTheOpenRoomInFrontPostsNothing` fails;
    - add a fallback gateway → `noMatrixGatewayRegistersNothing` fails;
    - forget `setRemotePush(true)` → `aRegisteredPusherTurnsTheRosterPathOff` fails.
  - Live verification pending: all of it (Q3). Write the owner's checklist into the task report
    and into `m6-android-verification.md` (Task 5):
    1. Sideload ntfy (F-Droid APK) onto `supermessage-phone`, with server ntfy.sh.
    2. Open supermessage. Account → Notifications reads "On, through ntfy".
    3. `GET /_matrix/client/v3/pushers` shows `pushkey` = the ntfy endpoint,
       `data.url = https://ntfy.sh/_matrix/push/v1/notify` and `format: event_id_only`.
    4. Swipe the app away. A message from another client arrives as a notification with the core's
       title and body.
    5. A reaction arrives as nothing.
    6. A gate arrives with three buttons.
    7. With a PIN set and the device locked, Approve asks for unlock, then the decision lands in
       the room.
    8. With the network off, Approve gives "Your answer wasn't sent".
    9. Sign out. `GET …/pushers` no longer lists the device.
- [ ] **Step 5: Commit.** `android: background push over UnifiedPush`.

### Task 5: C2 voice recording, the branch-end pass, and the verification table **[composer]**

Needs CP2. Recording, staging and marking are local. Sending is a write, proven with fakes.

**iOS behaviour and copy:**
- `SupermessageKit/VoiceRecordingFormat.swift:4-27` (now `voiceRecordingSpec`): Opus, 48 kHz mono,
  24 kbit/s. iOS writes CAF because `AVAudioRecorder` writes Opus only into CAF. Android's
  `MediaRecorder.OutputFormat.OGG` with `AudioEncoder.OPUS` (API 29+, under minSdk 31) writes the
  Ogg that Matrix wants. The core sends a marked Ogg/Opus as it is and reads its length from it
  (`C/attachments.rs:910-918`). The file is `cacheDir/voice/<uuid>/Voice message.ogg`; its own
  folder lets the name stay plain (`Supermessage/Composer/VoiceRecorder.swift:90-93`).
- `VoiceRecorder.swift`:
  - `:72-81`: ask for the microphone the first time. A refusal reads "Microphone access is off.
    Turn it on in Settings to record."
  - `:100`, `:116`: "Couldn't start recording."
  - `:46-47`: sample the level every 100 ms and keep at most 60 points (now
    `voiceWaveform(decibels, bins)`).
  - `:124-138`: shorter than 600 ms is thrown away (now `voiceLongEnough`).
  - `:173-182`: a hold that ends before recording starts is abandoned, and cancel deletes the file.
  - `:193-197`: hand the audio route back afterwards. On Android, request
    `AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE` while recording and abandon it after.
- `Supermessage/Composer/ComposerView.swift`:
  - `:189-197`: the mic shows when the field is empty and nothing is staged. While held it stays
    the mic. While recording by tap, the trailing button becomes send.
  - `:199-216`: a tap starts a recording. A hold of **300 ms** (`:422`) starts a held one, release
    sends it, and sliding left past **80 pt** (`:401`; use 80dp) cancels it. Voice playback stops
    first (`:204`, `:207`).
  - `:236`: accessibility labels "Send voice message", "Send" and "Save edit".
  - `:441`: "Record voice message".
  - `:448-500`: the recording pill. It has a pulsing `danger` dot (0.8 s, and none under reduce
    motion), the elapsed time (`ElapsedTime.label`, now the core's `elapsedLabel`), the words
    "Slide left to cancel" while held or "Recording" otherwise (`:477`), and, when not held, a
    trash button "Discard recording" (`:490`).
  - `:304-317`: send = stage the file → mark it as voice with the length and waveform → send with
    empty text. A send that reaches the room plays the light send haptic (`:110-113`; Android M2
    Task 15's `Haptic.Light` on the same "delivered" trigger).
  - `:87`: the recorder's failure shows where the composer's failures show.
- `SupermessageKit/Stores/StagedAttachment.swift:120-125`: `markVoice` only marks the staged file of
  its own room.

**Files:**
- Modify: `android/app/src/main/AndroidManifest.xml`
  (`<uses-permission android:name="android.permission.RECORD_AUDIO" />`).
- Create: `K/VoiceRecording.kt`. The state machine is ported from `VoiceRecorder.swift:18-190`,
  over `interface Recorder { fun start(file: File); fun stop(); fun maxAmplitude(): Int }`, a clock
  and `voiceRecordingSpec()`. Levels are `20·log10(amp / 32767)`, and `amp == 0` gives `-inf`,
  which the core reads as silence.
- Create: `A/MediaRecorderRecorder.kt` (the real `Recorder`: `MediaRecorder(context)`, MIC source,
  OGG/OPUS, 48 000 Hz, 1 channel, 24 000 bit/s) and `A/RecordingPill.kt`.
- Modify: `K/stores/StagedAttachment.kt` (`suspend fun markVoice(durationMs: Long, waveform: List<Float>, roomId: String)`)
  and `A/Composer.kt` (the mic button, the pill, the permission request, and the send).
- Create: `PV/RecordingPreviews.kt`, with the pill by tap, the pill held, the mic idle, the mic
  while a note plays, and the failure line. Each renders still.
- Create: `.superpowers/sdd/m6-android/m6-android-verification.md`.
- Tests: `KT/VoiceRecordingTest.kt`, `KT/stores/StagedAttachmentTest.kt` (modify),
  `AT/VoiceComposerTest.kt` and `AT/ManifestTest.kt`.

- [ ] **Step 0:** grep the CP2 names and `elapsedLabel`. Confirm `VoicePlayback` in `K/` has a stop
  call. Confirm how M2 Task 15's send haptic is triggered in `A/Composer.kt`.
- [ ] **Step 1: Write the failing tests.**
  - `VoiceRecordingTest`, with a fake recorder and clock:
    - `aSlipOfTheThumbIsThrownAway` (500 ms → null, file deleted);
    - `aRecordingHasItsLengthAndAtMostSixtyLevels` (90 s of samples → 60 levels within 0…1);
    - `liftingDuringThePermissionPromptAbandonsTheStart`;
    - `cancelDeletesTheFolder`;
    - `aSecondStartWhileRecordingIsRefused`;
    - `aRefusedMicrophoneSaysSo` (the copy is verbatim).
  - `StagedAttachmentTest`: `markVoiceMarksTheStagedFileForItsRoom`, where `attachmentMarkVoice`
    gets the token, the room, the `ULong` duration and the waveform; and
    `markVoiceForAnotherRoomDoesNothing`.
  - `VoiceComposerTest` (instrumented, fake `Recorder` and fake core, one height; the pill classes
    also run at 1080x1280 because the pill sits above the keyboard):
    - `theMicShowsOnlyWhenThereIsNothingToSend`;
    - `aTapRecordsAndTheButtonSaysSendVoiceMessage`;
    - `discardRecordingThrowsItAway`;
    - `holdingRecordsAndSlidingLeftCancels` (with `performTouchInput`);
    - `releasingAHoldSends`;
    - `aSentVoiceNoteIsStagedMarkedThenSent`: the fake records `attachmentStagePath`,
      `attachmentMarkVoice`, `attachmentSend` in that order, with caption null or empty as the Kit
      sends it;
    - `aDeliveredVoiceNotePlaysTheSendHaptic`;
    - `aPlayingNoteStopsWhenRecordingStarts`;
    - `thePillFitsAtFontScaleTwo` (411dp: the discard button is displayed and inside the
      composer's bounds).
  - `VoiceComposerTest.theRealRecorderWritesOggOpus`: one second on the emulator's virtual mic
    (silence is fine). The file starts with `OggS` and contains `OpusHead`.
  - `ManifestTest`: `declaresRecordAudio`.
- [ ] **Step 2: Run them and confirm they fail.**
- [ ] **Step 3: Implement.** Grant or revoke the permission in tests through
  `InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission/revokeRuntimePermission`.
  That adds no UiAutomator dependency.
- [ ] **Step 4: Run the checks.** This is the **branch end**: the full instrumented suite at
  1080x2400 **and** 1080x1280.
  - **Falsify:**
    - record `OutputFormat.MPEG_4` → `theRealRecorderWritesOggOpus` fails;
    - drop `markVoice` → `aSentVoiceNoteIsStagedMarkedThenSent` fails;
    - change the hold to 0 ms → `aTapRecordsAndTheButtonSaysSendVoiceMessage` fails (three runs);
    - skip the 600 ms rule → `aSlipOfTheThumbIsThrownAway` fails;
    - skip the playback stop → `aPlayingNoteStopsWhenRecordingStarts` fails.
  - Live (read-only): record a note in a real room, play it back from the pill if offered, and
    discard it. Live verification pending: sending a voice note, then Element X on iOS playing it
    and the hub transcribing it.
  - Write `m6-android-verification.md` from the template below.
- [ ] **Step 5: Commit.** `android: record voice messages (C2); M6 verification table`.

**Verification table template** (`.superpowers/sdd/m6-android/m6-android-verification.md`):

| Row | Behaviour | Evidence (test, screenshot) | Live |
|---|---|---|---|
| P2 | Local notification worded by the core; replaces by id; muted rule | Task 2 tests; screenshot | ✅ read-only |
| P2 | Allow once / Reject; Approve / Request changes / Reject; unlock flag; failure notice | Task 3 tests incl. the instrumented action send | pending (owner, locked device) |
| P1 | UnifiedPush endpoint → event_id_only pusher; push → decided notification; suppressed dropped | Task 4 tests, fake distributor | pending (owner checklist, Q3) |
| C2 | Record, cap, mark, send; Ogg/Opus on disk | Task 5 tests | send pending |
| RF3/RF5 | Font scale 2.0 rows; sign-out withdraws everything | named tests | — |
