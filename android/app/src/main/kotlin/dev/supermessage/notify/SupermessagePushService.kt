package dev.supermessage.notify

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.supermessage.AppCore
import dev.supermessage.kit.CoreClient
import java.util.concurrent.atomic.AtomicReference
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
    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        val context = applicationContext
        val url = endpoint.url
        AppCore.scope.launch { AppCore.registrar(context).newEndpoint(url, distributorLabel(context)) }
    }

    /**
     * The connector binds this service for about 5 s (its 5000 ms timer), but a
     * push may take up to [BUDGET_MS]. So the push is only handed to expedited
     * WorkManager here, which keeps the process and a wake lock for as long as
     * the work runs ([PushWorker]), and this returns at once.
     */
    override fun onMessage(message: PushMessage, instance: String) {
        try {
            enqueuePush(applicationContext, message.content)
        } catch (e: Exception) {
            // Whatever the body holds, a push never takes the process down.
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
    }
}

/** What the core made of a push. */
private sealed interface PushOutcome {
    /** Shown as the core worded it, under the event id. */
    data class Show(val note: PlannedNotification, val account: String) : PushOutcome

    /**
     * Nothing posted. Suppressed (Android may always post nothing,
     * `NotificationComposer.swift:344-347`), or not for the account signed in:
     * no one is, or another account is, or who is cannot be told.
     */
    data object Drop : PushOutcome

    /** The signed-in account's, but not had: said generically, and opens its room (`NotificationService.swift:17-19`). */
    data class Generic(val account: String) : PushOutcome
}

/**
 * One push, start to finish (`NotificationService.swift:104-116, 150-181`):
 * restore quietly, check the push is the signed-in account's, ask the core
 * (`notificationFor`), then post what it decided, drop what it suppressed, or
 * say "New message" for what could not be had. In the foreground a push is
 * not shown for the room on screen, or for an event the app already posted
 * (`AppDelegate.swift:95-108`); with no live coordinator it presents. The
 * work runs in [scope], and the wait for it is bounded by [budgetMs]: past
 * it, the generic note goes up (only once the account is known to be the
 * push's) and a late answer is dropped.
 *
 * **Only for [owner], the account the push came in for** (the pusher's
 * account when it arrived, [PushOwner]). Nothing is posted when no one is
 * signed in, when another account is, or when the owner changed (a sign-out)
 * while the push was worked: a signed-out phone, or the next account, never
 * sees the last account's push, not even as "New message".
 */
internal fun deliverPush(
    context: Context,
    push: MatrixPush,
    owner: String?,
    scope: CoroutineScope = AppCore.scope,
    budgetMs: Long = SupermessagePushService.BUDGET_MS,
    core: () -> CoreInterface = { AppCore.core(context) },
    currentOwner: () -> String? = { AppCore.pushOwner(context).account },
): Job = scope.launch {
    val verified = AtomicReference<String?>(null)
    val work = scope.async { decide(context, push, owner, core, verified, currentOwner) }
    val outcome = withTimeoutOrNull(budgetMs) { work.await() }
        ?: verified.get()?.let { PushOutcome.Generic(it) }
        ?: PushOutcome.Drop
    if (outcome == PushOutcome.Drop) return@launch
    // Signed out while it was worked: the sign-out cleared the owner, and
    // withdrew everything, so this must not go up after it.
    if (currentOwner() != owner) return@launch
    try {
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
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // A push never takes the process down.
    }
}

private suspend fun decide(
    context: Context,
    push: MatrixPush,
    owner: String?,
    core: () -> CoreInterface,
    verified: AtomicReference<String?>,
    currentOwner: () -> String?,
): PushOutcome {
    // No account had a pusher when it came: it is no one's.
    if (owner == null) return PushOutcome.Drop
    return try {
        val client = withContext(Dispatchers.IO) { CoreClient(core()) }
        // No session: no reader to tell. iOS has no signed-out extension case.
        if (!client.restoreSessionQuietly()) return PushOutcome.Drop
        // The account the note is for, before it is posted: its buttons check it.
        val account = client.account().userId
        if (account != owner) return PushOutcome.Drop
        verified.set(account)
        try {
            val note = client.notificationFor(push.roomId, push.eventId)
            // Merged before the push is shown, as on iOS (`NotificationService.swift:118-125`);
            // its failure is its own. Only while the push's account is still the one signed in,
            // asked under the snapshot's lock: a sign-out while the note was fetched has reset
            // the snapshot, and this must not put the last account's decision back.
            try {
                AppCore.widgetsDecided(context, note) { currentOwner() == owner && signedInAs(client, owner) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            if (note.suppress != null) PushOutcome.Drop else PushOutcome.Show(plannedFromDecided(note), account)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PushOutcome.Generic(account)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Who is signed in could not be told: not posted for anyone.
        PushOutcome.Drop
    }
}

/** The session is [account]'s still; anything unknown (signed out, no session) is no. */
private suspend fun signedInAs(client: CoreClient, account: String): Boolean = try {
    client.account().userId == account
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    false
}

/**
 * Hand one push to expedited WorkManager (one job per event, so a repeat
 * delivery is not worked twice), stamped with the account the pusher is for
 * ([PushOwner]) and tagged [PushWorker.TAG], so a sign-out cancels it
 * ([cancelQueuedPushes]). Only the room and event ids go in, never the raw
 * body: WorkManager's `Data` holds 10 KB, and the ids are bounded
 * ([MatrixPush.parse]). If WorkManager cannot take it, the push is worked
 * directly in [AppCore.scope], as before: a push is never dropped for want of
 * a scheduler, and never takes the process down.
 */
internal fun enqueuePush(context: Context, bytes: ByteArray) {
    val push = MatrixPush.parse(bytes) ?: return
    val owner = try {
        AppCore.pushOwner(context).account
    } catch (e: Exception) {
        null
    }
    try {
        val request = OneTimeWorkRequestBuilder<PushWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(PushWorker.input(push, owner))
            .addTag(PushWorker.TAG)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork("push:${push.roomId}:${push.eventId}", ExistingWorkPolicy.KEEP, request)
    } catch (e: Exception) {
        deliverPush(context, push, owner)
    }
}

/**
 * Sign-out (RF5): every push still queued is cancelled, so none runs after
 * it, in this process or a later one. A push already running is stopped at
 * post time by its owner check ([deliverPush]).
 */
internal fun cancelQueuedPushes(context: Context) {
    try {
        WorkManager.getInstance(context).cancelAllWorkByTag(PushWorker.TAG)
    } catch (e: Exception) {
        // No scheduler: nothing was queued in it.
    }
}

/**
 * Does the push's work inside the job: restore quietly, check the account,
 * ask the core (`notificationFor`), post what it decided, with the "New
 * message" fallback when the budget runs out ([deliverPush]). Same handler,
 * same one core.
 */
class PushWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val room = inputData.getString(KEY_ROOM) ?: return Result.failure()
        val event = inputData.getString(KEY_EVENT) ?: return Result.failure()
        deliverPush(applicationContext, MatrixPush(room, event, unread = null), inputData.getString(KEY_ACCOUNT)).join()
        return Result.success()
    }

    companion object {
        const val TAG = "push"
        const val KEY_ROOM = "room"
        const val KEY_EVENT = "event"
        const val KEY_ACCOUNT = "account"

        internal fun input(push: MatrixPush, owner: String?): Data = Data.Builder()
            .putString(KEY_ROOM, push.roomId)
            .putString(KEY_EVENT, push.eventId)
            .putString(KEY_ACCOUNT, owner)
            .build()
    }
}
