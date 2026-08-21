# Android `kit` Implementation Plan (companion steps 3–4)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port `apple/SupermessageKit` (2,892 lines) to `android/kit` as Kotlin, with its 2,134 lines of Swift tests, so the Android state layer is complete and testable on the JVM with no emulator.

**Architecture:** `kit` sits between `:core` (the Rust boundary over UniFFI) and `:app` (Compose). Every `Core` call goes through one `CoreClient` on `Dispatchers.IO`; every core event enters through one `EventPump` and is drained by exactly one collector, so arrival order survives. State is exposed as `StateFlow`, replacing Swift's `@Observable`. `kit` imports no Compose — a build-file rule enforces it.

**Tech Stack:** Kotlin 2.4.0, coroutines, `StateFlow`/`Channel`, JUnit 4, AGP 9.3.1, JDK 21.

**Spec:** `docs/superpowers/specs/2026-08-20-android-app-design.md` (steps 3–4 of its sequence). Scaffold context: `docs/superpowers/specs/2026-08-20-android-scaffold-design.md`.

## Global Constraints

- `minSdk = 31`, `compileSdk = 36`. `:kit` is a library and omits `targetSdk`.
- **`:kit` must declare no dependency on any `androidx.compose.*` artifact.** The build fails if it does (`android/kit/build.gradle.kts`), and `ModuleShapeTest` probes the classpath as a second check. Both already exist — do not weaken either.
- Every version lives in `android/gradle/libs.versions.toml`. Never hardcode one in a module.
- All Gradle commands run from `android/`, not the repo root.
- No `org.jetbrains.kotlin.android` plugin, no `sourceSets[...].kotlin.srcDir(...)`, no `jvmToolchain` — AGP 9.3.1 rejects the first two and the existing modules use `compileOptions` only. Follow `android/core/build.gradle.kts` as the house pattern.
- **A test that has never failed is not yet a regression test.** Every ported test is mutated until it fails before it is kept. This is not optional and it is what makes the port trustworthy.
- New tests go in `android/kit/src/test/kotlin/dev/supermessage/kit/` and run on the JVM. Nothing in this plan needs a device.
- **The app parses nothing and decides nothing.** If a port tempts you to re-derive a rule that lives in Rust, stop — call the core instead. That rule is why this port is tractable.

---

## The translation rules

Every task applies these. They are stated once here rather than repeated fifteen times.

| Swift | Kotlin | Note |
|---|---|---|
| `@Observable final class` | class exposing `StateFlow` | `private val _x = MutableStateFlow(...)`; `val x: StateFlow<T> = _x.asStateFlow()` |
| `@MainActor` | `Dispatchers.Main` confinement | Stores are main-confined; assert it in tests with a test dispatcher |
| `actor` | class + `Mutex`, or confinement to one dispatcher | Only `CoreClient` is an actor in Swift |
| `AsyncStream` | `Channel(UNLIMITED)` + `receiveAsFlow()` | Unbounded is deliberate — see Task 8 |
| `Task { }` | `scope.launch { }` | Never `GlobalScope` — see Task 7 |
| `withCheckedContinuation` | `suspendCancellableCoroutine`, or just `withContext(Dispatchers.IO)` | Prefer the latter |
| `struct` | `data class` | |
| `enum` with payloads | `sealed interface` / `sealed class` | |
| `Optional`/`?` | nullable `T?` | |
| `&+=` (wrapping add) | `+=` on `ULong` | Kotlin wraps by default on unsigned |
| XCTest `XCTAssertEqual` | JUnit `assertEquals` | |
| Swift Testing `#expect` | JUnit `assertTrue`/`assertEquals` | Some tests use the newer syntax |

**The source file is the specification.** Each task names the Swift original and its test. Read both before writing Kotlin. The doc comments carry the reasoning and most of them should survive the translation — they explain *why*, and the why is language-independent.

---

## File Structure

