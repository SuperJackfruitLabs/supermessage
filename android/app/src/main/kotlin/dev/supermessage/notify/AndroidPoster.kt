package dev.supermessage.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import java.net.URLEncoder
import androidx.core.content.ContextCompat
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.supermessage.GeneratedThemeTokens
import dev.supermessage.MainActivity
import dev.supermessage.R
import dev.supermessage.kit.notify.Poster
import uniffi.supermessage_core.NotificationCategory
import uniffi.supermessage_core.PlannedNotification

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
 * - Amber (`signal`) is for a decision and nothing else.
 */
internal class AndroidPoster(
    context: Context,
    /** The signed-in account's id, set by the feed; stamped into each note's extras. */
    @Volatile var accountId: String? = null,
) : Poster {
    private val context = context.applicationContext

    @SuppressLint("MissingPermission") // checked: a refused permission posts nothing
    override fun post(note: PlannedNotification) {
        // At post time, so a post from a cold process or a receiver never
        // meets a missing channel.
        ensureChannels(context)
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled() || !permitted()) return
        val isMessage = note.category == NotificationCategory.MESSAGE
        val builder = NotificationCompat.Builder(context, if (isMessage) Channels.MESSAGES else Channels.DECISIONS)
            .setSmallIcon(R.drawable.ic_stat_supermessage)
            .setContentTitle(note.title)
            .setContentText(note.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(note.body))
            .setGroup(note.roomId)
            .setAutoCancel(true)
            .setContentIntent(openRoom(note))
        note.subtitle?.let { builder.setSubText(it) }
        if (!isMessage) builder.setColor(GeneratedThemeTokens.light.signal.toArgb())
        manager.notify(note.id, NOTIFICATION_ID, builder.build())
    }

    private fun permitted(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun openRoom(note: PlannedNotification): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, openRoomUri(note.roomId), context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
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

        /** `supermessage://open?room=<id>`; a Matrix id holds `!` and `:`, so it is a query item, encoded. */
        fun openRoomUri(roomId: String): Uri =
            Uri.parse("supermessage://open?room=" + URLEncoder.encode(roomId, "UTF-8"))
    }
}
