package dev.supermessage.kit

import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import uniffi.supermessage_core.FirstRunStep
import uniffi.supermessage_ffi.CoreInterface

/**
 * The demo's rules, through the boundary (real FFI, host library). The rules are the core's
 * (`first_run.rs`, which states the same test in Rust); what is pinned here is that this host
 * carries them faithfully and holds nothing else.
 */
class FirstRunDemoTest {
    @Before
    fun hostCore() = HostCore.ensureBuilt("FirstRunDemoTest")

    @Test
    fun itOnlyGetsPastTheCardOnTheReadersAnswer() {
        val demo = FirstRunDemo()
        assertEquals(FirstRunStep.GREETING, demo.step.value)
        assertFalse("nothing to answer while Atlas is typing", demo.answer("approve"))
        assertEquals(FirstRunStep.GREETING, demo.step.value)
        assertNull(demo.answer.value)

        demo.advance()
        assertEquals(FirstRunStep.ASKING, demo.step.value)
        demo.advance()
        assertEquals(FirstRunStep.WAITING, demo.step.value)
        // Pacing alone never gets past the card.
        demo.advance()
        assertEquals(FirstRunStep.WAITING, demo.step.value)

        assertTrue(demo.answer("approve"))
        assertEquals(FirstRunStep.APPROVED, demo.step.value)
        assertEquals("approve", demo.answer.value)
        assertFalse("a second answer changes nothing", demo.answer("reject"))
        assertEquals("approve", demo.answer.value)
    }

    @Test
    fun anyOptionAdvancesAsOnIos() {
        val demo = FirstRunDemo()
        demo.advance()
        demo.advance()
        assertTrue(demo.answer("deny"))
        assertEquals(FirstRunStep.APPROVED, demo.step.value)
    }

    @Test
    fun theScriptIsTheCores() {
        val script = FirstRunDemo().script
        assertEquals("A sample conversation — nothing here is sent anywhere.", script.banner)
        assertEquals(listOf(1100uL, 900uL), script.stepDelaysMs)
        assertEquals("Atlas", script.agentName)
        assertEquals("Skip", script.skipLabel)
        assertEquals("Get started", script.finishLabel)
    }

    /** The signature is the promise: nothing here could reach the homeserver. */
    @Test
    fun theDemoHasNoClient() {
        val forbidden = listOf(CoreClient::class.java, Session::class.java, CoreInterface::class.java)
        fun forbidden(type: Class<*>) = forbidden.any { it.isAssignableFrom(type) }
        val cls = FirstRunDemo::class.java
        for (c in cls.declaredConstructors) {
            for (p in c.parameterTypes) assertFalse("constructor takes ${p.name}", forbidden(p))
        }
        for (f in cls.declaredFields) {
            assertFalse("field ${f.name}: ${f.type.name}", forbidden(f.type))
        }
        for (m in cls.declaredMethods.filter { !Modifier.isPrivate(it.modifiers) }) {
            for (p in m.parameterTypes) assertFalse("${m.name} takes ${p.name}", forbidden(p))
        }
    }
}
