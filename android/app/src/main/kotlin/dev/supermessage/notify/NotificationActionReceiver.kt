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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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
 */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val job = handle(context.applicationContext, intent)
        job.invokeOnCompletion { pending?.finish() }
    }

    internal fun handle(
        context: Context,
        intent: Intent,
        scope: CoroutineScope = AppCore.scope,
        budgetMs: Long = BUDGET_MS,
    ): Job = scope.launch {
        try {
            run(context, intent, scope, budgetMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Nothing more can be done from a receiver.
        }
    }

    private suspend fun run(context: Context, intent: Intent, scope: CoroutineScope, budgetMs: Long) {
        val tag = intent.getStringExtra(AndroidPoster.EXTRA_TAG)
        if (tag != null) NotificationManagerCompat.from(context).cancel(tag, AndroidPoster.NOTIFICATION_ID)
        val info = intent.getBundleExtra(AndroidPoster.EXTRA_USER_INFO) ?: return
        val userInfo = info.keySet().mapNotNull { k -> info.getString(k)?.let { k to it } }.toMap()
        val typed = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(AndroidPoster.KEY_TEXT)?.toString()
        val account = intent.getStringExtra(AndroidPoster.EXTRA_ACCOUNT)
        val poster = AndroidPoster(context, accountId = account)
        when (val response = notificationResponse(intent.getStringExtra(AndroidPoster.EXTRA_ACTION_ID) ?: "", userInfo, typed)) {
            is NotificationResponse.Answer -> {
                val client = CoreClient(AppCore.core(context))
                val guarded = AccountScopedAnswering(client, account) { client.account().userId }
                val sent = NotificationAnswerer.send(response.answer, guarded, budgetMs, scope)
                // Another account's note is dropped without a word.
                if (!sent && !guarded.refusedOtherAccount) {
                    poster.post(answerFailedNotification(roomOf(response)))
                }
            }
            is NotificationResponse.Open -> AndroidPoster.noteFromExtras(intent)?.let { poster.post(it) }
            NotificationResponse.Ignore -> Unit
        }
    }

    private fun roomOf(response: NotificationResponse.Answer): String = when (val a = response.answer) {
        is uniffi.supermessage_core.NotificationAnswer.Permission -> a.roomId
        is uniffi.supermessage_core.NotificationAnswer.Gate -> a.roomId
    }

    companion object {
        /** Inside `goAsync()`'s 10 s; not iOS's 15 s, which is half its system's 30 s. */
        const val BUDGET_MS = 9_000L
    }
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
