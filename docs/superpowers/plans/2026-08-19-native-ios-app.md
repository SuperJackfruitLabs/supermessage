# Native iOS App Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A native SwiftUI client for iPhone and iPad at full parity with the Svelte desktop app, driven by the Rust core over the UniFFI boundary Part A finished widening.

**Architecture:** Three Xcode targets — a Swift-5 island holding the generated bindings, a SwiftUI-free `SupermessageKit` holding the boundary and the stores, and the app itself. Every `Core` call goes out through one actor that runs it off the main thread; every event comes back through one `AsyncStream` drained by exactly one MainActor task, because ordering is a correctness requirement. Screens read `@Observable` stores and never touch `Core`.

**Tech Stack:** Swift 6 (strict concurrency), SwiftUI, Observation, Swift Testing, xcodegen 2.46, Xcode 16.4 / iOS 18.5 SDK, UniFFI 0.28 bindings.

**Spec:** [`docs/superpowers/specs/2026-08-18-native-ios-app-design.md`](../specs/2026-08-18-native-ios-app-design.md) — Part B (§1, §4–§11). Part A is complete and on this branch; this plan consumes its DTOs.

## Global Constraints

- **Deployment target iOS 18.0.** `onScrollGeometryChange` and `ScrollPosition` landed in 18 and both go at the hardest problem in this app; Xcode 16.4 / iOS 18.5 SDK is the ceiling on this machine.
- **The app and Kit targets build in Swift 6 language mode.** `SupermessageFFI` stays Swift 5 — UniFFI 0.28's output is not `Sendable`-clean and would bury the build in diagnostics that are not ours.
- **`SupermessageKit` imports no SwiftUI.** Enforcement, not taste: it is what keeps the state layer testable and what stops view code leaking into it.
- **Events are delivered in order.** One `AsyncStream`, one consumer. `Task { @MainActor in … }` per event does **not** preserve order, and out-of-order diff application corrupts the reader's view in a way that presents as a rendering bug.
- **No `Core` method may run on the main thread.** Every one of them blocks — they are synchronous Rust functions that `block_on` a tokio runtime.
- **Type: `Font.system(_:design:)` only.** `.serif` resolves to New York for peer message bodies, `.monospaced` to SF Mono for sigils, roles and timestamps, the default to SF Pro for chrome and own messages. No `Font.custom`, no bundled faces.
- **Amber is reserved for a pending decision.** Not unread badges, not warnings, not the connection bar. If amber is on screen, the operator owes someone an answer. Any other use is a review defect.
- **The app parses nothing.** No markdown, no HTML, no `matrix.to`, no room-name splitting. Those are `RichBlock`, `ItemView`, `MatrixLinkTarget` and `RoomIdentity`, decided by the core and delivered on the row.
- **Licences:** MIT / Apache-2.0 / BSD, or MPL-2.0 unmodified. This app adds **no** Swift package dependencies.
- **TDD, and falsify.** A test that has never failed is not yet a regression test: mutate the implementation, watch it fail, restore, and record what you saw in the commit. This project has shipped worthless green tests before.

### Correcting the spec on one point

Spec §10 says `SupermessageKit`'s tests run "without booting a simulator". **That is wrong and this plan does not rely on it.** `Supermessage.xcframework` carries `aarch64-apple-ios` and `aarch64-apple-ios-sim` slices only, so nothing that links it can build for the macOS host, and `swift test` cannot run. Kit tests run on a simulator through `xcodebuild test`.

The alternative — adding an `aarch64-apple-darwin` slice so the Kit could be a SwiftPM package tested on the host in seconds — is real and worth taking if the cycle gets painful. It costs another Rust target build (~4GB of `target/`), and disk on this machine has been the binding constraint three times this session. Revisit it, do not assume it.

**The one command every task runs:**

```bash
xcodebuild test -project apple/Supermessage.xcodeproj -scheme SupermessageKit -destination 'platform=iOS Simulator,name=iPhone 16 Pro' -quiet
```

## File Structure

```
apple/
  project.yml                       xcodegen: three targets + two test targets
  Generated/                        checked-in bindings (Part A); Swift 5 island
  Supermessage.xcframework          the Rust core, iOS + iOS-sim slices

  SupermessageKit/
    CoreClient.swift                actor over Core; every call off the main thread
    EventPump.swift                 EventSink -> AsyncStream -> one MainActor drain
    DiffApply.swift                 DiffOp application; port of diff.ts
    DiffTracker.swift               seq tracking + gap detection; port of diff.ts
    GapSync.swift                   gap -> resync -> reset; port of gapSync.ts
    ErrorPresenter.swift            FfiError -> what a person is told
    Stores/
      ConnectionStore.swift  RoomsStore.swift     TimelineStore.swift
      SpacesStore.swift      TypingStore.swift    LiveStore.swift
      ReplyTarget.swift      DraftStore.swift
      AvatarCache.swift      MemberAvatarCache.swift  MediaCache.swift
    Session.swift                   owns the stores; wires the pump to them

  Supermessage/
    SupermessageApp.swift           @main, scenePhase, the Session
    RootView.swift                  splash -> login -> main
    LoginView.swift
    Rooms/  RoomListView.swift  RoomRowView.swift  SpacePillStrip.swift
    Timeline/
      TimelineView.swift            ScrollView + LazyVStack, anchoring, follow
      TimelineRowView.swift         one row, switching on ItemView
      RichTextView.swift            RichBlock -> SwiftUI
      DecisionCard.swift            the amber one
      LiveTurnView.swift
      TimelineGrouping.swift        sender runs; written natively, not ported
      TimelineFollow.swift          shouldRepin / shouldSettleAtBottom
    Composer/ ComposerView.swift  MentionPopover.swift  AttachmentChip.swift
    Panels/   RoomInfoPanel.swift  SearchPanel.swift  NewRoomPanel.swift
              InvitationView.swift  SpaceInvitePanel.swift
    Theme.swift                     colours and type ramp
  SupermessageKitTests/             Swift Testing
  SupermessageUITests/              XCUITest, deliberately thin
```

`apple/Probe` is deleted in Task 1.

---

### Task 1: Three targets, and an app that launches

