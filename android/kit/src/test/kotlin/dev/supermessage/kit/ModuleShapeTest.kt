package dev.supermessage.kit

import org.junit.Assert.fail
import org.junit.Test

/**
 * kit is empty of logic in this pass, but the rule it exists to enforce —
 * no Compose — is already load-bearing, and it deserves more than one check.
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
}