```
android/kit/src/main/kotlin/dev/supermessage/kit/
  CoreClient.kt          every Core call, off the main thread
  EventPump.kt           one channel, one collector, order preserved
  Session.kt             the orchestrator that owns the stores
  DiffApply.kt           + DiffTracker.kt   diff application and gap detection
  GapSync.kt             resync when a sequence breaks
  ErrorPresenter.kt      typed failures → what the user sees
  RelativeTime.kt  SendState.kt  SearchState.kt  RichTextFolding.kt
  StreamingText.kt       the reveal pacer
  TimelineGrouping.kt  TimelineFollow.kt  RosterArrangement.kt
  stores/
    ConnectionStore.kt  RoomsStore.kt  SpacesStore.kt  TimelineStore.kt
    LiveStore.kt  TypingStore.kt  DraftStore.kt  ReplyTarget.kt
    EditTarget.kt  StagedAttachment.kt  AvatarCache.kt  MediaCache.kt
android/kit/src/test/kotlin/dev/supermessage/kit/
  (one test file per source file, ported from apple/SupermessageKitTests/)
```

Ordered by dependency: pure functions first (Tasks 1–6), then the boundary (7–8), then stores (9–14), then the orchestrator that needs all of them (15).

---

### Task 1: The small pure values

**Files:**
- Create: `kit/.../RelativeTime.kt`, `SendState.kt`, `SearchState.kt`, `RichTextFolding.kt`
- Test: `kit/.../RelativeTimeTest.kt`, `SendStateTest.kt`, `SearchStateTest.kt`, `RichTextFoldingTest.kt`
- Read first: `apple/SupermessageKit/{RelativeTime,SendState,SearchState,RichTextFolding}.swift` and the matching `apple/SupermessageKitTests/*Tests.swift`

**Interfaces:**
- Consumes: nothing.
- Produces: four pure modules. `RelativeTime` formats an instant against a clock; `SendState` models a message's send lifecycle; `SearchState` models a search's; `RichTextFolding` folds the core's recursive `[RichInline]` tree into flat styled runs a view can draw — it collapses nothing, and it parses nothing, since the core hands over an already-parsed tree. Later tasks use `SendState` (Task 15) and `RichTextFolding` (`:app`, later plan).

This is one task rather than four because each is under 60 lines, pure, and shaped identically — a batch a single reviewer can judge as one diff.

- [ ] **Step 1: Port the four test files first**

TDD applies: write the Kotlin tests before the Kotlin source. The Swift tests are the specification — 209 lines across the four. Translate them mechanically, keeping test names and their intent.

**`RelativeTime` needs an injected clock.** Swift takes a `Date` parameter; do the same in Kotlin (`fun format(instant: Instant, now: Instant): String`) rather than reaching for `System.currentTimeMillis()` inside. A test that cannot pin the clock is a flaky test.

- [ ] **Step 2: Run them, confirm they fail for the right reason**

Run: `cd android && ./gradlew :kit:test`
Expected: FAIL, `Unresolved reference` for each of the four types. Not a compile error in the tests themselves — read the output and confirm the failure is "the code does not exist yet", not "the test is malformed".

- [ ] **Step 3: Port the four sources**

Follow the translation table. Keep the doc comments; they explain rules, and the rules did not change.

- [ ] **Step 4: Run to green**

Run: `cd android && ./gradlew :kit:test`
Expected: PASS, 4 files' worth of tests.

- [ ] **Step 5: Mutate each of the four**

For each: change one expected value, confirm a real FAIL, change it back. Four mutations, four confirmed failures. Paste the output.

- [ ] **Step 6: Commit**

```bash
cd /home/rakeshgangwar/Projects/supermessage
git add android/kit/
git commit -m "Android kit: the small pure values"
```

---

### Task 2: `StreamingText` — the reveal pacer

**Files:**
- Create: `kit/.../StreamingText.kt`
- Test: `kit/.../StreamingTextTest.kt`
- Read first: `apple/SupermessageKit/StreamingText.swift` (107 lines), `apple/SupermessageKitTests/StreamingTextTests.swift` (74)

**Interfaces:**
- Consumes: nothing.
- Produces: a pacer that reveals text progressively. `:app` and `LiveStore` (Task 12) consume it.

**The rule it carries:** *the network must not decide the animation speed.* Tokens arrive in bursts at whatever rate the homeserver and the agent produce them; revealing them as they land looks like stuttering. The pacer decouples arrival from display.

- [ ] **Step 1: Port the test file**

74 lines. Pay attention to how the Swift test controls time — the port must be able to advance a clock deterministically. Use `kotlinx-coroutines-test`'s `runTest` and `TestScope`'s virtual time if the pacer is coroutine-driven; if it is a pure function of elapsed time, inject the elapsed value.

Add `libs.kotlinx.coroutines.test` to the catalog and `:kit`'s `testImplementation` if it is not already there.

