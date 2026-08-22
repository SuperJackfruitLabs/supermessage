# Android Composer (Phase B) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development.

**Goal:** Hold a conversation — type, send, reply, edit, react, attach.

**Architecture:** A `Composer` composable over `:kit`'s already-ported stores (`DraftStore`, `ReplyTarget`, `EditTarget`, `StagedAttachment`). The shell owns the keyboard-dismiss rule so the timeline and composer agree about it. Nothing is classified in Compose.

**Design note:** this document carries its own rationale rather than referencing a separate spec, because the roadmap (`2026-08-21-android-app-roadmap.md`, Phase B) already settled the open questions and A2's spec settled the surrounding surface.

## Global Constraints

- **The app decides nothing.** `Session.send`, `DraftStore`, `ReplyTarget`, `EditTarget` and `StagedAttachment` already hold every rule. A composable that re-derives one is the defect this architecture exists to prevent.
- **No `else`** on any `when` over a core sealed class — a new variant must break the build.
- **Free functions cross in `uniffi/supermessage_ffi/supermessage_ffi.kt`**, never `supermessage_core.kt`. Grep there before writing any helper that formats, names or classifies.
- **68 instrumented tests are green and must stay green**, including `RootScaffoldTest`'s five and `TimelineTest`'s eight, unmodified.
- **A test that has never failed is not yet a regression test.** Mutate until it fails.
- Emulator launches only via `scripts/android-emulator.sh` (headless). `--tests` is a config-time error on `connectedDebugAndroidTest`; use `-Pandroid.testInstrumentationRunnerArguments.class=<FQCN>`.
- A **real signed-in session** lives on the emulator. Never clear app data; never touch the Keystore alias `dev.supermessage.secrets`.

---

### Task 1: Rule 4 — the keyboard comes down on drag

**Files:**
- Create: `android/app/src/main/kotlin/dev/supermessage/KeyboardDismiss.kt`
- Test: `android/app/src/androidTest/kotlin/dev/supermessage/KeyboardDismissTest.kt`

**Why this is first, and why it is the shell's.** On iOS the keyboard had no way down for weeks — one of the four timeline rules, and the only one A2 deliberately did not build, because with no composer there was no IME to dismiss and any implementation would have been untestable dead code. It belongs to the screen rather than to the timeline or the composer, so both agree about it.

**Interfaces:**
- Produces: `fun Modifier.dismissKeyboardOnDrag(): Modifier`

- [ ] **Step 1: Write the failing test** — with a `TextField` focused and the IME shown, a downward drag over the scrollable area hides it; an upward drag does not.
- [ ] **Step 2: Run, confirm failure**
- [ ] **Step 3: Implement** via `nestedScroll` with a connection whose `onPreScroll` hides the IME on a downward gesture (`available.y > 0`) when it is visible, using `LocalSoftwareKeyboardController` / `WindowInsets.isImeVisible`.
- [ ] **Step 4: Run, confirm pass**
- [ ] **Step 5: Mutate** — dismiss on *any* drag direction; confirm the upward-drag case fails.
- [ ] **Step 6: Commit**

---

### Task 2: `Composer` — text, drafts, send

**Files:**
- Create: `android/app/src/main/kotlin/dev/supermessage/Composer.kt`
- Test: `android/app/src/androidTest/kotlin/dev/supermessage/ComposerTest.kt`
- Read first: `apple/Supermessage/Composer/ComposerView.swift` (274 lines)

**Interfaces:**
```kotlin
@Composable fun Composer(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    sending: Boolean = false,
    failure: String? = null,
    replyTo: ReplyTarget.Pending? = null,
    onCancelReply: () -> Unit = {},
    editing: EditTarget.Pending? = null,
    onCancelEdit: () -> Unit = {},
    modifier: Modifier = Modifier,
)
```

`DraftStore` already keys drafts by room (`draft(roomId)`, `set(text, roomId)`, `clear(roomId)`) — **per-room drafts are its job, not the composable's.** The composable takes `text` and reports changes; the caller routes them to the store.

- [ ] **Step 1: Write the failing tests**

