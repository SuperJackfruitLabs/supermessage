# Native Previews and Parity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every SwiftUI view and Compose surface gets a preview, and a document records what differs between the three platforms.

**Architecture:** Narrow per-store protocols give iOS the injection seam the web side already has, so a preview can construct a `Session` without the Rust core. Previews live beside their view under `#if DEBUG` (iOS) or in a `src/debug/` source set (Android), and a CI gate asserts neither reaches a release build.

**Tech Stack:** SwiftUI (Swift 6, strict concurrency complete), Jetpack Compose, XcodeGen, Gradle, UniFFI-generated bindings.

**Spec:** `docs/superpowers/specs/2026-09-12-native-previews-parity-design.md`

## Global Constraints

- **No preview will be rendered by this plan's author.** Xcode 16.4 ships the iOS 18.5 SDK against a 26.6.1 device; the simulator is out on disk; there is no Android SDK. Compilation is the only visual-adjacent gate, and a preview that compiles to a blank frame would not be detected. Do not write a step claiming otherwise.
- **iOS before Android**, always. iOS can be compiled locally; Android cannot.
- **`AGENTS.md`'s three iOS rules are binding**, and one of them is the reason this plan takes narrow protocols rather than a wide one: *"No `Core` call on a cooperative thread. `CoreClient` puts every one on a `DispatchQueue`, because they all block."* A protocol must not become a way to bypass that.
- **The app parses nothing and decides nothing.** Previews construct view-models the core would have produced; they must not compute one.
- **Amber means a pending decision and nothing else** (`docs/design-language.md` §2). A preview that paints `signal` anywhere else is a defect.
- **Previews and fixtures must not reach a release build**, verified rather than assumed.
- **A test that has never failed is not a regression test.** Every check added here must be shown to fail against a deliberately broken input — and the mutation must be confirmed present in the artefact before the result is believed. Three near-misses in the preceding projects came from skipping that.

### The iOS view inventory

| | Views |
|---|---|
| Previewable from plain values (6) | `DecisionCard` (`CustomEventCard`), `LiveTurnView`, `RichTextView`, `RoomRowView`, `SpacePillStrip`, `StreamingTextView` |
| Needs `MediaCache` + `AvatarCache` (1) | `TimelineRowView` |
| Needs `Session` (11) | `RootView`, `RoomListView`, `SearchPanel`, `NewRoomPanel`, `RoomInfoPanel`, `AccountPanel`, `InvitationView`, `ComposerView`, `LoginView`, `TimelineView`, `TimelineCollectionView` |

### Known view signatures, verbatim

```swift
struct RoomRowView: View {
    let row: RoomRow
    let avatarURI: String?
    let state: AgentState
    let when: String
    var showsState: Bool = true
}

struct CustomEventCard: View {
    let view: CustomEventView
    let label: String
    let eventType: String
    let senderName: String
    var onDecide: ((GateAnswer) async -> Bool)?
}

struct TimelineRowView: View {
    let row: TimelineRow
    var continuesRun: Bool = false
    var attribution: String = ""
    let media: MediaCache
    let faces: AvatarCache
    var onReply: (() -> Void)?
    var onReact: ((String) -> Void)?
    var onDecide: ((GateAnswer) async -> Bool)?
}
```

`Session`'s stores, all constructed from `client` in `Session.init(client:)`:
`rooms: RoomsStore`, `spaces: SpacesStore`, `avatars: AvatarCache`,
`faces: AvatarCache`, `media: MediaCache`, `timeline: TimelineStore`,
`staged: StagedAttachment`. Plus `connection`, `live`, `typing`, `drafts`,
`replies`, `edits`, which take no client.

---

## Task 1: Prove the seam is possible before building it

The whole plan rests on a protocol at the `CoreClient` level satisfying
strict concurrency. `AGENTS.md` records that UniFFI 0.28's output is not
`Sendable`-clean, which is why `SupermessageFFI` is quarantined at Swift 5.
If a `Sendable` protocol cannot be declared here, routes change and it is
much cheaper to learn that now.

**Files:**
- Create: `apple/SupermessageKit/CoreSeam.swift`

**Interfaces:**
- Produces: `protocol AvatarFetching: Sendable { func roomAvatarFull(roomId: String) async throws -> String? }`, and `extension CoreClient: AvatarFetching {}`.