- [ ] **Step 2: Run, confirm it fails on the missing type**

Run: `cd android && ./gradlew :kit:test --tests '*StreamingTextTest*'`

- [ ] **Step 3: Port the source**

- [ ] **Step 4: Run to green**

- [ ] **Step 5: Mutate — and mutate the pacing, not just a string**

Change the pacing constant so the reveal is instant, and confirm a timing test fails. If no test fails when the pacer stops pacing, the port has lost the rule and the test file needs strengthening before this task is done. Say so if that happens rather than moving on.

- [ ] **Step 6: Commit**

```bash
git add android/kit/ && git commit -m "Android kit: the reveal pacer"
```

---

### Task 3: `TimelineGrouping`

**Files:**
- Create: `kit/.../TimelineGrouping.kt`
- Test: `kit/.../TimelineGroupingTest.kt`
- Read first: `apple/SupermessageKit/TimelineGrouping.swift` (144), `apple/SupermessageKitTests/TimelineGroupingTests.swift` (214)

**Interfaces:**
- Consumes: timeline DTOs from `:core` (`uniffi.supermessage_ffi.*`).
- Produces: the grouping decision — when consecutive messages from one sender collapse into a run, when a run of membership events collapses into one line, and which silent rows drop out entirely. No date dividers: `TimelineGrouping.swift` has no such logic, so there is none to port. `:app`'s timeline consumes it.

214 lines of tests against 144 of source: the tests are the larger artifact, which is the signal that this file is mostly rules.

- [ ] **Step 1: Port the test file**

- [ ] **Step 2: Run, confirm failure**

- [ ] **Step 3: Port the source**

- [ ] **Step 4: Run to green**

- [ ] **Step 5: Mutate the grouping boundary**

Change the rule that ends a run — the time window, or the sender comparison — and confirm a real failure. Restore.

- [ ] **Step 6: Commit**

```bash
git add android/kit/ && git commit -m "Android kit: timeline grouping"
```

---

### Task 4: `TimelineFollow` and `RosterArrangement`

**Files:**
- Create: `kit/.../TimelineFollow.kt`, `kit/.../RosterArrangement.kt`
- Test: `kit/.../TimelineFollowTest.kt`, `kit/.../RosterArrangementTest.kt`
- Read first: the four matching Swift files (75/105 and 83/194 lines)

**Interfaces:**
- Consumes: `:core` DTOs.
- Produces: `TimelineFollow` decides whether the view should stay pinned to the newest message; `RosterArrangement` orders the room list.

**`RosterArrangement` must stay thin.** Its 83 Swift lines are mostly a call into `core::roster` — the ordering rules moved into Rust precisely so a second host could not re-invent them. If your port grows comparison logic, you are re-deriving a decision that already has an answer. Call the core.

- [ ] **Step 1: Port both test files** (299 lines total)

- [ ] **Step 2: Run, confirm failure**

- [ ] **Step 3: Port both sources**

- [ ] **Step 4: Run to green**

- [ ] **Step 5: Mutate both**

For `RosterArrangement`, the useful mutation is to reverse the order the core returns and confirm a test notices.

- [ ] **Step 6: Commit**

```bash
git add android/kit/ && git commit -m "Android kit: follow and roster arrangement"
```

---

### Task 5: `DiffApply` and `DiffTracker`

**Files:**
- Create: `kit/.../DiffApply.kt`, `kit/.../DiffTracker.kt`
- Test: `kit/.../DiffApplyTest.kt`
- Read first: `apple/SupermessageKit/DiffApply.swift` (132), `DiffTracker.swift` (50), `apple/SupermessageKitTests/DiffApplyTests.swift` (174)

**Interfaces:**
- Consumes: `TimelineDiffEnvelope` and its ops from `:core`.
- Produces: `DiffApply` applies an ordered op list to a list of rows; `DiffTracker` notices when a `seq` gap means ops were missed. Tasks 6, 12 and 13 consume both.

**The spec calls these "the most valuable tests in the Kit".** `DiffApplyTests` includes the rule that **a confirmed message keeps its place and identity** — that is the flicker `TimelineItemDto.id` exists to prevent. A local echo becoming a confirmed event must not look like a delete plus an insert, or the row visibly jumps.

- [ ] **Step 1: Port the test file** (174 lines)

- [ ] **Step 2: Run, confirm failure**

