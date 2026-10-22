# Parity M0 — Defects and Unreachable Plumbing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task by task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** fix the verified bugs from the 2026-10-22 parity audit (B1–B7). Make the desktop
edit and delete commands reachable from the message menu.

**Execution:** Android (tasks A1–A5) and desktop (tasks D1–D4) run in parallel, on two
branches from `main`:
- `parity/m0-android-defects`, in worktree `parity-android`;
- `parity/m0-desktop-defects`, in worktree `parity-desktop`.

Task X1 goes last, on whichever branch merges second.

**Architecture:**
- **Android:** UI fixes in `android/app` over Kit and FFI functions that already exist, plus
  one new `CoreClient` wrapper (`sendPermissionAnswer`). It makes no FFI change, so the
  bindings are untouched.
- **Desktop:** one new Tauri command (`ensure_recovery`, over the existing core function),
  two `ipc.ts` wrappers over existing commands, and component changes.

**Tech Stack:**
- Android: Kotlin/Compose, JUnit4, compose-ui-test.
- Desktop: Svelte 5, Vitest, Storybook stories with the snapshot gate, and Tauri 2 commands
  in Rust.

**Spec:** `docs/research/2026-10-22-ios-parity/README.md` §2. The umbrella plan is
`docs/superpowers/plans/2026-10-22-ios-parity-roadmap.md`.

## Global Constraints

- Everything in the roadmap's Global Constraints applies.
- Copy is from iOS, verbatim:
  - Message menu: **"Copy"**, **"Delete"**.
  - Delete confirmation: title **"Delete message?"**, message **"This removes it for
    everyone in the room."**, actions **"Delete"** (destructive) and **"Cancel"**.
  - Connection bar labels: **"connecting"**, **"offline"**, **"reconnecting…"**. Nothing is
    shown when live.
  - Network error with no detail: **"Can't reach the homeserver."**
- Rules from iOS:
  - Delete is offered when `item.isOwn && row.canReplyOrReact`.
  - Copy is offered when `item.body` is non-empty.
  - The connection bar is never amber.
  - A decision card is answerable whenever a handler is listening. A gate (subject non-null)
    goes through `sendGateDecision`. A permission request (subject null) goes through
    `sendPermissionAnswer(roomId, optionId)`.
  - Recovery is set up after a fresh sign-in, not after a session restore. It runs without
    blocking sign-in, and a failure is silent.
- Android verification: `cargo build -p supermessage-ffi` once (the host library `:kit`
  tests need). Then, from `android/`:
  - `./gradlew test :app:testDebugUnitTest`;
  - `scripts/android-ci-parity.sh`;
  - `./gradlew :app:connectedDebugAndroidTest`, at pixel_6 height and again after
    `adb shell wm size 1080x1280`.
- Desktop verification:
  - `pnpm test`, `pnpm check` and `./scripts/snapshot-stories.sh`;
  - `cargo test --workspace`, `cargo fmt --all --check`, and
    `cargo clippy --workspace --all-targets --all-features -- -D warnings`.
- New desktop stories get baselines with `./scripts/snapshot-stories.sh --record`, only
  after the new frame has been inspected and shows the state named.

## Review Focus

1. **A permission request answered while offline.** The card must stay answerable, not show a
   receipt. Pinned in A1 by `answerPermissionReturnsFalseWhenTheCoreRefuses`, and in D2 by
   the "Send failed" story and `answerDecisionReportsFailure`.
2. **An attachment staged with whitespace-only text.** Send is enabled and sends no caption.
   Pinned in A2 by `stagedAttachmentWithBlankTextEnablesSend`.
3. **An image whose fetch fails.** The alt-text box stays, and the row does not spin forever.
   Pinned in A3 by `imageRowFallsBackToAltWhenNoPicture`.
4. **App backgrounded mid-typing.** The typing notice goes off. Pinned in A4 by
   `stopReportsInactive`. The existing `Session.scenePhaseChanged` test covers the typing
   half.
