# Android Scaffold Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up `android/{core,kit,app}` as a Gradle build that consumes the existing Rust boundary and renders an adaptive shell that lays out correctly on both phone and tablet.

**Architecture:** Three modules mirroring the three Xcode targets, `app → kit → core`, never back. `core` wraps the checked-in generated Kotlin plus the gitignored `.so` files. `kit` is deliberately empty in this pass and exists to establish its no-Compose rule before there is code to violate it. `app` builds a `ListDetailPaneScaffold` whose pane count comes from a **measured width**, not a window size class.

**Tech Stack:** Gradle 9.5.1, AGP 9.3.1, Kotlin 2.4.0, JDK 21, Compose BOM 2026.08.00, `material3-adaptive` 1.3.0, JNA 5.17.0.

**Spec:** `docs/superpowers/specs/2026-08-20-android-scaffold-design.md`

## Global Constraints

- `minSdk = 31`, `targetSdk = 36`, `compileSdk = 36` in every Android module.
- `applicationId = "dev.supermessage"`. This must NOT be `dev.supermessage.app` — that is the Tauri build's id, and reusing it means the two cannot coexist on one device.
- JNA must be declared as `net.java.dev.jna:jna:5.17.0@aar`. The `@aar` classifier is mandatory; the JAR compiles and then fails at run time with no Android native support.
- `:kit` must declare no dependency on any `androidx.compose.*` artifact.
- Every test is mutated until it fails before it is kept. **A test that has never failed is not yet a regression test.**
- All Gradle commands run from `android/`, not the repo root.
- The `.so` files are gitignored. If `android/core/src/main/jniLibs/` is empty, run `scripts/build-android-libs.sh` from the repo root with `ANDROID_NDK_HOME` set.

## Version pinning notes

Gradle 9.5.1 is already extracted in `~/.gradle/wrapper/dists/` on this machine, so pinning it costs no download. JNA 5.17.0 and the Kotlin 2.4.0 plugin are already in `~/.gradle/caches/`. AGP, Compose and `material3-adaptive` are **not** cached and will be fetched on first build — this network has stalled twice during this session, so expect to retry Task 1.

---

## File Structure

| File | Responsibility |
|---|---|
| `android/settings.gradle.kts` | Module inclusion, repository declarations |
| `android/build.gradle.kts` | Plugin versions, `apply false` |
| `android/gradle.properties` | JVM args, AndroidX flags |
| `android/gradle/libs.versions.toml` | Every version in one place |
| `android/gradle/wrapper/*` | Pinned Gradle 9.5.1 |
| `android/core/build.gradle.kts` | Source sets, JNA, the jniLibs guard |
| `android/core/src/androidTest/.../BoundaryTest.kt` | Proof of life across the FFI |
| `android/kit/build.gradle.kts` | The no-Compose rule, enforced |
| `android/app/build.gradle.kts` | Application module, Compose |
| `android/app/src/main/.../PaneLayout.kt` | Pure width → pane-count rule |
| `android/app/src/main/.../RootScaffold.kt` | The adaptive shell |
| `android/app/src/main/.../MainActivity.kt` | Entry point |
| `android/app/src/test/.../PaneLayoutTest.kt` | JVM test of the rule, incl. 840dp |
| `android/app/src/androidTest/.../RootScaffoldTest.kt` | Rendered pane geometry |

**Refinement of spec §5:** the spec put all `app` tests on-device. The width → pane-count *rule* is a pure function, so it gets a JVM unit test instead — the 840dp regression then runs on every `./gradlew test` with no emulator. Only the *rendered* assertions stay instrumented.

---

### Task 1: Gradle skeleton

**Files:**
- Create: `android/settings.gradle.kts`, `android/build.gradle.kts`, `android/gradle.properties`, `android/gradle/libs.versions.toml`
- Create: `android/gradle/wrapper/gradle-wrapper.properties`, `android/gradlew`, `android/gradle/wrapper/gradle-wrapper.jar`

**Interfaces:**
- Consumes: nothing.
- Produces: the version catalog aliases `libs.plugins.android.application`, `libs.plugins.android.library`, `libs.plugins.kotlin.android`, `libs.plugins.kotlin.compose`, `libs.jna`, `libs.compose.bom`, `libs.androidx.core.ktx`, `libs.androidx.activity.compose`, `libs.compose.ui`, `libs.compose.material3`, `libs.adaptive`, `libs.adaptive.layout`, `libs.adaptive.navigation`, `libs.junit`, `libs.androidx.test.junit`, `libs.compose.ui.test.junit4`, `libs.compose.ui.test.manifest`. Every later task refers to these names.