- [ ] **Step 3: Port both sources**

- [ ] **Step 4: Run to green**

- [ ] **Step 5: Mutate the identity rule specifically**

Make a confirmed message replace rather than update its row, and confirm the identity test fails. This is the one mutation in this task that matters most — if it passes, the flicker rule is not actually covered.

- [ ] **Step 6: Commit**

```bash
git add android/kit/ && git commit -m "Android kit: diff application, and the rule that stops a flicker"
```

---

### Task 6: `GapSync`

**Files:**
- Create: `kit/.../GapSync.kt`
- Test: `kit/.../GapSyncTest.kt`
- Read first: `apple/SupermessageKit/GapSync.swift` (159), `apple/SupermessageKitTests/GapSyncTests.swift` (222)

**Interfaces:**
- Consumes: `DiffTracker` (Task 5).
- Produces: the recovery path — when a sequence gap is detected, fetch a fresh snapshot rather than applying diffs onto a corrupt list. `TimelineStore` (Task 13) and `RoomsStore` (Task 11) consume it via the `accepts`/`onUpdate` callback shape visible in `TimelineStore.swift:62-63`.

222 lines of tests: the largest test file in the Kit. Recovery logic is where being wrong is invisible until it is catastrophic.

- [ ] **Step 1: Port the test file**

- [ ] **Step 2: Run, confirm failure**

- [ ] **Step 3: Port the source**

- [ ] **Step 4: Run to green**

- [ ] **Step 5: Mutate the gap detection**

Make the tracker accept an out-of-order `seq` silently and confirm a test fails. A gap detector that never detects is the failure mode here, and it reads as protection.

- [ ] **Step 6: Commit**

```bash
git add android/kit/ && git commit -m "Android kit: gap detection and resync"
```

---

### Task 7: `CoreClient` — every call off the main thread

**Files:**
- Create: `kit/.../CoreClient.kt`
- Test: `kit/.../CoreClientTest.kt`
- Read first: `apple/SupermessageKit/CoreClient.swift` (260) — **read the whole doc comment, it is the rule**

**Interfaces:**
- Consumes: `uniffi.supermessage_ffi.Core` from `:core`.
- Produces: `CoreClient`, the only thing in the app that holds a `Core`. Every later task calls the core through it. Suspending wrappers, one per `Core` method the app needs.

**The rule, and it is identical on both platforms:** every method on `Core` **blocks the calling thread**. They are synchronous Rust functions that `block_on` a tokio runtime, so a call takes as long as the homeserver does and does nothing else while it waits.

iOS learned that `Task.detached` is the wrong answer, because a detached task still runs on Swift's *cooperative* pool, which is sized to the core count and assumes tasks yield. A handful of concurrent blocking calls occupy the whole pool and the app hangs — under load, not in a test.

**Kotlin has the same trap with a different name.** `Dispatchers.Default` is the cooperative-equivalent: CPU-sized, and blocking it starves everything else. `Dispatchers.IO` is the thread pool that expects to be blocked. And `GlobalScope` is the wrong answer here for the same reason `Task.detached` was — it detaches the work from any lifecycle, so nothing cancels it on logout.

- [ ] **Step 1: Write the failing test**

Port `CoreClientTests.swift` (51 lines), and keep its central idea: **it proves a call actually landed off the main thread.** Swift asserts on the dispatch queue's label, deliberately, because "not main" is true of every plausible implementation and proves nothing.

The Kotlin equivalent is to assert the thread name matches `Dispatchers.IO`'s pool (`DefaultDispatcher-worker` / `kotlinx.coroutines.io`) — or, better, to inject the dispatcher and assert the client used the one it was given:

```kotlin
class CoreClientTest {
    @Test
    fun everyCallRunsOnTheGivenDispatcher() = runTest {
        var ranOn: String? = null
        val probe = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                ranOn = "probe"; block.run()
            }
        }
        val client = CoreClient(core = FakeCore(), dispatcher = probe)
        client.rooms()
        assertEquals("probe", ranOn)
    }
}
```

Injecting the dispatcher also makes every later store testable without a real `Core`. Do it now rather than retrofitting it in Task 15.

- [ ] **Step 2: Run, confirm it fails**

- [ ] **Step 3: Write `CoreClient`**

```kotlin
class CoreClient(
    private val core: CoreInterface,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private suspend fun <T> run(body: (CoreInterface) -> T): T =
        withContext(dispatcher) { body(core) }
    // one suspending wrapper per Core method the app needs
}
```

