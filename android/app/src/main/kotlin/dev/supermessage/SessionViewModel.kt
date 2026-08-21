package dev.supermessage

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.supermessage.kit.CoreClient
import dev.supermessage.kit.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import uniffi.supermessage_ffi.Core
import uniffi.supermessage_ffi.CoreInterface

/**
 * The one place in the Android app that names a filesystem location.
 *
 * A `ViewModel` rather than a `remember` or an `Application` singleton. The
 * manifest suppresses recreation for `orientation|screenSize|screenLayout|
 * keyboardHidden` and nothing else — a locale change, a dark-mode toggle, a
 * font-scale change or process death all recreate the Activity, and under
 * `remember` each of those would rebuild the Rust core and reopen SQLite
 * while the previous `Session`'s drain outlived it. `viewModelScope` also
 * cancels at a defined moment, which a process-scoped session never does.
 *
 * ## Why [onCleared] does nothing beyond `super`
 *
 * `onCleared` only runs when this ViewModel's owner is genuinely finishing
 * — not on any of the recreations the paragraph above lists, since a
 * `ViewModel` is exactly what survives those. Signing the session out here
 * would mean closing the app (the back button, a swiped-away recent-apps
 * entry) silently logs the reader out, which is not a rule this app has
 * anywhere else and would be a regression, not a feature. So there is
 * deliberately nothing to run — and separately, by the time `onCleared` is
 * called, [viewModelScope] is already being cancelled (`ViewModel.clear()`
 * closes its `Closeable`s — what tears `viewModelScope` down — strictly
 * before calling `onCleared()`), so launching a sign-out on it here would
 * not reliably run to completion even if it were the right thing to do.
 * A real "sign out" is a reader action a later task wires to
 * [Session.signOut] directly, not something this lifecycle hook infers.
 */
class SessionViewModel(app: Application) : AndroidViewModel(app) {
    val session: Session = build(
        Core.withSecretStore(
            dataDir = app.filesDir.path,
            store = AndroidSecretStore(app),
        ),
        viewModelScope,
    )

    /**
     * Deliberately empty beyond `super.onCleared()`. See this class's own
     * KDoc above for why a genuine clear must not mean "signed out".
     *
     * **If you are here to add `session.signOut()` because clearing feels
     * like it should sign out: don't.** No test in `SessionViewModelTest`
     * asserts that, and none should — `theSessionReachesTheCoreItWasBuiltWith`
     * is what this class's coverage actually pins (that [build] wires the
     * given `CoreInterface` through to the `Session` it returns), not that
     * clearing the ViewModel signs anyone out. An earlier draft of that
     * suite had exactly that test, asserting a fake `logout()` was called
     * once a test-only teardown ran — it passed by exercising the test
     * harness, not this method, and it was removed once that was noticed.
     */
    override fun onCleared() {
        super.onCleared()
    }

    companion object {
        internal fun build(core: CoreInterface, scope: CoroutineScope): Session =
            Session(client = CoreClient(core), scope = scope)

        /**
         * The JVM-only construction path [SessionViewModelTest] drives.
         *
         * The public constructor above needs a live `Application` (for
         * `filesDir`) and constructs a real `Core`, which loads Rust's
         * native library and opens SQLite — none of which exists on a plain
         * unit test JVM, and Robolectric is deliberately not reached for
         * here (see the Task 1 brief). [forTest] skips both: the same
         * [build] factory the real constructor uses, handed a fake
         * [CoreInterface] and an ordinary [CoroutineScope] instead of
         * `viewModelScope`, wrapped in [Harness] so the test can read
         * [Harness.session] without ever touching `Application` or `Core`.
         */
        internal fun forTest(
            core: CoreInterface,
            scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined),
        ): Harness = Harness(core = core, scope = scope)
    }

    /**
     * The seam [forTest] hands back. `internal`, not `private` — it exists
     * for this module's tests, not for `:app` at large.
     */
    internal class Harness internal constructor(
        private val core: CoreInterface,
        private val scope: CoroutineScope,
    ) {
        /** Built once, from the same [build] factory the real ViewModel uses. */
        val session: Session = build(core, scope)
    }
}
