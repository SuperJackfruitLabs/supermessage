# Android Roster Implementation Plan (Phase A1)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Sign in on a device against a real homeserver, see your actual rooms in the right sections, and tap one to open the detail pane.

**Architecture:** A `ViewModel` builds the Rust `Core` from `filesDir` and owns a `Session` on `viewModelScope`. `RootScaffold` gates on `Session.phase`: a spinner, `LoginScreen`, or the existing `ListDetailPaneScaffold` with the real roster in its list pane. Every ordering and grouping decision comes from `RosterArrangement`, which is a thin call into `core::roster`.

**Tech Stack:** Kotlin 2.4.0, Compose (BOM 2026.06.01), `androidx.lifecycle.viewmodel.compose`, DataStore Preferences, JUnit 4, AGP 9.3.1, JDK 21.

**Spec:** `docs/superpowers/specs/2026-08-21-android-roster-design.md`

## Global Constraints

- `minSdk = 31`, `targetSdk = 36`, `compileSdk = 36`. `applicationId = "dev.supermessage"`.
- Every version lives in `android/gradle/libs.versions.toml`. Never hardcode one in a module.
- All Gradle commands run from `android/`, not the repo root.
- No `org.jetbrains.kotlin.android` plugin, no `sourceSets[...].kotlin.srcDir(...)`, no `jvmToolchain` — AGP 9.3.1 rejects the first two. `android/app/build.gradle.kts` is the house pattern.
- **`:kit` must declare no dependency on any `androidx.compose.*` artifact.** You are working in `:app`, which is unconstrained — but do not move view code into `:kit` to make something testable.
- **A test that has never failed is not yet a regression test.** Every test is mutated until it fails before it is kept. This is not ceremony: applied literally during the `:kit` port, it found five shipping iOS tests that did not cover the rule their name claimed, and two live defects in the shared design (issue #28).
- **The app parses nothing and decides nothing.** If a view is tempted to sort, group, or decide which section a room belongs to, stop — `core::roster` already answers it, with 13 Rust tests.
- **`RootScaffoldTest`'s three geometry tests must keep passing without modification.** They are the contract that the roster did not disturb the pane rule. A red one is a finding, not a test to adjust.

## The rule that is easiest to get wrong

**Read `state` off the section's row, never by calling `RosterArrangement.state` per row.**

`sections(...)` returns `List<RosterSection>`, each carrying `rows: List<RosterRow>`, and `RosterRow` is `(row: RoomRow, state: AgentState)`. The state is already there. `RosterArrangement.state(row, now)` exists, but its own KDoc says it is "rarely needed on its own", and `RoomListView.swift:62-64` says why:

> The state arrives on the row. Asking per row would be a boundary crossing per visible room per re-render.

## Signatures you will consume

Verified against the committed code, not remembered:

```kotlin
// dev.supermessage.kit.Session
val phase: StateFlow<Session.Phase>        // STARTING | SIGNED_OUT | SIGNED_IN
val failure: StateFlow<String?>
val rooms: RoomsStore
val avatars: AvatarCache
suspend fun start(): Boolean
suspend fun signIn(homeserver: String, username: String, password: String)
suspend fun open(roomId: String)
suspend fun signOut()

// dev.supermessage.kit.stores.RoomsStore
val rooms: StateFlow<List<RoomRow>>
val selectedId: StateFlow<String?>
fun select(roomId: String)
fun deselect()

// dev.supermessage.kit.stores.AvatarCache
fun uri(roomId: String): String?           // a data: URI, or null
suspend fun load(roomId: String)

// dev.supermessage.kit.RosterArrangement
fun sections(rows: List<RoomRow>, view: RosterChoice,
             showsInvitations: Boolean, now: Instant): List<RosterSection>
fun hiddenInvitations(rows: List<RoomRow>, showsInvitations: Boolean): Int

// dev.supermessage.kit.RelativeTime
fun label(ms: ULong?, now: Instant,
          zone: ZoneId = ZoneId.systemDefault(),
          locale: Locale = Locale.getDefault()): String

// uniffi.supermessage_core (generated)
data class RosterSection(id: String, title: String?, detail: String?,
                         rows: List<RosterRow>, attention: Boolean)
data class RosterRow(row: RoomRow, state: AgentState)
```

`Session` is constructed as `Session(client = CoreClient(core), scope = …)`. `CoreClient(core: CoreInterface, dispatcher: CoroutineDispatcher = Dispatchers.IO)`.

---

## File Structure

| File | Responsibility |
|---|---|
| `app/…/SessionViewModel.kt` | Builds `Core` from `filesDir`; owns `Session` on `viewModelScope` |
| `app/…/RosterPreferences.kt` | DataStore: four keys, exposed as `Flow`s |
| `app/…/LoginScreen.kt` | Homeserver / username / password, and the failure line |
| `app/…/Roster.kt` | Sections, the arrangement menu, the hidden-invitation count |
| `app/…/RoomRow.kt` | One row: avatar, name, preview, state dot, relative time |
| `app/…/MainActivity.kt` | *(edit)* hoist the ViewModel |
| `app/…/RootScaffold.kt` | *(edit)* phase gate; real roster in the list pane |

`RoomRow.kt` is separate from `Roster.kt` because a row is what gets read on its own when something looks wrong.

---

### Task 1: Dependencies and `SessionViewModel`

**Files:**
- Modify: `android/gradle/libs.versions.toml`, `android/app/build.gradle.kts`
- Create: `android/app/src/main/kotlin/dev/supermessage/SessionViewModel.kt`
- Test: `android/app/src/test/kotlin/dev/supermessage/SessionViewModelTest.kt`

**Interfaces:**
- Consumes: `Session`, `CoreClient` from `:kit`; `Core` from `:core`.
- Produces: `class SessionViewModel(app: Application) : AndroidViewModel(app)` with `val session: Session`. Tasks 3–6 read `viewModel.session`.

- [ ] **Step 1: Add the three dependencies to the catalog**

Add to `android/gradle/libs.versions.toml`:

```toml
# [versions]
lifecycle = "2.10.0"
datastorePreferences = "1.2.1"

# [libraries]
androidx-lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycle" }
androidx-lifecycle-runtime-compose = { group = "androidx.lifecycle", name = "lifecycle-runtime-compose", version.ref = "lifecycle" }
androidx-datastore-preferences = { group = "androidx.datastore", name = "datastore-preferences", version.ref = "datastorePreferences" }
```

Then in `android/app/build.gradle.kts`'s `dependencies`:

```kotlin
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.datastore.preferences)
```

**These versions are not the newest, and that is deliberate — do not "update" them.** I checked each artifact's `aar-metadata.properties` directly:

| Artifact | Newest | `minCompileSdk` | Pinned here |
|---|---|---|---|
| `lifecycle-viewmodel-compose` | 2.11.0 | **37** | 2.10.0 (`minCompileSdk=35`) |
| `lifecycle-runtime-compose` | 2.11.0 | **37** | 2.10.0 (`minCompileSdk=35`) |
| `datastore-preferences` | 1.2.1 | 34 | 1.2.1 — newest is fine |

This project's `compileSdk` is fixed at 36, so `2.11.0` would fail `checkDebugAarMetadata`. The scaffold hit the identical ceiling and pinned `composeBom` to `2026.06.01` and `adaptive` to `1.2.0` for the same reason. If a future bump is wanted, raising `compileSdk` is the decision to make first — not stepping the library up and finding out.

`lifecycle-runtime-compose` is a **separate artifact** from `lifecycle-viewmodel-compose`, and Task 3 needs it for `collectAsStateWithLifecycle`. Both are added here so Task 3 does not have to stop and add one.

- [ ] **Step 2: Write the failing test**

`SessionViewModel` builds a real `Core`, which opens SQLite — so the test asserts the wiring, not a live session. Use Robolectric? **No.** Keep it on the JVM by testing the one thing that has no Android dependency: that the ViewModel exposes a `Session` built on its own scope, and that clearing it tears the session down.

```kotlin
package dev.supermessage

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class SessionViewModelTest {

    /** The session is built once and handed out, not rebuilt per read. */
    @Test
    fun theSessionIsStable() = runTest {
        val vm = SessionViewModel.forTest(FakeCore())
        assertNotNull(vm.session)
        assertEquals(vm.session, vm.session)
    }

    /** Clearing the ViewModel signs the session out. */
    @Test
    fun clearingSignsOut() = runTest {
        val core = FakeCore()
        val vm = SessionViewModel.forTest(core)
        vm.clearForTest()
        assertEquals(1, core.logoutCalls)
    }
}
```

`FakeCore : CoreInterface` — `:app` has no fake yet, so write one here. `:kit`'s house pattern is a **nested private class that throws `NotImplementedError()` by default** and implements only what the test needs; `android/kit/src/test/kotlin/dev/supermessage/kit/CoreClientTest.kt` shows it. Follow that.

`CoreInterface` has 37 methods, so this is boilerplate. If it proves genuinely unworkable rather than merely tedious, say so and propose a narrower seam — do not skip testing the ViewModel.

- [ ] **Step 3: Run it, confirm it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest`
Expected: FAIL, `Unresolved reference: SessionViewModel`.

- [ ] **Step 4: Write `SessionViewModel`**

```kotlin
package dev.supermessage

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.supermessage.kit.CoreClient
import dev.supermessage.kit.Session
import kotlinx.coroutines.CoroutineScope
import uniffi.supermessage_core.Core
import uniffi.supermessage_ffi.CoreInterface

/**
 * The one place in the Android app that names a filesystem location.
 *
 * A `ViewModel` rather than a `remember` or an `Application` singleton. The
 * manifest suppresses recreation for `orientation|screenSize|screenLayout|
 * keyboardHidden` and nothing else — a locale change, a dark-mode toggle, a
 * font-scale change or process death all recreate the Activity, and under
 * `remember` each of those would rebuild the Rust core and reopen SQLite
 * while the previous `Session`'s drain outlived it. `viewModelScope` also
 * cancels at a defined moment, which a process-scoped session never does.
 */
class SessionViewModel(app: Application) : AndroidViewModel(app) {
    val session: Session = build(Core(dataDir = app.filesDir.path), viewModelScope)

    override fun onCleared() {
        // Nothing awaits this; viewModelScope is already cancelling.
        super.onCleared()
    }

    companion object {
        internal fun build(core: CoreInterface, scope: CoroutineScope): Session =
            Session(client = CoreClient(core), scope = scope)
    }
}
```

Add whatever `forTest`/`clearForTest` seam the tests above need. Keep it `internal` — it exists for the tests in this module, not for `:app` at large.

- [ ] **Step 5: Run to green**

Run: `cd android && ./gradlew :app:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 6: Mutate**

Make `session` a `get()` that builds a new `Session` each read; confirm `theSessionIsStable` fails. Restore. Then break the teardown; confirm `clearingSignsOut` fails. Restore. Paste both.

- [ ] **Step 7: Commit**

```bash
cd /home/rakeshgangwar/Projects/supermessage
git add android/gradle/libs.versions.toml android/app/
git commit -m "Android app: the session, and the one place that names a path"
```

---

### Task 2: `RosterPreferences`

**Files:**
- Create: `android/app/src/main/kotlin/dev/supermessage/RosterPreferences.kt`
- Test: `android/app/src/test/kotlin/dev/supermessage/RosterPreferencesTest.kt`

**Interfaces:**
- Consumes: `RosterChoice` from `:kit`.
- Produces: `class RosterPreferences(private val store: DataStore<Preferences>)` exposing `val homeserver: Flow<String>`, `val view: Flow<RosterChoice>`, `val showsInvitations: Flow<Boolean>`, `val showsState: Flow<Boolean>`, and a `suspend fun set…` for each. Tasks 3 and 5 consume these.

Four keys and their defaults, from the spec:

| Key | Type | Default |
|---|---|---|
| `login.homeserver` | String | `https://id.agentpod.dev` |
| `roster.view` | `RosterChoice` | `RosterChoice.Waiting` |
| `roster.showsInvitations` | Boolean | `false` |
| `roster.showsState` | Boolean | `true` |

**`login.homeserver` persists for a reason worth keeping in a comment.** From `LoginView.swift:15-18`: it *was* `@State`, so a failed sign-in — a typo in the password, a homeserver briefly down — threw the address away and made the reader type it again to try the thing that was nearly right.

- [ ] **Step 1: Write the failing tests**

Take a `DataStore<Preferences>` as a constructor parameter so the test can supply one backed by a temp directory. Do not reach for a `Context` inside the class.

```kotlin
class RosterPreferencesTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun prefs(scope: TestScope): RosterPreferences =
        RosterPreferences(
            PreferenceDataStoreFactory.create(scope = scope) {
                tmp.newFile("prefs.preferences_pb")
            })

    /** Defaults come back before anything has been written. */
    @Test
    fun defaultsBeforeAnyWrite() = runTest {
        val p = prefs(this)
        assertEquals("https://id.agentpod.dev", p.homeserver.first())
        assertEquals(RosterChoice.Waiting, p.view.first())
        assertEquals(false, p.showsInvitations.first())
        assertEquals(true, p.showsState.first())
    }

    /** A written value round-trips. */
    @Test
    fun theChosenArrangementSurvives() = runTest {
        val p = prefs(this)
        p.setView(RosterChoice.Machine)
        assertEquals(RosterChoice.Machine, p.view.first())
    }

    /**
     * The homeserver outlives a failed attempt.
     *
     * It was `@State` on iOS, so a typo in the password threw the address
     * away and made the reader retype something that was nearly right.
     */
    @Test
    fun theHomeserverIsRemembered() = runTest {
        val p = prefs(this)
        p.setHomeserver("https://matrix.example.org")
        assertEquals("https://matrix.example.org", p.homeserver.first())
    }

    /** An unreadable stored arrangement falls back rather than throwing. */
    @Test
    fun anUnknownArrangementFallsBack() = runTest {
        val p = prefs(this)
        p.setRawViewForTest("NotAChoice")
        assertEquals(RosterChoice.Waiting, p.view.first())
    }
}
```

Add `testImplementation(libs.junit)` is already present; you will also need a `TemporaryFolder` rule (JUnit 4 core, no new dependency) and `kotlinx-coroutines-test`, which `:app` already has.

- [ ] **Step 2: Run, confirm failure**

- [ ] **Step 3: Write `RosterPreferences`**

Map `RosterChoice` by `name`, and fall back to `Waiting` on anything unrecognised — `enumValueOf` throws, and a preferences file written by a future version must not crash the app.

- [ ] **Step 4: Run to green**

- [ ] **Step 5: Mutate**

Change the homeserver default to `""`; confirm `defaultsBeforeAnyWrite` fails. Remove the unknown-value fallback; confirm `anUnknownArrangementFallsBack` fails. Restore both, paste the output.

- [ ] **Step 6: Commit**

```bash
git add android/app/ && git commit -m "Android app: four preferences, and why the homeserver is one"
```

---

### Task 3: The phase gate

**Files:**
- Modify: `android/app/src/main/kotlin/dev/supermessage/RootScaffold.kt`, `MainActivity.kt`
- Test: `android/app/src/androidTest/kotlin/dev/supermessage/PhaseGateTest.kt`

**Interfaces:**
- Consumes: `SessionViewModel` (Task 1), `Session.Phase`.
- Produces: `RootScaffold` renders by phase. Task 6 puts the roster inside the `SIGNED_IN` branch.

The shape iOS uses, at `RootView.swift:15-25`: `STARTING` → progress, `SIGNED_OUT` → login, `SIGNED_IN` → the panes.

- [ ] **Step 1: Write the failing instrumented test**

This is a Compose test, so it goes in `androidTest` and needs a device. `:app` already has `compose-ui-test-junit4` and `androidx.test.runner` wired.

Make the phase injectable rather than reaching into a real `Session` — have `RootScaffold` take `phase: Session.Phase` and the pieces it needs, so the gate is testable without a `Core`.

```kotlin
class PhaseGateTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun startingShowsProgressAndNoPanes() {
        compose.setContent { RootScaffold(phase = Session.Phase.STARTING, …) }
        compose.onNodeWithTag("phase-starting").assertIsDisplayed()
        compose.onNodeWithTag("pane-roster").assertDoesNotExist()
    }

    @Test
    fun signedOutShowsLoginAndNoPanes() {
        compose.setContent { RootScaffold(phase = Session.Phase.SIGNED_OUT, …) }
        compose.onNodeWithTag("login").assertIsDisplayed()
        compose.onNodeWithTag("pane-roster").assertDoesNotExist()
    }

    @Test
    fun signedInShowsThePanes() {
        compose.setContent { RootScaffold(phase = Session.Phase.SIGNED_IN, …) }
        compose.onNodeWithTag("pane-roster").assertIsDisplayed()
        compose.onNodeWithTag("login").assertDoesNotExist()
    }
}
```

**Assert geometry, not existence, wherever a thing is expected to be visible.** `assertIsDisplayed()`, not `assertExists()` — a test once asserted the room-info panel existed while it was laid out off the side of an iPad. `assertDoesNotExist()` for absence is correct and device-independent.

- [ ] **Step 2: Run on a device, confirm failure**

Boot an AVD if none is attached: `$ANDROID_HOME/emulator/emulator -avd supermessage-phone &`, then poll `adb shell getprop sys.boot_completed` until it returns 1. AVDs available: `supermessage-phone` (411dp portrait / 914dp landscape), `supermessage-tablet` (800/1280), `supermessage-16k` (411/923).

Run: `cd android && ./gradlew :app:connectedDebugAndroidTest`

- [ ] **Step 3: Add the gate to `RootScaffold`**

Keep the existing `BoxWithConstraints` + `paneCountFor` + `ListDetailPaneScaffold` exactly as it is, inside the `SIGNED_IN` branch. Nothing about the pane rule changes.

- [ ] **Step 4: Hoist the ViewModel in `MainActivity`**

```kotlin
setContent {
    MaterialTheme {
        Surface {
            val vm: SessionViewModel = viewModel()
            val phase by vm.session.phase.collectAsStateWithLifecycle()
            LaunchedEffect(Unit) { if (phase == Session.Phase.STARTING) vm.session.start() }
            RootScaffold(phase = phase, session = vm.session)
        }
    }
}
```

`collectAsStateWithLifecycle` comes from `lifecycle-runtime-compose`, which Task 1 already added — it is a separate artifact from `lifecycle-viewmodel-compose`, which is why Task 1 adds both.

- [ ] **Step 5: Run to green**

Run: `cd android && ./gradlew :app:connectedDebugAndroidTest`
Expected: PASS — 3 new, plus `RootScaffoldTest`'s 3 unchanged. **If any of those three went red, stop and report it.**

- [ ] **Step 6: Mutate**

Make the gate always render the panes; confirm both `startingShowsProgress…` and `signedOutShowsLogin…` fail. Restore.

- [ ] **Step 7: Commit**

```bash
git add android/app/ && git commit -m "Android app: what the app shows, and when"
```

---

### Task 4: `LoginScreen`

**Files:**
- Create: `android/app/src/main/kotlin/dev/supermessage/LoginScreen.kt`
- Test: `android/app/src/androidTest/kotlin/dev/supermessage/LoginScreenTest.kt`
- Read first: `apple/Supermessage/LoginView.swift` (73 lines)

**Interfaces:**
- Consumes: `RosterPreferences.homeserver` (Task 2), `Session.signIn`, `Session.failure`.
- Produces: `@Composable fun LoginScreen(homeserver: String, onHomeserverChange: (String) -> Unit, failure: String?, busy: Boolean, onSignIn: (username: String, password: String) -> Unit)`. Task 3's `SIGNED_OUT` branch renders it.

Three fields, a button, and the failure line. Username and password are ordinary composable state; **the homeserver is hoisted**, because it persists.

- [ ] **Step 1: Write the failing tests**

```kotlin
class LoginScreenTest {
    @get:Rule val compose = createComposeRule()

    /** A failure is shown, not swallowed. */
    @Test
    fun theFailureIsVisible() {
        compose.setContent {
            LoginScreen(homeserver = "https://h", onHomeserverChange = {},
                failure = "the homeserver refused those credentials",
                busy = false, onSignIn = { _, _ -> })
        }
        compose.onNodeWithText("the homeserver refused those credentials").assertIsDisplayed()
    }

    /** Signing in hands over what was typed. */
    @Test
    fun signingInPassesTheCredentials() {
        var got: Pair<String, String>? = null
        compose.setContent {
            LoginScreen(homeserver = "https://h", onHomeserverChange = {},
                failure = null, busy = false, onSignIn = { u, p -> got = u to p })
        }
        compose.onNodeWithTag("username").performTextInput("ganesha")
        compose.onNodeWithTag("password").performTextInput("hunter2")
        compose.onNodeWithTag("sign-in").performClick()
        assertEquals("ganesha" to "hunter2", got)
    }

    /** A sign-in already in flight cannot be started twice. */
    @Test
    fun aBusyFormDoesNotSubmitAgain() {
        var calls = 0
        compose.setContent {
            LoginScreen(homeserver = "https://h", onHomeserverChange = {},
                failure = null, busy = true, onSignIn = { _, _ -> calls++ })
        }
        compose.onNodeWithTag("sign-in").performClick()
        assertEquals(0, calls)
    }
}
```

- [ ] **Step 2: Run, confirm failure**

- [ ] **Step 3: Write `LoginScreen`**

Password field uses `PasswordVisualTransformation`. The failure line renders only when `failure != null`.

- [ ] **Step 4: Run to green**

- [ ] **Step 5: Mutate**

Remove the `busy` guard on the button; confirm `aBusyFormDoesNotSubmitAgain` fails. Restore.

- [ ] **Step 6: Wire it into `RootScaffold`'s `SIGNED_OUT` branch**, reading the homeserver from `RosterPreferences` and calling `session.signIn`. Keep `busy` in the composable while the suspend call runs.

- [ ] **Step 7: Commit**

```bash
git add android/app/ && git commit -m "Android app: the way in"
```

---

### Task 5: `RoomRow`

**Files:**
- Create: `android/app/src/main/kotlin/dev/supermessage/RoomRow.kt`
- Test: `android/app/src/androidTest/kotlin/dev/supermessage/RoomRowTest.kt`
- Read first: `apple/Supermessage/Rooms/RoomRowView.swift` (167 lines)

**Interfaces:**
- Consumes: `RoomRow` and `AgentState` from `:core`; `RelativeTime` from `:kit`.
- Produces: `@Composable fun RoomRow(row: RoomRow, avatarUri: String?, state: AgentState, when: String, showsState: Boolean = true, hidesHost: Boolean = false, onOpenInfo: (() -> Void)? = null)`. Task 6 renders it per row.

**Everything on the row was decided by the core.** From the Swift's own doc comment: *"the sigil and name come from `row.identity`, the preview line from `row.preview`. This view parses nothing and composes nothing; it lays out what it was handed."*

Note the avatar is its own tap target: tapping it asks about the room, tapping anywhere else opens the conversation.

- [ ] **Step 1: Write the failing tests**

Build `RoomRow` fixtures the way `:kit`'s store tests do — `android/kit/src/test/kotlin/dev/supermessage/kit/stores/RoomsStoreTest.kt` has helpers to copy the shape from.

Cover: the name and preview render; the invitation badge appears only for `RoomAffordance.RespondToInvitation`; `showsState = false` hides the state dot; a null `avatarUri` still renders a row (the sigil fallback) rather than a blank.

- [ ] **Step 2: Run, confirm failure**

- [ ] **Step 3: Write `RoomRow`**

Decode the avatar in the composable:

```kotlin
val bitmap = remember(avatarUri) { avatarUri?.decodeDataUri() }
```

Write `decodeDataUri()` as a small private helper: strip the `data:…;base64,` prefix, `Base64.decode`, `BitmapFactory.decodeByteArray`, `.asImageBitmap()`. Return null on anything malformed rather than throwing — a bad avatar must not take the row down.

**The decode budget is A2's problem, not yours.** Avatars are small and bounded by what `LazyColumn` composes. Do not add a bitmap cache here.

- [ ] **Step 4: Run to green**

- [ ] **Step 5: Mutate**

Make the invitation badge unconditional; confirm the badge test fails. Restore.

- [ ] **Step 6: Commit**

```bash
git add android/app/ && git commit -m "Android app: one roster row, laid out as handed"
```

---

### Task 6: `Roster`, and into the pane

**Files:**
- Create: `android/app/src/main/kotlin/dev/supermessage/Roster.kt`
- Modify: `android/app/src/main/kotlin/dev/supermessage/RootScaffold.kt`
- Test: `android/app/src/androidTest/kotlin/dev/supermessage/RosterTest.kt`
- Read first: `apple/Supermessage/Rooms/RoomListView.swift` (270 lines)

**Interfaces:**
- Consumes: everything above.
- Produces: `@Composable fun Roster(sections: List<RosterSection>, hiddenInvitations: Int, …)` rendered in the list pane.

- [ ] **Step 1: Write the failing tests**

Three things, each chosen because it has failed somewhere:

```kotlin
/** Sections and their rows render in the order the core returned them. */
@Test fun sectionsRenderInCoreOrder() { … }

/**
 * The list re-renders when rooms emits.
 *
 * Asserting an observer was NOTIFIED, not that the value reads back —
 * reading back succeeds even when nothing told the view, which is exactly
 * how iOS's avatars appeared only on the second scroll.
 */
@Test fun anArrivingRoomIsShownWithoutATouch() { … }

/** The picker admits how many invitations it is withholding. */
@Test fun hiddenInvitationsAreCounted() { … }
```

- [ ] **Step 2: Run, confirm failure**

- [ ] **Step 3: Write `Roster`**

`LazyColumn`, with `stickyHeader` per section where `section.title != null`. **Key rows by `entry.row.room.id`** — a stable key is what stops a room jumping when the list reorders.

Read `state` from `entry.state`; do not call `RosterArrangement.state`.

- [ ] **Step 4: Wire it into the list pane**

Replace the placeholder in `RootScaffold`'s list pane. Compute `sections` and `hiddenInvitations` from `session.rooms.rooms` and the preferences, with `now` from a `LaunchedEffect` ticking every 30 seconds so "3m" becomes "4m" without a new event.

Tapping a row calls `session.rooms.select(id)` and `session.open(id)`.

- [ ] **Step 5: Run the whole instrumented suite**

Run: `cd android && ./gradlew :app:connectedDebugAndroidTest`
Expected: PASS — including `RootScaffoldTest`'s three geometry tests, **unmodified**. If one is red, that is the finding this plan's Global Constraints warn about.

- [ ] **Step 6: Mutate**

Sort the sections before rendering; confirm `sectionsRenderInCoreOrder` fails. Restore. That mutation is the one that matters — it is the app re-deciding something the core decided.

- [ ] **Step 7: Look at it on a device**

```bash
cd android && ./gradlew :app:installDebug
adb shell am start -n dev.supermessage/.MainActivity
```

Sign in against a real homeserver. Confirm: your actual rooms, in the right sections, with the right previews and times. Tap one — the detail pane opens. Rotate — the roster survives and the pane count follows the width.

**This is A1's acceptance criterion and it cannot be automated here.** Report what you saw, and say plainly that it was a manual check.

- [ ] **Step 8: Commit**

```bash
git add android/app/ && git commit -m "Android app: the roster, from the core's own arrangement"
```

---

## Self-Review

**Spec coverage:**

| Spec section | Task |
|---|---|
| §1 structure — five new files, two edited | 1–6 |
| §1.1 pane rule untouched, three geometry tests green | 3 step 5, 6 step 5 |
| §2 `Session` in a `ViewModel` on `viewModelScope` | 1 |
| §2 data directory supplied by `:app` | 1 step 4 |
| §3 data flow, no decisions in the view | 6 step 3, 6 step 6 |
| §3 read `state` off the section | 6 step 3 |
| §3.1 the clock ticks, injected not read | 6 step 4 |
| §4 four preferences, DataStore | 2 |
| §4 `login.homeserver` persists | 2 |
| §5 phase gate tested | 3 |
| §5 notification path tested | 6 step 1 |
| §5 geometry tests unmodified | 3, 6 |
| §5 device check, stated as manual | 6 step 7 |
| §6 sequence | Tasks map 1:1 to the spec's six steps |

**Placeholder scan:** clean. Task 1's versions were resolved against each artifact's real `aar-metadata.properties` rather than left as a guess, and the two that would have failed this project's `compileSdk` ceiling are pinned with the evidence in a table. Task 5's and 6's test bodies give the cases and the reasoning rather than full literals, because the fixtures depend on `RoomRow`'s generated shape, which the implementer must read from `RoomsStoreTest.kt` rather than from my transcription. Every other step carries real code.

**Type consistency:** `SessionViewModel.session: Session` (Task 1) is consumed under that name in 3–6. `RosterPreferences`' four `Flow`s (Task 2) are consumed in 4 and 6. `RoomRow`'s composable signature (Task 5) matches its call in Task 6. `RosterSection`/`RosterRow` field names match the generated bindings verified above.

**What this plan does not cover:** the timeline (A2), the spaces rail, room info, search, theme, and the decode budget — all recorded in the spec's §7.