Take `CoreInterface` (the generated Kotlin interface), not the concrete `Core`, so tests can supply a fake. `:core` already exports it.

- [ ] **Step 4: Run to green**

- [ ] **Step 5: Mutate — swap the dispatcher for `Dispatchers.Main`**

Confirm the test fails. If it passes with the work on the main thread, the test is not pinning what it claims and the task is not done.

- [ ] **Step 6: Commit**

```bash
git add android/kit/ && git commit -m "Android kit: every core call off the main thread, and the test that pins it"
```

---

### Task 8: `EventPump` — one channel, one collector

**Files:**
- Create: `kit/.../EventPump.kt`
- Test: `kit/.../EventPumpTest.kt`
- Read first: `apple/SupermessageKit/EventPump.swift` (58) — **the doc comment is the whole design**

**Interfaces:**
- Consumes: `EventSink` from `:core` (the generated callback interface).
- Produces: `EventPump` with an exposed `Flow<FfiEvent>` and a `finish()`. `Session` (Task 15) drains it with exactly one collector.

**Three rules, all from the Swift original:**

1. **`onEvent` does one thing and returns.** The core's contract: *"Implementations must not block: this is called from inside sync and timeline processing, and a slow sink stalls the client rather than the UI."* So it enqueues and gives the core its thread back.

2. **Exactly one consumer, and order survives.** `DiffEnvelope` carries a `seq` and the recovery logic is built on those arriving in emission order. The tempting alternative — launching a coroutine per event inside `onEvent` — looks equivalent and is not: coroutine dispatch order is not guaranteed, so under load the diffs interleave and applying them out of order corrupts the reader's view *in a way that presents as a rendering bug rather than a threading one*. **There is a ten-thousand-event test in the Swift suite that fails when this is reintroduced. Port it.**

3. **The buffer is unbounded on purpose.** Dropping the oldest would drop a diff envelope, and a dropped envelope is a gap the tracker cannot distinguish from a lost one. Recoverable via `GapSync`, but only by a resync nobody asked for, over a connection that is already the reason the app fell behind. So `Channel(Channel.UNLIMITED)`, not `CONFLATED` and not a fixed capacity.

- [ ] **Step 1: Port the test file, including the 10,000-event ordering test**

That test is the reason this class exists. It must be in the first commit, not added later.

- [ ] **Step 2: Run, confirm failure**

- [ ] **Step 3: Write `EventPump`**

```kotlin
class EventPump : EventSink {
    private val channel = Channel<FfiEvent>(Channel.UNLIMITED)
    val events: Flow<FfiEvent> = channel.receiveAsFlow()

    // Called by the core, on the core's thread. Hands over and returns.
    override fun onEvent(event: FfiEvent) { channel.trySend(event) }

    fun finish() { channel.close() }
}
```

`trySend` rather than `send` because `onEvent` is not a suspending function and must not block — with an UNLIMITED channel it always succeeds.

- [ ] **Step 4: Run to green**

- [ ] **Step 5: Mutate — reintroduce the bug the class exists to prevent**

Change `onEvent` to `scope.launch { channel.send(event) }` and confirm the 10,000-event ordering test fails. Restore. This is the single most important mutation in the plan: it proves the ordering guarantee is real rather than incidental.

- [ ] **Step 6: Commit**

```bash
git add android/kit/ && git commit -m "Android kit: one channel, one collector, order preserved"
```

---

### Task 9: The four small target stores

**Files:**
- Create: `kit/stores/DraftStore.kt`, `ReplyTarget.kt`, `EditTarget.kt`, `StagedAttachment.kt`
- Test: `kit/stores/EditTargetTest.kt` (56 lines exists); write new tests for the other three, which have none in Swift
- Read first: the four Swift files (36/52/55/59 lines)

**Interfaces:**
- Consumes: `:core` DTOs.
- Produces: four small main-confined stores exposing `StateFlow`. `Session` (Task 15) owns them.

Three of these have **no Swift tests**. Do not port an absence — write real ones. Each is small enough that its whole behaviour fits in a handful of cases, and a store with no test is where a `StateFlow` that never emits hides.

- [ ] **Step 1: Port `EditTargetTests.swift`, and write tests for the other three**

