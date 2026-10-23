package dev.supermessage

import android.content.Context
import dev.supermessage.kit.stores.HiddenEventStorage

/**
 * The ids of messages each account reported, kept on the device so they stay
 * hidden after a restart (open question Q8). One set per user id, under
 * `safety.hiddenEvents.<userId>`: a set is read back only for its own account.
 * The device-wide key iOS and older builds used, `safety.hiddenEvents`, is
 * handed over once by [takeLegacy].
 *
 * Synchronous on purpose: the store reads it when an account reaches
 * signed-in, before any row is drawn. Only event ids are stored.
 */
class HiddenEventsPreferences(context: Context) : HiddenEventStorage {
    private val prefs = context.getSharedPreferences("safety", Context.MODE_PRIVATE)

    override fun load(owner: String): Set<String> = prefs.getStringSet(key(owner), null)?.toSet() ?: emptySet()

    override fun save(owner: String, ids: Set<String>) {
        prefs.edit().putStringSet(key(owner), ids.toSet()).apply()
    }

    override fun clear(owner: String) {
        prefs.edit().remove(key(owner)).apply()
    }

    override fun clearAll() {
        val edit = prefs.edit()
        prefs.all.keys.filter { it == LEGACY_KEY || it.startsWith("$LEGACY_KEY.") }.forEach { edit.remove(it) }
        edit.apply()
    }

    override fun takeLegacy(): Set<String> {
        val ids = prefs.getStringSet(LEGACY_KEY, null)?.toSet() ?: return emptySet()
        prefs.edit().remove(LEGACY_KEY).apply()
        return ids
    }

    private fun key(owner: String) = "$LEGACY_KEY.$owner"

    private companion object {
        const val LEGACY_KEY = "safety.hiddenEvents"
    }
}
