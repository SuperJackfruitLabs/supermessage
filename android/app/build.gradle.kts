plugins {
    alias(libs.plugins.android.application)
    // No org.jetbrains.kotlin.android here: AGP 9.3.1 (this repo's version, per
    // Task 1's catalog) has built-in Kotlin support, and applying the separate
    // Kotlin Gradle plugin on top of it is a hard configuration error as of
    // AGP 9.0. See :core's build file, which established this pattern first.
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
        versionCode = 2
        versionName = "0.0.11"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures { compose = true }

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
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.datastore.preferences)

    testImplementation(libs.junit)
    // SessionViewModelTest drives suspend functions via runTest — kotlinx's
    // own test dispatcher, not this repo's real Dispatchers.IO. :kit exposes
    // kotlinx-coroutines-core transitively (its api dependency), but not the
    // test artifact, so :app needs its own like :kit's test source does.
    testImplementation(libs.kotlinx.coroutines.test)

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