```kotlin
/** Send is disabled with nothing to send, and enabled once there is. */
@Test fun sendIsDisabledUntilThereIsSomethingToSend()
/** Whitespace alone is nothing to send. */
@Test fun whitespaceAloneDoesNotEnableSend()
/** A failure is shown inline rather than swallowed. */
@Test fun aFailureIsShown()
/** While sending, the control does not accept a second tap. */
@Test fun aSecondTapWhileSendingIsIgnored()
```

The last one guards a double-send, which is the composer's version of the double-tap guard `LoginScreen` already carries.

- [ ] **Step 2: Run, confirm failure**
- [ ] **Step 3: Implement**
- [ ] **Step 4: Run, confirm pass**
- [ ] **Step 5: Mutate** — enable send on non-empty rather than non-blank; confirm `whitespaceAloneDoesNotEnableSend` fails. Remove the sending guard; confirm the double-tap test fails.
- [ ] **Step 6: Commit**

---

### Task 3: Reply and edit banners

**Files:**
- Modify: `android/app/src/main/kotlin/dev/supermessage/Composer.kt`
- Test: extend `ComposerTest.kt`

`ReplyTarget.Pending(eventId, sender, excerpt)` and `EditTarget.Pending(...)` are already stores. The banner shows who/what and offers a cancel.

**An edit in progress replaces the composer's text**, and cancelling restores what was there — `EditTarget.start(row, roomId)` returns the original body (`String?`) for exactly this.

- [ ] Steps 1–6 as above. Tests: a reply banner names the sender and can be cancelled; starting an edit fills the field; cancelling an edit restores the prior draft. Mutation: make cancel-edit clear the field instead of restoring, and confirm the restore test fails.

---

### Task 4: Attachments

**Files:**
- Modify: `android/app/src/main/kotlin/dev/supermessage/Composer.kt`
- Test: extend `ComposerTest.kt`

Android's photo picker is `ActivityResultContracts.PickVisualMedia` — **a platform contract with no iOS analogue worth copying.** `StagedAttachment.stage(path, roomId)` takes a path, so the picker's `Uri` must be resolved to something the core can open; do that at the call site, not in the core.

`StagedAttachment` already exposes `file: StateFlow<StagedFile?>`, `stage`, `send`, `discard`. The composable shows what is staged and offers to discard it.

- [ ] Steps 1–6. Tests: a staged file is shown by name; discarding removes it. Mutation: ignore `discard`'s result and confirm the test fails.

---

### Task 5: Reactions on a row

**Files:**
- Modify: `android/app/src/main/kotlin/dev/supermessage/TimelineRow.kt`
- Test: extend `TimelineRowTest.kt`

A2 renders existing reactions read-only. This adds `onReact: ((String) -> Unit)?`, matching iOS's row signature, wired to the core's toggle.

**iOS's own comment is the constraint:** *"two different quick reactions is two different apps"* — the quick set is a product decision already made; do not invent a picker.

- [ ] Steps 1–6. Mutation: make the toggle always add rather than toggle, and confirm a remove test fails.

---

### Task 6: Wire it into the shell, and hold a conversation

**Files:**
- Modify: `android/app/src/main/kotlin/dev/supermessage/MainActivity.kt`, `Timeline.kt`

Route the composer's text through `DraftStore`, its send through `Session.send`, and typing through `Session.setTyping`. Apply `dismissKeyboardOnDrag` to the timeline's scroll container.

- [ ] **Device check, and it is reachable:** a real signed-in session is on `emulator-5554`. Send a real message to a real room and confirm it appears. Reply to one. Edit one. React to one. Report what you actually saw, and what you could not.

---

## Self-Review

**Coverage:** rule 4 (Task 1), the four timeline rules' remaining half; composer text/draft/send (2); reply+edit (3); attachments (4); reactions (5); wiring and a real conversation (6).

**Placeholder scan:** Task 4 deliberately does not transcribe the photo-picker API — it is a platform contract that must be read from the current androidx artifact rather than remembered. Task 5 does not enumerate the quick-reaction set; it is already a product decision in the Swift and must be read there.

**Type consistency:** `Composer`'s signature in Task 2 is extended, not changed, by 3 and 4. `ReplyTarget.Pending` and `EditTarget.Pending` are used under their real names, verified against `:kit`.

**Known risk:** Task 1's test needs a real IME on the emulator. If the IME cannot be driven deterministically from an instrumented test, say so and assert the `nestedScroll` connection's own behaviour instead — but say which was done, and never let the test pass without exercising the rule.