- [ ] **Step 1: Write the narrowest possible protocol**

`apple/SupermessageKit/CoreSeam.swift`:

```swift
import Foundation

/// The seam that lets a store be built without the Rust core behind it.
///
/// **Narrow on purpose, one protocol per store.** A single protocol over all
/// 38 of `CoreClient`'s methods was the obvious shape and is the wrong one:
/// `AGENTS.md` records that *"no `Core` call runs on a cooperative
/// thread — `CoreClient` puts every one on a `DispatchQueue`, because they
/// all block and the cooperative pool assumes tasks yield."* A wide protocol
/// is an invitation to supply an implementation that forgets that. A
/// two-method protocol used by one cache is not.
///
/// This is the shape the web side already has. `AvatarCacheDeps` and
/// `RoomsStoreDeps` in `src/lib/stores/` are exactly this, and the token
/// project recorded that those exist so the 386 frontend tests can stub
/// them. iOS never got the same treatment; this is that treatment.
public protocol AvatarFetching: Sendable {
    func roomAvatarFull(roomId: String) async throws -> String?
}

extension CoreClient: AvatarFetching {}
```

- [ ] **Step 2: Compile it, which is the whole point of this task**

Run:
```bash
cd apple && xcodegen generate && cd ..
xcodebuild build -project apple/Supermessage.xcodeproj -scheme SupermessageKit \
  -destination 'platform=iOS Simulator,name=iPhone 16 Pro' -quiet
```
Expected: exit 0.

**If `CoreClient` cannot conform to a `Sendable` protocol, stop.** Report
which of these it is, because they have different answers: `CoreClient`
itself is not `Sendable` (it holds a `DispatchQueue` and the FFI `Core`), or
the `async throws` signature does not match, or strict concurrency rejects
the existential. Do not work around it by removing `Sendable` — that would
hand a preview a way onto a cooperative thread, which is the rule this
protocol is shaped to protect.

- [ ] **Step 3: Commit**

```bash
git add apple/SupermessageKit/CoreSeam.swift apple/Supermessage.xcodeproj
git commit -m "ios: one narrow protocol, to find out if the seam is possible

The whole preview plan rests on a Sendable protocol at the CoreClient
level. AGENTS.md records that UniFFI 0.28's output is not Sendable-clean,
so this is a two-method protocol used by one cache, compiled before
anything is built on it.

Narrow rather than wide, and that is the rule it protects: no Core call
runs on a cooperative thread because CoreClient puts every one on a
DispatchQueue. A 38-method protocol is an invitation to supply an
implementation that forgets."
```

---

## Task 2: The remaining store protocols

Only once Task 1 compiles.

**Files:**
- Modify: `apple/SupermessageKit/CoreSeam.swift`
- Modify: `apple/SupermessageKit/Stores/AvatarCache.swift`, `MediaCache.swift`, `RoomsStore.swift`, `SpacesStore.swift`, `TimelineStore.swift`, `StagedAttachment.swift`, `Session.swift`

- [ ] **Step 1: Read each store and list exactly what it calls on `client`**

```bash
for f in apple/SupermessageKit/Stores/*.swift apple/SupermessageKit/Session.swift; do
  echo "── $f"
  grep -oE "client\.[a-zA-Z]+" "$f" | sort -u
done
```

Write one protocol per store carrying exactly those methods and no more. A
protocol with a method the store does not call is a method a preview has to
stub for nothing.

- [ ] **Step 2: Change each store's stored property and initialiser** to the protocol, leaving the `CoreClient` convenience initialiser in place so no existing call site changes.
- [ ] **Step 3: `xcodebuild build -scheme SupermessageKit`** — expected exit 0.
- [ ] **Step 4: `xcodebuild test -scheme SupermessageKit`** — expected 163 tests still passing. They construct real `CoreClient`s, so they exercise the conformance rather than the stubs.
- [ ] **Step 5: Commit** — `ios: a protocol per store, sized to what that store calls`

---

## Task 3: Preview fixtures and stubs

**Files:**
- Create: `apple/Supermessage/Previews/PreviewFixtures.swift`

- [ ] **Step 1: Write the file, entirely inside `#if DEBUG`**

It carries two things: stub conformers for the Task 2 protocols, and
view-model builders.