5. **Delete on a message whose send is still pending, with no event id.** Delete is not
   offered. Pinned in D3 by `deleteNotOfferedWithoutEventId`.

---

## Android track — branch `parity/m0-android-defects`

Paths: `A=android/app/src/main/kotlin/dev/supermessage`,
`K=android/kit/src/main/kotlin/dev/supermessage/kit`,
`AT=android/app/src/androidTest/kotlin/dev/supermessage`,
`KT=android/kit/src/test/kotlin/dev/supermessage/kit`.

### Task A1: Permission requests can be answered (B1)

**Files:**
- Modify: `K/CoreClient.kt` (beside `sendGateDecision`, ~:223)
- Modify: `K/Session.kt` (beside `answerGate`, ~:399)
- Modify: `A/DecisionCard.kt:350-360, 503-510`
- Modify: `A/MainActivity.kt:397-406`
- Test: `KT/SessionTest.kt`, `AT/DecisionCardTest.kt`

**Interfaces:**
- Produces:
  - `CoreClient.sendPermissionAnswer(roomId: String, optionId: String)`, which is `suspend`.
  - `Session.answerPermission(optionId: String, roomId: String): Boolean`, which is
    `suspend`.
  - `GateAnswer.subject` becomes `String?`.

- [ ] **Step 1: Write the failing Kit tests** in `SessionTest.kt`.
  - Add `sendPermissionAnswerResult: (String, String) -> Unit = { _, _ -> }` to `FakeCore`,
    overriding `sendPermissionAnswer`.
  - Test `answerPermissionSendsTheOptionToTheRoom`: signed in, call
    `session.answerPermission("allow_once", "!r:x")`. Assert it returns `true` and the fake
    recorded `("!r:x", "allow_once")`.
  - Test `answerPermissionReturnsFalseWhenTheCoreRefuses`: the fake throws
    `FfiException.Network("offline")`. Assert it returns `false`.
  - Test `answerPermissionReturnsFalseWhenSignedOut`: assert it returns `false` and the fake
    recorded nothing.
- [ ] **Step 2: Run them and confirm they fail.**
  `./gradlew :kit:test --tests '*SessionTest.answerPermission*'` fails: the method is
  unresolved.
- [ ] **Step 3: Implement the wrapper and `Session.answerPermission`.**
  - Add `suspend fun sendPermissionAnswer(roomId: String, optionId: String)` to `CoreClient`,
    using `run { it.sendPermissionAnswer(roomId, optionId) }`.
  - Add `Session.answerPermission`. It returns `false` unless the phase is `SIGNED_IN`. It
    rethrows `CancellationException` and maps any other exception to `false`, the same shape
    as `answerGate`.
  - Its KDoc cites iOS `Session.swift:633`.
- [ ] **Step 4: Run the Kit tests and confirm they pass.** Same command, PASS.
- [ ] **Step 5: Write the failing UI test** in `DecisionCardTest.kt`.
  - Test `permissionOptionsArePressableAndSendANullSubject`: use the existing permission
    fixture (`subject = null`, eventType `dev.agentpod.permission.v1`), with an `onDecide`
    that records its `GateAnswer` and returns `true`.
  - Assert `onNodeWithText("Approve").assertIsEnabled()`.
  - Click it. Assert the recorded answer has `subject == null` and
    `optionId == "approve-restart-hermes-gateway"`.
- [ ] **Step 6: Make the card answerable.**
  - Change `answerable` to `onDecide != null && !sending`, matching iOS
    `DecisionCard.swift:509`.
  - Make `GateAnswer.subject` a `String?`, and have `send()` stop returning on a null
    subject.
  - Update the KDoc at :312 and :499 to say that a permission request has no subject.
- [ ] **Step 7: Route it in `MainActivity`.** In `onDecide`:
  - when `answer.subject == null`, call
    `session.answerPermission(answer.optionId, currentRoomId)`;
  - otherwise call `answerGate` as now.
  - This is the routing in iOS `TimelineCollectionView.swift:1022-1031`.