For each: setting a target emits, clearing it emits null, and setting the same value twice does not emit twice (`StateFlow` conflates equal values — assert that rather than assuming it).

- [ ] **Step 2: Run, confirm failure**

- [ ] **Step 3: Port the four sources**

- [ ] **Step 4: Run to green**

- [ ] **Step 5: Mutate each**

- [ ] **Step 6: Commit**

```bash
git add android/kit/ && git commit -m "Android kit: the composer's target stores"
```

---

### Task 10: `ConnectionStore` and `ErrorPresenter`

**Files:**
- Create: `kit/stores/ConnectionStore.kt`, `kit/.../ErrorPresenter.kt`
- Test: `kit/stores/ConnectionStoreTest.kt`, `kit/.../ErrorPresenterTest.kt`
- Read first: `ConnectionStore.swift` (55/55), `ErrorPresenter.swift` (61/54)

**Interfaces:**
- Consumes: `ConnectionState` and `FfiError` from `:core`.
- Produces: `ConnectionStore` exposes connection state for the connection bar; `ErrorPresenter` turns a typed failure into what the user sees and decides whether a failure means "sign out".

**`ErrorPresenter` carries a rule worth stating:** treating a network failure as a sign-out throws away a working session every time a train enters a tunnel. Only `.Auth` signs out.

**Note on the Swift test you are porting.** `ErrorPresenterTests.swift` had a tautology — `for error in Self.all where !isAuthFailure(error) { expect(!isAuthFailure(error)) }` — where the filter tested the same predicate as the assertion, so the loop could not fail for any implementation. It was fixed on the scaffold branch to filter on the *case*. Port the fixed version, and do not reintroduce the pattern.

**`FfiError`'s field is `detail`, not `message`** — renamed on the scaffold branch because `message` collides with `Throwable.message` in generated Kotlin. Your Kotlin will see `detail`.

- [ ] **Step 1: Port both test files**

- [ ] **Step 2: Run, confirm failure**

- [ ] **Step 3: Port both sources**

- [ ] **Step 4: Run to green**

- [ ] **Step 5: Mutate — make `isAuthFailure` return true for everything**

Confirm a test fails. Given the tautology this file used to contain, this specific mutation is the one that proves the port did not inherit it.

- [ ] **Step 6: Commit**

```bash
git add android/kit/ && git commit -m "Android kit: connection state, and what a failure means"
```

---

### Task 11: `RoomsStore` and `SpacesStore`

**Files:**
- Create: `kit/stores/RoomsStore.kt`, `kit/stores/SpacesStore.kt`
- Test: `kit/stores/RoomsStoreTest.kt`; write new tests for `SpacesStore` (no Swift test exists)
- Read first: `RoomsStore.swift` (89/81), `SpacesStore.swift` (65/none)

**Interfaces:**
- Consumes: `CoreClient` (Task 7), `GapSync` (Task 6), `RosterArrangement` (Task 4).
- Produces: the room list and the space list as `StateFlow`. `Session` owns both; `:app`'s roster consumes them.

- [ ] **Step 1: Port `RoomsStoreTests.swift`, write `SpacesStoreTest`**

- [ ] **Step 2: Run, confirm failure**

- [ ] **Step 3: Port both sources**

- [ ] **Step 4: Run to green**

- [ ] **Step 5: Mutate both**

- [ ] **Step 6: Commit**

```bash
git add android/kit/ && git commit -m "Android kit: rooms and spaces"
```

---

### Task 12: `TypingStore` and `LiveStore`

**Files:**
- Create: `kit/stores/TypingStore.kt`, `kit/stores/LiveStore.kt`
- Test: `kit/stores/TypingStoreTest.kt`, `kit/stores/LiveStoreTest.kt`
- Read first: `TypingStore.swift` (72/90), `LiveStore.swift` (151/127)

**Interfaces:**
- Consumes: `CoreClient`, `StreamingText` (Task 2).
- Produces: who is typing; and the live agent turn — the reasoning and text an agent streams before it commits a message.

**`TypingStore` carries a hard-won rule: it matches on user id, never on a display name.** The two hosts name people differently, and matching on the rendered name left the typing indicator stuck for minutes. The Swift test suite pins this. Port that test and do not let the Kotlin drift to comparing labels.

- [ ] **Step 1: Port both test files** (217 lines)

- [ ] **Step 2: Run, confirm failure**

- [ ] **Step 3: Port both sources**

