package dev.supermessage.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import dev.supermessage.AppCore
import dev.supermessage.kit.CoreClient
import dev.supermessage.kit.notify.NotificationAnswering
import dev.supermessage.kit.notify.NotificationAnswerer
import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import uniffi.supermessage_ffi.CoreInterface
import uniffi.supermessage_core.NotificationResponse
import uniffi.supermessage_ffi.answerFailedNotification
import uniffi.supermessage_ffi.notificationResponse

/**
 * Runs an answer from a notification's button, with no Activity
 * (`AppDelegate.swift:172-180`).
 *
 * - **Off the main thread.** `onReceive` only hands the work to
 *   [AppCore.scope] and keeps the receiver alive with `goAsync()`, whose
 *   limit is 10 s; the answer's budget is [BUDGET_MS], inside it.
 * - **The core decodes the action** (`notificationResponse`), including
 *   "Request changes" with typed text. Anything that cannot be answered
 *   exactly as labelled comes back as `Open`.
 * - **Never another account's.** The account the note was made for rides in
 *   its extras. It must equal the signed-in account, and an unknown account
 *   counts as no match: nothing is sent.
 * - **Cannot start an Activity** (Android 12+ trampoline restriction), so an
 *   `Open` decided here re-posts the note unchanged, for the reader to tap.
 * - The notification is withdrawn at once, as a RemoteInput requires.
 * - **Once per note.** A second delivery for a note whose answer is under way
 *   (a quick double tap, a repeated broadcast) is dropped, so nothing is sent
 *   twice.
 * - **The budget counts from the broadcast**, not from the send: building a
 *   cold core happens inside it. A send that outruns it may still land, so
 *   the reader is told "wasn't sent" only once it has definitely failed.
 */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val receivedAt = SystemClock.elapsedRealtime()
        val pending = goAsync()
        val job = handle(context.applicationContext, intent, receivedAt = receivedAt)
        job.invokeOnCompletion { pending?.finish() }
    }

    internal fun handle(
        context: Context,
        intent: Intent,
        scope: CoroutineScope = AppCore.scope,
        budgetMs: Long = BUDGET_MS,
        clock: () -> Long = SystemClock::elapsedRealtime,
        receivedAt: Long = clock(),
        core: () -> CoreInterface = { AppCore.core(context) },
    ): Job = scope.launch {
        val tag = intent.getStringExtra(AndroidPoster.EXTRA_TAG)
        // A repeat of a note whose answer is under way is dropped.
        if (tag != null && !inFlight.add(tag)) return@launch
        var releasedLater = false
        try {
            releasedLater = run(context, intent, scope, deadline = receivedAt + budgetMs, clock, core) {
                if (tag != null) inFlight.remove(tag)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Nothing more can be done from a receiver.
        } finally {
            if (!releasedLater && tag != null) inFlight.remove(tag)
        }
    }

    /** True when the answer is still under way, and [release] will run when it ends. */
    private suspend fun run(
        context: Context,
        intent: Intent,
        scope: CoroutineScope,
        deadline: Long,
        clock: () -> Long,
        core: () -> CoreInterface,
        release: () -> Unit,
    ): Boolean {
        val tag = intent.getStringExtra(AndroidPoster.EXTRA_TAG)
        if (tag != null) NotificationManagerCompat.from(context).cancel(tag, AndroidPoster.NOTIFICATION_ID)
        val info = intent.getBundleExtra(AndroidPoster.EXTRA_USER_INFO) ?: return false
        val userInfo = info.keySet().mapNotNull { k -> info.getString(k)?.let { k to it } }.toMap()
        val typed = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(AndroidPoster.KEY_TEXT)?.toString()
        val account = intent.getStringExtra(AndroidPoster.EXTRA_ACCOUNT)
        val poster = AndroidPoster(context, accountId = account)
        when (val response = notificationResponse(intent.getStringExtra(AndroidPoster.EXTRA_ACTION_ID) ?: "", userInfo, typed)) {
            is NotificationResponse.Answer -> {
                // The core is built inside the work, so a cold start is inside the budget too.
                val client = LazyCoreAnswering(core)
                val guarded = AccountScopedAnswering(client, account) { client.account().userId }
                val work = NotificationAnswerer.start(response.answer, guarded, scope)
                val left = deadline - clock()
                val sent = if (left > 0) withTimeoutOrNull(left) { work.await() } else null
                val failed = answerFailedNotification(roomOf(response))
                when (sent) {
                    // Another account's note is dropped without a word.
                    false -> if (!guarded.refusedOtherAccount) poster.postFailure(failed)
                    true -> Unit
                    null -> {
                        // Still under way, and it may yet land: say so only if it fails.
                        scope.launch {
                            try {
                                if (!work.await() && !guarded.refusedOtherAccount) poster.postFailure(failed)
                            } finally {
                                release()
                            }
                        }
                        return true
                    }
                }
            }
            is NotificationResponse.Open -> AndroidPoster.noteFromExtras(intent)?.let { poster.postAgain(it) }
            NotificationResponse.Ignore -> Unit
        }
        return false
    }

    private fun roomOf(response: NotificationResponse.Answer): String = when (val a = response.answer) {
        is uniffi.supermessage_core.NotificationAnswer.Permission -> a.roomId
        is uniffi.supermessage_core.NotificationAnswer.Gate -> a.roomId
    }

    companion object {
        /**
         * Inside `goAsync()`'s 10 s, counted from `onReceive`; not iOS's 15 s,
         * which is half its system's 30 s.
         */
        const val BUDGET_MS = 9_000L

        /** The tags of notes whose answer is under way, process-wide. */
        private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()
    }
}

/**
 * The answering calls on the process's one core, which is fetched (built, in
 * a cold process) on the first call, inside the answer's own work and off
 * every cooperative thread.
 */
private class LazyCoreAnswering(private val core: () -> CoreInterface) : NotificationAnswering {
    @Volatile
    private var client: CoreClient? = null

    private suspend fun client(): CoreClient =
        client ?: withContext(Dispatchers.IO) { CoreClient(core()) }.also { client = it }

    suspend fun account() = client().account()

    override suspend fun restoreSessionQuietly(): Boolean = client().restoreSessionQuietly()

    override suspend fun sendPermissionAnswer(roomId: String, optionId: String) =
        client().sendPermissionAnswer(roomId, optionId)

    override suspend fun sendGateDecisionTo(
        roomId: String,
        gateId: String,
        optionId: String,
        comment: String?,
        inReplyTo: String,
        prompt: String,
    ) = client().sendGateDecisionTo(roomId, gateId, optionId, comment, inReplyTo, prompt)
}

/**
 * Restores only when the signed-in account is the one the notification was
 * made for. An answer from account A's notification must never act on B.
 * [expected] null (the feed had not recorded it yet) is no match.
 */
internal class AccountScopedAnswering(
    private val inner: NotificationAnswering,
    private val expected: String?,
    private val signedIn: suspend () -> String?,
) : NotificationAnswering by inner {
    @Volatile
    var refusedOtherAccount = false
        private set

    override suspend fun restoreSessionQuietly(): Boolean {
        if (expected == null) return false
        if (!inner.restoreSessionQuietly()) return false
        val me = try {
            signedIn()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (me != expected) {
            refusedOtherAccount = me != null
            return false
        }
        return true
    }
}
