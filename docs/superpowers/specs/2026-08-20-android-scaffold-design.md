# The Android scaffold

**Status:** design, 20 Aug 2026. Written against `main` at `1be33be`, after
`scripts/build-android-libs.sh` was run green for the first time.
**Audience:** whoever writes the three Gradle modules, and whoever later
wonders why the adaptive rule is a measured width rather than a size class.
**Companion:** `docs/superpowers/specs/2026-08-20-android-app-design.md` decides
*what* the Android app is. This decides how its build is shaped, and covers the
one requirement that document never addressed: **tablets**.

## What this builds

Steps 2 and part of 5 from the companion's sequence: three Gradle modules, the
wiring that lets Gradle consume a Rust boundary whose binary is not in the
repo, and an adaptive shell with placeholder panes that adapts correctly
between phone and tablet.

It does **not** build the app. No stores, no `CoreClient`, no theme, no real
data. Those are the companion's steps 3–6 and each deserves its own pass.

### Decisions taken, and by whom

| Decision | Choice |
|---|---|
| Scope | Skeleton **and** adaptive shell, not skeleton alone |
| Devices | Phone **and** tablet, from the first commit |
| `minSdk` | 31 (Android 12). `targetSdk` / `compileSdk` 36 |
| Adaptive strategy | `ListDetailPaneScaffold` for structure, **measured width** for the decision |
| `kit` module type | Android library, JVM unit tests |
| Proof of life | `peopleLabel` across the boundary, on a device |

`minSdk 31` mirrors the iOS deployment target of 18.0 and for the same reason:
a new app should not carry compatibility weight it has never needed. Dynamic
colour, predictive back and the modern window metrics are then simply
available, and nothing needs desugaring. The cost is the roughly 10–15% of
active devices below Android 12, which is a product trade and is recorded here
so it can be revisited deliberately rather than discovered.

---

## 1. The module graph

```
android/
  settings.gradle.kts          includes :core :kit :app
  build.gradle.kts             plugins declared, apply false
  gradle/libs.versions.toml    version catalog
  gradle.properties
  gradlew + wrapper
  core/   com.android.library      dev.supermessage.core
  kit/    com.android.library      dev.supermessage.kit
  app/    com.android.application  dev.supermessage
```

Three modules mirroring the three Xcode targets, for the reasons the companion
gives. The dependency arrow runs one way: `app → kit → core`, and never back.

---

## 2. `:core` — a module whose binary is not in the repo

`core` carries the generated Kotlin (checked in, 9,449 lines) and the four
`.so` files (gitignored, 362MB). That split is settled in `AGENTS.md`; what
matters here is what it does to the build.

**JNA is a hard dependency, and the classifier matters.** The generated Kotlin
imports `com.sun.jna.*` and reaches the library through
`Native.load("supermessage_ffi")`. The dependency must be
`net.java.dev.jna:jna:5.17.0@aar` — the AAR, not the JAR. The JAR resolves,
compiles, and then fails at run time because it carries no Android native
support. This is a one-character mistake with a run-time-only symptom.

**A missing `jniLibs` must fail the build, not the app.** A fresh clone has the
Kotlin and nothing behind it. Gradle never invokes cargo, so it will happily
assemble a valid APK that dies on the first call into `Core` with
`UnsatisfiedLinkError` — a run-time failure, far from its cause, that reads as
a code bug. So `:core` gets a check task, wired ahead of `preBuild`, that
asserts all four ABI directories contain `libsupermessage_ffi.so` and otherwise
fails with the exact command to run:

```
android/core/src/main/jniLibs is missing the arm64-v8a slice.
The .so files are gitignored — run this once per checkout:

  export ANDROID_NDK_HOME="$HOME/Android/Sdk/ndk/29.0.14206865"
  ./scripts/build-android-libs.sh
```

Turning a run-time link error into a build error with an instruction in it is
the whole value of this task. It costs a directory listing per build.

---

## 3. `:kit` — the module defined by what it may not import

`kit` is an Android library rather than a pure JVM one, because it must depend
on `:core` for the generated types and JNA. That is a mechanical constraint,
and it does not weaken the property the companion actually asks for.

> `kit` importing no Compose is not tidiness. It is what keeps the state layer
> testable on the JVM without an emulator.

What preserves that property is not the module type but **where the tests live**
(`src/test/`, JVM) and **what the module may depend on**. So the ban is
enforced in the build file rather than left to reviewers:

```kotlin
configurations.all {
    resolutionStrategy.eachDependency {
        check(!requested.group.startsWith("androidx.compose")) {
            ":kit must not depend on Compose — see the Android app design"
        }
    }
}
```

A rule a build can check is a rule; a rule in a document is a hope. This module
is empty in this pass, which is the right time to establish it: before there is
any code to violate it.

---

## 4. `:app` — the adaptive shell

`RootScaffold.kt`, built on `ListDetailPaneScaffold` from
`androidx.compose.material3.adaptive`: list = roster, detail = timeline, extra
= room info. The component supplies back handling, pane state and animation,
which is why it is worth using rather than hand-rolling.

Its **directive is overridden**, and §4.1 is why.

### 4.1 Why not the default directive

The principle is: never let a window size class stand in for a measured
width. That principle predates Android and does not depend on which way any
particular library's default happens to be wrong today — a size class is a
coarse, device-classification signal, and a pane count is a layout decision
that needs the actual number of dp available to this composition, not the
bucket some other component decided the window belongs to.

The reason the principle exists is iOS, and that history stays exactly
because it is not what Android's library does — it is why we measure instead
of trusting either platform's classification. From `RootView.swift`:

