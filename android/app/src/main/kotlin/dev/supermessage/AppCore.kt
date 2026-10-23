package dev.supermessage

import android.content.Context
import androidx.annotation.VisibleForTesting
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import dev.supermessage.kit.notify.NotificationCoordinator
import dev.supermessage.notify.PushOwner
import dev.supermessage.notify.PushRegistrar
import org.unifiedpush.android.connector.INSTANCE_DEFAULT
import org.unifiedpush.android.connector.UnifiedPush
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
 * The push registrar and the live notification coordinator are shared the
 * same way, for the same reason: a push arrives with no Activity.
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
     * never cancels: the process ending is its end. A failure in one piece of
     * work is logged, not thrown: a background push or answer must never take
     * the process down.
     */
    val scope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, e -> Log.e("supermessage", "background work failed", e) },
    )

    /**
     * The live notification coordinator, while a [SessionViewModel] holds
     * one: what a push consults to stay out of the way of the room on screen
     * and of what was already posted (`notificationPresentsRemote`). `null`
     * in a cold process, where a push simply presents
     * (`AppDelegate.swift:111`, `platform?.presentsRemote(...) ?? true`).
     */
    @Volatile
    var coordinator: NotificationCoordinator? = null

    @Volatile
    private var registrar: PushRegistrar? = null

    /** The one [PushRegistrar]: the Activity asks for push, the push service hands it endpoints. */
    internal fun registrar(context: Context): PushRegistrar {
        registrar?.let { return it }
        val app = context.applicationContext
        return synchronized(lock) {
            registrar ?: PushRegistrar(
                core = { core(app) },
                unregisterDistributor = { UnifiedPush.unregister(app, INSTANCE_DEFAULT) },
                owner = pushOwner(app),
            ).also { registrar = it }
        }
    }

    /** Which account the pusher is for, on disk: the registrar sets it, each push is stamped with it. */
    internal fun pushOwner(context: Context): PushOwner = PushOwner.Preferences(context)

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

    /** Replaces the registrar (or, with `null`, forgets it). Tests only. */
    @VisibleForTesting
    internal fun installRegistrarForTest(registrar: PushRegistrar?) {
        synchronized(lock) { this.registrar = registrar }
    }
}