- [ ] **Step 8: Run the tests.** Run `./gradlew :kit:test :app:testDebugUnitTest`, then
  `./gradlew :app:connectedDebugAndroidTest --tests '*DecisionCardTest*'`. Expected: PASS.
- [ ] **Step 9: Commit.** Message: `android: answer permission requests (B1)`.

### Task A2: Send an attachment with no text (B2)

**Files:**
- Modify: `A/Composer.kt:60-67` (KDoc), `A/Composer.kt:179`
- Test: `AT/ComposerTest.kt`

**Interfaces:** consumes the existing `attachment: StagedFile?` parameter.
`Session.send` already sends an attachment-only message with `caption = null`
(`K/Session.kt:291-305`).

- [ ] **Step 1: Write the failing tests.**
  - Test `stagedAttachmentWithBlankTextEnablesSend`: compose `Composer(text = "  ",
    attachment = StagedFile(...fixture...))`. Assert the send control is enabled.
  - Keep `whitespaceAloneDoesNotEnableSend`. It still passes with `attachment = null`.
- [ ] **Step 2: Run the test and confirm it fails.**
  `./gradlew :app:connectedDebugAndroidTest --tests '*ComposerTest*'` fails on the first
  test.
- [ ] **Step 3: Change the rule** to `canSend = text.isNotBlank() || attachment != null`.
  Update the KDoc section's heading and its sentence to say that a staged attachment is
  sendable alone.
- [ ] **Step 4: Run the tests and confirm they pass.** Same command, PASS.
- [ ] **Step 5: Commit.** Message: `android: send an attachment without text (B2)`.

### Task A3: Images and sender faces render (B3)

**Files:**
- Modify: `A/TimelineRow.kt`:
  - `TimelineRow` params at :112;
  - the `ItemView.Image` branch at :171;
  - `ImageRow` at :655;
  - `SenderFace` at :616.
- Modify: `A/Timeline.kt:145-160` (params) and :318 (the `TimelineRow` call)
- Modify: `A/MainActivity.kt`, where `Timeline(...)` is called
- Test: `AT/TimelineRowTest.kt`

**Interfaces:**
- Consumes:
  - `Session.media.image(eventId): String?`, which starts the fetch;
  - `Session.media.cache: StateFlow<Map<String,String>>`;
  - `Session.media.hasFailed(eventId)`;
  - `Session.faces.cache`;
  - `Session.faces.load(key)`, which is `suspend`;
  - `TimelineItem.senderAvatar: String?` (an `mxc:` URI);
  - `String.decodeDataUri(): ImageBitmap?` (`A/RoomRow.kt:307`).
- Produces new `TimelineRow` and `Timeline` parameters:
  - `imageUri: (eventId: String) -> String? = { null }`;
  - `faceUri: (mxcUri: String) -> String? = { null }`;
  - `loadFace: suspend (mxcUri: String) -> Unit = {}`.

  These replace `avatarUri: (userId) -> String?`, which was keyed by the wrong thing: iOS
  keys faces by `mxc:` (`TimelineChrome.swift:92`).

- [ ] **Step 1: Write the failing tests** in `TimelineRowTest.kt`. All three test tags are
  new.
  - Test `imageRowDrawsThePictureWhenTheCacheHasIt`: an `ItemView.Image` row, with
    `imageUri` returning a 1×1 PNG `data:` URI. Assert a node with test tag `timeline-image`
    exists.
  - Test `imageRowFallsBackToAltWhenNoPicture`: `imageUri = { null }`. Assert the alt text
    is displayed and `timeline-image` is absent.
  - Test `senderFaceUsesTheMxcAvatar`: the item has `senderAvatar = "mxc://x/y"` and
    `faceUri` maps it to the PNG. Assert test tag `sender-face-image` exists.
