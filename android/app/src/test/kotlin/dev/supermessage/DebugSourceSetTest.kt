package dev.supermessage

import dev.supermessage.previews.PreviewFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The debug source set is actually compiled.
 *
 * **This test exists because the failure it catches is silent.** The previews
 * live in `src/debug/kotlin`, which `app/build.gradle.kts` does not wire up
 * explicitly — AGP 9.3.1's built-in Kotlin support is expected to discover
 * it, the way it discovers `src/main/kotlin` and `src/test/kotlin`. If it
 * does not, the previews are simply never compiled: no error, no warning,
 * and a directory of dead files that look like work. Nobody on this machine
 * can open Android Studio to notice.
 *
 * Reading one constant from that source set turns the silence into a compile
 * error in `:app:testDebugUnitTest`, which CI already runs.
 *
 * Unit tests compile against the debug variant (`testBuildType` defaults to
 * `debug`), which is why a test in `src/test` can see `src/debug` at all.
 */
class DebugSourceSetTest {
    @Test
    fun theDebugSourceSetCompiles() {
        assertEquals("__supermessage_android_preview_fixture_do_not_ship__", PreviewFixtures.MARKER)
    }

    /**
     * The fixtures are the values the core would have produced, and the two
     * that carry a design-language rule say what their names say.
     *
     * A fixture that has drifted from its name is worse than no fixture: a
     * preview built on it demonstrates the wrong thing, convincingly. Exactly
     * one roster row may be pending, because amber means a pending decision
     * and nothing else — and a second pending row would make the "every
     * state" preview quietly stop being the check it is described as.
     */
    @Test
    fun exactlyOneRosterFixtureIsPending() {
        val pending = PreviewFixtures.roster.count { it.preview?.pending == true }
        assertEquals(1, pending)
        assertTrue(PreviewFixtures.roomNeedsYou.preview?.pending == true)
    }

    /** `unbreakable` has to actually be unbreakable. */
    @Test
    fun theUnbreakableBodyHasNoWhitespace() {
        val body = PreviewFixtures.unbreakable.item.body ?: ""
        assertTrue(body.isNotEmpty())
        assertTrue(body.none { it.isWhitespace() })
        assertTrue(body.length > 100)
    }

    /**
     * The hostile event type carries the override that makes it hostile.
     *
     * U+202E RIGHT-TO-LEFT OVERRIDE is the whole fixture. Without it this is
     * just a long string, and the preview named for it proves nothing.
     */
    @Test
    fun theHostileEventTypeIsActuallyHostile() {
        assertTrue(PreviewFixtures.HOSTILE_EVENT_TYPE.contains('‮'))
    }

    /**
     * Every timeline fixture carries a view the core would have decided.
     *
     * The app decides nothing, so a fixture without a view is not a state the
     * app can be in.
     */
    @Test
    fun everyTimelineFixtureCarriesAView() {
        val rows = PreviewFixtures.history +
            listOf(
                PreviewFixtures.ownFailed, PreviewFixtures.unbreakable,
                PreviewFixtures.encrypted, PreviewFixtures.image,
                PreviewFixtures.attachment, PreviewFixtures.membership,
                PreviewFixtures.replyToNothing,
            )
        rows.forEach { row ->
            assertTrue(
                "a fixture with ItemView.None is not a state the app can be in",
                row.view !is uniffi.supermessage_core.ItemView.None,
            )
        }
    }
}
