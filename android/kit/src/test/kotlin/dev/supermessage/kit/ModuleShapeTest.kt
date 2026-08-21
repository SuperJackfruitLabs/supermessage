package dev.supermessage.kit

import org.junit.Assert.fail
import org.junit.Test

/**
 * kit gained its first real logic in this pass, but the rule that predates
 * it — no Compose — is already load-bearing, and it deserves more than one
 * check.
 *
 * build.gradle.kts bans Compose by inspecting *declared* dependencies via
 * resolutionStrategy.eachDependency. That is a real check, but it is a
 * single mechanism: anything that puts a Compose class on this module's
 * runtime classpath without going through a dependency Gradle resolves the
 * normal way — a fat jar, a shaded artifact, a future refactor of the ban
 * itself — would slip past it undetected. This test is the second,
 * independent check: it asks the classloader directly, at run time,
 * whether any Compose type is actually reachable from :kit. It does not
 * care how such a type would have gotten here, only that none has.
 *
 * It does NOT call into Core. Doing so would load the .so and defeat the
 * point — the stores that arrive here later are tested against fakes.
 */
class ModuleShapeTest {

    @Test
    fun noComposeTypeIsReachableFromKit() {
        val loader = this::class.java.classLoader!!
        val bannedTypes = listOf(
            "androidx.compose.runtime.Composer",
            "androidx.compose.ui.Modifier",
            "androidx.compose.material3.MaterialTheme",
        )
        for (probe in bannedTypes) {
            try {
                Class.forName(probe, false, loader)
                fail("$probe is on :kit's classpath — the Compose ban has been weakened or bypassed")
            } catch (expected: ClassNotFoundException) {
                // what we want: kit stays testable on a plain JVM, with no
                // view toolkit reachable from its state layer.
            }
        }
    }

    /**
     * The mirror image of the test above. :kit's whole purpose is to sit on
     * top of Core, so a type from Core's generated bindings had better be
     * reachable — the Android sibling of apple/SupermessageKit/Version.swift
     * and its BuildTests: "a constant of the Kit's own would still compile
     * with the dependency removed, and would prove nothing about the
     * structure this file exists to pin."
     *
     * Deliberately a plain classloader probe, not a call into a Core
     * function: calling one would load the .so, which is exactly what the
     * class comment above says this file must not do.
     */
    @Test
    fun aCoreTypeIsReachableFromKit() {
        val loader = this::class.java.classLoader!!
        try {
            Class.forName("uniffi.supermessage_core.SearchResultDto", false, loader)
        } catch (notFound: ClassNotFoundException) {
            fail(
                "uniffi.supermessage_core.SearchResultDto is not reachable from :kit — " +
                    "the dependency on :core's generated bindings is missing or broken"
            )
        }
    }
}
