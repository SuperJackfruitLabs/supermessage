plugins {
    alias(libs.plugins.android.library)
    // No org.jetbrains.kotlin.android here: AGP 9.3.1 (this repo's version, per
    // Task 1's catalog) has built-in Kotlin support, and applying the separate
    // Kotlin Gradle plugin on top of it is a hard configuration error as of
    // AGP 9.0 ("no longer required for Kotlin support since AGP 9.0"). Java/Kotlin
    // target level below is expressed once, via compileOptions.
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
    // No explicit sourceSet wiring needed: AGP 9's built-in Kotlin support
    // auto-discovers src/main/kotlin (this repo's AGP 9.3.1). The old
    // AndroidSourceSet.kotlin.srcDir()/directories accessors throw a
    // ClassCastException against this AGP's library source set type.
    //
    // Same story for src/androidTest/kotlin below: the androidTest source
    // set's kotlin.srcDir() throws the identical ClassCastException, and AGP
    // 9 auto-discovers that directory too, so no wiring is added for it either.

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    // The @aar classifier is mandatory. The plain JAR resolves and compiles,
    // then fails at run time because it carries no Android native support.
    api("${libs.jna.get()}@aar")

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.junit)
    // The brief's two androidTestImplementation lines resolve without
    // androidx.test:runner on the classpath (androidx.test.ext:junit 1.2.1
    // no longer pulls it in transitively), and the instrumentation process
    // crashes with ClassNotFoundException on
    // androidx.test.runner.AndroidJUnitRunner — the class named by
    // testInstrumentationRunner above — without it. Added explicitly.
    androidTestImplementation(libs.androidx.test.runner)
}

// A fresh clone has the generated Kotlin and no libraries behind it, because
// the .so files are gitignored. Gradle never invokes cargo, so without this
// check it assembles a valid APK that dies on the first call into Core with
// UnsatisfiedLinkError — a run-time failure far from its cause. Turning that
// into a build error with the command in it is the whole point.
// Overridable by the same `ANDROID_ABIS` that `scripts/build-android-libs.sh`
// takes, so a build that deliberately produced one ABI is checked for one
// rather than failed for three it never asked for. CI on a pull request does
// exactly that: the emulator is x86_64, no artifact is uploaded, and the other
// three cost thirteen minutes to satisfy a check rather than a consumer.
//
// The DEFAULT stays all four, because the reader this guard exists for is
// someone on a fresh clone who has built nothing — and telling them they are
// fine when three ABIs are missing would give back the UnsatisfiedLinkError
// this whole task exists to prevent.
val abis: List<String> =
    (System.getenv("ANDROID_ABIS")
        ?: project.findProperty("android.abis") as String?
        ?: "arm64-v8a armeabi-v7a x86_64 x86")
        .split(Regex("[,\\s]+"))
        .filter { it.isNotBlank() }

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
            |Expected: ${abis.joinToString(", ")}
            |
            |The .so files are gitignored — run this once per checkout:
            |
            |  export ANDROID_NDK_HOME="${'$'}HOME/Android/Sdk/ndk/29.0.14206865"
            |  ./scripts/build-android-libs.sh
            |
            |Building a subset is fine as long as both halves agree:
            |
            |  ANDROID_ABIS="x86_64" ./scripts/build-android-libs.sh
            |  ANDROID_ABIS="x86_64" ./gradlew ...
            """.trimMargin()
        }
    }
}

// Attached to the JNI-merge tasks, not preBuild and not the assemble
// lifecycle tasks. preBuild is an ancestor of compileDebugKotlin, which
// :app:testDebugUnitTest reaches through the project(":core") dependency to
// build its JVM classpath — a pure-JVM unit test run needs none of the .so
// files and must not require a 15-minute NDK build in a fresh clone.
//
// assembleDebug/assembleRelease looked right and were not: :app consuming
// :core as a project dependency never invokes :core:assembleDebug at all —
// it resolves straight to :core:mergeDebugJniLibFolders /
// copyDebugJniLibsProjectOnly. And connectedDebugAndroidTest installs via
// assembleDebugAndroidTest, a variant assembleDebug/assembleRelease does not
// cover either. Verified with --dry-run task-graph dumps for all four paths
// that matter (:core:assembleDebug, :app:assembleDebug,
// :core:assembleDebugAndroidTest, :app:assembleDebugAndroidTest) before
// settling on the merge tasks below, which every one of those four actually
// runs through.
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }
    .configureEach { dependsOn(checkJniLibs) }