- [ ] **Step 4: Run to green**

- [ ] **Step 5: Mutate — make `TypingStore` match on display name**

Confirm the test fails. Then mutate `LiveStore`'s turn lifecycle and confirm that fails too.

- [ ] **Step 6: Commit**

```bash
git add android/kit/ && git commit -m "Android kit: typing, matched on id; and the live turn"
```

---

### Task 13: `TimelineStore`

**Files:**
- Create: `kit/stores/TimelineStore.kt`
- Test: `kit/stores/TimelineStoreTest.kt` — port `TimelinePaginationTests.swift` (41) and `TimelineRevisionTests.swift` (52), which are the tests for this file under different names
- Read first: `apple/SupermessageKit/Stores/TimelineStore.swift` (136)

**Interfaces:**
- Consumes: `CoreClient`, `GapSync`, `DiffApply`, `TimelineGrouping`.
- Produces: `items`, `revision`, `roomId`, `isPaginating`, `canPaginate` as `StateFlow`. `:app`'s timeline consumes all five.

**`revision` is the fix for a shipped jitter bug and it must survive the port.** From the Swift doc comment: it answers "did the history actually change" in **constant time**, and it matters because a streaming turn updates other observable state many times a second — a list that cannot tell "new token" from "new message" rebuilds every row for both, which is exactly what made the timeline jitter while an agent was writing.

Two structural details from the original:
- `replaceItems` is **the one place `items` is written**, so the revision cannot drift from it. Keep that invariant — a second write site is how this bug comes back.
- `revision` wraps (`&+=` in Swift). Use `ULong` and `+=`.

- [ ] **Step 1: Port both test files as `TimelineStoreTest.kt`**

Keep a test that asserts `revision` increments **exactly once** per item replacement, and does not change when unrelated state (pagination flags) does.

- [ ] **Step 2: Run, confirm failure**

- [ ] **Step 3: Port the source**

- [ ] **Step 4: Run to green**

- [ ] **Step 5: Mutate — add a second write site for `items`**

Write `items` directly somewhere without bumping `revision`, and confirm a test fails. If nothing fails, the revision invariant is not covered and this task is not done.

- [ ] **Step 6: Commit**

```bash
git add android/kit/ && git commit -m "Android kit: the timeline store, and the revision that stops the jitter"
```

---

### Task 14: `AvatarCache` and `MediaCache`

**Files:**
- Create: `kit/stores/AvatarCache.kt`, `kit/stores/MediaCache.kt`
- Test: `kit/stores/AvatarCacheTest.kt`, `kit/stores/MediaCacheTest.kt`
- Read first: `AvatarCache.swift` (136/88), `MediaCache.swift` (137/65)

**Interfaces:**
- Consumes: `CoreClient`.
- Produces: bounded caches of avatar and media data, observable by the view.

**`AvatarCache` is a hand-bounded map for a reason, and Compose has the same hazard.** On iOS, `@Observable` could not see through an `NSCache`, so avatars appeared only on the second scroll — the data was there and nothing told the view. Compose has the identical problem with a mutable cache hidden behind a `State`: mutating a `HashMap` in place does not invalidate anything.

So: expose an immutable map through `StateFlow` and replace it on write, or use `mutableStateMapOf` — but `kit` cannot import Compose, so **it must be the `StateFlow` of an immutable map**. Bound it by hand, as Swift does.

- [ ] **Step 1: Port both test files**

Include a test that asserts an observer is notified when an entry is added — that is the regression for the second-scroll bug, and it is the one that would pass vacuously against a plain mutable map.

- [ ] **Step 2: Run, confirm failure**

- [ ] **Step 3: Port both sources**

- [ ] **Step 4: Run to green**

- [ ] **Step 5: Mutate — mutate the backing map in place instead of replacing it**

Confirm the notification test fails. This reproduces the exact iOS bug in Kotlin form; if it does not fail, the test is not covering what it claims.

- [ ] **Step 6: Commit**

```bash
git add android/kit/ && git commit -m "Android kit: caches the view can actually see"
```

---

### Task 15: `Session` — the orchestrator

**Files:**
- Create: `kit/.../Session.kt`
- Test: `kit/.../SessionTest.kt` — **new; there is no Swift test for this file**
- Read first: `apple/SupermessageKit/Session.swift` (433 lines)

