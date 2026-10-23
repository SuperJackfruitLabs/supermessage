package dev.supermessage

import android.content.Context
import androidx.annotation.VisibleForTesting
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import dev.supermessage.kit.notify.NotificationCoordinator
import dev.supermessage.kit.widgets.WidgetFeed
import dev.supermessage.kit.widgets.WidgetWriting
import dev.supermessage.notify.PushOwner
import dev.supermessage.notify.PushRegistrar
import dev.supermessage.widgets.FileSnapshotStore
import dev.supermessage.widgets.WidgetAccountStamp
import dev.supermessage.widgets.WidgetPublisher
import dev.supermessage.widgets.WidgetRedraw
import dev.supermessage.widgets.WidgetWriteQueue
import uniffi.supermessage_core.NotificationDto
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

    @Volatile
    private var widgetFeed: WidgetFeed? = null

    @Volatile
    private var widgetWriting: WidgetWriting? = null

    @Volatile
    private var widgetRedraw: WidgetRedraw? = null

    @Volatile
    private var widgetPublisher: WidgetPublisher? = null

    @Volatile
    private var processObserved = false

    @Volatile
    private var widgetAccount: WidgetAccountStamp? = null

    /** Whose decisions the widgets' snapshot holds (a widget's button checks it). */
    internal fun widgetAccount(context: Context): WidgetAccountStamp {
        widgetAccount?.let { return it }
        val app = context.applicationContext
        return synchronized(lock) {
            widgetAccount ?: WidgetAccountStamp.inside(app.filesDir).also { widgetAccount = it }
        }
    }

    /**
     * The one writer of the widgets' snapshot (`filesDir/widgets/snapshot.json`):
     * the roster, the open room, every push and every widget button merge
     * through it, under its one lock. Android has one process, so one [WidgetFeed]
     * is the whole of what iOS's file lock across processes is for.
     */
    internal fun widgets(context: Context): WidgetFeed {
        widgetFeed?.let { return it }
        val app = context.applicationContext
        return synchronized(lock) {
            widgetFeed ?: WidgetFeed(FileSnapshotStore.inside(app.filesDir)).also { widgetFeed = it }
        }
    }

    /** The feed as the writers use it: [widgets], or a recording stand-in in a test. */
    internal fun widgetWriter(context: Context): WidgetWriting = widgetWriting ?: widgets(context)

    /** When the widgets are drawn again, from the snapshot [widgets] holds. */
    internal fun widgetRedraw(context: Context): WidgetRedraw {
        widgetRedraw?.let { return it }
        val app = context.applicationContext
        return synchronized(lock) {
            widgetRedraw ?: WidgetRedraw(app, nextChangeMs = { widgets(app).view(it).nextChangeMs?.toLong() })
                .also { widgetRedraw = it }
        }
    }

    /**
     * The one [WidgetPublisher]: when the app asks the snapshot to merge. Process-wide, with
     * its writes on one IO queue, so an Activity's recreation neither restarts the recap nor
     * is held by it. It also takes the card answers ([decisionAnswered]).
     */
    internal fun widgetPublisher(context: Context): WidgetPublisher {
        widgetPublisher?.let { return it }
        val app = context.applicationContext
        return synchronized(lock) {
            widgetPublisher ?: WidgetPublisher(
                WidgetWriteQueue(
                    writer = widgetWriter(app),
                    written = { reload -> widgetRedraw(app).after(reload) },
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                ),
                account = widgetAccount(app),
            ).also {
                widgetPublisher = it
                decisionAnswered = it::decisionAnswered
            }
        }
    }

    /**
     * Follows the app's real foreground and background (`ProcessLifecycleOwner`, which
     * ignores an Activity's recreation) into the widgets' recap, as iOS's `scenePhase`
     * does. Once per process; call from the main thread.
     */
    internal fun observeProcess(context: Context, lifecycle: Lifecycle = ProcessLifecycleOwner.get().lifecycle) {
        synchronized(lock) {
            if (processObserved) return
            processObserved = true
        }
        val publisher = widgetPublisher(context)
        lifecycle.addObserver(ScenePhaseObserver(publisher::activeChanged))
    }

    /**
     * A push the core decided: merged into the snapshot, and the widgets drawn if it is worth it.
     * [stillFor] is asked under the snapshot's lock, just before the merge: false (the push's
     * account signed out, or another signed in) drops the write.
     */
    internal suspend fun widgetsDecided(context: Context, note: NotificationDto, stillFor: suspend () -> Boolean = { true }) {
        val reload = widgetWriter(context).apply(note, stillFor)
        widgetRedraw(context).after(reload)
    }

    /**
     * Called when a decision card's answer has landed from the app, with the
     * room, event and option: the widgets stop offering it. The process's
     * [WidgetPublisher] sets it when it is made.
     */
    @Volatile
    internal var decisionAnswered: (roomId: String, eventId: String, optionId: String) -> Unit = { _, _, _ -> }

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

    /** Replaces the widgets' writer and redraw (or, with `null`, forgets them). Tests only. */
    @VisibleForTesting
    internal fun installWidgetsForTest(writing: WidgetWriting?, redraw: WidgetRedraw? = null) {
        synchronized(lock) {
            widgetFeed = null
            widgetWriting = writing
            widgetRedraw = redraw
            widgetPublisher = null
            widgetAccount = null
            processObserved = false
        }
    }

    /** Replaces the registrar (or, with `null`, forgets it). Tests only. */
    @VisibleForTesting
    internal fun installRegistrarForTest(registrar: PushRegistrar?) {
        synchronized(lock) { this.registrar = registrar }
    }
}
