# Previews on the platforms nobody can look at, and an inventory of what differs

**Status:** design approved 2026-09-12, not yet planned or implemented.
**Scope:** P2b. Follows P2a (`2026-09-12-component-extraction-storybook-design.md`).

## 1. The problem

`docs/superpowers/specs/2026-09-12-design-language-tokens-design.md` recorded
that the three platforms had drifted and that nobody could see it. P2a fixed
half of that: the web UI now has 83 named story states across 22 components,
so its states are enumerable.

The native halves have **zero** previews. Not few — none:

| | Files | `#Preview` / `@Preview` |
|---|---|---|
| SwiftUI (`apple/Supermessage`) | 21, of which **18 are views** | 0 |
| Compose (`android/app/src/main`) | 19 with `@Composable`, of which **~14 are UI** | 0 |

So every visual change on either platform costs a full build and a device,
and neither platform's states can be listed — which is the condition that
made the palette drift invisible in the first place.

**32 previewable surfaces**, not the 45 an earlier count claimed; that
number included generated bindings (`ThemeTokens.swift`), the palette
(`Theme.swift`, `Theme.kt`), the entry points (`SupermessageApp.swift`,
`MainActivity.kt`) and infrastructure (`KeyboardDismiss.kt`,
`SeededHomeserver.kt`).

## 2. The condition this project is built under

**No preview written by this project will be looked at by its author.** That
is stated first because it shapes everything else.

- Xcode 16.4 ships the **iOS 18.5 SDK**; the available device runs **iOS
  26.6.1**, so it cannot deploy there.
- The simulator is ruled out on disk (3.7 GB free at the time of writing).
- There is **no Android SDK** on the development machine — no `ANDROID_HOME`,
  no `adb`, no `kotlinc`.

Previews are code whose entire purpose is to be rendered. Writing 32 of them
without rendering one is a weaker position than P2a was ever in, and it is
the same exposure that cost the token project three CI round-trips on
Android. The decision to proceed anyway was taken explicitly with that
stated.

What *is* reachable: compilation on both platforms, release hygiene on both,
and the inventory in full. §6 is honest about the boundary.

## 3. Decisions

1. **All 32 surfaces get a preview**, per-platform states rather than
   mirrors of P2a's named scenarios.
2. **iOS first, Android second**, so the pattern is proven where it can be
   compiled before it is applied where it cannot.
3. **Previews are excluded from release builds** — `#if DEBUG` on iOS, a
   `src/debug/` source set on Android — and a CI gate asserts it.
4. **Each platform gets its own fixture builders.** There is no shared
   fixture source across the FFI boundary.
5. **The parity inventory is a documented comparison**, not a mechanical
   one; see §5 for why that follows from decision 1.

## 4. The work

### 4.1 iOS — 18 previews

Each preview lives beside its view, wrapped in `#if DEBUG`. Fixtures go in
one `#if DEBUG` file in the app target.

**The fixtures are copied, not imported, and the duplication is
deliberate.** `apple/SupermessageKitTests/TimelineGroupingTests.swift`
already has the builder this needs —
`static func row(id:sender:at:isOwn:system:)`, constructing a
`TimelineItemDto` and a `TimelineRow`. A test target is not visible to the
app target, so a preview cannot import it. The copy carries a comment
saying so, because an undocumented duplicate of a fixture builder is how two
of them drift.

**Strict concurrency is the likely failure mode, and it is checkable.**
`apple/project.yml` sets `SWIFT_VERSION: "6.0"` and
`SWIFT_STRICT_CONCURRENCY: complete` on both `SupermessageKit` and
`Supermessage`. A preview that constructs a main-actor-bound store will fail
to compile, and `xcodebuild build` against a simulator destination will say
so. That is the one real gate this project has.

### 4.2 Android — 14 previews, and three build changes

The Compose half is **not just annotations**, and this is the finding that
most changed this design:

- **The preview renderer is absent from the project.**
  `android/gradle/libs.versions.toml` declares only
  `compose-ui-tooling-preview` — the annotation. It does not declare
  `compose-ui-tooling`, which is what Android Studio needs to render a
  preview. Without adding it, 14 `@Preview` annotations are decorative even
  on a machine with an SDK.