- [ ] **Step 1: Create the directory and copy a wrapper**

There is no `gradle` on PATH. Copy the wrapper from the Tauri build, then repoint it.

```bash
cd /home/rakeshgangwar/Projects/supermessage
mkdir -p android/gradle/wrapper
cp src-tauri/gen/android/gradlew android/gradlew
cp src-tauri/gen/android/gradle/wrapper/gradle-wrapper.jar android/gradle/wrapper/
chmod +x android/gradlew
```

- [ ] **Step 2: Pin Gradle 9.5.1**

Create `android/gradle/wrapper/gradle-wrapper.properties`:

```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-9.5.1-bin.zip
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

9.5.1 rather than the Tauri build's 8.14.3 because 9.5.1 is already extracted in `~/.gradle/wrapper/dists/` and 8.14.3 is not.

- [ ] **Step 3: Write the version catalog**

Create `android/gradle/libs.versions.toml`:

```toml
[versions]
agp = "9.3.1"
kotlin = "2.4.0"
composeBom = "2026.08.00"
adaptive = "1.3.0"
jna = "5.17.0"
coreKtx = "1.15.0"
activityCompose = "1.9.3"
junit = "4.13.2"
androidxTestJunit = "1.2.1"

[libraries]
jna = { group = "net.java.dev.jna", name = "jna", version.ref = "jna" }
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
compose-ui = { group = "androidx.compose.ui", name = "ui" }
compose-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
compose-material3 = { group = "androidx.compose.material3", name = "material3" }
adaptive = { group = "androidx.compose.material3.adaptive", name = "adaptive", version.ref = "adaptive" }
adaptive-layout = { group = "androidx.compose.material3.adaptive", name = "adaptive-layout", version.ref = "adaptive" }
adaptive-navigation = { group = "androidx.compose.material3.adaptive", name = "adaptive-navigation", version.ref = "adaptive" }
junit = { group = "junit", name = "junit", version.ref = "junit" }
androidx-test-junit = { group = "androidx.test.ext", name = "junit", version.ref = "androidxTestJunit" }
compose-ui-test-junit4 = { group = "androidx.compose.ui", name = "ui-test-junit4" }
compose-ui-test-manifest = { group = "androidx.compose.ui", name = "ui-test-manifest" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
android-library = { id = "com.android.library", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
```

Note the JNA alias carries no `@aar`; the classifier is applied at the dependency site in Task 2, because catalog entries cannot express it.

- [ ] **Step 4: Write settings and root build**

Create `android/settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "supermessage"
include(":core", ":kit", ":app")
```

Create `android/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
```

Create `android/gradle.properties`:

```properties
org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8
org.gradle.caching=true
org.gradle.parallel=true
android.useAndroidX=true
android.nonTransitiveRClass=true
kotlin.code.style=official
```

- [ ] **Step 5: Create the three empty module build files**

Placeholders so `include` resolves. Each is replaced in its own task.

```bash
cd /home/rakeshgangwar/Projects/supermessage/android
mkdir -p core kit app
for m in core kit app; do echo "// replaced in a later task" > $m/build.gradle.kts; done
```

- [ ] **Step 6: Verify the build configures**

Run: `cd android && ./gradlew projects`
Expected: PASS, listing `+--- Project ':app'`, `+--- Project ':core'`, `\--- Project ':kit'`.

If this fails on a network stall (this has happened twice this session), re-run. If it fails on an AGP/Gradle incompatibility, that is a real finding — report the exact message rather than bumping versions blindly.

- [ ] **Step 7: Commit**

```bash
cd /home/rakeshgangwar/Projects/supermessage
git add android/settings.gradle.kts android/build.gradle.kts android/gradle.properties \
        android/gradle/ android/gradlew android/core/build.gradle.kts \
        android/kit/build.gradle.kts android/app/build.gradle.kts
git commit -m "Android: the Gradle skeleton"
```

---

### Task 2: `:core` consumes the Rust boundary

**Files:**
- Modify: `android/core/build.gradle.kts` (replace placeholder)
- Create: `android/core/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: catalog aliases from Task 1.
- Produces: a `:core` module exporting `uniffi.supermessage_ffi.peopleLabel(List<String>): String` on its `api` configuration, and a task named `checkJniLibs` wired ahead of `preBuild`.

- [ ] **Step 1: Write the module build file**

Replace `android/core/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.supermessage.core"
    compileSdk = 36

    defaultConfig {
        minSdk = 31
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // The generated Kotlin lives in src/main/kotlin rather than src/main/java,
    // because build-android-libs.sh writes it there and it is checked in.
    sourceSets["main"].kotlin.srcDir("src/main/kotlin")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin { jvmToolchain(21) }
}

dependencies {
    // The @aar classifier is mandatory. The plain JAR resolves and compiles,
    // then fails at run time because it carries no Android native support.
    api("${libs.jna.get()}@aar")
}

// A fresh clone has the generated Kotlin and no libraries behind it, because
// the .so files are gitignored. Gradle never invokes cargo, so without this
// check it assembles a valid APK that dies on the first call into Core with
// UnsatisfiedLinkError — a run-time failure far from its cause. Turning that
// into a build error with the command in it is the whole point.
val abis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

val checkJniLibs by tasks.registering {
    val jniDir = layout.projectDirectory.dir("src/main/jniLibs")
    doLast {
        val missing = abis.filterNot {
            jniDir.file("$it/libsupermessage_ffi.so").asFile.exists()
        }
        check(missing.isEmpty()) {
            """
            |android/core/src/main/jniLibs is missing: ${missing.joinToString(", ")}
            |
            |The .so files are gitignored — run this once per checkout:
            |
            |  export ANDROID_NDK_HOME="${'$'}HOME/Android/Sdk/ndk/29.0.14206865"
            |  ./scripts/build-android-libs.sh
            """.trimMargin()
        }
    }
}

tasks.named("preBuild") { dependsOn(checkJniLibs) }
```

- [ ] **Step 2: Write the manifest**

Create `android/core/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" />
```

- [ ] **Step 3: Prove the guard fires — the falsification step**

Move a slice aside and confirm the build fails with the instruction in it.

```bash
cd /home/rakeshgangwar/Projects/supermessage
mv android/core/src/main/jniLibs/arm64-v8a /tmp/abi-held
cd android && ./gradlew :core:assembleDebug
```

Expected: FAIL, with `missing: arm64-v8a` and the `build-android-libs.sh` command in the message.

If it *passes*, the guard is not wired and the task is not done. This is the mutation that makes it a regression test.

- [ ] **Step 4: Restore and verify it passes**

```bash
mv /tmp/abi-held /home/rakeshgangwar/Projects/supermessage/android/core/src/main/jniLibs/arm64-v8a
cd /home/rakeshgangwar/Projects/supermessage/android && ./gradlew :core:assembleDebug
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
cd /home/rakeshgangwar/Projects/supermessage
git add android/core/
git commit -m "Android: core consumes the Rust boundary, and says so when it cannot"
```

---

### Task 3: Proof of life across the FFI

**Files:**
- Create: `android/core/src/androidTest/kotlin/dev/supermessage/core/BoundaryTest.kt`
- Modify: `android/core/build.gradle.kts` (test dependencies, androidTest source set)

**Interfaces:**
- Consumes: `uniffi.supermessage_ffi.peopleLabel` from Task 2.
- Produces: nothing later tasks depend on. This closes step 2 of the companion spec's sequence.

- [ ] **Step 1: Add the test dependencies**

Append to `dependencies` in `android/core/build.gradle.kts`:

```kotlin
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.junit)
```

And inside the `android { }` block:

```kotlin
    sourceSets["androidTest"].kotlin.srcDir("src/androidTest/kotlin")
```

- [ ] **Step 2: Write the failing test**

`peopleLabel` is a **free function** in package `uniffi.supermessage_ffi` — no `Core` instance, no data directory, no homeserver. It is the cheapest possible call that still proves Gradle → `.so` → JNA → Rust.

Create `android/core/src/androidTest/kotlin/dev/supermessage/core/BoundaryTest.kt`:

```kotlin
package dev.supermessage.core

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import uniffi.supermessage_ffi.peopleLabel

/**
 * The whole chain in one call: Gradle packaged the .so, JNA found it by the
 * name the generated Kotlin asks for, and Rust answered.
 *
 * If this fails with UnsatisfiedLinkError the libraries are missing or the
 * ABI is wrong. If it fails on the assertion, the boundary drifted and the
 * bindings were not regenerated.
 */
@RunWith(AndroidJUnit4::class)
class BoundaryTest {

    @Test
    fun theCoreAnswersFromTheOtherSideOfTheBoundary() {
        val label = peopleLabel(listOf("@ganesha:supermessage.dev"))
        assertEquals("ganesha", label)
    }

    @Test
    fun twoPeopleAreNamedTogether() {
        val label = peopleLabel(
            listOf("@ganesha:supermessage.dev", "@rakesh:supermessage.dev"))
        assertEquals("ganesha and rakesh", label)
    }
}
```

- [ ] **Step 3: Run it and read the real answer**

Run: `cd android && ./gradlew :core:connectedDebugAndroidTest`

The assertions above encode a *guess* at `people_label`'s formatting. Check the Rust before trusting them:

```bash
cd /home/rakeshgangwar/Projects/supermessage
sed -n '/pub fn people_label/,/^}/p' crates/supermessage-ffi/src/lib.rs
grep -n "pub fn people_label" -A 40 crates/supermessage-core/src/display_name.rs
```

Correct the expected strings to what the Rust actually produces, including its `and` / oxford-comma behaviour and how it truncates a long list. Do **not** change the Rust to match the test.

Expected once corrected: PASS.

- [ ] **Step 4: Mutate to confirm the test can fail**

Change one expected string to `"nonsense"`, re-run, confirm FAIL, change it back.

- [ ] **Step 5: Commit**

```bash
cd /home/rakeshgangwar/Projects/supermessage
git add android/core/
git commit -m "Android: prove the boundary answers on a device"
```

---

### Task 4: `:kit` and the rule that defines it

**Files:**
- Modify: `android/kit/build.gradle.kts` (replace placeholder)
- Create: `android/kit/src/main/AndroidManifest.xml`
- Create: `android/kit/src/test/kotlin/dev/supermessage/kit/ModuleShapeTest.kt`

**Interfaces:**
- Consumes: `:core` from Task 2.
- Produces: a `:kit` module that `:app` depends on. Empty of logic by design.

- [ ] **Step 1: Write the module build file**

Replace `android/kit/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.supermessage.kit"
    compileSdk = 36
    defaultConfig { minSdk = 31 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin { jvmToolchain(21) }
    sourceSets["main"].kotlin.srcDir("src/main/kotlin")
    sourceSets["test"].kotlin.srcDir("src/test/kotlin")
}

dependencies {
    api(project(":core"))
    testImplementation(libs.junit)
}

// kit importing no Compose is not tidiness. It is what keeps the state layer
// testable on the JVM without an emulator, and what stops view code leaking
// into it. A rule a build can check is a rule; a rule in a document is a hope.
configurations.configureEach {
    resolutionStrategy.eachDependency {
        check(!requested.group.startsWith("androidx.compose")) {
            ":kit must not depend on Compose (${requested.group}:${requested.name}) — " +
                "see docs/superpowers/specs/2026-08-20-android-scaffold-design.md §3"
        }
    }
}
```

- [ ] **Step 2: Write the manifest**

Create `android/kit/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" />
```

- [ ] **Step 3: Write a test that proves the module wires to core**

Create `android/kit/src/test/kotlin/dev/supermessage/kit/ModuleShapeTest.kt`:

```kotlin
package dev.supermessage.kit

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * kit is empty of logic in this pass. This test asserts the one property the
 * module exists to have right now: its unit tests run on the JVM, with no
 * emulator and no device.
 *
 * It does NOT call into Core. Doing so would load the .so and defeat the
 * point — the stores that arrive here later are tested against fakes.
 */
class ModuleShapeTest {

    @Test
    fun theseTestsRunOnAPlainJvm() {
        val vendor = System.getProperty("java.vm.name") ?: ""
        assertTrue(
            "expected a JVM, got '$vendor' — has this test been moved to androidTest?",
            vendor.isNotEmpty() && !vendor.contains("Dalvik"))
    }
}
```

- [ ] **Step 4: Run it**

Run: `cd android && ./gradlew :kit:test`
Expected: PASS, and fast — no emulator involved.

- [ ] **Step 5: Prove the Compose ban fires — the falsification step**

```bash
cd /home/rakeshgangwar/Projects/supermessage/android
# temporarily add a Compose dependency
sed -i 's|    testImplementation(libs.junit)|    testImplementation(libs.junit)\n    implementation("androidx.compose.ui:ui:1.7.0")|' kit/build.gradle.kts
./gradlew :kit:dependencies --configuration debugRuntimeClasspath
```

Expected: FAIL with `:kit must not depend on Compose (androidx.compose.ui:ui)`.

Then revert:

```bash
sed -i '/implementation("androidx.compose.ui:ui:1.7.0")/d' kit/build.gradle.kts
./gradlew :kit:test
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
cd /home/rakeshgangwar/Projects/supermessage
git add android/kit/
git commit -m "Android: kit, and the rule that defines it"
```

---

### Task 5: The width rule, as a pure function

**Files:**
- Modify: `android/app/build.gradle.kts` (replace placeholder)
- Create: `android/app/src/main/kotlin/dev/supermessage/PaneLayout.kt`
- Create: `android/app/src/test/kotlin/dev/supermessage/PaneLayoutTest.kt`
- Create: `android/app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `:kit` from Task 4.
- Produces: `dev.supermessage.paneCountFor(width: Dp): Int`, returning 1, 2 or 3. Task 6 calls it.

- [ ] **Step 1: Write the app module build file**

Replace `android/app/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.supermessage"
    compileSdk = 36

    defaultConfig {
        // NOT dev.supermessage.app — that is the Tauri build's id, and reusing
        // it means the two cannot be installed side by side.
        applicationId = "dev.supermessage"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.9"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin { jvmToolchain(21) }
    sourceSets["main"].kotlin.srcDir("src/main/kotlin")
    sourceSets["test"].kotlin.srcDir("src/test/kotlin")
    sourceSets["androidTest"].kotlin.srcDir("src/androidTest/kotlin")
}

dependencies {
    implementation(project(":kit"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.adaptive)
    implementation(libs.adaptive.layout)
    implementation(libs.adaptive.navigation)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
```

- [ ] **Step 2: Write the failing test**

Create `android/app/src/test/kotlin/dev/supermessage/PaneLayoutTest.kt`:

```kotlin
package dev.supermessage

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rule iOS paid for. From RootView.swift:
 *
 *   `sizeClass == .regular` was the first answer and it is wrong on the device
 *   that exposed it. In portrait at 834 points the inspector was laid out at
 *   x=850.5: present in the accessibility tree, off the side of the screen.
 *
 * WindowWidthSizeClass.Expanded begins at 840dp, so the default pane directive
 * would call an 834-point tablet portrait "expanded" and lay out three panes
 * where two fit. That is the case this file exists for.
 */
class PaneLayoutTest {

    @Test
    fun aPhoneInPortraitGetsOnePane() {
        assertEquals(1, paneCountFor(411.dp))
    }

    @Test
    fun aTabletInPortraitGetsTwoPanesNotThree() {
        // The regression. If this returns 3, the info pane is off-screen.
        assertEquals(2, paneCountFor(840.dp))
    }

    @Test
    fun theBoundaryBelowThreePanesIsExclusive() {
        assertEquals(2, paneCountFor(999.dp))
        assertEquals(3, paneCountFor(1000.dp))
    }

    @Test
    fun aTabletInLandscapeGetsThreePanes() {
        assertEquals(3, paneCountFor(1200.dp))
    }

    @Test
    fun theBoundaryBelowTwoPanesIsExclusive() {
        assertEquals(1, paneCountFor(599.dp))
        assertEquals(2, paneCountFor(600.dp))
    }
}
```

- [ ] **Step 3: Run it to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest`
Expected: FAIL — `Unresolved reference: paneCountFor`.

- [ ] **Step 4: Write the minimal implementation**

Create `android/app/src/main/kotlin/dev/supermessage/PaneLayout.kt`:

```kotlin
package dev.supermessage

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Roster, a readable timeline, and a panel, none squeezed to uselessness. */
val ThreePaneWidth: Dp = 1000.dp

/** Roster beside a timeline, with the panel as a sheet over them. */
val TwoPaneWidth: Dp = 600.dp

/**
 * How many panes fit in [width], measured rather than inferred.
 *
 * Deliberately not derived from WindowWidthSizeClass: its Expanded bucket
 * starts at 840dp, and iOS found the info panel laid out off the side of an
 * iPad at 834 points. Measuring is the only honest answer to "is there room".
 */
fun paneCountFor(width: Dp): Int = when {
    width >= ThreePaneWidth -> 3
    width >= TwoPaneWidth -> 2
    else -> 1
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd android && ./gradlew :app:testDebugUnitTest`
Expected: PASS, 5 tests.

- [ ] **Step 6: Mutate to confirm the 840dp test can fail**

Temporarily change `ThreePaneWidth` to `840.dp`, re-run, confirm `aTabletInPortraitGetsTwoPanesNotThree` FAILS, then change it back and re-run to green. This is the mutation that makes it a regression test rather than a restatement.

- [ ] **Step 7: Write the manifest**

Create `android/app/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application
        android:label="Supermessage"
        android:supportsRtl="true"
        android:theme="@style/Theme.Material3.DayNight.NoActionBar">
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:configChanges="orientation|screenSize|screenLayout|keyboardHidden">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

`configChanges` includes `screenSize|screenLayout` so a rotation resizes the
composition rather than recreating the Activity — the pane rule then sees the
new width directly.

- [ ] **Step 8: Commit**

```bash
cd /home/rakeshgangwar/Projects/supermessage
git add android/app/
git commit -m "Android: the pane rule is a measured width, not a size class"
```

---

### Task 6: The adaptive shell

**Files:**
- Create: `android/app/src/main/kotlin/dev/supermessage/RootScaffold.kt`
- Create: `android/app/src/main/kotlin/dev/supermessage/MainActivity.kt`
- Create: `android/app/src/androidTest/kotlin/dev/supermessage/RootScaffoldTest.kt`

**Interfaces:**
- Consumes: `paneCountFor(width: Dp): Int` from Task 5.
- Produces: `@Composable fun RootScaffold(modifier: Modifier = Modifier)`. Test tags `"pane-roster"`, `"pane-timeline"`, `"pane-info"`.

- [ ] **Step 1: Write the failing instrumented test**

Create `android/app/src/androidTest/kotlin/dev/supermessage/RootScaffoldTest.kt`:

```kotlin
package dev.supermessage

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Geometry, not existence.
 *
 * A test once asserted the room-info panel existed while it was laid out off
 * the side of an iPad — present in the tree, invisible on the screen. So these
 * assert assertIsDisplayed() and check the reported bounds, never merely
 * assertExists().
 */
class RootScaffoldTest {

    @get:Rule val compose = createComposeRule()

    private fun shellOfWidth(width: Int) {
        compose.setContent {
            Box(Modifier.size(width.dp, 800.dp)) { RootScaffold() }
        }
    }

    @Test
    fun aPhoneShowsTheRosterAndNoInfoPane() {
        shellOfWidth(411)
        // The roster is the stack's root, and it is on screen at launch —
        // not behind a toggle nobody has reason to look for.
        compose.onNodeWithTag("pane-roster").assertIsDisplayed()
        compose.onNodeWithTag("pane-info").assertDoesNotExist()
    }

    @Test
    fun aTabletInPortraitShowsTwoPanesAndNoInfoPane() {
        shellOfWidth(840)
        compose.onNodeWithTag("pane-roster").assertIsDisplayed()
        compose.onNodeWithTag("pane-timeline").assertIsDisplayed()
        // The regression: at 840dp the default directive would place three.
        compose.onNodeWithTag("pane-info").assertDoesNotExist()
    }

    @Test
    fun aTabletInLandscapeShowsAllThreeOnScreen() {
        shellOfWidth(1200)
        compose.onNodeWithTag("pane-roster").assertIsDisplayed()
        compose.onNodeWithTag("pane-timeline").assertIsDisplayed()
        compose.onNodeWithTag("pane-info").assertIsDisplayed()

        // Bounds, not presence: the iPad fault was an on-tree, off-screen pane.
        val info = compose.onNodeWithTag("pane-info")
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            "info pane starts at ${info.left}, outside the 1200dp shell",
            info.left >= 0f && info.right <= 1200f * compose.density.density)
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd android && ./gradlew :app:connectedDebugAndroidTest`
Expected: FAIL — `Unresolved reference: RootScaffold`.

- [ ] **Step 3: Write the shell**

Create `android/app/src/main/kotlin/dev/supermessage/RootScaffold.kt`:

```kotlin
package dev.supermessage

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The shell, and the one decision it makes.
 *
 * Panes are placeholders in this pass: each reports its own measured width, so
 * the adaptation is visible and testable before there is any real data.
 *
 * The width is measured here, at the top, because this is the only place that
 * knows the window's width — a pane reports its own.
 */
@Composable
fun RootScaffold(modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val panes = paneCountFor(maxWidth)
        Row(Modifier.fillMaxSize()) {
            // The roster is on screen at launch in every configuration. On a
            // phone it is the stack's root; on a tablet it sits beside the
            // timeline. It is never behind a toggle.
            Pane("pane-roster", "Roster", RosterWidth, maxWidth)
            if (panes >= 2) Pane("pane-timeline", "Timeline", null, maxWidth)
            if (panes >= 3) Pane("pane-info", "Room info", InfoWidth, maxWidth)
        }
    }
}

private val RosterWidth: Dp = 320.dp
private val InfoWidth: Dp = 320.dp

@Composable
private fun Pane(tag: String, label: String, fixed: Dp?, shellWidth: Dp) {
    val sizing = if (fixed != null) Modifier.width(fixed) else Modifier.weight(1f)
    Column(
        Modifier
            .then(sizing)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp)
            .testTag(tag)
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Text("shell $shellWidth", style = MaterialTheme.typography.bodySmall)
    }
}
```

Note: `Modifier.weight` is a `RowScope` member. If the compiler rejects the
`Pane` signature above because it is not in `RowScope`, make `Pane` an
extension on `RowScope` — do not delete the weight and give the timeline a
fixed width, which would break the 1200dp bounds assertion.

- [ ] **Step 4: Write the entry point**

Create `android/app/src/main/kotlin/dev/supermessage/MainActivity.kt`:

```kotlin
package dev.supermessage

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface { RootScaffold() }
            }
        }
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd android && ./gradlew :app:connectedDebugAndroidTest`
Expected: PASS, 3 tests.

- [ ] **Step 6: Mutate to confirm the 840dp case can fail**

Temporarily change the shell to `if (panes >= 2)` for the info pane, re-run,
confirm `aTabletInPortraitShowsTwoPanesAndNoInfoPane` FAILS, then change back.

- [ ] **Step 7: Look at it**

```bash
cd /home/rakeshgangwar/Projects/supermessage/android
./gradlew :app:installDebug
adb shell am start -n dev.supermessage/.MainActivity
```

Rotate the emulator. The pane count should change, and the reported shell
width should match what the rule expects.

- [ ] **Step 8: Commit**

```bash
cd /home/rakeshgangwar/Projects/supermessage
git add android/app/
git commit -m "Android: the adaptive shell, phone and tablet"
```

---

### Task 7: Swap the placeholder Row for the real scaffold

**Files:**
- Modify: `android/app/src/main/kotlin/dev/supermessage/RootScaffold.kt`
- Modify: `android/app/src/androidTest/kotlin/dev/supermessage/RootScaffoldTest.kt` (add one test)

**Interfaces:**
- Consumes: `paneCountFor(width: Dp): Int` from Task 5, and Task 6's test tags.
- Produces: the same `RootScaffold(modifier: Modifier)` signature. Task 6's three tests are the contract and must stay green through this swap.

This task exists because Task 6 proved the *rule* with a `Row`. The rule is the
part iOS paid for; the component is a second decision with its own failure
modes. Doing them separately means a red test points at one of them, not both.

- [ ] **Step 1: Write the failing test for rule 2**

§4.2 rule 2: rotating from landscape to portrait with the panel open must
collapse it, not strand it where it no longer fits.

Add to `RootScaffoldTest.kt`:

```kotlin
    @Test
    fun narrowingCollapsesAnOpenInfoPane() {
        var width by mutableStateOf(1200.dp)
        compose.setContent {
            Box(Modifier.size(width, 800.dp)) { RootScaffold() }
        }
        compose.onNodeWithTag("pane-info").assertIsDisplayed()

        // The rotation. iOS left the inspector laid out at x=850.5 on a screen
        // 834 points wide: present in the tree, off the side of the screen.
        width = 840.dp
        compose.waitForIdle()

        compose.onNodeWithTag("pane-info").assertDoesNotExist()
    }
```

Add the imports `androidx.compose.runtime.getValue`,
`androidx.compose.runtime.setValue`, `androidx.compose.runtime.mutableStateOf`.

- [ ] **Step 2: Run it to verify it fails**

Run: `cd android && ./gradlew :app:connectedDebugAndroidTest --tests "*narrowingCollapses*"`

Expected: this may already PASS against Task 6's `Row`, because that shell is
stateless — it recomputes panes from the measured width every frame and has no
"open" state to strand. **If it passes, say so and keep it**: it is the
regression test that stops the scaffold in Step 3 from introducing the fault.
That is the honest outcome, not a failure of the task.

- [ ] **Step 3: Adopt `ListDetailPaneScaffold` with a custom directive**

Replace the `Row` in `RootScaffold.kt`:

```kotlin
@Composable
fun RootScaffold(modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val panes = paneCountFor(maxWidth)
        val navigator = rememberListDetailPaneScaffoldNavigator<Nothing>(
            scaffoldDirective = directiveFor(panes),
        )

        // Rule 2: when the shell narrows past three panes, an open info pane
        // must go away rather than be laid out where it no longer fits.
        LaunchedEffect(panes) {
            if (panes < 3 && navigator.currentDestination?.pane
                    == ListDetailPaneScaffoldRole.Extra) {
                navigator.navigateBack()
            }
        }

        ListDetailPaneScaffold(
            directive = navigator.scaffoldDirective,
            value = navigator.scaffoldValue,
            listPane = { Pane("pane-roster", "Roster", maxWidth) },
            detailPane = { Pane("pane-timeline", "Timeline", maxWidth) },
            extraPane = if (panes >= 3) {
                { Pane("pane-info", "Room info", maxWidth) }
            } else null,
        )
    }
}