> `sizeClass == .regular` was the first answer and it is wrong on the device
> that exposed it. An iPad is a regular width class in both orientations, but
> three columns only fit in landscape. In portrait at 834 points the inspector
> was laid out at x=850.5: present in the accessibility tree, off the side of
> the screen, invisible.

That was `UIUserInterfaceSizeClass`, not `calculatePaneScaffoldDirective`, and
the two are not the same fault. **On `androidx.compose.material3.adaptive`
1.2.0 — the version pinned in `gradle/libs.versions.toml`, because 1.3.0
requires `minCompileSdk=37` — `calculatePaneScaffoldDirective(currentWindowAdaptiveInfo())`
does not overcount.** Decompiling the pinned jar
(`adaptive-layout-android-1.2.0.aar`, `PaneScaffoldDirectiveKt.class`) shows
its `Expanded` branch is hardcoded to `maxHorizontalPartitions = 2`
(`iconst_2`), and the no-arg `currentWindowAdaptiveInfo()` classifies width
through a 3-bucket set (Compact/Medium/Expanded, floors 0/600/840dp) with no
upper bound on Expanded — so the branch that yields three partitions is
unreachable through that entrypoint at *any* width, iPad-scale or larger.
This was found by mutation, not by reading the library's docs: Task 7 swapped
the custom directive for `calculatePaneScaffoldDirective(currentWindowAdaptiveInfo())`
and re-ran the pane geometry suite expecting the 840dp test to fail the way
this section originally predicted. It didn't — it stayed green. The 1200dp
test failed instead: a shell measured wide enough for three panes lost its
third one, because the default directive answers from the real window's
(capped) size class, not from the width this shell was actually given. The
default's fault on this pinned version is an **undercount**, not the iPad's
overcount — same substitution, opposite direction. See `task-7-report.md` for
the full decompilation and test trail, and note for whoever next touches this
version pin: an upgrade past 1.2.0 could change this mechanism again in
either direction, which is exactly why the directive stays overridden rather
than reasoned about from whichever behavior happens to be current.

Overriding it removes the guesswork regardless of which way the default is
wrong this month. So the shell measures, with `BoxWithConstraints`, and:

```
maxWidth >= 1000.dp   three panes: roster | timeline | info
maxWidth >= 600.dp    two panes:   roster | timeline, info as a bottom sheet
otherwise             one pane:    the detail stack, roster as its root
```

1000dp is the iOS threshold and it means the same thing: roster, a *readable*
timeline, and a panel, none of them squeezed to uselessness.

### 4.2 The rules ported from iOS

1. **The roster is on screen at launch**, not behind a toggle. `NavigationSplitView`'s `.automatic` hid it on an iPad in portrait and the app opened on an empty detail pane. The Compose equivalent is to seed the scaffold's navigator at the list pane rather than accept its default.
2. **Narrowing collapses an open info pane.** Rotating from landscape to portrait with the panel open leaves it laid out where it no longer fits.
3. **Measure, do not infer.** The rule in §4.1, and the reason it is a rule.

Panes are placeholders in this pass: labelled boxes that report their own
measured width, so the adaptation is visible and testable before any real data
exists.

---

## 5. Testing

| Module | Kind | Runs on |
|---|---|---|
| `core` | instrumented — `peopleLabel` across the boundary | device |
| `kit` | unit | JVM, no emulator |
| `app` | Compose UI — pane geometry at three widths | device |

**The proof of life is cheaper than expected.** `peopleLabel` is a free
function in package `uniffi.supermessage_ffi` (`supermessage_ffi.kt:4675`), not
a method on `Core`. So the test that proves Gradle → `.so` → Rust needs no
`Core` instance, no data directory, no homeserver and no network. It is one
call and one assertion, and it fails loudly if any link in the chain is wrong.

**The `app` tests assert geometry, not existence.** This is the iOS lesson
restated: a test once asserted the room-info panel existed while it was laid
out off the side of an iPad. The three widths are chosen to include the trap:

| Width | Expect |
|---|---|
| 411dp (phone portrait) | one pane |
| **840dp** | **two panes, not three** — the band the default directive gets wrong |
| 1200dp (tablet landscape) | three panes |

The 840dp case is the regression test for §4.1. It is the one that fails if
somebody later deletes the custom directive as redundant.

The repo's falsification standard applies and is not optional: **a test that
has never failed is not yet a regression test.** Each of these gets mutated
until it fails before it is kept.

---

## 6. Sequence

1. Wrapper, settings, version catalog, root build file. Done when `./gradlew projects` lists three modules.
2. `:core` — source sets, JNA, the `jniLibs` check task. Done when the check task fails correctly on a moved directory and passes on a real one.
3. `:core` instrumented test — `peopleLabel`. **Done when it passes on a device**, which closes step 2 of the companion's sequence.
4. `:kit` — empty module, JVM test source set, the Compose ban. Done when adding a Compose dependency fails the build.
5. `:app` — manifest, theme stub, `RootScaffold` with placeholder panes.
6. `:app` UI tests at the three widths, including 840dp.

Steps 1–3 are where being wrong is expensive and the answer is binary. Step 5
is where the judgement is.

---

## 7. What this does not cover

- **Everything the app does.** Stores, `CoreClient`, the event pump, the theme, the timeline. Companion steps 3–6.
- **The spaces rail.** It is a fourth surface and the companion does not place it on Android yet. `ListDetailPaneScaffold` will need negotiating with when it arrives; that is the known cost of using it.
- **Release and signing.** The release workflow builds macOS, Linux and Windows. Neither mobile platform is in it.
- **Push.** Unbuilt on both platforms.
