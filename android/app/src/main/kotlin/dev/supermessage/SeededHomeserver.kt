package dev.supermessage

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The homeserver field's live value.
 *
 * Seeded once from [RosterPreferences.homeserver] — not bound continuously
 * to it, and not re-read on every keystroke — because a `TextField` is a
 * controlled component: binding its displayed value straight to the store's
 * `Flow` created a race where a second keystroke arriving before the first
 * write's re-emission landed fired `onChange` against a stale value and
 * silently dropped the character in between.
 *
 * Extracted from `MainActivity` into its own seam, the way `LoginScreen` was
 * pulled out from under `Session`: this class and [rememberSeededHomeserver]
 * depend only on [RosterPreferences], never on `Session` or `Core`, which is
 * what lets [SeededHomeserverTest] drive the seeding race directly.
 */
class SeededHomeserverState internal constructor(
    private val state: MutableState<String>,
    private val userEdited: MutableState<Boolean>,
    private val prefs: RosterPreferences,
    private val scope: CoroutineScope,
) {
    val value: String get() = state.value

    fun onChange(newValue: String) {
        userEdited.value = true
        state.value = newValue
        scope.launch { prefs.setHomeserver(newValue) }
    }
}

@Composable
fun rememberSeededHomeserver(prefs: RosterPreferences): SeededHomeserverState {
    val state = rememberSaveable { mutableStateOf("") }
    val userEdited = rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(prefs) {
        val stored = prefs.homeserver.first()
        // Checked *after* the suspend point resumes, not only before it
        // started: an earlier version of this guard checked once, entered
        // the suspend, and assigned unconditionally on the way out — a
        // value typed during the wait was silently overwritten by the
        // stale read landing late. Re-checking here is what makes a typed
        // value win, confirmed by SeededHomeserverTest, which fails against
        // the check-before-only shape and passes against this one.
        if (!userEdited.value) {
            state.value = stored
        }
    }

    return remember(prefs) { SeededHomeserverState(state, userEdited, prefs, scope) }
}
