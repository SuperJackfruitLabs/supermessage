package dev.supermessage.kit.widgets

import dev.supermessage.kit.notify.NotificationAnswering
import dev.supermessage.kit.notify.NotificationAnswerer
import dev.supermessage.kit.notify.Poster
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import uniffi.supermessage_ffi.answerFailedNotification
import uniffi.supermessage_ffi.notificationAnswerForWidget

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

    /** The decisions whose answer is under way, process-wide: a double tap sends once. */
    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Answer, and say how it went.
     *
     * @param budgetMs how long to wait for the send; it is not cancelled when it runs out ([scope] owns it).
     * @param reload called whenever the snapshot changed, so the widgets redraw from it.
     * @param failedNotice where "Your answer wasn't sent" goes when the send fails, now or later.
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
        failedNotice: Poster,
    ): Outcome {
        val key = "$roomId|$eventId"
        // The snapshot's mark is the real guard; this closes the gap between reading
        // "owed" and marking it, where a second tap would also read "owed".
        if (!inFlight.add(key)) return Outcome.NOT_OWED
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
                failedNotice.post(answerFailedNotification(roomId))
            }
            return when (landed) {
                true -> Outcome.SENT
                false -> {
                    failed()
                    Outcome.FAILED
                }
                null -> {
                    handedOn = true
                    scope.launch {
                        try {
                            if (!work.await()) failed()
                        } finally {
                            inFlight.remove(key)
                        }
                    }
                    Outcome.PENDING
                }
            }
        } finally {
            if (!handedOn) inFlight.remove(key)
        }
    }
}
