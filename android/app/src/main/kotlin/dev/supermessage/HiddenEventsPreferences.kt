package dev.supermessage

import android.content.Context
import dev.supermessage.kit.stores.HiddenEventStorage

/**
 * The ids of messages this reader reported, kept on the device so they stay
 * hidden after a restart (open question Q8; iOS keeps them in `UserDefaults`
 * under the same key).
 *
 * Synchronous on purpose: the store reads it once, when the `Session` is
 * built, before any row is drawn. Only event ids are stored, and `clear` runs
 * on every path back to sign-in.
 */
class HiddenEventsPreferences(context: Context) : HiddenEventStorage {
    private val prefs = context.getSharedPreferences("safety", Context.MODE_PRIVATE)

    override fun load(): Set<String> = prefs.getStringSet(KEY, null)?.toSet() ?: emptySet()

    override fun save(ids: Set<String>) {
        prefs.edit().putStringSet(KEY, ids.toSet()).apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    private companion object {
        const val KEY = "safety.hiddenEvents"
    }
}
