package dev.supermessage.kit

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * kit is empty of logic in this pass. This test asserts the one property the
 * module exists to have right now: its unit tests run on the JVM, with no
 * emulator and no device.
 *
 * It does NOT call into Core. Doing so would load the .so and defeat the
 * point — the stores that arrive here later are tested against fakes.
 */
class ModuleShapeTest {

    @Test
    fun theseTestsRunOnAPlainJvm() {
        val vendor = System.getProperty("java.vm.name") ?: ""
        assertTrue(
            "expected a JVM, got '$vendor' — has this test been moved to androidTest?",
            vendor.isNotEmpty() && !vendor.contains("Dalvik"))
    }
}
