plugins {
    alias(libs.plugins.android.application)
    // No org.jetbrains.kotlin.android here: AGP 9.3.1 (this repo's version, per
    // Task 1's catalog) has built-in Kotlin support, and applying the separate
    // Kotlin Gradle plugin on top of it is a hard configuration error as of
    // AGP 9.0. See :core's build file, which established this pattern first.
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
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
        versionCode = 2
        versionName = "0.0.11"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures { compose = true }

    // Robolectric needs the merged resources to inflate anything.
    testOptions { unitTests { isIncludeAndroidResources = true } }

    // The debug APK bundled all four ABIs' copies of libsupermessage_ffi.so
    // (matrix-sdk's dependency tree, ~100MB per ABI unstripped) into one
    // 595MB file. Splitting gives each ABI its own APK — the one a tester
    // installs carries only the .so it can actually run — while the
    // universal APK is kept too, since dropping it costs nothing but a
    // slightly longer `assembleDebug` and it is still the only option for an
    // ABI nobody thought to name here.
    //
    // No per-ABI versionCode override (the usual companion to this block,
    // via applicationVariants.all { outputs... }): that scheme exists so the
    // Play Store can tell a device which of several same-versionCode APKs to
    // serve. This project does not publish to Play — a human picks the
    // right file by name and sideloads it — so the extra build-script
    // complexity would have no reader.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            isUniversalApk = true
        }
    }

    // No explicit sourceSet wiring: AGP 9's built-in Kotlin support
    // auto-discovers src/main/kotlin, src/test/kotlin and src/androidTest/kotlin
    // (this repo's AGP 9.3.1). The old AndroidSourceSet.kotlin.srcDir()
    // accessors throw a ClassCastException against this AGP's source set type
    // — the same failure :core's and :kit's build files document.

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    // No kotlin { jvmToolchain(21) }: :core and :kit express the target level
    // once, via compileOptions above, and this module matches them.
}

// Resolves the JNA jar exactly as published, with no artifact transform in
// the way. `isTransitive = false` because only the one artifact is wanted —
// its dependencies already arrive through :core.
val jnaUntransformed = configurations.create("jnaUntransformed") {
    isTransitive = false
}

dependencies {
    implementation(project(":kit"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    // Both tooling artifacts are debug-scoped, and the two halves of that are
    // one decision rather than two.
    //
    // `ui-tooling-preview` was `implementation`, so the annotation shipped in
    // release. It can only move here because the @Preview functions live in
    // `src/debug/kotlin` — a preview in `src/main` would no longer compile.
    // `ui-tooling` is the renderer, which this project never declared at all;
    // without it a @Preview is an annotation nothing draws.
    //
    // R8 was never going to save this: there is no `buildTypes` block in this
    // file, so minification is off and "ship it and let R8 strip it" would
    // have stripped nothing.
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.adaptive)
    implementation(libs.adaptive.layout)
    implementation(libs.adaptive.navigation)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.datastore.preferences)

    testImplementation(libs.junit)
    // SessionViewModelTest drives suspend functions via runTest — kotlinx's
    // own test dispatcher, not this repo's real Dispatchers.IO. :kit exposes
    // kotlinx-coroutines-core transitively (its api dependency), but not the
    // test artifact, so :app needs its own like :kit's test source does.
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.preview.scanner)
    testImplementation(libs.composable.preview.scanner)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)

    // JNA's own bootstrap library, on the test classpath *untransformed*.
    //
    // Three previews reach a type whose class initialiser loads the core —
    // AccountPanel's and NewRoomPanel's — and on a host JVM `Native.load`
    // looks for `libjnidispatch.jnilib` as a classpath **resource**.
    //
    // A plain `testImplementation(libs.jna)` is not enough here, which cost a
    // run to establish. Two JNA artifacts reach an AGP unit-test classpath and
    // neither carries the native: `:core`'s `jna@aar` never had it, because an
    // AAR packages native code as Android jniLibs — and the plain jar arrives
    // as `jna-5.17.0-runtime.jar`, an AGP-transformed copy with the
    // `com/sun/jna/**` natives stripped. Both were checked; both contain zero
    // entries matching `jnidispatch`.
    //
    // So the jar is resolved through a configuration of its own and added as a
    // *file*, which no artifact transform touches.
    jnaUntransformed(libs.jna)
    testRuntimeOnly(files(jnaUntransformed))

    androidTestImplementation(libs.androidx.test.junit)
    // androidx.test.ext:junit 1.2.1 no longer pulls in androidx.test:runner
    // transitively (see :core's build.gradle.kts) — the class named by
    // testInstrumentationRunner above. :app currently gets it transitively
    // through compose-ui-test-junit4, but that is not this module's own
    // dependency to rely on; declared explicitly, as :core does.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}

// Points JNA at a *host* build of libsupermessage_ffi for :app's unit tests,
// the same way :kit's build file does and for the same reason: the
// Android-ABI .so's under core/src/main/jniLibs cannot be loaded by a desktop
// JVM, and Cargo — never this Gradle build — produces the host one at the
// workspace's target/debug.
//
// :app needs it because PreviewScreenshotTest renders every @Preview, and a
// few of those reach a type whose class initialiser loads the core.
tasks.withType<Test>().configureEach {
    // Rendering the previews is opt-in, and the number that decided that is
    // worth keeping: with it on, CI's "Unit tests" step went from ~2 minutes
    // to **31**, and the Android job from ~25 to 46. Locally a serial run was
    // 18 minutes; a GitHub runner is slower because it has four cores to this
    // machine's eight, so `maxParallelForks` resolves to 2 there.
    //
    // So it runs on `main` and not on every pull request — the same trade
    // `ANDROID_ABIS` already makes in that job, and for the same reason. A
    // reviewer who wants the images before merge runs it locally with
    // `-PrenderPreviews=true`, or opens Android Studio, where they are free.
    if (providers.gradleProperty("renderPreviews").orNull != "true") {
        filter { excludeTestsMatching("*PreviewScreenshotTest*") }
    }

    // Each preview pays for its own Robolectric sandbox and there are 48 of
    // them. Half the cores, because the sandboxes are memory-hungry and this
    // shares a machine with Gradle itself.
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)

    systemProperty(
        "jna.library.path",
        layout.projectDirectory.dir("../../target/debug").asFile.absolutePath,
    )
}