**Files:**
- Modify: `apple/project.yml`
- Create: `apple/Supermessage/SupermessageApp.swift`, `apple/Supermessage/RootView.swift`
- Create: `apple/SupermessageKit/Version.swift`
- Create: `apple/SupermessageKitTests/BuildTests.swift`
- Delete: `apple/Probe/`

**Interfaces:**
- Produces: three targets named `Supermessage`, `SupermessageKit`, `SupermessageFFI`, and a `SupermessageKit` test scheme.

- [ ] **Step 1: Write the failing test**

`apple/SupermessageKitTests/BuildTests.swift`:

```swift
import Testing
@testable import SupermessageKit

/// The three-target split is the app's central structural claim, and it is
/// worth one test that fails when it stops being true.
struct BuildTests {
    @Test("the Kit can see the generated bindings")
    func kitLinksTheCore() {
        // If the Swift-5 island is not linked into the Kit, this does not
        // compile — which is the assertion.
        #expect(SupermessageKit.linkedCoreVersion.isEmpty == false)
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `xcodebuild test -project apple/Supermessage.xcodeproj -scheme SupermessageKit -destination 'platform=iOS Simulator,name=iPhone 16 Pro' -quiet`
Expected: FAIL — the scheme does not exist yet.

- [ ] **Step 3: Write `project.yml`**

```yaml
# Three targets, and the reason for each.
#
#   SupermessageFFI  the generated bindings and the xcframework. Swift 5,
#                    because UniFFI 0.28's output is not Sendable-clean and a
#                    Swift 6 build of it is a wall of diagnostics that are not
#                    ours to fix. Quarantining it is what lets everything else
#                    compile under strict concurrency.
#   SupermessageKit  the boundary and the stores. Imports no SwiftUI — that is
#                    enforcement, not taste.
#   Supermessage     the views.
name: Supermessage
options:
  bundleIdPrefix: dev.supermessage
  deploymentTarget:
    iOS: "18.0"
  createIntermediateGroups: true

settings:
  base:
    DEVELOPMENT_TEAM: 9DC6VWV9X4
    SWIFT_VERSION: "6.0"

targets:
  SupermessageFFI:
    type: framework
    platform: iOS
    sources:
      # Both files: `setup_scaffolding!()` runs in the core crate as well as
      # the FFI crate, so the DTOs and the `Core` object are declared
      # separately and neither resolves alone.
      - path: Generated/supermessage_ffi.swift
      - path: Generated/supermessage_core.swift
    dependencies:
      - framework: Supermessage.xcframework
        embed: false   # a static library: linked, never embedded
    settings:
      base:
        SWIFT_VERSION: "5.0"
        SWIFT_SUPPRESS_WARNINGS: YES
        PRODUCT_BUNDLE_IDENTIFIER: dev.supermessage.ffi

  SupermessageKit:
    type: framework
    platform: iOS
    sources: [SupermessageKit]
    dependencies:
      - target: SupermessageFFI
    settings:
      base:
        PRODUCT_BUNDLE_IDENTIFIER: dev.supermessage.kit
        SWIFT_STRICT_CONCURRENCY: complete

  Supermessage:
    type: application
    platform: iOS
    sources: [Supermessage]
    dependencies:
      - target: SupermessageKit
      - target: SupermessageFFI
    settings:
      base:
        PRODUCT_BUNDLE_IDENTIFIER: dev.supermessage.ios
        GENERATE_INFOPLIST_FILE: YES
        INFOPLIST_KEY_UILaunchScreen_Generation: YES
        INFOPLIST_KEY_CFBundleDisplayName: supermessage
        SWIFT_STRICT_CONCURRENCY: complete

  SupermessageKitTests:
    type: bundle.unit-test
    platform: iOS
    sources: [SupermessageKitTests]
    dependencies:
      - target: SupermessageKit
      - target: SupermessageFFI

schemes:
  SupermessageKit:
    build:
      targets:
        SupermessageKit: all
        SupermessageKitTests: [test]
    test:
      targets: [SupermessageKitTests]
  Supermessage:
    build:
      targets:
        Supermessage: all
    run: {}
```

- [ ] **Step 4: Write the two app files and the Kit's one symbol**

`apple/SupermessageKit/Version.swift`:

```swift
import SupermessageFFI

/// Proof the Swift-5 island is linked, and somewhere for the Kit's first
/// symbol to live. Reads a type from the generated bindings on purpose: a
/// constant of its own would still compile with the dependency removed.
public let linkedCoreVersion: String = String(describing: ConnectionState.self)
```

`apple/Supermessage/SupermessageApp.swift`:

```swift
import SwiftUI

@main
struct SupermessageApp: App {
    var body: some Scene {
        WindowGroup {
            RootView()
        }
    }
}
```

`apple/Supermessage/RootView.swift`:

```swift
import SwiftUI

struct RootView: View {
    var body: some View {
        Text("supermessage")
            .font(.system(.title, design: .serif))
    }
}
```

- [ ] **Step 5: Generate and run the tests**

```bash
cd apple && xcodegen generate && cd ..
xcodebuild test -project apple/Supermessage.xcodeproj -scheme SupermessageKit -destination 'platform=iOS Simulator,name=iPhone 16 Pro' -quiet
```
Expected: PASS.

- [ ] **Step 6: Verify the app launches**

```bash
xcodebuild build -project apple/Supermessage.xcodeproj -scheme Supermessage -destination 'platform=iOS Simulator,name=iPhone 16 Pro' -quiet
xcrun simctl boot "iPhone 16 Pro" 2>/dev/null; open -a Simulator
xcrun simctl install booted "$(xcodebuild -project apple/Supermessage.xcodeproj -scheme Supermessage -destination 'platform=iOS Simulator,name=iPhone 16 Pro' -showBuildSettings 2>/dev/null | awk -F' = ' '/ BUILT_PRODUCTS_DIR/{d=$2} / FULL_PRODUCT_NAME/{n=$2} END{print d"/"n}')"
xcrun simctl launch booted dev.supermessage.ios
```
Expected: a window reading "supermessage" in a serif face.

- [ ] **Step 7: Falsify**

Remove the `SupermessageFFI` dependency from the `SupermessageKit` target and regenerate. `Version.swift` must fail to compile. Restore.

- [ ] **Step 8: Delete the probe and commit**

```bash
git rm -r apple/Probe
git add -A apple
git commit -m "feat(ios): three targets, and an app that launches