**The builders are copied from `SupermessageKitTests`, and the copy is
deliberate.** `TimelineGroupingTests.swift` has
`static func row(id:sender:at:isOwn:system:)` constructing a
`TimelineItemDto` and a `TimelineRow`. A test target is not visible to the
app target, so a preview cannot import it. The copy must carry a comment
saying exactly that, because an undocumented duplicate fixture builder is
how two of them drift apart.

Include `PREVIEW_FIXTURE_MARKER`, a long unlikely string, for Task 6.

- [ ] **Step 2: Build.** Expected exit 0. Strict concurrency is the likely failure: a stub that is not `Sendable`, or a builder touching a main-actor type from a non-isolated context.
- [ ] **Step 3: Commit** — `ios: preview fixtures, behind #if DEBUG`

---

## Task 4: The 7 previews that need no Session

`DecisionCard` · `LiveTurnView` · `RichTextView` · `RoomRowView` · `SpacePillStrip` · `StreamingTextView` · `TimelineRowView`

- [ ] **Step 1: One `#Preview` block per view**, appended to its own file inside `#if DEBUG`, using the Task 3 builders. Signatures are in this plan's header; read each view first — `showsState`, `continuesRun` and `attribution` all have defaults and a preview that omits them is showing the default, which is worth being deliberate about.
- [ ] **Step 2: `RoomRowView` gets four previews** covering the states `RoomRow`'s own doc comments call out: quiet, an agent working, a pending decision, an invitation. The pending-decision one is the only place amber may appear.
- [ ] **Step 3: `DecisionCard` gets three** — pending, answered, and a field value that is one long unbroken run, since every value on it is arbitrary JSON from anyone who can send to the room.
- [ ] **Step 4: Build and test.** `xcodebuild build -scheme Supermessage` and `xcodebuild test -scheme SupermessageKit`.
- [ ] **Step 5: Commit** — `ios: previews for the views that take plain values`

---

## Task 5: The 11 previews that need a Session

- [ ] **Step 1: Add a `#if DEBUG` `Session` convenience initialiser** taking the stub protocol conformers, in `Session.swift`. It must not call `start()` — a preview has no core to restore from, and `phase` starting at `.starting` is a legitimate state to preview.
- [ ] **Step 2: One `#Preview` per view.** For views whose interesting states depend on `Session.phase`, add a second preview per reachable phase — `LoginView` in `.signedOut` is the one a reader will actually want.
- [ ] **Step 3: Build and test.** Expected exit 0, 163 tests.
- [ ] **Step 4: Commit** — `ios: previews for the views that take a Session`

---

## Task 6: The iOS release gate

- [ ] **Step 1: Write `scripts/tests/test_ios_preview_leak.sh`** — build for release and assert `PREVIEW_FIXTURE_MARKER` does not appear in the binary:

```bash
xcodebuild build -project apple/Supermessage.xcodeproj -scheme Supermessage \
  -configuration Release -destination 'generic/platform=iOS' \
  -derivedDataPath "$TMP" CODE_SIGNING_ALLOWED=NO -quiet
strings "$APP/Supermessage" | grep -c PREVIEW_FIXTURE_MARKER
```

- [ ] **Step 2: Prove it catches a leak.** Move one fixture outside `#if DEBUG`, build release, confirm the check fails and names the binary. Restore.

**Confirm the mutation is present in the built binary before believing the result.** The equivalent check on the web side passed meaninglessly on its first attempt because an unused binding was tree-shaken; the same will happen here if the marker is not actually referenced by shipped code.

- [ ] **Step 3: Add to CI's iOS job. Commit.**

---

## Task 7: Android — the build changes

Nothing here can be compiled locally. Keep each change as small as it can be.

- [ ] **Step 1: Add `compose-ui-tooling` to `android/gradle/libs.versions.toml`.** The catalog has only `compose-ui-tooling-preview`, the annotation. Without the renderer, every `@Preview` is decorative even on a machine with an SDK.
- [ ] **Step 2: In `android/app/build.gradle.kts`** — `debugImplementation(libs.compose.ui.tooling)`, and move `compose-ui-tooling-preview` from `implementation` to `debugImplementation`.
- [ ] **Step 3: Create `android/app/src/debug/kotlin/dev/supermessage/`.**