**Interfaces:**
- Consumes: everything above.
- Produces: `Session` — owns the twelve stores, the `CoreClient` and the `EventPump`, exposes `phase`, `failure`, and the ~25 operations the app performs (`signIn`, `send`, `setTyping`, `openRoom`, `toggleReaction`, `edit`, `delete`, `search`, `createRoom`, `joinByAlias`, `signOut`, …). `:app` talks to this and nothing below it.

**This is the largest file in the Kit and the only significant one with no Swift tests.** That is the risk in this task: there is no ported suite to lean on, so the tests are new work and the temptation is to write shallow ones.

**It is also where the event drain lives** — exactly one collector over `EventPump.events`, dispatching each event to the store that owns it. Order survives only if that stays one collector (Task 8's rule).

- [ ] **Step 1: Build a fake `CoreInterface`**

`CoreClient` takes `CoreInterface` (Task 7), so `Session` can be driven entirely from a fake with no Rust, no network and no device. Write one that records calls and lets a test push events into the sink.

This fake is the reason Task 7 injects the interface. If it turns out `CoreInterface` is awkward to fake — too many methods — say so and propose a narrower port-interface rather than giving up on testing `Session`.

- [ ] **Step 2: Write failing tests for the behaviours that matter**

Not coverage for its own sake. These:

1. **A sign-in failure sets `failure` and leaves `phase` recoverable** — the app must not strand the user on a dead screen.
2. **Events reach the right store in order.** Push a burst of diff envelopes with ascending `seq` through the pump and assert `TimelineStore` saw them in that order.
3. **`signOut` finishes the pump and cancels the drain.** A leaked collector after logout is a coroutine holding a dead session.
4. **`open(roomId:)` is idempotent** — calling it for the already-open room does nothing, which `TimelineStore.swift:77` documents.
5. **A refused operation surfaces its refusal** rather than failing silently — `Session.swift:329`'s `refusal` wrapper is the shape.

- [ ] **Step 3: Run, confirm they fail**

- [ ] **Step 4: Port `Session`**

Swift's `@MainActor` class becomes a main-confined class taking a `CoroutineScope`. Inject the scope rather than creating one internally, so tests control it and `signOut` can cancel it.

- [ ] **Step 5: Run to green**

- [ ] **Step 6: Mutate each of the five**

Most important: break the single-collector rule (drain with two collectors, or launch per event) and confirm the ordering test fails.

- [ ] **Step 7: Full suite, then commit**

Run: `cd android && ./gradlew :kit:test` — every test from Tasks 1–15.

```bash
git add android/kit/ && git commit -m "Android kit: the session that owns it all"
```

---

## Self-Review

**Spec coverage** (companion spec steps 3–4):

| Requirement | Task |
|---|---|
| `CoreClient` off the main thread | 7 |
| `EventPump` in order, tests ported | 8 |
| `DiffApply` / `DiffTracker` / `GapSync` | 5, 6 |
| The eleven stores | 9, 10, 11, 12, 13, 14 |
| `TimelineGrouping`, `TimelineFollow`, `RelativeTime`, `SearchState`, `SendState`, `RichTextFolding`, `StreamingText` | 1, 2, 3, 4 |
| `RosterArrangement` stays thin | 4 |
| Tests run on the JVM, no emulator | every task |
| `AvatarCache`'s observability rule | 14 |
| `TypingStore` matches on user id | 12 |
| Step 3 done when "a login and a room list arrive" | 15 (`signIn`) + 11 (`RoomsStore`) |

Twelve stores are listed in `Session.swift`, not eleven as the spec says — the spec undercounts by treating `faces` and `avatars` as one, though they are two `AvatarCache` instances. Same class, two instances: Task 14 covers it.

**Placeholder scan:** clean. Where a task says "port the Swift original", the original is a real file in this repo at a named path with a named test — that is a specification, not a TODO. Kotlin is given inline wherever an idiom is not mechanically derivable from the Swift (Tasks 7, 8, 13, 14).

**Type consistency:** `CoreClient(core: CoreInterface, dispatcher: CoroutineDispatcher)` is defined in Task 7 and consumed under that signature in Tasks 11–15. `EventPump.events: Flow<FfiEvent>` is defined in Task 8 and drained in Task 15. `FfiError`'s field is `detail` throughout, matching the scaffold branch's rename.

**What this plan does not cover:** the views (companion steps 5–6), the spaces rail, push, and mobile release/signing.
