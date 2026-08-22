# Android Panels (Phase C) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development.

**Goal:** Room info, search, new room, invitations and account — the five surfaces that make this the app the spec describes.

**Architecture:** Each panel is a composable calling `Core` directly. Unlike the roster and timeline, `:kit` has **no store** for most of these — they are request/response, not diff-driven, so they hold their own local state and call the core in a coroutine.

**Design note:** rationale is inline rather than in a separate spec; the roadmap's Phase C section already settled the adaptive question, and A2's spec settled the surrounding surface.

## Global Constraints

- **The app decides nothing.** `roomInfo`, `searchMessages`, `knownPeople`, `peopleMatching`, `peopleLabel` and `createRoom` all live in the core. A panel that filters, ranks, formats a name, or decides what a room "is" re-derives a core decision.
- **Free functions and `Core` methods cross in `uniffi/supermessage_ffi/supermessage_ffi.kt`**, never `supermessage_core.kt`.
- **No `else`** on a `when` over a core sealed class.
- **90 instrumented tests are green and must stay green**, all existing ones unmodified.
- **A test that has never failed is not yet a regression test.**
- Emulator only via `scripts/android-emulator.sh` (headless). `--tests` is a config-time error on `connectedDebugAndroidTest`.
- A **real signed-in session** lives on the emulator. Never clear app data; never touch the Keystore alias `dev.supermessage.secrets`.

## The adaptive rule, already decided

On a wide shell these are the **third pane** (`ListDetailPaneScaffoldRole.Extra`); on a narrow one they are a **bottom sheet**. `paneCountFor(width)` decides which. `RootScaffold` already gates `extraPane` on `navigator.scaffoldValue`, and already carries the collapse effect.

---

### Task 1: Open the info pane — and strand it

**This task is specified before its design because the failure it guards has been observed twice on a sibling platform.**

`RootScaffold.kt:185` holds a `LaunchedEffect(panes)` that collapses an open info pane when the shell narrows past three panes. Its own comment records that **it cannot fire today**, because nothing calls `navigateTo(Extra, ...)`, and that deleting it changes no test. **The moment this task opens the info pane, that block goes from dead code to load-bearing, with nothing covering it.**

The roadmap's requirement, quoted:

> The same task adds the test that strands it — open the pane at a wide width, narrow below `ThreePaneWidth`, assert the pane is gone. Then mutate it: delete the `LaunchedEffect`, watch that test fail, restore. Until that mutation has been seen to fail, rule 2 is documentation rather than a guarantee.

This is the iPad incident (issue #26) — a panel laid out at x=850.5 on an 834-point window, still present on `iPad Pro 11-inch (M4)`, invisible for months because its test died on a stale selector and there was no iOS CI job.

**Files:** modify `RootScaffold.kt` (an `extraPaneContent` slot + a way to open it), `MainActivity.kt`; extend `RootScaffoldTest.kt`.

- [ ] **Step 1:** Write the stranding test — open Extra at a three-pane width, narrow below it, assert the pane is **gone**. Assert geometry, not existence.
- [ ] **Step 2:** Run, confirm failure.
- [ ] **Step 3:** Add an `extraPaneContent` slot (default keeping `Pane("pane-info", …)` and its `testTag`) and a callback that navigates to `Extra`. Wire the roster row's existing `onOpenInfo` — it has been a dead affordance since A1 and this is what it was for.
- [ ] **Step 4:** Run; all 90 plus the new one green, `RootScaffoldTest`'s five unmodified.
- [ ] **Step 5: The mandatory mutation** — delete the `LaunchedEffect`, watch the stranding test fail, restore. **Until this has been seen to fail, the rule is documentation.**
- [ ] **Step 6:** Commit.

---

### Task 2: `RoomInfoPanel`

**Files:** create `RoomInfo.kt`; test `RoomInfoTest.kt`. Read `apple/Supermessage/Panels/RoomInfoPanel.swift` (370 lines).

Core: `roomInfo(roomId)`, `roomAvatarFull(roomId)`, `setRoomNotifications(...)`, `setRoomPinned(...)`, `leaveRoom(roomId)`, `inviteUser(...)`. **Read each signature off the bindings.**

- [ ] Failing tests first: the panel shows the room's name/topic/members from `roomInfo` and derives none of them; mute and pin round-trip; leaving asks first. Then implement, then mutate (make mute ignore its argument; confirm the round-trip test fails). Commit.

---

### Task 3: `SearchPanel`

**Files:** create `Search.kt`; test `SearchTest.kt`. Read `SearchPanel.swift` (161 lines).

Core: `searchMessages(...)`. **The panel ranks and filters nothing** — it renders what the core returned, in the order returned.

- [ ] Failing tests first: results render in core order; an empty result says so rather than showing a blank pane; a query in flight is distinguishable from one that returned nothing. Implement. Mutate: sort the results client-side; confirm the order test fails. Commit.

---

### Task 4: `NewRoomPanel` and invitations

**Files:** create `NewRoom.kt`, `Invitation.kt`; tests. Read `NewRoomPanel.swift` (236) and `InvitationView.swift` (80).

Core: `createRoom(...)`, `directRoomWith(...)`, `knownPeople()`, `peopleMatching(...)`, `joinRoom(...)`, `joinRoomByAlias(...)`, `roomInviter(...)`.

**People matching is the core's.** A panel that filters `knownPeople()` itself with `contains()` has re-derived `peopleMatching`.

- [ ] Failing tests first: creating requires a name; a direct room with a person uses `directRoomWith`, not `createRoom`; an invitation names its inviter via `roomInviter`; accepting joins. Implement. Mutate: filter locally instead of calling `peopleMatching`; confirm the matching test fails. Commit.

---

### Task 5: `AccountPanel`, and the panels on a device

**Files:** create `Account.kt`; test. Read `AccountPanel.swift` (88 lines). Core: `account()`, and sign-out through `Session`.

- [ ] Implement with tests and a mutation.
- [ ] **Device check, reachable:** a real signed-in session is on `emulator-5554`. Open room info on a real room; search for a word you know exists; open the account panel. Confirm on a **phone-width** shell they present as sheets and on a **tablet** width as the third pane. Report what you saw and what you could not.
- [ ] Commit.

---

## Self-Review

**Coverage:** the stranding guarantee (1); five panels (2–5); the adaptive presentation (5's device check).

**Placeholder scan:** Tasks 2–5 deliberately do not transcribe core signatures — every one must be read off the bindings, because six of my transcribed shapes were wrong in Phase A.

**Known risk:** `:kit` has **no store** for room info, search or people, unlike the roster and timeline. These panels hold local state and call the core directly. That is the right shape for request/response, but it means each panel owns its own loading and failure states with no shared idiom — say so in each report so the divergence is visible rather than discovered later.