/**
 * The directive is built from our measured pane count, never from
 * calculatePaneScaffoldDirective().
 *
 * That default derives pane count from WindowWidthSizeClass, whose Expanded
 * bucket begins at 840dp — and iOS found the info panel off the side of an
 * iPad at 834 points. Accepting the default reproduces a bug we already have
 * the postmortem for. See PaneLayout.kt.
 */
private fun directiveFor(panes: Int) = PaneScaffoldDirective.Default.copy(
    maxHorizontalPartitions = panes,
)
```

- [ ] **Step 4: Run the whole suite**

Run: `cd android && ./gradlew :app:connectedDebugAndroidTest`
Expected: PASS, 4 tests. Task 6's three are unchanged and are the contract —
if any of them went red, the scaffold changed behaviour the rule had fixed.

- [ ] **Step 5: Mutate to confirm the directive is load-bearing**

Replace `directiveFor(panes)` with
`calculatePaneScaffoldDirective(currentWindowAdaptiveInfo())`, re-run, and
confirm `aTabletInPortraitShowsTwoPanesAndNoInfoPane` FAILS at 840dp. Then put
it back. This is the mutation that proves §4.1 is a real constraint and not a
comment.

- [ ] **Step 6: Commit**

```bash
cd /home/rakeshgangwar/Projects/supermessage
git add android/app/
git commit -m "Android: the real scaffold, still refusing the default directive"
```

---

## Self-Review

**Spec coverage:**

| Spec section | Task |
|---|---|
| §1 module graph | 1 |
| §2 JNA `@aar` | 2 step 1 |
| §2 jniLibs guard | 2 steps 1, 3, 4 |
| §3 kit Android library, JVM tests | 4 |
| §3 Compose ban enforced in build | 4 steps 1, 5 |
| §4 `ListDetailPaneScaffold` | 7 |
| §4.1 measured width, 1000dp | 5 |
| §4.2 rule 1 — roster on screen at launch | 6 step 3, tested step 1 |
| §4.2 rule 2 — narrowing collapses info | 7 steps 1, 3 |
| §4.2 rule 3 — measure, do not infer | 5 |
| §5 `peopleLabel` proof of life | 3 |
| §5 geometry not existence | 6 step 1 |
| §5 three widths incl. 840dp | 5 step 2, 6 step 1 |
| §6 sequence | Tasks map 1:1 |

**One sequencing note, recorded rather than hidden:**

§4 specifies `ListDetailPaneScaffold`, and Task 6 builds a plain `Row` before
Task 7 swaps it in. That is deliberate. The pane *rule* is the load-bearing
part and the part iOS paid for; the component brings its own navigator, back
handling and animation. Implementing them in one task would mean a red test
could be either. Splitting them gives Task 7 a suite that already passes as its
contract — the swap has something to be checked by. Both tasks are in this
plan; neither is deferred.

**Placeholder scan:** clean. Every code step carries real code. Task 3 step 3 deliberately instructs the implementer to read `people_label` and correct the expected strings rather than guessing — that is an instruction with a command attached, not a TBD.

**Type consistency:** `paneCountFor(width: Dp): Int` is defined in Task 5 step 4 and consumed in Task 6 step 3 under the same name and signature. Test tags `pane-roster` / `pane-timeline` / `pane-info` match between Task 6 step 1 and step 3. Catalog aliases used in Tasks 2, 4 and 5 are all declared in Task 1 step 3.