- **`compose-ui-tooling-preview` is `implementation`, not
  `debugImplementation`**, so the annotation artifact ships in release today.
  It can only move to debug scope if the `@Preview` functions live in a debug
  source set — which means the source-set decision and the dependency scope
  are one decision, not two.
- **R8 is not enabled.** `android/app/build.gradle.kts` has no `buildTypes`
  block. This is recorded because "ship the previews and let R8 strip them"
  was considered and would have been wrong here: nothing would have been
  stripped.

So: add `compose-ui-tooling` to the catalog, `debugImplementation` it, move
`compose-ui-tooling-preview` to debug scope, create
`android/app/src/debug/kotlin/dev/supermessage/`, and write 14 previews
there.

**Each debug file is a thin wrapper around the real composable** — a
`@Preview` function, its fixture, and nothing else. Four Gradle-level
changes to a build that cannot be run locally is already the largest
unverifiable surface in this arc; keeping the preview bodies trivial means
the only thing that can plausibly break is an import path.

### 4.3 The release gate

A CI step asserting a release build contains no fixture marker — the native
mirror of P2a's `test_fixture_leak.mjs`.

Mutation-proven, and specifically proven the way P2a's had to be: the first
attempt there imported the marker into a route, assigned it to an unused
binding, and **passed** — because an unused binding is stripped. The
mutation has to make the value reach the built artifact.

## 5. The parity inventory

`docs/platform-parity.md`. Per component, what each platform has.

**It is a documented judgement, not detected drift**, and that follows
directly from decision 1. Because the native previews cover per-platform
states rather than mirroring P2a's named scenarios, the three platforms'
states do not line up by name — so nothing can mechanically report "web has
a story for this state and iOS does not". The inventory will say which
comparisons are judgement calls rather than presenting all of them as
findings.

What it can still do, and what makes it worth writing:

- List the 22 web components with story counts against the 18 iOS views and
  14 Compose surfaces, and name the components that exist on one platform
  and not another. That part *is* mechanical, and it is where the original
  audit's findings came from: web has `AgentReasoning`, `LiveActivity` and
  `Shimmer`; native has neither. Native had a real `DecisionCard`; web did
  not until P2a.
- Record the design-language rules each platform is known to honour and
  known to violate, with the measurements where they exist — P2a measured
  amber appearing in exactly 3 of 83 web stories; nothing equivalent is
  known for either native platform.
- Carry forward what P1 left open: iOS defines `ground`/`sunken`/`hairline`
  and has zero call sites for them, and Android reaches the palette only
  through the Material bridge. That is P6, and the inventory is where it
  should be visible.

## 6. Verification

| | Reachable | How |
|---|---|---|
| iOS previews compile | **yes** | `xcodebuild build`, simulator destination, strict concurrency on |
| iOS Kit tests still pass | **yes** | `xcodebuild test -scheme SupermessageKit` |
| Android compiles | **no, locally** | CI's Android job only |
| Gradle changes correct | **no, locally** | CI only |
| Release excludes fixtures | **yes** | the §4.3 gate, mutation-proven |
| **Any preview renders** | **no** | nobody on this machine can look at one |

That last row is the project's defining limitation and the plan must not
paper over it. A preview that compiles and renders a blank frame is a
plausible outcome here and would not be detected.

## 7. Open questions

- **Does `compose-ui-tooling` actually render these previews?** Adding the
  dependency is necessary; whether each `@Preview` renders something useful
  is unknown until someone opens Android Studio.
- **Should the native previews later be re-pointed at P2a's named states?**
  That is what would make the parity inventory mechanical. Declined here;
  worth revisiting once there is a way to look at the previews, because
  re-pointing states nobody can see would be speculative work.
- P1's open question stands: web derives its pane breakpoint from pane
  widths, `RootView.swift` hardcodes 1000, Android measures its own width.

## 8. Non-goals

- **No redesign, and no adoption.** Previews render what the components
  already render. Making iOS paint its surfaces with the token ramp is P6.
- P2a's remaining work — `PaneShell`, `RoomHeader`, and the four Timeline
  leaves inside the virtualiser — is not this project.
- Icons (P3), native accessibility (P4), i18n (P5).
- No attempt to render a preview, because there is no way to.
