package dev.supermessage.kit.widgets

import dev.supermessage.kit.notify.NotificationAnswering
import dev.supermessage.kit.notify.NotificationAnswerer
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import uniffi.supermessage_core.PlannedNotification
import uniffi.supermessage_ffi.answerFailedNotification
import uniffi.supermessage_ffi.notificationAnswerForWidget

/**
 * The decisions whose answer is being sent right now, from either surface: a widget's button
 * or the decision's notification. Process-wide and keyed by the decision (room and event), so
 * the one surface cannot send a second answer while the other's is under way. Once an answer
 * lands, each surface withdraws the other's offer (the widget's mark, the notification); once
 * it fails, the decision may be answered again from either.
 */
object DecisionAnswersUnderWay {
    private val underWay: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private fun key(roomId: String, eventId: String) = "$roomId|$eventId"

    /** True when nothing else is answering this decision, and from now on this is. */
    fun claim(roomId: String, eventId: String): Boolean = underWay.add(key(roomId, eventId))

    fun release(roomId: String, eventId: String) {
        underWay.remove(key(roomId, eventId))
    }
}

/**
 * A widget's Allow / Reject / Approve button, answered with no room open
 * (`SupermessageKit/Notifications/WidgetAnswering.swift:25-58`).
 *
 * The snapshot says whether the decision is still owed and what the button
 * means (`widget::answer_for`, the core's), the tap is marked sent at once so
 * the widget stops offering it, the answer goes the way a notification's does
 * ([NotificationAnswerer]), and a failed send makes the decision owed again.
 * **Sent, never resolved**: for a gate the board's receipt is what resolves it.
 *
 * Decides nothing: owed or not, which option, what the room is told are the
 * core's. The host's part is the order (read, mark, send, undo on failure),
 * the once-only guard and the time budget.
 */
object WidgetAnswering {
    enum class Outcome {
        /** Delivered. For a gate that is *answered*; the board's receipt resolves it. */
        SENT,

        /**
         * The snapshot does not owe this answer (resolved, already sent, not a button's to
         * send), or the same tap is already under way. Nothing went to the room.
         */
        NOT_OWED,

        /** It did not land. The decision is owed again, and the failure notice was posted. */
        FAILED,

        /**
         * Still under way when the budget ran out. It may yet land, so the decision stays
         * marked sent; only a later failure makes it owed again (and says so).
         */
        PENDING,
    }

    /**
     * Answer, and say how it went.
     *
     * @param budgetMs how long to wait for the send; it is not cancelled when it runs out ([scope] owns it).
     * @param reload called whenever the snapshot changed, so the widgets redraw from it.
     * @param failedNotice where "Your answer wasn't sent" goes when the send fails, now or later.
     * @param sent called once the answer has landed, now or later: the decision's other offers
     *   (its notification) are withdrawn then.
     */
    suspend fun answer(
        roomId: String,
        eventId: String,
        optionId: String,
        feed: WidgetFeed,
        client: NotificationAnswering,
        budgetMs: Long,
        scope: CoroutineScope,
        reload: suspend () -> Unit,
        failedNotice: suspend (PlannedNotification) -> Unit,
        sent: suspend () -> Unit = {},
    ): Outcome {
        // The snapshot's mark is the real guard; this closes the gap between reading
        // "owed" and marking it, where a second tap would also read "owed", and keeps
        // the decision's notification from answering it while this answer is under way.
        if (!DecisionAnswersUnderWay.claim(roomId, eventId)) return Outcome.NOT_OWED
        var handedOn = false
        try {
            val owed = feed.answer(roomId, eventId, optionId)
            if (owed == null) {
                // Redraw anyway: the widget was showing a button the snapshot no longer has.
                reload()
                return Outcome.NOT_OWED
            }
            if (feed.markAnswered(roomId, eventId, optionId)) reload()
            val work = NotificationAnswerer.start(notificationAnswerForWidget(owed), client, scope)
            val landed = withTimeoutOrNull(budgetMs) { work.await() }

            suspend fun failed() {
                if (feed.clearAnswer(roomId, eventId)) reload()
                failedNotice(answerFailedNotification(roomId))
            }
            return when (landed) {
                true -> {
                    sent()
                    Outcome.SENT
                }
                false -> {
                    failed()
                    Outcome.FAILED
                }
                null -> {
                    handedOn = true
                    scope.launch {
                        try {
                            if (work.await()) sent() else failed()
                        } finally {
                            DecisionAnswersUnderWay.release(roomId, eventId)
                        }
                    }
                    Outcome.PENDING
                }
            }
        } finally {
            if (!handedOn) DecisionAnswersUnderWay.release(roomId, eventId)
        }
    }
}
