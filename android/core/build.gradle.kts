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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
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
