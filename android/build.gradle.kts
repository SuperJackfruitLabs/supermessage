plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    // No org.jetbrains.kotlin.android: AGP 9.3.1 (this repo's version) has
    // built-in Kotlin support, and applying the separate Kotlin Gradle plugin
    // on top of it is a hard configuration error as of AGP 9.0. See :core's,
    // :kit's, and :app's build.gradle.kts for the full explanation.
    alias(libs.plugins.kotlin.compose) apply false
}