The Swift-5 island holds the generated bindings so everything else can
compile under strict concurrency. The Kit imports no SwiftUI.

Falsified: dropping the FFI dependency fails the Kit's compile."
```

---

### Task 2: `CoreClient` — every call off the main thread

**Files:**
- Create: `apple/SupermessageKit/CoreClient.swift`
- Test: `apple/SupermessageKitTests/CoreClientTests.swift`

**Interfaces:**
- Consumes: the generated `Core`, whose methods are all synchronous and blocking — `Core(dataDir: String)`, `restoreSession(sink: EventSink) throws -> Bool`, `roomsSnapshot() throws -> RoomsSnapshot`, `timelineSubscribe(roomId: String) throws`, `timelineResync() throws -> TimelineSnapshot`, `sendMessage(roomId:body:mentions:) throws`, and 24 more.
- Produces: `actor CoreClient` with an `async` wrapper per method, and `CoreClient.dataDirectory()`.

- [ ] **Step 1: Write the failing test**

```swift
import Testing
@testable import SupermessageKit

struct CoreClientTests {
    @Test("a call never runs on the main thread")
    func callsRunOffMain() async throws {
        // The invariant the whole actor exists for. Every Core method is a
        // synchronous Rust function that block_on's a tokio runtime; one of
        // them on the main thread freezes the app for as long as the network
        // takes.
        let client = CoreClient(dataDirectory: CoreClient.dataDirectory())
        let wasMain = await client.probeIsMainThread()
        #expect(wasMain == false)
    }

