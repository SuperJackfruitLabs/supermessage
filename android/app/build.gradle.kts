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
        versionCode = 1
        versionName = "0.0.9"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures { compose = true }

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

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
