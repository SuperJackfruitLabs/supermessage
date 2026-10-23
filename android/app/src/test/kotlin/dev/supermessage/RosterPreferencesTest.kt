package dev.supermessage

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.supermessage.kit.RosterChoice
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import uniffi.supermessage_core.RosterFilter

class RosterPreferencesTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun prefs(scope: TestScope): RosterPreferences =
        RosterPreferences(
            PreferenceDataStoreFactory.create(scope = scope) {
                tmp.newFile("prefs.preferences_pb")
            })

    /** Defaults come back before anything has been written. */
    @Test
    fun defaultsBeforeAnyWrite() = runTest {
        val p = prefs(this)
        assertEquals("https://id.agentpod.dev", p.homeserver.first())
        assertEquals(RosterChoice.WAITING, p.view.first())
        assertEquals(true, p.showsState.first())
    }

    /** A written value round-trips. */
    @Test
    fun theChosenArrangementSurvives() = runTest {
        val p = prefs(this)
        p.setView(RosterChoice.MACHINE)
        assertEquals(RosterChoice.MACHINE, p.view.first())
    }

    /**
     * The homeserver outlives a failed attempt.
     *
     * It was `@State` on iOS, so a typo in the password threw the address
     * away and made the reader retype something that was nearly right.
     */
    @Test
    fun theHomeserverIsRemembered() = runTest {
        val p = prefs(this)
        p.setHomeserver("https://matrix.example.org")
        assertEquals("https://matrix.example.org", p.homeserver.first())
    }

    /** An unreadable stored arrangement falls back rather than throwing. */
    @Test
    fun anUnknownArrangementFallsBack() = runTest {
        val p = prefs(this)
        p.setRawViewForTest("NotAChoice")
        assertEquals(RosterChoice.WAITING, p.view.first())
    }

    /** The chip chosen is the chip found next launch, through a fresh instance over the same store. */
    @Test
    fun theChipIsRemembered() = runTest {
        val store = PreferenceDataStoreFactory.create(scope = this) { tmp.newFile("chip.preferences_pb") }
        assertEquals(RosterFilter.ALL, RosterPreferences(store).filter.first())
        RosterPreferences(store).setFilter(RosterFilter.UNREAD)
        assertEquals(RosterFilter.UNREAD, RosterPreferences(store).filter.first())
    }

    /** A chip an older build stored but this one does not offer reads as All, never as a hidden narrowing. */
    @Test
    fun aStoredAgentsChipReadsAsAll() = runTest {
        val p = prefs(this)
        p.setRawFilterForTest("agents")
        assertEquals(RosterFilter.ALL, p.filter.first())
    }
}
