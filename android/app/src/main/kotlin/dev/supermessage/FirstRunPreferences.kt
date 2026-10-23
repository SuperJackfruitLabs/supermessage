package dev.supermessage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * What the welcome and the first-run demo remember, **per install, not per account**
 * (iOS `WelcomeView.swift:4-17`): a second account on the same phone does not see them again.
 *
 * - [welcomeSeen]: the welcome has been passed.
 * - [demoPending]: an interactive sign-in just succeeded and the demo has not run.
 * - [demoSeen]: the demo was shown, finished or skipped, once, ever.
 *
 * Wraps the app's one DataStore (`rosterDataStore` in `MainActivity.kt`); one DataStore per file.
 */
class FirstRunPreferences(private val store: DataStore<Preferences>) {
    private object Keys {
        val WELCOME_SEEN = booleanPreferencesKey("onboarding.welcomeSeen")
        val DEMO_PENDING = booleanPreferencesKey("onboarding.demoPending")
        val DEMO_SEEN = booleanPreferencesKey("onboarding.demoSeen")
    }

    val welcomeSeen: Flow<Boolean> = store.data.map { it[Keys.WELCOME_SEEN] ?: false }
    val demoPending: Flow<Boolean> = store.data.map { it[Keys.DEMO_PENDING] ?: false }
    val demoSeen: Flow<Boolean> = store.data.map { it[Keys.DEMO_SEEN] ?: false }

    suspend fun setWelcomeSeen(value: Boolean) {
        store.edit { it[Keys.WELCOME_SEEN] = value }
    }

    suspend fun setDemoPending(value: Boolean) {
        store.edit { it[Keys.DEMO_PENDING] = value }
    }

    suspend fun setDemoSeen(value: Boolean) {
        store.edit { it[Keys.DEMO_SEEN] = value }
    }

    /** Finishing or skipping: shown once, ever, and no longer queued (`RootView.swift:136-139`). */
    suspend fun finishDemo() {
        store.edit {
            it[Keys.DEMO_SEEN] = true
            it[Keys.DEMO_PENDING] = false
        }
    }

    /** The debug launch's way to show the demo on a signed-in device (iOS `-openAccount`). */
    internal suspend fun queueDemoForDebug() {
        store.edit {
            it[Keys.DEMO_PENDING] = true
            it[Keys.DEMO_SEEN] = false
        }
    }
}
