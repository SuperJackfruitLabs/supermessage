package dev.supermessage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.supermessage.kit.RosterArrangement
import dev.supermessage.kit.RosterChoice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import uniffi.supermessage_core.RosterFilter

/**
 * The settings a reader's device remembers between launches.
 *
 * Takes a `DataStore<Preferences>` rather than a `Context`: the store is
 * this class's only dependency, so a test can hand it one backed by a
 * temp directory and this class never needs a device or Robolectric to
 * be exercised on the JVM.
 */
class RosterPreferences(private val store: DataStore<Preferences>) {

    private object Keys {
        val HOMESERVER = stringPreferencesKey("login.homeserver")
        val VIEW = stringPreferencesKey("roster.view")
        val FILTER = stringPreferencesKey("roster.filter")
        val SHOWS_STATE = booleanPreferencesKey("roster.showsState")
    }

    /**
     * Remembered between sign-in attempts.
     *
     * On iOS this was `@State`, so a failed sign-in — a typo in the
     * password, a homeserver that was briefly down — threw the address
     * away and made the reader type it again to try the thing that was
     * nearly right. Persisting it here is that fix, not a convenience.
     */
    val homeserver: Flow<String> =
        store.data.map { it[Keys.HOMESERVER] ?: "https://id.agentpod.dev" }

    /**
     * Which arrangement the reader chose, by [RosterChoice.name].
     *
     * `enumValueOf` throws on a string it does not recognise, and a
     * preferences file written by a future version of the app — one with
     * a [RosterChoice] this build has never heard of — will contain
     * exactly that. Falling back to [RosterChoice.WAITING] keeps a stale
     * preferences file from crashing the app.
     */
    val view: Flow<RosterChoice> =
        store.data.map { prefs ->
            val raw = prefs[Keys.VIEW]
            if (raw == null) {
                RosterChoice.WAITING
            } else {
                try {
                    enumValueOf<RosterChoice>(raw)
                } catch (_: IllegalArgumentException) {
                    RosterChoice.WAITING
                }
            }
        }

    /**
     * The chip in force. Read through the core, so a key this build does not
     * offer (an older build's Agents or Needs you) reads as All rather than
     * narrowing the list with no chip lit.
     */
    val filter: Flow<RosterFilter> =
        store.data.map { RosterArrangement.filterFromKey(it[Keys.FILTER] ?: "") }

    val showsState: Flow<Boolean> =
        store.data.map { it[Keys.SHOWS_STATE] ?: true }

    suspend fun setHomeserver(value: String) {
        store.edit { it[Keys.HOMESERVER] = value }
    }

    suspend fun setView(value: RosterChoice) {
        store.edit { it[Keys.VIEW] = value.name }
    }

    suspend fun setFilter(value: RosterFilter) {
        store.edit { it[Keys.FILTER] = RosterArrangement.filterKey(value) }
    }

    suspend fun setShowsState(value: Boolean) {
        store.edit { it[Keys.SHOWS_STATE] = value }
    }

    /**
     * Writes a raw, possibly-unrecognised string into the `roster.view`
     * key. Exists only so [RosterPreferencesTest.anUnknownArrangementFallsBack]
     * can simulate a preferences file written by a version of the app with
     * a [RosterChoice] this build does not have — [setView] cannot produce
     * that string, since it only ever writes a real enum's name.
     */
    internal suspend fun setRawViewForTest(raw: String) {
        store.edit { it[Keys.VIEW] = raw }
    }

    /** As [setRawViewForTest], for the `roster.filter` key. */
    internal suspend fun setRawFilterForTest(raw: String) {
        store.edit { it[Keys.FILTER] = raw }
    }
}
