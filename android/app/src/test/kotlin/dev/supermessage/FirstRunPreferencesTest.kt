package dev.supermessage

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The three per-install keys (iOS `WelcomeView.swift:4-17`): welcome passed, demo queued, demo shown. */
class FirstRunPreferencesTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun store(scope: TestScope) =
        PreferenceDataStoreFactory.create(scope = scope) { tmp.newFile("first-run.preferences_pb") }

    @Test
    fun theKeysAreIosSpelling() = runTest {
        val store = store(this)
        val prefs = FirstRunPreferences(store)
        prefs.setWelcomeSeen(true)
        prefs.setDemoPending(true)
        prefs.setDemoSeen(true)
        val data = store.data.first().asMap().mapKeys { it.key.name }
        assertEquals(
            mapOf("onboarding.welcomeSeen" to true, "onboarding.demoPending" to true, "onboarding.demoSeen" to true),
            data,
        )
    }

    @Test
    fun allThreeDefaultToFalse() = runTest {
        val prefs = FirstRunPreferences(store(this))
        assertEquals(false, prefs.welcomeSeen.first())
        assertEquals(false, prefs.demoPending.first())
        assertEquals(false, prefs.demoSeen.first())
    }

    @Test
    fun finishingSetsSeenAndClearsPending() = runTest {
        val prefs = FirstRunPreferences(store(this))
        prefs.setDemoPending(true)
        prefs.finishDemo()
        assertEquals(true, prefs.demoSeen.first())
        assertEquals(false, prefs.demoPending.first())
    }

    @Test
    fun theDebugLaunchQueuesAgain() = runTest {
        val prefs = FirstRunPreferences(store(this))
        prefs.finishDemo()
        prefs.queueDemoForDebug()
        assertEquals(true, prefs.demoPending.first())
        assertEquals(false, prefs.demoSeen.first())
    }
}
