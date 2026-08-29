package dev.supermessage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The seeding race [rememberSeededHomeserver] exists to close: a value typed
 * while the one-shot read of [RosterPreferences.homeserver] is still
 * pending must survive that read completing, not be silently overwritten
 * by it.
 *
 * [GatedPreferencesStore] makes this deterministic rather than
 * timing-dependent: its `data` flow never emits until [gate] is completed,
 * so "typing while the seed is pending" and "the seed arrives" become two
 * ordered, test-controlled steps instead of a race raced against real
 * wall-clock time.
 */
class SeededHomeserverTest {
    @get:Rule val compose = createComposeRule()

    /**
     * A real [DataStore], not a fake `RosterPreferences` — [updateData]
     * behaves exactly as the real preferences file would (an in-memory
     * [MutableStateFlow] standing in for the file). Only [data]'s emission
     * is gated, and only its *first* emission: this simulates the read that
     * [rememberSeededHomeserver] performs at startup taking real,
     * unpredictable time to resolve.
     */
    private class GatedPreferencesStore(private val gate: CompletableDeferred<Unit>) : DataStore<Preferences> {
        private val current = MutableStateFlow(emptyPreferences())

        // The snapshot is captured at *subscription* time, before awaiting
        // the gate — modelling a read that was already in flight, carrying
        // whatever was on "disk" at the moment it started, and only
        // delivered late. A live emitAll(current) here would instead read
        // whatever the store holds *when the gate opens*, which folds in
        // any write onChange made in the meantime and defeats the point of
        // the test: it would pass regardless of whether the guard in
        // rememberSeededHomeserver is checked before or after its suspend
        // point, because the concurrent write would already be reflected.
        override val data: Flow<Preferences> = flow {
            val snapshot = current.value
            gate.await()
            emit(snapshot)
        }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val updated = transform(current.value)
            current.value = updated
            return updated
        }
    }

    @Test
    fun aValueTypedWhileSeedingIsPendingSurvivesTheSeed() {
        val gate = CompletableDeferred<Unit>()
        val store = GatedPreferencesStore(gate)
        val prefs = RosterPreferences(store)
        // What the gated read will eventually return, once released —
        // simulating a homeserver saved on a previous run.
        runBlocking { prefs.setHomeserver("https://stored.example") }

        lateinit var state: SeededHomeserverState
        compose.setContent {
            state = rememberSeededHomeserver(prefs)
        }
        compose.waitForIdle()

        // The seed's LaunchedEffect is suspended on prefs.homeserver.first(),
        // itself suspended on gate.await(): nothing has been assigned to
        // `state` yet. Edit now, before releasing it.
        compose.runOnUiThread { state.onChange("https://typed-while-pending.example") }
        compose.waitForIdle()

        // Now let the pending read resolve.
        gate.complete(Unit)
        compose.waitForIdle()

        assertEquals("https://typed-while-pending.example", state.value)
    }
}
