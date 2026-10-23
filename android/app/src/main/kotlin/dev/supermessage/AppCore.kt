package dev.supermessage

import android.content.Context
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import uniffi.supermessage_ffi.Core
import uniffi.supermessage_ffi.CoreInterface

/**
 * The process-wide Rust core.
 *
 * A notification action, and a push that arrives while the app is closed, run
 * with no Activity and so no [SessionViewModel]. They must reach **the same
 * core** the app uses: a second `Core` over the same store would contend for
 * the SDK store lock (the failure iOS avoids by answering "through the app's
 * one core directly", `Supermessage/Platform/AppDelegate.swift:172-180`).
 * So the core is built here, lazily, once per process, and everything —
 * [SessionViewModel], receivers, the push service — takes it from [core].
 *
 * Only the core is shared. The `Session` stays ViewModel-scoped, for the
 * reasons [SessionViewModel] gives.
 *
 * Android runs a component in the application's one process unless the
 * manifest says `android:process`; no component of this app does, and none
 * may: a second process would load a second native library and open a second
 * core over the same store.
 */
object AppCore {
    private val lock = Any()

    @Volatile
    private var built: CoreInterface? = null

    /**
     * For work that outlives any Activity (a receiver's answer, a push). It
     * never cancels: the process ending is its end.
     */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun core(context: Context): CoreInterface {
        built?.let { return it }
        return synchronized(lock) {
            built ?: Core.withSecretStore(
                dataDir = context.applicationContext.filesDir.path,
                store = AndroidSecretStore(context.applicationContext),
            ).also { built = it }
        }
    }

    /** Replaces the core with a fake (or, with `null`, forgets it). Tests only. */
    @VisibleForTesting
    fun installForTest(core: CoreInterface?) {
        synchronized(lock) { built = core }
    }
}