- [ ] **Step 2: Run the tests and confirm they fail.**
  `--tests '*TimelineRowTest*'` fails: unknown parameters.
- [ ] **Step 3: Implement.**
  - `ImageRow` takes `picture: ImageBitmap?`. When it is non-null, draw `Image` (tagged
    `timeline-image`, `ContentScale.Crop`) inside the existing box. Otherwise draw the alt
    text as now.
  - The `Image` branch passes `item.eventId?.let(imageUri)?.decodeDataUri()`.
  - `SenderFace` takes `mxcUri: String?`, `faceUri` and `loadFace`. It runs
    `LaunchedEffect(mxcUri) { mxcUri?.let { loadFace(it) } }` and tags the image
    `sender-face-image`.
  - `Timeline` threads all three lambdas through.
  - In `MainActivity`, collect `session.media.cache` and `session.faces.cache` with
    `collectAsState()`, so a landed fetch recomposes. Pass
    `imageUri = { id -> session.media.image(id) }`,
    `faceUri = { mxc -> faces[mxc] }` and `loadFace = session.faces::load`.
- [ ] **Step 4: Run the tests and confirm they pass.** Same command, PASS. Then run the full
  `:app:connectedDebugAndroidTest`, because the `avatarUri` callers changed.
- [ ] **Step 5: Commit.** Message: `android: render images and sender faces (B3)`.

### Task A4: Foreground and background reach the session (B4)

**Files:**
- Create: `A/AppLifecycle.kt`
- Modify: `A/MainActivity.kt` (register the observer where `session` is in scope)
- Test: `android/app/src/test/kotlin/dev/supermessage/AppLifecycleTest.kt`

**Interfaces:**
- Consumes: `Session.scenePhaseChanged(active: Boolean)`, which is `suspend`
  (`K/Session.kt:351`).
- Produces: `class ScenePhaseObserver(private val onPhase: (active: Boolean) -> Unit) :
  DefaultLifecycleObserver`. Its `onStart` calls `onPhase(true)` and its `onStop` calls
  `onPhase(false)`.

- [ ] **Step 1: Write the failing tests.** Drive the observer with
  `LifecycleRegistry.createUnsafe(owner)` and a fake `LifecycleOwner`, recording `onPhase`
  calls.
  - `startReportsActive`: move to STARTED. The calls are `[true]`.
  - `stopReportsInactive`: move to STARTED, then CREATED. The calls are `[true, false]`.
  - `resumePauseDoNotReport`: STARTED → RESUMED → STARTED. The calls are `[true]`.
- [ ] **Step 2: Run the tests and confirm they fail.**
  `./gradlew :app:testDebugUnitTest --tests '*AppLifecycleTest*'` fails: the class is
  unresolved.
- [ ] **Step 3: Implement** `ScenePhaseObserver`. In `MainActivity`, register it once per
  signed-in session:
  `lifecycle.addObserver(ScenePhaseObserver { active -> scope.launch { session.scenePhaseChanged(active) } })`.
  Remove it on dispose (`DisposableEffect(session)`). Start and stop, not resume and pause,
  match iOS's `scenePhase` `.active` and `.background` for a split-screen window that stays
  visible.
- [ ] **Step 4: Run the tests and confirm they pass.** Same command, PASS.
- [ ] **Step 5: Commit.** Message: `android: tell the session about foreground/background (B4)`.

### Task A5: Draw the connection status

**Files:**
- Create: `A/ConnectionBar.kt`
- Modify: `A/MainActivity.kt` (above the roster, ~:212)
- Create: a debug preview in `android/app/src/debug/.../ConnectionBarPreviews.kt` (offline,
  reconnecting with a message)
- Test: `AT/ConnectionBarTest.kt`

**Interfaces:**
- Consumes: `ConnectionStore.state: StateFlow<Connection>`, `.message: StateFlow<String?>`
  and `.isWorthShowing`.
- Produces: `@Composable fun ConnectionBar(state: ConnectionStore.Connection, message: String?,
  worthShowing: Boolean, modifier: Modifier = Modifier)`.