Moving the annotation to debug scope is what requires the previews to live here; the two are one decision. Note that R8 is **not** enabled — there is no `buildTypes` block — so "release will strip it" was never true for this project.

- [ ] **Step 4: Add one trivial `@Preview` there** and stop. The smallest thing that proves the source set, the renderer dependency and the scope change all work, before 14 previews ride on them.
- [ ] **Step 5: Commit and push, and wait for CI.** State plainly in the commit that this is unverified locally.

---

## Task 8: Android — the 14 previews

Only after Task 7 is green on CI.

- [ ] **Step 1: One preview per Compose surface**, each a thin `@Preview` wrapper around the real composable plus its fixture. Keep the bodies trivial: with no local compiler, the narrowest failure surface is an import path.
- [ ] **Step 2: `RoomRow` and `DecisionCard` get the same state coverage as their iOS counterparts** — including that amber appears only on a pending decision.
- [ ] **Step 3: Push and wait for CI.** Commit per coherent group, not one commit for fourteen.

---

## Task 9: The Android release gate

- [ ] **Step 1: Assert an assembled release APK contains no `PREVIEW_FIXTURE_MARKER`**, using the debug source set rather than R8 as the mechanism, since R8 is off.
- [ ] **Step 2: Mutation-prove it** by moving one fixture into `main`. Confirm in CI.
- [ ] **Step 3: Add to CI's Android job. Commit.**

---

## Task 10: The parity inventory

**Files:**
- Create: `docs/platform-parity.md`

- [ ] **Step 1: The mechanical part.** A table of every component on each platform, with story or preview counts, marking what exists on one platform and not another. This is where the original audit's findings live: web has `AgentReasoning`, `LiveActivity` and `Shimmer` that native lacks; native had a real `DecisionCard` before web did.
- [ ] **Step 2: The judgement part, labelled as such.** Because the native previews cover per-platform states rather than mirroring P2a's 83 named scenarios, no tool can report "web has a story for this state and iOS does not". Say so in the document rather than presenting comparisons as detected drift.
- [ ] **Step 3: Record what is measured and what is not.** P2a measured amber in exactly 3 of 83 web stories. Nothing equivalent is known for either native platform, and this document should say that rather than imply parity.
- [ ] **Step 4: Carry forward P6.** iOS defines `ground`/`sunken`/`hairline` with zero call sites; Android reaches the palette only through the Material bridge. The inventory is where that should be visible.
- [ ] **Step 5: Commit.**

---

## Task 11: Report what cannot be checked

- [ ] **Step 1: Confirm every gate.** iOS builds, 163 Kit tests, both release gates, CI green on both platforms.
- [ ] **Step 2: Write the PR description, leading with the limitation.** Not one of these ~32 previews has been rendered. A preview that compiles and shows a blank frame is a plausible, undetected outcome. Say it first, not in a footnote.
- [ ] **Step 3: List, per platform, what a person with a working toolchain should look at first** — the states carrying a design-language rule, because those are where a blank or wrong preview costs the most.

---

## Self-Review

**Spec coverage:** §4.0 seam → Tasks 1–2. §4.1 iOS previews → Tasks 3–5. §4.2 Android → Tasks 7–8. §4.3 release gate → Tasks 6, 9. §5 inventory → Task 10. §6 verification → Task 11. §7 open questions recorded, not implemented. §8 non-goals excluded.

**Placeholder scan:** Tasks 2, 5, 8 and 10 describe per-file work whose exact content depends on reading each file first, and each says so with the command to run. Tasks 1, 3, 6, 7 and 9 carry the actual code or commands. No TBDs.

**Type consistency:** `AvatarFetching` is defined in Task 1 and extended in Task 2. `PREVIEW_FIXTURE_MARKER` is defined in Task 3 and consumed in Tasks 6 and 9. The view signatures in the header are quoted verbatim from the source and are what Task 4 builds against.

**The risk I want on the record:** Task 2 changes the stored property type of seven files in a shipped library to serve previews. The 163 Kit tests construct real `CoreClient`s, so they will exercise the conformance — but they will not exercise the stub path, which only previews use. The stub path is therefore compiled and never run, on a platform where nothing can be looked at. That is the weakest link in this plan and no step in it fixes that.
