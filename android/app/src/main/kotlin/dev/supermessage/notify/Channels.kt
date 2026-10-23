package dev.supermessage.notify

import android.content.Context
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Notification channels. iOS has none, so these are *(no iOS copy)*.
 *
 * Three. The first two, because the reader's choices differ: a message may arrive quietly in
 * the shade, but a pending decision (a permission request, a gate) is the one
 * thing worth interrupting for. The third is a running agent turn, which interrupts no one.
 */
internal object Channels {
    const val MESSAGES = "messages"
    const val DECISIONS = "decisions"

    /** The ongoing notification of a running agent turn: quiet, no sound, no badge. */
    const val AGENT_ACTIVITY = "agent-activity"
}

/**
 * Creates both channels. Idempotent: creating a channel that exists is a no-op
 * on Android, and importance is the reader's to change afterwards, so a second
 * call never overrides it.
 */
internal fun ensureChannels(context: Context) {
    val manager = NotificationManagerCompat.from(context.applicationContext)
    manager.createNotificationChannel(
        NotificationChannelCompat.Builder(Channels.MESSAGES, NotificationManagerCompat.IMPORTANCE_DEFAULT)
            .setName("Messages")
            .build(),
    )
    manager.createNotificationChannel(
        NotificationChannelCompat.Builder(Channels.DECISIONS, NotificationManagerCompat.IMPORTANCE_HIGH)
            .setName("Decisions")
            .build(),
    )
    manager.createNotificationChannel(
        NotificationChannelCompat.Builder(Channels.AGENT_ACTIVITY, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName("Agent activity")
            .setShowBadge(false)
            .setSound(null, null)
            .setVibrationEnabled(false)
            .build(),
    )
}