- [ ] **Step 1: Write the failing tests.**
  - `offlineShowsLabelAndMessage`: Offline with the message `"connection refused"`. Assert
    the text "offline" and "connection refused" are displayed.
  - `hiddenWhenNotWorthShowing`: `worthShowing = false`. Assert the test tag
    `connection-bar` is absent.
  - `errorReadsReconnecting`: Error. Assert "reconnecting…".
- [ ] **Step 2: Run the tests and confirm they fail.** `--tests '*ConnectionBarTest*'` fails:
  unresolved.
- [ ] **Step 3: Implement it as iOS `ConnectionBar` (`RootView.swift:181-209`).**
  - It is a full-width row, tagged `connection-bar`, on the `surfaceSunken` token, with the
    label and optional message in the meta style and the message muted.
  - It is never amber.
  - For `Unknown(raw)`, show `raw`.
- [ ] **Step 4: Run the tests and confirm they pass.** Then run the whole
  `:app:connectedDebugAndroidTest` at both heights, and `./gradlew :app:testDebugUnitTest`
  (Roborazzi previews). Record the new preview baselines after inspecting them.
- [ ] **Step 5: Commit.** Message: `android: show the connection state`.

## Desktop track — branch `parity/m0-desktop-defects`

### Task D1: Set up recovery at sign-in (B5)

**Files:**
- Modify: `src-tauri/src/commands.rs` (beside `enable_recovery`, :62-69)
- Modify: `src-tauri/src/lib.rs:215-261` (register it)
- Modify: `src/lib/ipc.ts` (beside `enableRecovery`, :1273)
- Modify: `src/lib/components/RecoveryPanel.svelte` (props; delete the unreferenced
  `enable()` and the `onEnable` prop)
- Modify: `src/lib/components/RecoveryPanel.stories.svelte`
- Modify: `src/routes/+page.svelte` (the `onEnable` wiring at :1197)
- Modify: `src/routes/login/+page.svelte` (after a successful `login`)
- Test: `src/lib/ipc.test.ts`

**Interfaces:**
- Produces:
  - Tauri command `ensure_recovery() -> Result<Option<String>, CoreError>`, over
    `Session::ensure_recovery` (`session.rs:1938`).
  - `ensureRecovery(): Promise<string | null>` in `ipc.ts`.
  - `RecoveryPanel` prop `initialKey?: string | null`. When set, the panel opens on the
    existing "key, shown once" screen (`freshKey`).

- [ ] **Step 1: Write the failing test.** In `ipc.test.ts`, test
  `ensureRecovery invokes ensure_recovery and passes the key through`: mock resolves to
  `"EsTk abcd"`. Assert `invokeMock` was called with `("ensure_recovery")` and the result is
  `"EsTk abcd"`. Add the same with `null`.
- [ ] **Step 2: Run the test and confirm it fails.** `pnpm vitest run src/lib/ipc.test.ts`
  fails: no export.
- [ ] **Step 3: Implement the command, the wrapper and the wiring.**
  - Write the command and its doc comment in the same "never logged" terms as
    `enable_recovery`.
  - After a successful **login** (not on restore), the page calls
    `void ensureRecovery().then(k => { if (k) openRecovery(k) }).catch(() => {})`. The
    failure is silent, as in iOS `Session.swift:714`.
  - `openRecovery(k)` opens `RecoveryPanel` with `initialKey={k}`.
  - Delete the dead `enable()` and `onEnable`.
  - Add a story, "New key after sign-in", with `initialKey` set.
- [ ] **Step 4: Run all the checks.** `pnpm test`, `pnpm check`, `cargo test --workspace`,
  `cargo clippy …` and `./scripts/snapshot-stories.sh` all pass. Record the new story frame
  after inspecting it.
- [ ] **Step 5: Commit.** Message: `desktop: set up recovery at sign-in (B5)`.

