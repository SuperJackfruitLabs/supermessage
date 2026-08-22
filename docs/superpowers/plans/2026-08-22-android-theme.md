# Android Theme and Polish (Phase D) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development.

**Goal:** Give the app its own voice — structural typography, a real dark mode, and the two adaptive gaps A–C left behind.

**Architecture:** A Compose `Theme.kt` porting `Theme.swift`'s *structure* (not its exact colours), a `styles.xml` replacing the light-only framework placeholder, and two functional fixes the earlier phases deferred with reasons recorded.

## Global Constraints

- **120 instrumented tests are green and must stay green**, all existing ones unmodified unless a signature forces an arity-only edit (disclose it).
- **A test that has never failed is not yet a regression test.**
- No `else` on a `when` over a core sealed class. Core methods cross in `uniffi/supermessage_ffi/supermessage_ffi.kt`.
- Emulator headless via `scripts/android-emulator.sh`; `--tests` is a config-time error on `connectedDebugAndroidTest`.
- A **real signed-in session** is on the device. Never clear app data; never touch the Keystore alias `dev.supermessage.secrets`; never invoke sign-out on the device.

## Typography is structural, not decorative

From the spec, and it survived a typeface swap on iOS: **serif for what agents write, sans for what the operator writes, mono for data and sigils, and amber for nothing but a pending decision.** `Theme.swift` encodes this as `body` (serif), `own` (sans), `code` (mono), plus `metaFace`/`nameFace` modifiers and an `accent` reserved for decisions.

That distinction is the point. A theme that makes everything one face has thrown away the only thing this app's typography is *for*.

---

### Task 1: `Theme.kt` — the structure

**Files:** create `android/app/src/main/kotlin/dev/supermessage/Theme.kt`; test `ThemeTest.kt`. Read `apple/Supermessage/Theme.swift` (140 lines) fully — it is small.

- [ ] **Step 1: Failing tests.** A message an *agent* wrote renders in the serif face; one the *operator* wrote renders in sans; a code span renders mono. Assert the resolved `FontFamily`, not a colour.
- [ ] **Step 2: Run, confirm failure.**
- [ ] **Step 3: Implement** — a `SupermessageTheme` wrapping `MaterialTheme` with a `Typography` carrying the three faces, and semantic colours (`ground`, `sunken`, `hairline`, `accent`, `signal`, `danger`, `ok`) defined for **both** light and dark.
- [ ] **Step 4: Run, confirm pass.**
- [ ] **Step 5: Mutate** — make `own` serif too; confirm the operator-face test fails.
- [ ] **Step 6: Commit.**

---

### Task 2: Adopt the theme, and replace the placeholder

**Files:** modify `AndroidManifest.xml`, `MainActivity.kt`, and every composable currently reaching for a raw colour.

`AndroidManifest.xml:35` still carries `@android:style/Theme.Material.Light.NoActionBar`, and its own comment explains why: `Theme.Material3.DayNight.NoActionBar` lives in a dependency this project does not have, so **a cold start under system dark mode shows a white window** until Compose paints. The comment says a custom `styles.xml` is the fix and calls itself a "forward-looking placeholder… revisit there" — this is there.

Two known hardcoded colours to replace, both flagged in earlier reviews with a note pointing at this phase:
- `RoomRow.kt`'s state-dot colours (`Color(0xFF34C759)`, `PendingAmber = Color(0xFFFF9500)`)
- `DecisionCard.kt`'s amber constant

- [ ] Failing test first: under dark mode the window background is **not** white (assert the resolved background, not a screenshot). Then `styles.xml` with a `DayNight` parent that needs no new dependency, adopt `SupermessageTheme` in `MainActivity`, and replace the hardcoded colours with tokens. Mutate: point the dark token at the light value; confirm the test fails. Commit.

---

### Task 3: The two-pane info sheet — a functional gap, not polish

**Files:** modify `RootScaffold.kt`, `PaneLayout.kt`'s doc; extend `RootScaffoldTest.kt`.

`PaneLayout.kt:12` states: *"Below `ThreePaneWidth` the info panel has no pane to live in — RootScaffold withholds it entirely rather than squeeze it in, and today that just means 'not shown'. A sheet over the two panes is the intended future treatment, not something that exists yet."*

Meanwhile the roadmap (line 59) and the scaffold spec (line 181) both describe the sheet as decided. **The docs promise what the code says is unbuilt**, and a device check at ~700dp found the consequence: on a tablet in portrait, tapping a roster avatar to open room info has nowhere to go.

- [ ] **Step 1: Failing test** — at a two-pane width, requesting room info **shows it** (as a sheet over the two panes). Assert geometry: it must be on-screen and within the shell, not at degenerate bounds.
- [ ] **Step 2: Run, confirm failure** — this should fail today, which is the point.
- [ ] **Step 3: Implement** a `ModalBottomSheet` (or the equivalent that needs no new dependency) shown when `panes == 2` and info is requested, while `panes == 3` keeps the Extra pane.
- [ ] **Step 4:** Run; 120 plus new, `RootScaffoldTest`'s geometry and stranding tests unmodified.
- [ ] **Step 5: Mutate** — withhold the sheet at two panes; confirm the new test fails. Also re-run the **stranding** test to prove the three-pane collapse still works.
- [ ] **Step 6:** Update `PaneLayout.kt`'s comment — it currently says the sheet does not exist. Commit.

---

### Task 4: The reaction picker, and a device pass

**Files:** modify `TimelineRow.kt`; extend `TimelineRowTest.kt`.

Phase B made existing reaction chips toggleable but **left no way to add a reaction that is not already on a message** — iOS reaches its picker from a context menu. The quick set is already decided: `["✅", "👍", "❌", "👀"]`, verbatim from `TimelineRowView.swift:22`. **Do not invent a different set** — "two different quick reactions is two different apps."

- [ ] Failing test, implement a long-press picker offering exactly that set, mutate (offer a different set / drop the picker), commit.
- [ ] **Final device pass** on the real session: dark mode looks right, an agent's message is serif and yours is sans, room info opens at two-pane width, and a reaction can be added from scratch. Report what you saw and what you could not.

---

## Self-Review

**Coverage:** typography structure (1); dark mode and the manifest placeholder (2); the two-pane sheet (3); the reaction picker (4).

**Placeholder scan:** no colour values are transcribed here — `Theme.swift`'s are iOS-specific and Android has its own dynamic-colour story; the *structure* ports, the hex does not.

**Known risk:** Task 2 touches every surface A–C built. If it goes wide and destabilises the suite, land Task 1 and Task 3 first — Task 3 is a functional fix and matters more than colour.
