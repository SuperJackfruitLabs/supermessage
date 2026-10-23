package dev.supermessage.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import java.net.URLEncoder
import androidx.core.content.ContextCompat
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import dev.supermessage.GeneratedThemeTokens
import dev.supermessage.MainActivity
import dev.supermessage.R
import dev.supermessage.kit.notify.Poster
import uniffi.supermessage_core.GateAnswers
import uniffi.supermessage_core.NotificationActionSpec
import uniffi.supermessage_core.NotificationCategory
import uniffi.supermessage_core.PermissionAnswers
import uniffi.supermessage_core.PlannedNotification
import uniffi.supermessage_ffi.notificationActions
import uniffi.supermessage_ffi.notificationUserInfo

/**
 * Posts a planned notification with `NotificationCompat`
 * (`LocalNotifier.swift:65-81`).
 *
 * - Title, subtitle (when there is one), body and thread (the room) are the
 *   core's words; the category only picks the channel and the accent.
 * - The identifier is the plan's `id`, as the notification's tag, so posting
 *   the same thing twice replaces it (`NotificationComposer.swift:54-56`).
 * - A tap opens the room through the M5 deep link
 *   `supermessage://open?room=<id>`. The room, the event and the account the
 *   note was made for ride along as extras, so a later action can check the
 *   account before it answers.
 * - Amber (`signal`) is for a pending decision and nothing else. The
 *   failure notice is not one, though the core files it under Decision
 *   ([postFailure]).
 */
