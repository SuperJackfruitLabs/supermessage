package dev.supermessage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The stored [ThemeChoice], under the keys iOS uses (`Appearance.swift:111-116`).
 *
 * Its own DataStore (named `"appearance"`, see `MainActivity`): the theme is
 * read before the first frame, and has nothing to do with the roster's file.
 * An unknown stored value falls back to System, Tinted and the house violet,
 * so a file written by a newer build cannot crash this one. The accent is
 * stored as `""` for violet, as on iOS.
 */
class AppearancePreferences(private val store: DataStore<Preferences>) {

    private object Keys {
        val MODE = stringPreferencesKey("appearance.mode")
        val DARK_STYLE = stringPreferencesKey("appearance.darkStyle")
        val ACCENT = stringPreferencesKey("appearance.accent")
    }

    val choice: Flow<ThemeChoice> = store.data.map { prefs ->
        ThemeChoice(
            mode = AppearanceMode.entries.firstOrNull { it.raw == prefs[Keys.MODE] } ?: AppearanceMode.SYSTEM,
            darkStyle = DarkStyle.entries.firstOrNull { it.raw == prefs[Keys.DARK_STYLE] } ?: DarkStyle.TINTED,
            accent = prefs[Keys.ACCENT]?.takeIf { it.isNotEmpty() },
        )
    }

    suspend fun setMode(value: AppearanceMode) {
        store.edit { it[Keys.MODE] = value.raw }
    }

    suspend fun setDarkStyle(value: DarkStyle) {
        store.edit { it[Keys.DARK_STYLE] = value.raw }
    }

    /** `null` is the house violet, stored as `""`. */
    suspend fun setAccent(value: String?) {
        store.edit { it[Keys.ACCENT] = value.orEmpty() }
    }

    /** Writes raw strings, for tests of the fallbacks [setMode] and friends cannot produce. */
    internal suspend fun setRawForTest(mode: String? = null, darkStyle: String? = null, accent: String? = null) {
        store.edit {
            mode?.let { v -> it[Keys.MODE] = v }
            darkStyle?.let { v -> it[Keys.DARK_STYLE] = v }
            accent?.let { v -> it[Keys.ACCENT] = v }
        }
    }
}
