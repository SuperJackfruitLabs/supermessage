package dev.supermessage.kit.notify

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import uniffi.supermessage_core.NotificationAnswer

/**
 * The three core calls an answer from a notification needs
 * (`NotificationAnswerer.swift:19-84`). Implemented by `CoreClient`.
 */
interface NotificationAnswering {
    /** Picks up the stored session **without sync**; false when there is none. */
    suspend fun restoreSessionQuietly(): Boolean

    suspend fun sendPermissionAnswer(roomId: String, optionId: String)

    suspend fun sendGateDecisionTo(
        roomId: String,
        gateId: String,
        optionId: String,
        comment: String?,
        inReplyTo: String,
        prompt: String,
    )
}

/**
 * Sends an answer straight to its room, with no room open: restore the stored
 * session without sync, then send. A missing session means "not sent".
 *
 * The core call cannot be cancelled, so the wait for it is bounded and the
 * work is not: it runs in [scope] (outside the caller's job) and the caller
 * stops waiting after the budget ("Not a task group", `:55-58`). Cancelling
 * the wait does not cancel the work.
 */
object NotificationAnswerer {
    /** True only when the homeserver accepted the answer inside [budgetMs]. */
    suspend fun send(
        answer: NotificationAnswer,
        client: NotificationAnswering,
        budgetMs: Long,
        scope: CoroutineScope,
    ): Boolean {
        val work = scope.async { attempt(answer, client) }
        return withTimeoutOrNull(budgetMs) { work.await() } ?: false
    }

    private suspend fun attempt(answer: NotificationAnswer, client: NotificationAnswering): Boolean {
        return try {
            if (!client.restoreSessionQuietly()) {
                false
            } else {
                when (answer) {
                    is NotificationAnswer.Permission ->
                        client.sendPermissionAnswer(answer.roomId, answer.optionId)
                    is NotificationAnswer.Gate -> client.sendGateDecisionTo(
                        roomId = answer.roomId,
                        gateId = answer.gateId,
                        optionId = answer.optionId,
                        comment = answer.comment,
                        inReplyTo = answer.gateEventId,
                        prompt = answer.prompt,
                    )
                }
                true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }
}