internal class AndroidPoster(
    context: Context,
    /** The signed-in account's id, set by the feed; stamped into each note's extras. */
    @Volatile var accountId: String? = null,
) : Poster {
    private val context = context.applicationContext

    override fun post(note: PlannedNotification) = post(note, pendingDecision = note.category != NotificationCategory.MESSAGE)

    /**
     * "Your answer wasn't sent" (`answerFailedNotification`). Nothing is
     * pending any more, so it is not amber and does not interrupt as a
     * decision does: it goes on the Messages channel with no accent, as iOS
     * posts it like any other notification (`LocalNotifier.postFailure`). Its
     * tap opens the room, and its words and "Open" are the core's.
     */
    fun postFailure(note: PlannedNotification) = post(note, pendingDecision = false)

    /**
     * The same note again, for the reader to tap (an action that came back
     * as `Open`, which a receiver cannot start an Activity for). Silent: it
     * was already announced once.
     */
    fun postAgain(note: PlannedNotification) =
        post(note, pendingDecision = note.category != NotificationCategory.MESSAGE, silent = true)

    @SuppressLint("MissingPermission") // checked: a refused permission posts nothing
    private fun post(note: PlannedNotification, pendingDecision: Boolean, silent: Boolean = false) {
        // At post time, so a post from a cold process or a receiver never
        // meets a missing channel.
        ensureChannels(context)
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled() || !permitted()) return
        val builder = NotificationCompat.Builder(context, if (pendingDecision) Channels.DECISIONS else Channels.MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_supermessage)
            .setContentTitle(note.title)
            .setContentText(note.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(note.body))
            .setGroup(note.roomId)
            .setAutoCancel(true)
            // A replacement under the same tag (the same event, posted by the
            // roster and then the push) does not sound or pop up again.
            .setOnlyAlertOnce(true)
            .setSilent(silent)
            .setContentIntent(openRoom(note))
        note.subtitle?.let { builder.setSubText(it) }
        addActions(builder, note)
        if (pendingDecision) builder.setColor(GeneratedThemeTokens.light.signal.toArgb())
        manager.notify(note.id, NOTIFICATION_ID, builder.build())
    }

    /**
     * The core's actions for the category (`LocalNotifier.swift:23-61`).
     * Android shows three buttons, so when a note has answers its "Open" is
     * dropped: the body tap opens, as iOS's tap does
     * (`NotificationRouter.swift:142-143`). A note with nothing to answer
     * keeps "Open" as its button.
     */
    private fun addActions(builder: NotificationCompat.Builder, note: PlannedNotification) {
        val specs = notificationActions(note.category)
        val answers = specs.filterNot { it.opensApp }
        val info = notificationUserInfo(note)
        for (spec in specs) {
            if (spec.opensApp) {
                if (answers.isEmpty()) builder.addAction(0, spec.title, openRoom(note))
            } else {
                builder.addAction(answerAction(spec, note, info))
            }
        }
    }

    /**
     * A broadcast to [NotificationActionReceiver]. The unlock requirement is
     * the core's (`requiresUnlock`, true for every answer): the system makes
     * the reader authenticate before it runs the action, on a locked device
     * (`LocalNotifier.swift:18-21`'s `.authenticationRequired`).
     */
    private fun answerAction(
        spec: NotificationActionSpec,
        note: PlannedNotification,
        info: Map<String, String>,
    ): NotificationCompat.Action {
        val intent = Intent(context, NotificationActionReceiver::class.java)
            .setAction(spec.id)
            .putExtra(EXTRA_ACTION_ID, spec.id)
            .putExtra(EXTRA_TAG, note.id)
            .putExtra(EXTRA_ACCOUNT, accountId)
            .putExtra(EXTRA_USER_INFO, Bundle().apply { info.forEach { (k, v) -> putString(k, v) } })
            .putExtra(EXTRA_TITLE, note.title)
            .putExtra(EXTRA_SUBTITLE, note.subtitle)
            .putExtra(EXTRA_BODY, note.body)
            .putExtra(EXTRA_CATEGORY, note.category.name)
        // A RemoteInput needs the system to fill in the typed text, so only
        // that action's PendingIntent is mutable. One-shot: an answer is sent
        // once, and a second tap (from any process) is refused by the system;
        // the receiver's own once-per-note guard covers the same in-process.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_ONE_SHOT or
            (if (spec.textInput != null) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE)
        // Unique per (tag, action), or one note's Approve would stand in for another's.
        val pending = PendingIntent.getBroadcast(context, "${note.id}|${spec.id}".hashCode(), intent, flags)
        val action = NotificationCompat.Action.Builder(0, spec.title, pending)
            .setAuthenticationRequired(spec.requiresUnlock)
        spec.textInput?.let {
            // Android has no separate send title: only the placeholder shows.
            action.addRemoteInput(RemoteInput.Builder(KEY_TEXT).setLabel(it.placeholder).build())
            action.setAllowGeneratedReplies(false)
        }
        return action.build()
    }

    private fun permitted(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun openRoom(note: PlannedNotification): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, openRoomUri(note.roomId), context, MainActivity::class.java)
            // MainActivity is singleTask, so the tap reaches the running instance's
            // onNewIntent. SINGLE_TOP stays: should the launch mode ever go back to
            // standard, CLEAR_TOP without it would finish and rebuild the Activity,
            // dropping the ViewModel, the feed and the Session.
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
            .putExtra(EXTRA_ROOM, note.roomId)
            .putExtra(EXTRA_EVENT, note.eventId)
            .putExtra(EXTRA_ACCOUNT, accountId)
        // One request code per note, or the system would hand back the first
        // note's intent for every later one (they differ only in extras/data).
        return PendingIntent.getActivity(
            context, note.id.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        const val NOTIFICATION_ID = 1
        const val EXTRA_ROOM = "dev.supermessage.extra.ROOM"
        const val EXTRA_EVENT = "dev.supermessage.extra.EVENT"
        const val EXTRA_ACCOUNT = "dev.supermessage.extra.ACCOUNT"
        const val EXTRA_ACTION_ID = "dev.supermessage.extra.ACTION_ID"
        const val EXTRA_TAG = "dev.supermessage.extra.TAG"
        const val EXTRA_USER_INFO = "dev.supermessage.extra.USER_INFO"
        const val EXTRA_TITLE = "dev.supermessage.extra.TITLE"
        const val EXTRA_SUBTITLE = "dev.supermessage.extra.SUBTITLE"
        const val EXTRA_BODY = "dev.supermessage.extra.BODY"
        const val EXTRA_CATEGORY = "dev.supermessage.extra.CATEGORY"

        /** The RemoteInput result key of "Request changes". */
        const val KEY_TEXT = "dev.supermessage.reply"

        /**
         * The note an action's extras were made from, to post it again
         * unchanged. The core's user info carries what its actions need, so
         * the answer fields come back from it.
         */
        fun noteFromExtras(intent: Intent): PlannedNotification? {
            val info = intent.getBundleExtra(EXTRA_USER_INFO) ?: return null
            val room = info.getString("sm.roomId") ?: return null
            val category = runCatching { NotificationCategory.valueOf(intent.getStringExtra(EXTRA_CATEGORY) ?: "") }
                .getOrNull() ?: return null
            val allow = info.getString("sm.allowOptionId")
            val reject = info.getString("sm.rejectOptionId")
            val gateId = info.getString("sm.gateId")
            return PlannedNotification(
                id = intent.getStringExtra(EXTRA_TAG) ?: return null,
                roomId = room,
                eventId = info.getString("sm.eventId"),
                title = intent.getStringExtra(EXTRA_TITLE) ?: return null,
                subtitle = intent.getStringExtra(EXTRA_SUBTITLE),
                body = intent.getStringExtra(EXTRA_BODY) ?: return null,
                category = category,
                permission = if (allow != null && reject != null) PermissionAnswers(allow, reject) else null,
                gate = gateId?.let {
                    GateAnswers(
                        it,
                        info.getString("sm.gatePrompt").orEmpty(),
                        info.getString("sm.gateOptions").orEmpty().split(' ').filter(String::isNotEmpty),
                    )
                },
            )
        }

        /**
         * `supermessage://open?room=<id>[&event=<id>]`; a Matrix id holds `!` and `:`, so each is
         * a query item, encoded. With [eventId] it opens the decision card (iOS `AppLink.decision`).
         */
        fun openRoomUri(roomId: String, eventId: String? = null): Uri =
            Uri.parse(
                "supermessage://open?room=" + URLEncoder.encode(roomId, "UTF-8") +
                    (eventId?.let { "&event=" + URLEncoder.encode(it, "UTF-8") } ?: ""),
            )
    }
}
