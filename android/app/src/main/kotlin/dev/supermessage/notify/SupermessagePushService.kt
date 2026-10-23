package dev.supermessage.notify

import android.content.Context
import android.os.PowerManager
import androidx.annotation.VisibleForTesting
import dev.supermessage.AppCore
import dev.supermessage.kit.CoreClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage
import uniffi.supermessage_core.NotificationDto
import uniffi.supermessage_core.PlannedNotification
import uniffi.supermessage_ffi.CoreInterface
import uniffi.supermessage_ffi.genericNotification
import uniffi.supermessage_ffi.notificationPresentsRemote
import uniffi.supermessage_ffi.plannedFromDecided

/**
 * The UnifiedPush connector's service: endpoints and messages from the
 * distributor, with or without an Activity (G3; the hub's gateway is
 * APNs-only, so Android's pushes come through the distributor's own Matrix
 * gateway). Named so it does not shadow the connector's `PushService`.
 *
 * It runs in the app's one process (no `android:process`, `ManifestTest`),
 * and every callback only hands work to [AppCore.scope]: the core is
 * [AppCore.core], never a second one, and never touched on the main thread.
 */
class SupermessagePushService : PushService() {
    /** The last message's work, so a test can wait for it. */
    @VisibleForTesting
    internal var lastWork: Job? = null

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        val context = applicationContext
        val url = endpoint.url
        AppCore.scope.launch { AppCore.registrar(context).newEndpoint(url, distributorLabel(context)) }
    }

    override fun onMessage(message: PushMessage, instance: String) {
        val context = applicationContext
        // Awake for as long as the push may take; the system may doze otherwise.
        val wake = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "supermessage:push")
            .apply { acquire(BUDGET_MS + 1_000) }
        lastWork = deliverPush(context, message.content).also { job ->
            job.invokeOnCompletion { if (wake.isHeld) wake.release() }
        }
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        val context = applicationContext
        AppCore.scope.launch { AppCore.registrar(context).registrationFailed() }
    }

    override fun onUnregistered(instance: String) {
        val context = applicationContext
        AppCore.scope.launch { AppCore.registrar(context).unregistered() }
    }

    companion object {
        /** How long one push may take, from arrival to a posted (or generic) notification. */
        const val BUDGET_MS = 20_000L

        /**
         * Every push the core decided, posted or not (a suppressed one too).
         * M7 Task 1 feeds the widgets from it, as the iOS extension merges
         * each decided push (`NotificationService.swift:109-112`).
         */
        @Volatile
        var onDecided: (NotificationDto) -> Unit = {}
    }
}

/** What the core made of a push. */
private sealed interface PushOutcome {
    /** Shown as the core worded it, under the event id. */
    data class Show(val note: PlannedNotification, val account: String?) : PushOutcome

    /** Suppressed: Android may always post nothing (`NotificationComposer.swift:344-347`). */
    data object Drop : PushOutcome

    /** Not had: said generically, and opens its room (`NotificationService.swift:17-19`). */
    data class Generic(val account: String?) : PushOutcome
}

/**
 * One push, start to finish (`NotificationService.swift:104-116, 150-181`):
 * restore quietly, ask the core (`notificationFor`), then post what it
 * decided, drop what it suppressed, or say "New message" for what could not
 * be had. In the foreground a push is not shown for the room on screen, or
 * for an event the app already posted (`AppDelegate.swift:95-108`); with no
 * live coordinator it presents. The work runs in [scope], and the wait for it
 * is bounded by [budgetMs]: past it, the generic note goes up and a late
 * answer is dropped.
 */
internal fun deliverPush(
    context: Context,
    bytes: ByteArray,
    scope: CoroutineScope = AppCore.scope,
    budgetMs: Long = SupermessagePushService.BUDGET_MS,
    core: () -> CoreInterface = { AppCore.core(context) },
): Job = scope.launch {
    // The host parses its own transport and nothing more.
    val push = MatrixPush.parse(bytes) ?: return@launch
    val work = scope.async { decide(push, core) }
    val outcome = withTimeoutOrNull(budgetMs) { work.await() } ?: PushOutcome.Generic(account = null)
    val live = AppCore.coordinator
    if (live != null && !notificationPresentsRemote(push.roomId, push.eventId, live.context, live.notified.toList())) {
        return@launch
    }
    when (outcome) {
        is PushOutcome.Show -> AndroidPoster(context, accountId = outcome.account).post(outcome.note)
        PushOutcome.Drop -> Unit
        // No iOS copy for the title: iOS keeps the gateway's own alert title.
        is PushOutcome.Generic -> AndroidPoster(context, accountId = outcome.account)
            .post(genericNotification(push.roomId, push.eventId, "supermessage"))
    }
}

private suspend fun decide(push: MatrixPush, core: () -> CoreInterface): PushOutcome {
    var account: String? = null
    return try {
        val client = withContext(Dispatchers.IO) { CoreClient(core()) }
        if (!client.restoreSessionQuietly()) return PushOutcome.Generic(account = null)
        // The account the note is for, before it is posted: its buttons check it.
        account = try {
            client.account().userId
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val note = client.notificationFor(push.roomId, push.eventId)
        // The widgets' business; it never changes what is posted.
        runCatching { SupermessagePushService.onDecided(note) }
        if (note.suppress != null) PushOutcome.Drop else PushOutcome.Show(plannedFromDecided(note), account)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        PushOutcome.Generic(account)
    }
}