    @Test("the data directory is inside the app's own container")
    func dataDirectoryIsSandboxed() {
        let path = CoreClient.dataDirectory()
        #expect(path.contains("Application Support"))
        #expect(path.hasSuffix("supermessage"))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Expected: FAIL — `CoreClient` does not exist.

- [ ] **Step 3: Implement**

```swift
import Foundation
import SupermessageFFI

/// The only thing in this app that holds a `Core`.
///
/// **Every method on `Core` blocks the calling thread.** They are synchronous
/// Rust functions that `block_on` a tokio runtime, so one of them on the main
/// thread freezes the app for as long as the network takes. This actor is what
/// stands between the two: each wrapper hands the call to `Task.detached` and
/// awaits it, so nothing blocking ever touches the main actor.
///
/// Nothing above this file has a reference to `Core`.
public actor CoreClient {
    private let core: Core

    public init(dataDirectory: String) {
        core = Core(dataDir: dataDirectory)
    }

    /// Where the core keeps its SQLite stores. Inside the app container, so
    /// it inherits the sandbox and the backup rules rather than choosing its
    /// own.
    public static func dataDirectory() -> String {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let dir = base.appendingPathComponent("supermessage", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.path
    }

    /// Run one blocking call on the dedicated queue.
    ///
    /// CORRECTED DURING EXECUTION — see the commit for Task 2. `Task.detached`
    /// was the original prescription and it is wrong: a detached task still
    /// runs on Swift's *cooperative* pool, which is sized to the core count
    /// and assumes tasks yield rather than block. Every `Core` method blocks,
    /// so concurrent calls occupy the pool and hang unrelated work.
    private func run<T: Sendable>(_ body: @escaping @Sendable (Core) throws -> T) async throws -> T {
        let core = self.core
        return try await Task.detached { try body(core) }.value
    }

    func probeIsMainThread() async -> Bool {
        (try? await run { _ in Thread.isMainThread }) ?? true
    }

    public func restoreSession(sink: EventSink) async throws -> Bool {
        try await run { try $0.restoreSession(sink: sink) }
    }

    public func roomsSnapshot() async throws -> RoomsSnapshot {
        try await run { try $0.roomsSnapshot() }
    }

    public func timelineSubscribe(roomId: String) async throws {
        try await run { try $0.timelineSubscribe(roomId: roomId) }
    }

    public func timelineResync() async throws -> TimelineSnapshot {
        try await run { try $0.timelineResync() }
    }
}
```

- [ ] **Step 4: Run to verify it passes**

- [ ] **Step 5: Add the remaining 25 wrappers**

One `async` wrapper per `Core` method, each a single `try await run { … }` line, in the order the generated `CoreProtocol` lists them: `attachmentDiscard`, `attachmentSend`, `attachmentStagePath`, `connectionState`, `createRoom`, `inviteUser`, `joinRoom`, `joinRoomByAlias`, `leaveRoom`, `login`, `logout`, `markRoomRead`, `mediaFetch`, `memberAvatar`, `roomAvatar`, `roomInfo`, `searchMessages`, `sendMessage`, `sendReply`, `setTyping`, `spaceSelect`, `spacesList`, `timelinePaginateBack`, `toggleReaction`, `markRoomRead`.

- [ ] **Step 6: Falsify**

Change `run` to execute inline (`try body(core)`) instead of in `Task.detached`. `callsRunOffMain` must fail. Restore.

- [ ] **Step 7: Commit**

```bash
git add apple/SupermessageKit/CoreClient.swift apple/SupermessageKitTests/CoreClientTests.swift
git commit -m "feat(ios): one actor between the app and a blocking core

Falsified: running the call inline puts it on the main thread and fails."
```

---

### Task 3: `EventPump` — the ordering guarantee

The highest-risk forty lines in the app, and the invariant the probe never exercised.

**Files:**
- Create: `apple/SupermessageKit/EventPump.swift`
- Test: `apple/SupermessageKitTests/EventPumpTests.swift`

**Interfaces:**
- Consumes: the generated `protocol EventSink: AnyObject { func onEvent(event: FfiEvent) }`.
- Produces: `final class EventPump: EventSink`, `EventPump.events: AsyncStream<FfiEvent>`, `EventPump.finish()`.

- [ ] **Step 1: Write the failing test**

```swift
import Testing
@testable import SupermessageKit
import SupermessageFFI

struct EventPumpTests {
    /// The one the probe never ran.
    ///
    /// `DiffEnvelope` carries a `seq` and the timeline's recovery logic is
    /// built on those arriving in order. A pump that spawned a task per event
    /// would reorder them under load and corrupt the reader's view in a way
    /// that looks like a rendering bug rather than a threading one.
    @Test("ten thousand events arrive in the order they were emitted")
    func preservesOrderUnderLoad() async throws {
        let pump = EventPump()
        let count = 10_000

        // Emitted from a background thread, which is where the core emits:
        // UniFFI invokes a callback on whatever thread called it.
        Task.detached {
            for seq in 0..<count {
                pump.onEvent(event: .typing(roomId: "!r:x.org", users: ["\(seq)"]))
            }
            pump.finish()
        }

        var seen: [Int] = []
        for await event in pump.events {
            if case let .typing(_, users) = event, let first = users.first, let n = Int(first) {
                seen.append(n)
            }
        }

        #expect(seen.count == count)
        #expect(seen == Array(0..<count), "events were reordered")
    }

    @Test("the stream ends when the pump is finished")
    func finishEndsTheStream() async {
        let pump = EventPump()
        pump.finish()
        var received = 0
        for await _ in pump.events { received += 1 }
        #expect(received == 0)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Expected: FAIL — `EventPump` does not exist.

- [ ] **Step 3: Implement**

```swift
import Foundation
import SupermessageFFI

/// Where the core's events enter this app, and the only place their order is
/// guaranteed.
///
/// `onEvent` does exactly one thing and returns. The core's contract is
/// explicit that it must: *"Implementations must not block: this is called
/// from inside sync and timeline processing, and a slow sink stalls the client
/// rather than the UI."*
///
/// **One stream, one consumer.** Exactly one `@MainActor` task drains
/// `events`, so arrival order survives end to end. A `Task { @MainActor in … }`
/// per event would look equivalent and is not: task ordering is not
/// guaranteed, and out-of-order diff application corrupts the timeline.
///
/// The buffer is unbounded on purpose. Dropping the oldest would drop a diff
/// envelope, and a dropped envelope is a gap the tracker cannot tell from a
/// lost one — recoverable, but only by a resync nobody asked for.
public final class EventPump: EventSink, @unchecked Sendable {
    public let events: AsyncStream<FfiEvent>
    private let continuation: AsyncStream<FfiEvent>.Continuation

    public init() {
        var escaping: AsyncStream<FfiEvent>.Continuation!
        events = AsyncStream(bufferingPolicy: .unbounded) { escaping = $0 }
        continuation = escaping
    }

    /// Called by the core, on the core's thread. Hands the event over and
    /// returns; it never waits for anyone.
    public func onEvent(event: FfiEvent) {
        continuation.yield(event)
    }

    /// Ends the stream. Called on logout and on teardown.
    public func finish() {
        continuation.finish()
    }
}
```

- [ ] **Step 4: Run to verify it passes**

- [ ] **Step 5: Falsify — and run it several times**

Replace the body of `onEvent` with a per-event task:

```swift
public func onEvent(event: FfiEvent) {
    Task { self.continuation.yield(event) }
}
```

`preservesOrderUnderLoad` must fail. **Run it five times** and record how many failed: this is a scheduler-dependent test, and this project has already shipped one that passed on some runs and not others. If it passes even once, raise the event count until it fails every time, and say so in the commit.

- [ ] **Step 6: Commit**

```bash
git add apple/SupermessageKit/EventPump.swift apple/SupermessageKitTests/EventPumpTests.swift
git commit -m "feat(ios): one stream, one consumer, order preserved

The invariant event.rs demands and the probe never exercised.

Falsified with a task-per-event pump, run five times: <RECORD>."
```

---

### Task 4: `DiffApply` and `DiffTracker`

Ports of `src/lib/stores/diff.ts` (140 lines) with its tests. Transcriptions, not new designs.

**Files:**
- Create: `apple/SupermessageKit/DiffApply.swift`, `apple/SupermessageKit/DiffTracker.swift`
- Test: `apple/SupermessageKitTests/DiffApplyTests.swift`
- Reference: `src/lib/stores/diff.ts`, `src/lib/stores/diff.test.ts`

**Interfaces:**
- Consumes: the generated `RoomDiffOp` and `TimelineDiffOp`, each with 11 cases.
- Produces:
  - `enum DiffOp<T> { case append([T]), clear, pushFront(T), pushBack(T), popFront, popBack, insert(index: Int, value: T), set(index: Int, value: T), remove(index: Int), truncate(length: Int), reset([T]) }`
  - `func applyOps<T>(_ items: [T], _ ops: [DiffOp<T>]) -> [T]`
  - `extension RoomDiffOp { var generic: DiffOp<RoomRow> }`, `extension TimelineDiffOp { var generic: DiffOp<TimelineRow> }`
  - `struct DiffTracker<T>` with `items`, `mutating func apply(_ ops: [DiffOp<T>], seq: UInt64) -> DiffOutcome`, `mutating func reset(items: [T], seq: UInt64)`; `enum DiffOutcome { case ok, gap }`

- [ ] **Step 1: Port `diff.test.ts` case for case**

Every case, including the out-of-range handling, which is the part that matters: `set`/`remove` out of bounds are no-ops, `popFront`/`popBack` on an empty list are no-ops, `insert` is a no-op when `index > count` but a valid append when `index == count`. Write them as Swift Testing `@Test` functions.

Add one the TypeScript does not have, because Swift can fail where JavaScript cannot:

```swift
@Test("an out-of-range index is a no-op, not a crash")
func outOfRangeNeverTraps() {
    // In JavaScript an out-of-range splice is silently harmless. In Swift
    // `items[i]` traps, so the guards here are load-bearing in a way the
    // original's were not.
    #expect(applyOps([1, 2, 3], [.set(index: 99, value: 7)]) == [1, 2, 3])
    #expect(applyOps([1, 2, 3], [.remove(index: 99)]) == [1, 2, 3])
    #expect(applyOps([Int](), [.popFront]) == [])
    #expect(applyOps([1, 2], [.insert(index: 2, value: 3)]) == [1, 2, 3])
    #expect(applyOps([1, 2], [.insert(index: 9, value: 3)]) == [1, 2])
}
```

- [ ] **Step 2: Run to verify they fail**

- [ ] **Step 3: Implement, with the agreement note the original carries**

`applyOps` must agree with `core::dto::apply_ops` operation for operation. The core's own resync snapshot is maintained by folding the same op stream through that function, so a divergence here corrupts state silently on every resync — carry that sentence into the file.

`DiffTracker` detects a dropped envelope by sequence number: ahead of expected is a gap (return without touching state — applying partial state is the corruption it exists to prevent), behind is a duplicate (ignore). Sequences start at 1.

- [ ] **Step 4: Run to verify they pass**

- [ ] **Step 5: Falsify**

- Remove the `insert` upper-bound guard. The out-of-range test must fail (or trap, which is also a failure).
- Make `apply` fold a gap instead of returning `.gap`. The gap test must fail.
- Treat a behind-seq envelope as a gap. The duplicate test must fail.

- [ ] **Step 6: Commit**

---

### Task 5: `GapSync`

A port of `src/lib/stores/gapSync.ts` (188 lines) and its 283-line test file. Three hazards that took real incidents to find; carry the comments.

**Files:**
- Create: `apple/SupermessageKit/GapSync.swift`
- Test: `apple/SupermessageKitTests/GapSyncTests.swift`
- Reference: `src/lib/stores/gapSync.ts`, `src/lib/stores/gapSync.test.ts`

**Interfaces:**
- Consumes: `DiffTracker`, `DiffOp` (Task 4).
- Produces: `@MainActor final class GapSync<T>` with
  `init(resync: @escaping () async throws -> Snapshot<T>, accepts: @escaping (String) -> Bool = { _ in true }, onUpdate: @escaping ([T]) -> Void)`,
  `func handle(subject: String, seq: UInt64, ops: [DiffOp<T>])`,
  `func seed() async`,
  `func resetForNewSubscription()`,
  and `struct Snapshot<T> { let subject: String; let seq: UInt64; let items: [T] }`.

- [ ] **Step 1: Port `gapSync.test.ts` case for case**

The three hazards each need their test, and each is worth stating in the test's own comment:

1. **Subject filtering.** A channel's sequence is monotonic per channel *and subject*. The timeline's subject is the focused room id, and it changes while a subscribe round trip is in flight. An envelope for a subject the store no longer shows is not a gap and not a duplicate — it is somebody else's data. Treating it as a gap resyncs off the previous room and installs its messages under the new room's header, permanently.
2. **In-flight resync guard.** While a resync is in flight the core keeps emitting on the same channel. Applying those against the pre-reset tracker rediscovers the same gap forever.
3. **Generation counter.** A resync issued under one subscription context and landing after the context changed must be discarded, or a slow resync rolls the new room's state back to the old room's data.

Plus `seed()`, for a store built after the core has already spoken — on iOS this is not an edge case, it is what happens on every return from background.

- [ ] **Step 2: Run to verify they fail**

- [ ] **Step 3: Implement**

`@MainActor` because it owns store-adjacent state and is driven by the pump's consumer. `resync` is `async throws`; the generation counter is an `Int` bumped by `resetForNewSubscription`, captured before the await and compared after.

- [ ] **Step 4: Run to verify they pass**

- [ ] **Step 5: Falsify — all three hazards**

- Accept any subject. The subject test must fail.
- Drop the in-flight guard. The repeated-resync test must fail.
- Drop the generation check. The stale-resync test must fail.

- [ ] **Step 6: Commit**

---

### Task 6: Session, login, and the first screen that talks to the core

The first vertical slice: a real sign-in against `id.agentpod.dev`, and a connection state on screen.

**Files:**
- Create: `apple/SupermessageKit/Session.swift`, `apple/SupermessageKit/Stores/ConnectionStore.swift`, `apple/SupermessageKit/ErrorPresenter.swift`
- Create: `apple/Supermessage/LoginView.swift`, `apple/Supermessage/Theme.swift`
- Modify: `apple/Supermessage/RootView.swift`
- Test: `apple/SupermessageKitTests/ConnectionStoreTests.swift`, `apple/SupermessageKitTests/ErrorPresenterTests.swift`

**Interfaces:**
- Consumes: `CoreClient`, `EventPump`.
- Produces: `@MainActor @Observable final class Session` owning the client, the pump and the stores, with `func start() async -> Bool` (restore) and `func signIn(homeserver:username:password:) async throws`; `@MainActor @Observable final class ConnectionStore { var state: ConnectionState }`; `enum ErrorPresenter { static func message(for: FfiError) -> String; static func isAuthFailure(_: FfiError) -> Bool }`.

- [ ] **Step 1: Write the failing tests**

```swift
@Test("the connection store follows the core's state")
func followsConnectionEvents() { /* feed .connection events, assert state */ }

@Test("every error variant has something a person can read")
func everyErrorHasAMessage() {
    // Nine variants. A missing one renders an empty alert, which reads as the
    // app being broken rather than the network being down.
    let all: [FfiError] = [.auth(message: "m"), .network(message: "m"), .store(message: "m"),
                           .protocolError(message: "m"), .notReady(message: "m"),
                           .roomChanged(message: "m"), .attachmentTooLarge(message: "m"),
                           .unknownAttachment(message: "m"), .unknownSpace(message: "m")]
    for error in all {
        #expect(ErrorPresenter.message(for: error).isEmpty == false)
    }
}

@Test("only an auth failure sends the reader back to sign-in")
func onlyAuthSignsOut() {
    #expect(ErrorPresenter.isAuthFailure(.auth(message: "m")))
    #expect(!ErrorPresenter.isAuthFailure(.network(message: "m")))
    #expect(!ErrorPresenter.isAuthFailure(.notReady(message: "m")))
}
```

- [ ] **Step 2: Run to verify they fail**
- [ ] **Step 3: Implement `Session`, `ConnectionStore`, `ErrorPresenter`**

`Session.start()` calls `restoreSession(sink:)` with the pump, starts the single drain task, and returns whether a session was restored. The drain task is the *only* consumer of `pump.events`.

- [ ] **Step 4: Write `Theme.swift` and `LoginView.swift`**

`Theme` holds the palette from the console spec — the indigo-slate ramp, indigo accent, and `Theme.signal` (amber) with a comment saying it may appear only on a pending decision. Type helpers: `Theme.body` is `.system(.body, design: .serif)`, `Theme.own` is `.system(.body)`, `Theme.meta` is `.system(.caption, design: .monospaced)`.

`LoginView` is homeserver, username, password and a button — `m.login.password` is the only flow the homeserver advertises.

- [ ] **Step 5: Run the tests, then sign in on the simulator**

Sign in against `id.agentpod.dev` and confirm the connection state reaches `.connected`.

- [ ] **Step 6: Falsify**

Drop a variant from `ErrorPresenter.message`'s switch — it must fail to compile (the switch is exhaustive with no `default`, deliberately). Make `isAuthFailure` return `true` for `.network` and watch its test fail.

- [ ] **Step 7: Commit**

---

### Task 7: `RoomsStore` and the room list

**Files:**
- Create: `apple/SupermessageKit/Stores/RoomsStore.swift`, `apple/SupermessageKit/Stores/SpacesStore.swift`, `apple/SupermessageKit/Stores/AvatarCache.swift`
- Create: `apple/Supermessage/Rooms/RoomListView.swift`, `RoomRowView.swift`, `SpacePillStrip.swift`
- Test: `apple/SupermessageKitTests/RoomsStoreTests.swift` (port of `rooms.test.ts`, 432 lines), `SpacesStoreTests.swift` (port of `spaces.test.ts`, 221 lines)

**Interfaces:**
- Consumes: `GapSync<RoomRow>`, `CoreClient.roomsSnapshot()`, `RoomRow` (`.room`, `.identity`, `.preview`, `.affordance`).
- Produces: `@MainActor @Observable final class RoomsStore { var rooms: [RoomRow]; var selectedId: String?; func select(_:) }`; `@Observable final class AvatarCache` backed by `NSCache`.

- [ ] **Step 1: Port `rooms.test.ts`** — the gap/resync sequencing, the space-switch reset, the selection surviving a filtered-out room.
- [ ] **Step 2: Run to verify they fail**
- [ ] **Step 3: Implement the stores.** The caches are `NSCache`-backed with count limits, unlike the desktop's unbounded dictionaries: fine on a workstation, not on a phone, and it buys eviction under memory pressure.
- [ ] **Step 4: Run to verify they pass**
- [ ] **Step 5: Build the list.** Rows read `row.identity.initial`, `row.identity.name`, `row.identity.role`, `row.preview?.text` — the app parses nothing. `row.preview?.pending == true` is the only place `Theme.signal` may appear.

   The space pills are a horizontal strip **inside the list's scroll content**, so they scroll away and give their ~40pt back; the current space name also sits in the navigation title, so scope stays legible once they have.
- [ ] **Step 6: Falsify.** Render the preview from `row.room.lastMessage` instead of `row.preview` — the "You: " prefix and the pending line both disappear, and the row tests must fail.
- [ ] **Step 7: Commit**

---

### Task 8: `TimelineStore` and a timeline that scrolls correctly

The hardest task in the plan. Back-pagination anchoring is where this goes visibly wrong.

**Files:**
- Create: `apple/SupermessageKit/Stores/TimelineStore.swift`
- Create: `apple/Supermessage/Timeline/TimelineView.swift`, `TimelineFollow.swift`
- Test: `apple/SupermessageKitTests/TimelineStoreTests.swift` (port of `timeline.test.ts`, 377 lines), `TimelineFollowTests.swift` (port of `timelineFollow.test.ts`)

**Interfaces:**
- Consumes: `GapSync<TimelineRow>`, `CoreClient.timelineSubscribe/timelineResync/timelinePaginateBack`.
- Produces: `@MainActor @Observable final class TimelineStore { var items: [TimelineRow]; func subscribeTo(_ roomId: String) async }`; `func shouldRepin(previous: Int, next: Int, following: Bool) -> Bool`; `func shouldSettleAtBottom(previous: Int, next: Int, settled: Bool) -> Bool`.

- [ ] **Step 1: Port `timeline.test.ts` and `timelineFollow.test.ts`**

`shouldSettleAtBottom` is not optional: it is the fix for a room opened mid-history where the whole backlog arrives as one batch, and `shouldRepin`'s first-observation discard would otherwise leave the view stranded. It was found on a running desktop app, not in a test.

- [ ] **Step 2: Run to verify they fail**
- [ ] **Step 3: Implement the store**
- [ ] **Step 4: Run to verify they pass**
- [ ] **Step 5: Build `TimelineView`**

`ScrollView` + `LazyVStack`, **not** `List`: `List` imposes separators, insets and selection behaviour that fight an editorial layout, and its cell reuse makes precise anchoring harder rather than easier. `.defaultScrollAnchor(.bottom)` opens at the newest message.

Anchoring: `.scrollPosition(id:)` bound to the topmost visible item's id, so when `timelinePaginateBack` prepends older items the anchored id holds its screen position and content grows upward off-screen. `onScrollGeometryChange` drives both the pagination trigger and the distance-from-bottom that follow-scroll needs.

- [ ] **Step 6: Verify on the simulator against a room with deep history**

Scroll up until pagination fires. The rows already on screen must not move. This is the one thing in this plan a test cannot tell you.

- [ ] **Step 7: Falsify.** Bind `.scrollPosition(id:)` to the *bottom* item instead of the top and watch the view jump on pagination; restore. Break `shouldSettleAtBottom` to return `false` and watch its test fail.
- [ ] **Step 8: Commit**

---

### Task 9: `RichTextView` — blocks to SwiftUI

**Files:**
- Create: `apple/Supermessage/Timeline/RichTextView.swift`
- Test: `apple/SupermessageKitTests/RichTextTests.swift` (for the inline folding helper only)

**Interfaces:**
- Consumes: `RichBlock`, `RichInline` from the core.
- Produces: `struct RichTextView: View { let blocks: [RichBlock] }`, and `func attributed(_ inlines: [RichInline]) -> AttributedString`.

- [ ] **Step 1: Write the failing test for the inline folding**

The block layout needs a view; the inline folding does not, and it is where the logic is:

```swift
@Test("nested emphasis keeps its text and its trait")
func nestedInlinesFold() {
    let inlines: [RichInline] = [
        .text(text: "a "),
        .strong(inlines: [.text(text: "b"), .emphasis(inlines: [.text(text: "c")])]),
    ]
    let result = attributed(inlines)
    #expect(String(result.characters) == "a bc")
}

@Test("a link keeps its destination")
func linkKeepsItsHref() {
    let result = attributed([.link(href: "https://e.org/x", inlines: [.text(text: "go")])])
    #expect(result.runs.contains { $0.link?.absoluteString == "https://e.org/x" })
}
```

- [ ] **Step 2: Run to verify they fail**
- [ ] **Step 3: Implement**

Inlines fold to `AttributedString` — that is what `Text` renders well. Blocks are views: `Text` for a paragraph, a `VStack` for a quote with a leading rule, a monospaced `Text` in a horizontally-scrolling container for a code block, an `HStack` per list item with its marker, a `Divider` for a thematic break, a `Grid` for a table.

**No syntax highlighting.** The whole palette runs on one accent, and a code block lit in six competing hues would be the loudest thing on screen — the desktop refused this deliberately and the reasoning is stronger on a phone.

A code block and a table each scroll horizontally inside their own container so the page never does.

- [ ] **Step 4: Run to verify they pass**
- [ ] **Step 5: Falsify.** Drop the recursion in the `strong`/`emphasis` cases so nested text is lost — the folding test must fail.
- [ ] **Step 6: Commit**

---

### Task 10: The reading surface — rows, grouping, decisions, live turns

**Files:**
- Create: `apple/Supermessage/Timeline/TimelineRowView.swift`, `DecisionCard.swift`, `LiveTurnView.swift`, `TimelineGrouping.swift`
- Create: `apple/SupermessageKit/Stores/LiveStore.swift`, `Stores/TypingStore.swift`, `Stores/MediaCache.swift`, `Stores/MemberAvatarCache.swift`
- Test: `apple/SupermessageKitTests/LiveStoreTests.swift` (port of `live.test.ts`, 201 lines), `TypingStoreTests.swift` (162 lines), `TimelineGroupingTests.swift`

**Interfaces:**
- Consumes: `ItemView` (nine cases), `TimelineRow.senderName/membershipVerb/replyQuote/canReplyOrReact`, `CustomEventView`.
- Produces: `struct TimelineRowView: View`, `func groupRows(_ rows: [TimelineRow]) -> [DisplayRow]`.

- [ ] **Step 1: Port the live and typing store tests, and write the grouping tests**

Grouping is written natively rather than ported: thresholds are presentation and a phone may legitimately differ from a workstation. Its tests are new, and the run-boundary rules are the same ones `timelineGrouping.ts` documents — a run breaks on a different sender, on a gap longer than five minutes, and on any non-message row between.

- [ ] **Step 2: Run to verify they fail**
- [ ] **Step 3: Implement the stores and grouping**
- [ ] **Step 4: Run to verify they pass**
- [ ] **Step 5: Build the row**

Switch on `row.view`. Peer bodies in `.system(.body, design: .serif)`; own messages in `.system(.body)`, tinted and trailing-aligned; sigil, role and timestamp in `.system(.caption, design: .monospaced)`.

`DecisionCard` renders `CustomEventView.rendered`'s `decision` and is **the only view that may use `Theme.signal`.** Add a comment saying so, and say what it means: if amber is on screen, the operator owes someone an answer.

`Image` reserves its box from `ItemView.image`'s `width`/`height` before the bytes are requested, so the lazy stack does not reflow when they land.

- [ ] **Step 6: Verify on the simulator** — an agent room with prose, a list, a code block, and a turn-activity card.
- [ ] **Step 7: Falsify.** Use `Theme.signal` on the unread badge and confirm a reviewer would catch it; restore. Break grouping so a run continues across senders and watch its test fail.
- [ ] **Step 8: Commit**

---

### Task 11: Composer, drafts, mentions, attachments

**Files:**
- Create: `apple/Supermessage/Composer/ComposerView.swift`, `MentionPopover.swift`, `AttachmentChip.swift`
- Create: `apple/SupermessageKit/Stores/DraftStore.swift`, `Stores/ReplyTarget.swift`, `Pacer.swift`
- Test: `apple/SupermessageKitTests/DraftStoreTests.swift`, `ReplyTargetTests.swift` (port of `replyTarget.test.ts`, 133 lines), `PacerTests.swift`

**Interfaces:**
- Consumes: `CoreClient.sendMessage/sendReply/setTyping/attachmentStagePath/attachmentSend/attachmentDiscard`, the free function `collectMentions(text:members:)`.
- Produces: `@MainActor @Observable final class DraftStore { func draft(for roomId: String) -> String; func set(_:for:) }`, `@Observable final class ReplyTarget`.

- [ ] **Step 1: Port the reply-target tests and write the draft/pacer tests**
- [ ] **Step 2: Run to verify they fail**
- [ ] **Step 3: Implement**
- [ ] **Step 4: Run to verify they pass**
- [ ] **Step 5: Build the composer**

`TextField(axis: .vertical)` inside `.safeAreaInset(edge: .bottom)`. That single line deletes the largest risk `tech-stack.md` used to carry — the ~200 lines of objc2 budgeted for resizing a WKWebView the iOS keyboard would not resize. SwiftUI does keyboard avoidance natively.

Attachments: `PhotosPicker` for images, `.fileImporter` for everything else; both write to a temp URL whose path goes to `attachmentStagePath`. This is why `FilePicker` never crossed the FFI boundary — the host picks, the core receives a path.

Mentions: the autocomplete popover is native (caret handling is input UX and differs per platform); `collectMentions` is the core's, because it produces the `m.mentions` an agent reads to decide a message was addressed to it.

- [ ] **Step 6: Send a message, a reply and a reaction from the simulator, and see them land**
- [ ] **Step 7: Falsify.** Send with an empty mentions array regardless and watch a mention test fail.
- [ ] **Step 8: Commit**

---

### Task 12: Navigation for both size classes, and the five panels

**Files:**
- Modify: `apple/Supermessage/RootView.swift`
- Create: `apple/Supermessage/Panels/RoomInfoPanel.swift`, `SearchPanel.swift`, `NewRoomPanel.swift`, `InvitationView.swift`, `SpaceInvitePanel.swift`

**Interfaces:**
- Consumes: `CoreClient.roomInfo/searchMessages/createRoom/joinRoom/leaveRoom/inviteUser/joinRoomByAlias/spacesList/spaceSelect`, `RoomRow.affordance`.

- [ ] **Step 1: Build the container**

One `NavigationSplitView` serves both size classes — it collapses to a push stack on iPhone by itself.

On iPad the sidebar holds the spaces rail as a fixed 52pt strip beside the room list, **not** a third column: SwiftUI enforces a ~200pt column minimum that would turn a row of sigils into a mostly-empty panel. Room info uses `.inspector()`, sliding in beside the timeline rather than covering it.

- [ ] **Step 2: Build the panels**

| Panel | iPad | iPhone |
|---|---|---|
| Room info | `.inspector()` | sheet, `.medium` + `.large` |
| Search | sheet | full-height sheet |
| New room | sheet | sheet |
| Space invite | sheet | sheet |
| Invitation | inline where the composer would be | same |

The invitation view is chosen by `row.affordance == .respondToInvitation` — the core's decision, not a membership check written again here.

- [ ] **Step 3: Verify on both an iPhone 16 Pro and an iPad simulator**

Rotate, and use Slide Over on the iPad: the detail column resizes under it, and follow-scroll must survive that.

- [ ] **Step 4: Commit**

---

### Task 13: Lifecycle, and the connection bar

**Files:**
- Modify: `apple/Supermessage/SupermessageApp.swift`, `apple/SupermessageKit/Session.swift`
- Create: `apple/Supermessage/ConnectionBar.swift`
- Test: `apple/SupermessageKitTests/LifecycleTests.swift`

**Interfaces:**
- Produces: `Session.didEnterForeground() async`, `Session.willResignActive() async`.

- [ ] **Step 1: Write the failing test**

```swift
@Test("returning to the foreground re-seeds both gap syncs")
func foregroundReseeds() async {
    // The one thing iOS needs that desktop never did. A suspended app loses
    // its sockets, and `sm://` channels only speak when something changes —
    // so a store that came back to a quiet account would sit empty until the
    // next message, which in these rooms can be hours.
    //
    // This is exactly what `seed()` was written for, after a webview reload
    // left the desktop roster empty with a perfectly healthy core behind it.
    let session = Session.forTesting()
    await session.didEnterForeground()
    #expect(await session.roomsSeedCount == 1)
    #expect(await session.timelineSeedCount == 1)
}
```

- [ ] **Step 2: Run to verify it fails**
- [ ] **Step 3: Implement.** `scenePhase → .active` seeds both gap syncs; `→ .background` sends `setTyping(roomId:typing: false)`.
- [ ] **Step 4: Run to verify it passes**
- [ ] **Step 5: Build `ConnectionBar`** — a slim bar under the navigation bar, never amber.
- [ ] **Step 6: Verify by backgrounding the app for two minutes and returning**
- [ ] **Step 7: Falsify.** Remove the seed on foreground and watch the test fail.
- [ ] **Step 8: Commit**

---

### Task 14: A thin UI smoke test, and close-out

**Files:**
- Create: `apple/SupermessageUITests/SmokeTests.swift`
- Modify: `apple/project.yml` (add the UI test target), `AGENTS.md`, `docs/tech-stack.md`

- [ ] **Step 1: Write the smoke test**

Launch, sign in, open a room, send a message, background and foreground. Deliberately thin: enough to catch broken wiring, not a UI regression suite. Rendering faults are caught by looking, which is what Tasks 8 and 10 do.

- [ ] **Step 2: Run it**

```bash
xcodebuild test -project apple/Supermessage.xcodeproj -scheme Supermessage -destination 'platform=iOS Simulator,name=iPhone 16 Pro' -quiet
```

- [ ] **Step 3: Remove the Tauri iOS target**

```bash
git rm -r src-tauri/gen/apple
```

Remove the mobile viewport workarounds and safe-area gymnastics from `src/app.html` and `src/app.css`. **Keep** the `apple-native-keyring-store/protected` dependency in `supermessage-core` — the FFI build needs it.

- [ ] **Step 4: Update the docs**

`AGENTS.md` gains an iOS section: the three targets, the one test command, and the rule that the app parses nothing. `docs/tech-stack.md`'s corrected note gains a line saying the native client now exists rather than being planned.

- [ ] **Step 5: Full verification**

```bash
cargo test --workspace && pnpm check && pnpm test && pnpm build
xcodebuild test -project apple/Supermessage.xcodeproj -scheme SupermessageKit -destination 'platform=iOS Simulator,name=iPhone 16 Pro' -quiet
```

- [ ] **Step 6: Commit**

---

## Self-Review

**Spec coverage.** §4's three targets and deployment floor are Task 1. §5.1 `CoreClient` is Task 2, §5.2 `EventPump` Task 3, §5.3 `DiffApply`/`GapSync` Tasks 4–5. §6's eleven stores are spread across Tasks 6–11 with the store that needs them. §7's navigation and the five panels are Task 12, with the iPhone pill strip in Task 7 where the list is built. §8.1's reading surface is Tasks 8–10, §8.2's composer Task 11, §8.3's session Task 6 and lifecycle Task 13. §10's testing is per-task, with the ordering test called out in Task 3 and the XCUITest in Task 14. §9's removals are Task 14.

**Corrected while writing.** §10's claim that Kit tests need no simulator is false — the xcframework has no macOS slice — and the plan says so at the top rather than quietly running them differently.

**Type consistency.** `DiffOp<T>` and `applyOps` are named identically in Tasks 4, 5, 7 and 8. `Snapshot<T>`'s three fields match `RoomsSnapshot`/`TimelineSnapshot`'s shapes from the generated bindings. `ErrorPresenter.message(for:)`/`isAuthFailure(_:)` are named the same in Tasks 6 and 13. `shouldRepin`/`shouldSettleAtBottom` match `timelineFollow.ts`'s exports.

**Not covered, deliberately.** Push notifications, Android, message editing and deletion, voice/video, syntax highlighting, widgets — all spec §11.
