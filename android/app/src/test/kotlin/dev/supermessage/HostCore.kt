package dev.supermessage

import java.io.File
import org.junit.Assert.fail

/**
 * The host build of the core, which the preview snapshots need and Gradle
 * never produces.
 *
 * Three previews reach a type whose class initialiser loads the core through
 * JNA from `target/debug`. Cargo puts it there; this build does not. Delete
 * that directory — releasing another app from the same workspace will do it —
 * and the symptom is an `UnsatisfiedLinkError` raised inside a Compose
 * preview, naming neither the cause nor the fix.
 *
 * `:kit` has had this guard since its first test called real Core functions.
 * `:app` did not, which is how a cleared `target/` became a puzzle rather
 * than a sentence.
 */
internal object HostCore {
    /** Fail with instructions when the host library is absent. */
    fun ensureBuilt() {
        val dir = System.getProperty("jna.library.path") ?: return
        // Both extensions. Cargo writes a `.dylib` on macOS and a `.so` on
        // Linux, and checking only for the latter is why `:kit`'s copy of
        // this could never pass on a Mac.
        val names = listOf("libsupermessage_ffi.dylib", "libsupermessage_ffi.so")
        if (names.any { File(dir, it).exists() }) return
        fail(
            "The preview snapshots need a host build of the core in $dir — run " +
                "`cargo build -p supermessage-ffi` from the repo root, then retry. " +
                "Without it three previews fail with an UnsatisfiedLinkError that " +
                "says none of this.",
        )
    }
}
