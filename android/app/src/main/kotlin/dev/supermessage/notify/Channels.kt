package dev.supermessage.notify

import android.content.Context
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Notification channels. iOS has none, so these are *(no iOS copy)*.
 *
 * Two, because the reader's choices differ: a message may arrive quietly in
 * the shade, but a pending decision (a permission request, a gate) is the one
 * thing worth interrupting for.
 */
internal object Channels {
    const val MESSAGES = "messages"
    const val DECISIONS = "decisions"
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
}
