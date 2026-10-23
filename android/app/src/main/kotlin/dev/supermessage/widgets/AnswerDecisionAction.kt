package dev.supermessage.widgets

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.util.Log
import android.os.SystemClock
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import dev.supermessage.AppCore
import dev.supermessage.MainActivity
import dev.supermessage.kit.widgets.WidgetAnswering
import dev.supermessage.kit.widgets.WidgetFeed
import dev.supermessage.notify.AccountScopedAnswering
import dev.supermessage.notify.AndroidPoster
import dev.supermessage.notify.LazyCoreAnswering
import dev.supermessage.notify.NotificationActionReceiver
import dev.supermessage.notify.stillSignedInAs
import kotlinx.coroutines.CoroutineScope
import uniffi.supermessage_core.PlannedNotification
import uniffi.supermessage_ffi.CoreInterface

/**
 * A widget's Allow / Reject / Approve button (`AnswerDecisionIntent.swift`).
 *
 * **Runs in the app's process**, as iOS's intent does: Glance's callback receiver is this
 * app's own, so the one core, the account's session and the crypto store are the ones the
 * app has. It never builds a second client. The button carries only which decision and
 * which option; what that means (still owed? what does a gate need?) is the core's, read
 * from the snapshot ([WidgetAnswering]), so a stale widget cannot answer a gate the board
 * has already resolved.
 */
class AnswerDecisionAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val room = parameters[RoomKey] ?: return
        val event = parameters[EventKey] ?: return
        val option = parameters[OptionKey] ?: return
        AnswerFromWidget.run(context.applicationContext, room, event, option)
    }

    companion object {
        val RoomKey = ActionParameters.Key<String>("room")
        val EventKey = ActionParameters.Key<String>("event")
        val OptionKey = ActionParameters.Key<String>("option")
    }
}

/**
 * What a tap does, with its seams open for a test.
 *
 * - **A locked device answers nothing.** iOS answers need unlock (`.authenticationRequired`);
 *   a widget on a locked screen (a lock-screen or hub-mode widget) opens the decision
 *   instead, which the system puts behind the unlock. Nothing is sent.
 * - Otherwise [WidgetAnswering] runs it on the process's scope, inside the callback (Glance
 *   holds the broadcast open with `goAsync` until `onAction` returns), within the M6
 *   notification answer's [NotificationActionReceiver.BUDGET_MS], counted from the tap.
 * - **Never another account's.** The stamp of the account the snapshot was written for must
 *   equal the signed-in account; no stamp counts as no match.
 * - **"Your answer wasn't sent"** is M6's failure notice ([AndroidPoster.postFailure]: not amber,
 *   not on the decisions channel), and only while the stamp's account is still the one signed
 *   in: a send that fails after sign-out says nothing to the next reader.
 * - **Once it lands, the decision's notification goes**, so its buttons cannot send a second
 *   answer; while it is under way the notification's answer is refused
 *   ([dev.supermessage.kit.widgets.DecisionAnswersUnderWay]).
 */
internal object AnswerFromWidget {
    suspend fun run(
        context: Context,
        roomId: String,
        eventId: String,
        optionId: String,
        clock: () -> Long = SystemClock::elapsedRealtime,
        startedAt: Long = clock(),
        locked: () -> Boolean = { context.getSystemService(KeyguardManager::class.java).isKeyguardLocked },
        open: (Intent) -> Unit = { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
        feed: WidgetFeed = AppCore.widgets(context),
        core: () -> CoreInterface = { AppCore.core(context) },
        account: () -> String? = { AppCore.widgetAccount(context).read() },
        scope: CoroutineScope = AppCore.scope,
        budgetMs: Long = NotificationActionReceiver.BUDGET_MS,
        reload: suspend () -> Unit = { AppCore.widgetRedraw(context).after(true) },
        failure: (String?) -> (PlannedNotification) -> Unit = { id -> AndroidPoster(context, accountId = id)::postFailure },
        withdraw: (eventId: String) -> Unit = { AndroidPoster.cancelDecision(context, it) },
    ): WidgetAnswering.Outcome? {
        if (locked()) {
            // Starting an Activity from here can be refused (background-start rules, no handler):
            // that must not crash the callback, and nothing was owed to be sent.
            try {
                open(decisionIntent(context, roomId, eventId))
            } catch (e: RuntimeException) {
                Log.w("AnswerFromWidget", "could not open the decision from the widget", e)
            }
            return null
        }
        val expected = account()
        val client = LazyCoreAnswering(core)
        val guarded = AccountScopedAnswering(client, expected) { client.account().userId }
        val notice = failure(expected)
        return WidgetAnswering.answer(
            roomId, eventId, optionId, feed, guarded,
            budgetMs = (budgetMs - (clock() - startedAt)).coerceAtLeast(0),
            scope = scope,
            reload = reload,
            // Another account's decision is dropped without a word, as a notification's is; and
            // once the account has signed out (sign-out withdrew everything), nothing goes up for it.
            failedNotice = { if (!guarded.refusedOtherAccount && stillSignedInAs(client, expected)) notice(it) },
            sent = { withdraw(eventId) },
        )
    }

    /** A room's deep link (`supermessage://open?room=`), what an Agents row opens (iOS `AppLink.room`). */
    fun roomIntent(context: Context, roomId: String): Intent =
        Intent(Intent.ACTION_VIEW, AndroidPoster.openRoomUri(roomId), context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(AndroidPoster.EXTRA_ROOM, roomId)

    /** The decision's deep link (`supermessage://open?room=&event=`), into this app's one Activity. */
    fun decisionIntent(context: Context, roomId: String, eventId: String): Intent =
        Intent(Intent.ACTION_VIEW, AndroidPoster.openRoomUri(roomId, eventId), context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(AndroidPoster.EXTRA_ROOM, roomId)
            .putExtra(AndroidPoster.EXTRA_EVENT, eventId)
}