### Task D2: Decision sends report failure (B6, decisions)

**Files:**
- Modify: `src/lib/components/decisionReply.ts`
- Modify: `src/lib/components/Timeline.svelte:977-982`
- Modify: `src/lib/components/timeline/DispatchCard.svelte` (the props at :48, the buttons at
  :240-246)
- Modify: `src/lib/components/timeline/DispatchCard.stories.svelte`
- Test: `src/lib/components/decisionReply.test.ts`

**Interfaces:**
- Produces:
  - `answerDecision(roomId, eventId, decision, optionId): Promise<boolean>`, which resolves
    `false` and logs on failure. It never rejects.
  - The `DispatchCard` prop becomes
    `onDecide: (eventId, decision, optionId) => Promise<boolean>`.

- [ ] **Step 1: Write the failing tests.**
  - `answerDecisionReportsSuccess`: `sendGateDecision` mock resolves. The result is `true`.
  - `answerDecisionReportsFailure`: the mock rejects. The result is `false` and the promise
    does not reject.
- [ ] **Step 2: Run the tests and confirm they fail.**
  `pnpm vitest run src/lib/components/decisionReply.test.ts` fails.
- [ ] **Step 3: Implement.**
  - `DispatchCard` holds `sending` and `failed` state.
  - Buttons are disabled while sending.
  - On `false`, the card shows "Not sent. Try again." with `role="alert"`, in the
    `danger`-role token (not amber), and the buttons stay enabled.
  - This is desktop's equivalent of iOS's error haptic, since iOS has no visible copy here.
  - Add the stories "Sending" and "Send failed". Each needs an `onDecide` that actually runs
    when the story clicks, per `docs/story-snapshots.md` §1.
- [ ] **Step 4: Run the checks.** `pnpm test`, `pnpm check` and the snapshot gate all pass.
  Record the new frames after inspecting them.
- [ ] **Step 5: Commit.** Message: `desktop: say when a decision did not send (B6)`.

### Task D3: Copy and Delete in the message menu

**Files:**
- Modify: `src/lib/ipc.ts` (add wrappers beside `toggleReaction`, :1458)
- Create: `src/lib/components/timeline/messageMenu.ts`
- Create: `src/lib/components/timeline/messageMenu.test.ts`
- Modify: `src/lib/components/timeline/MessageActions.svelte` (props :23-41, buttons
  :87-116)
- Modify: `src/lib/components/Timeline.svelte:1184`
- Test: `src/lib/ipc.test.ts`

**Interfaces:**
- Produces:
  - `editMessage(roomId: string, eventId: string, body: string): Promise<void>`, invoking
    `"edit_message"`. M2's edit mode uses it.
  - `deleteMessage(roomId: string, eventId: string): Promise<void>`, invoking
    `"delete_message"`.
  - `menuActions(row: ItemRow): { copy: boolean; del: boolean }` in `messageMenu.ts`.
  - New `MessageActions` props: `onCopy: (row) => void` and `onDelete: (row) => void`.

- [ ] **Step 1: Write the failing tests.**
  - `ipc.test.ts`: `deleteMessage` invokes `("delete_message", {roomId, eventId})`, and
    `editMessage` invokes `("edit_message", {roomId, eventId, body})`.
  - `messageMenu.test.ts` covers the cases below.

  | Test | Row | `menuActions` returns |
  |---|---|---|
  | `copyOfferedForText` | `body = "hi"` | `copy: true` |
  | `copyNotOfferedWithoutBody` | `body = null` | `copy: false` |
  | `deleteOfferedForOwnReplyable` | `isOwn && canReplyOrReact` | `del: true` |
  | `deleteNotOfferedForOthers` | not own | `del: false` |
  | `deleteNotOfferedWithoutEventId` | `eventId = null` | `del: false` |
- [ ] **Step 2: Run the tests and confirm they fail.**
  `pnpm vitest run src/lib/ipc.test.ts src/lib/components/timeline/messageMenu.test.ts`
  fails.
