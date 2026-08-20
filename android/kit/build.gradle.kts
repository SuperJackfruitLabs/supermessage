plugins {
    alias(libs.plugins.android.library)
    // No org.jetbrains.kotlin.android here: AGP 9.3.1 (this repo's version, per
    // Task 1's catalog) has built-in Kotlin support, and applying the separate
    // Kotlin Gradle plugin on top of it is a hard configuration error as of
    // AGP 9.0 ("no longer required for Kotlin support since AGP 9.0"). See
    // :core's build file, which established this pattern first.
}

android {
    namespace = "dev.supermessage.kit"
    compileSdk = 36
    // No targetSdk: AGP deprecates it on library modules.

    defaultConfig {
        minSdk = 31
    }

    // No explicit sourceSet wiring: AGP 9's built-in Kotlin support
    // auto-discovers src/main/kotlin and src/test/kotlin (this repo's AGP
    // 9.3.1). The old AndroidSourceSet.kotlin.srcDir()/directories accessors
    // throw a ClassCastException against this AGP's library source set type
    // — the same failure :core's build file documents.

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    // kit is the state layer: the Android counterpart of apple/SupermessageKit.
    // It talks to Core, never to a view toolkit — see the Compose ban below.
    api(project(":core"))

    testImplementation(libs.junit)
}

// kit importing no Compose is not tidiness. It is what keeps the state layer
// testable on the JVM without an emulator, and what stops view code leaking
// into it. A rule a build can check is a rule; a rule in a document is a
// hope. See docs/superpowers/specs/2026-08-20-android-scaffold-design.md §3.
//
// configurations.all is deprecated on this Gradle/AGP combination in favour
// of configurations.configureEach; the effect is the same, this is just the
// modern spelling.
configurations.configureEach {
    resolutionStrategy.eachDependency {
        check(!requested.group.startsWith("androidx.compose")) {
            ":kit must not depend on Compose (${requested.group}:${requested.name}) — " +
                "kit is the state layer and must stay testable on a plain JVM; see " +
                "docs/superpowers/specs/2026-08-20-android-scaffold-design.md §3"
        }
    }
}
