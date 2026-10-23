package dev.supermessage.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.supermessage.MainActivity
import dev.supermessage.R
import dev.supermessage.kit.notify.TurnNotice
import java.util.concurrent.TimeUnit

/**
 * The ongoing notification of a running agent turn (P3): Android's stand-in for iOS's fleet Live
 * Activity, local and alive while the process is (there is no hub path on Android).
 *
 * One per room, under tag `turn:{roomId}`, in the `agent-activity` group and channel (low
 * importance: no sound, no badge). Its words are the core's `turnCard` ([TurnNotice]); its clock
 * is the chronometer from the turn's start, which ticks on its own. Updating it replaces it in
 * place and never alerts again. It is never amber (a running turn is not a pending decision) and
 * has no buttons: a tap opens the room.
 *
 * It times out 30 minutes after its last update, the core's turn limit, so a card whose process
 * died leaves within the time the core would have ended the turn. It is cancelled when the turn
 * ends ([cancel]) and at sign-out ([cancelAll]); there is no "finished" note, which would double
 * the reply's own notification.
 */
internal class TurnNotifier(context: Context) {
    private val context = context.applicationContext

    @SuppressLint("MissingPermission") // checked: a refused permission posts nothing
    fun show(notice: TurnNotice) {
        ensureChannels(context)
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled() || !permitted()) return
        val builder = NotificationCompat.Builder(context, Channels.AGENT_ACTIVITY)
            .setSmallIcon(R.drawable.ic_stat_supermessage)
            .setContentTitle(notice.headline)
            .setContentText(notice.subtitle)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setGroup(GROUP)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(true)
            .setWhen(notice.startedAtMs)
            .setUsesChronometer(true)
            .setTimeoutAfter(TIMEOUT_MS)
            .setContentIntent(openRoom(notice.roomId))
        if (notice.totalSteps > 0) builder.setProgress(notice.totalSteps, notice.completedSteps.coerceAtMost(notice.totalSteps), false)
        manager.notify(tag(notice.roomId), ID, builder.build())
    }

    /** The turn in [roomId] ended, or is in front of the reader. */
    fun cancel(roomId: String) {
        NotificationManagerCompat.from(context).cancel(tag(roomId), ID)
    }

    /** Sign-out: every turn notification goes. */
    fun cancelAll() {
        val manager = NotificationManagerCompat.from(context)
        manager.activeNotifications
            .filter { it.tag?.startsWith(TAG_PREFIX) == true }
            .forEach { manager.cancel(it.tag, it.id) }
    }

    private fun permitted(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun openRoom(roomId: String): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, AndroidPoster.openRoomUri(roomId), context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(AndroidPoster.EXTRA_ROOM, roomId)
        return PendingIntent.getActivity(
            context, tag(roomId).hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        const val GROUP = "agent-activity"
        const val TAG_PREFIX = "turn:"
        const val ID = 2

        /** The M1b turn limit (owner O1): 30 minutes. */
        val TIMEOUT_MS: Long = TimeUnit.MINUTES.toMillis(30)

        fun tag(roomId: String) = TAG_PREFIX + roomId
    }
}