- [ ] **Step 3: Implement.**
  - The menu shows "Copy" and "Delete" per `menuActions`.
  - Copy uses `navigator.clipboard.writeText(item.body)`.
  - Delete opens a confirm dialog with the iOS copy from Global Constraints, using the
    project's existing modal pattern (as `RecoveryPanel`'s scrim, not `window.confirm`). On
    confirm, it calls `deleteMessage`. A failure shows through the same `role="alert"`
    pattern as D2.
  - Keep `timelineActionAnchor.test.ts` green: the bar stays `absolute top-full` inside a
    `relative` `group`.
- [ ] **Step 4: Run the checks.** `pnpm test`, `pnpm check` and the snapshot gate all pass.
  Then verify in the running app: `pnpm dev` plus `target/debug/supermessage`, over the
  WebDriver on :4445. Hover your own message, delete it, confirm, and check the row becomes
  the redacted placeholder. Copy, then read the clipboard through `execute/sync`.
- [ ] **Step 5: Commit.** Message: `desktop: copy and delete from the message menu`.

### Task D4: A failed attachment stays staged; network errors use the homeserver's words (B6)

**Files:**
- Modify: `src/lib/components/Composer.svelte:397-401`
- Modify: `src/routes/login/+page.svelte:26-39`
- Modify: the composer stories (`StagedAttachmentChip.stories.svelte`, or the Composer story
  if one exists)
- Create: `src/routes/login/loginMessage.ts` and `loginMessage.test.ts`

**Interfaces:**
- Produces: `loginMessage(err: CoreError): string` in `loginMessage.ts`, moved out of the
  page so it can be tested.

- [ ] **Step 1: Write the failing tests** in `loginMessage.test.ts`. The `auth` and `store`
  cases keep their current copy.

  | Test | Error | `loginMessage` returns |
  |---|---|---|
  | `networkShowsTheHomeserversWords` | `{kind: "network", message: "connection refused"}` | `"connection refused"` |
  | `networkWithoutDetailIsGeneric` | `{kind: "network", message: ""}` | `"Can't reach the homeserver."` |
- [ ] **Step 2: Run the tests and confirm they fail.** `pnpm vitest run src/routes/login`
  fails: no module.
- [ ] **Step 3: Implement.**
  - Move `messageFor` into `loginMessage.ts`, changing only the `network` case.
  - In `Composer.svelte`, the failure branch keeps the staged attachment. Delete the
    `takeToken`/`discardStaged` lines in the `catch`, keep `failure = attachmentFailure(...)`,
    and leave the draft as it is.
  - Add a story, "Attachment send failed", showing the chip with the failure.
- [ ] **Step 4: Run the checks.** `pnpm test`, `pnpm check` and the snapshot gate all pass.
  Then, in the running app, stage a file, go offline (disconnect the network), send, and
  check the chip and error stay. Reconnect, send again, and check it lands.
- [ ] **Step 5: Commit.** Message:
  `desktop: keep a failed attachment; show the homeserver's words (B6)`.

## Shared

### Task X1: README capability rows (B7)

**Files:** modify `README.md:33,36` (the capability table).

- [ ] **Step 1: Correct the rows to match what has merged.**
  - Android "editing/deleting own messages": edit is implemented; delete reads "core only
    (no UI yet)" until M2.
  - Desktop: "editing: core only (no UI yet)" until M2; "deleting: implemented" after D3.
  - Android "permission cards": implemented after A1.

  Verify each claim against the merged code, not this plan.
- [ ] **Step 2: Commit.** Message: `docs: README capability rows match the code (B7)`.

---

## M0 exit

- All nine tasks are merged.
- Android CI and desktop CI are green.
- The research matrix's B1–B7 rows are fixed.
- On the phone AVD: a permission request is answerable; an image renders; an attachment
  sends with no text; the connection bar shows while the network is off.
- On desktop: a fresh sign-in shows the new recovery key, and Delete works.
