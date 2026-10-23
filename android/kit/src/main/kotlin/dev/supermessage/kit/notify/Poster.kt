package dev.supermessage.kit.notify

import uniffi.supermessage_core.PlannedNotification

/** Puts a planned notification in front of the reader (`LocalNotifier.post`). */
fun interface Poster {
    fun post(note: PlannedNotification)
}
