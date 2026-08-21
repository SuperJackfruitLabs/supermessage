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

    // StreamingText (Task 2) is the first type here that is coroutine-driven,
    // and every later store built on it (LiveStore, EventPump, CoreClient)
    // needs the same artifact — api, not implementation, because
    // StreamingText's constructor takes a CoroutineScope, which makes
    // CoroutineScope part of :kit's own public surface.
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    // RosterArrangement (Task 4) is the first type in :kit whose tests call
    // real Core functions rather than only constructing its plain data
    // classes: rosterState/rosterSections/rosterHiddenInvitations exist
    // solely in Rust, which is the entire reason RosterArrangement is thin.
    // Making that call succeed in a desktop JVM test process needs a JNA
    // bootstrap library that :core's own `libs.jna@aar` classifier does not
    // carry — the AAR packages native code as Android jniLibs, not as the
    // classpath resource JNA's `Native.load` looks for on a host JVM. The
    // plain (non-@aar) jar supplies that resource for tests only; :core's
    // production dependency is unchanged.
    testImplementation(libs.jna)
}

// Points JNA at a *host* build of libsupermessage_ffi.so for :kit's unit
// tests — distinct from the Android-ABI .so's under core/src/main/jniLibs,
// which a desktop JVM cannot load. Cargo (never this Gradle build) produces
// it at the workspace's target/debug. Relative, not absolute, so this
// resolves the same on any clone: two directories up from :kit is the
// workspace root.
//
// Default is failure, not a quiet skip, when that build is missing —
// RosterArrangementTest's own guard fails loudly with the rebuild command,
// the same shape as :core's checkJniLibs. `-Pkit.allowMissingHostCore=true`
// is the deliberate opt-out for a developer who does not want to build Rust
// right now; its name is meant to read clearly in a CI config diff, so a
// green run with tests skipped is never silent about why.
tasks.withType<Test>().configureEach {
    systemProperty(
        "jna.library.path",
        layout.projectDirectory.dir("../../target/debug").asFile.absolutePath,
    )
    systemProperty(
        "kit.allowMissingHostCore",
        (findProperty("kit.allowMissingHostCore") ?: "false").toString(),
    )
}

// kit importing no Compose is not tidiness. It is what keeps the state layer
// testable on the JVM without an emulator, and what stops view code leaking
// into it. A rule a build can check is a rule; a rule in a document is a
// hope. See docs/superpowers/specs/2026-08-20-android-scaffold-design.md §3.
//
// configurations.all is not deprecated; configurations.configureEach is the
// lazy equivalent, deferring its block until a configuration is actually
// realized rather than running eagerly for all of them at configuration
// time. Realized is not the same as resolved — this still runs for a
// configuration nobody ever resolves, as long as something realizes it (e.g.
// Gradle wiring it as another task's input). Preferred here for the
// performance reason, not because .all is going away.
configurations.configureEach {
    resolutionStrategy.eachDependency {
        check(!requested.group.startsWith("androidx.compose")) {
            ":kit must not depend on Compose (${requested.group}:${requested.name}) — " +
                "kit is the state layer and must stay testable on a plain JVM; see " +
                "docs/superpowers/specs/2026-08-20-android-scaffold-design.md §3"
        }
    }
}
