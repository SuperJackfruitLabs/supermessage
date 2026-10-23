package dev.supermessage.kit

import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Assert.fail

/**
 * The guard for tests that cross into the real Core (`foldTurn`, `typingView`,
 * `roomCastFrom`): the JVM test process needs the host library on
 * `jna.library.path`. Missing is a failure, not a skip, unless a developer
 * opts out with `-Pkit.allowMissingHostCore=true` (see
 * [RosterArrangementTest.ensureHostCoreIsBuilt], which this repeats).
 */
object HostCore {
    fun ensureBuilt(testName: String) {
        val dir = System.getProperty("jna.library.path")
        val hostLib = listOf("libsupermessage_ffi.dylib", "libsupermessage_ffi.so")
            .map { File(dir, it) }
            .firstOrNull { it.exists() }
            ?: File(dir, "libsupermessage_ffi.<dylib|so>")
        if (hostLib.exists()) return
        if (System.getProperty("kit.allowMissingHostCore").toBoolean()) {
            assumeTrue("kit.allowMissingHostCore is set: skipping $testName because $hostLib is missing.", false)
        } else {
            fail(
                "$testName calls the real Core and needs a host build at $hostLib — run " +
                    "`cargo build -p supermessage-ffi`, or pass -Pkit.allowMissingHostCore=true to skip.",
            )
        }
    }
}
