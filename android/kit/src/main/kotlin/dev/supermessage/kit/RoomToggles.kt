package dev.supermessage.kit

import uniffi.supermessage_core.NotificationMode
import uniffi.supermessage_ffi.nextNotificationMode as coreNextNotificationMode

/**
 * What a roster swipe sets next. The rule is the core's (`muted` goes back to
 * `default`, anything else goes to `muted`); this only names the call, so the
 * two hosts cannot disagree about it.
 */
object RoomToggles {
    fun nextNotificationMode(current: NotificationMode): NotificationMode = coreNextNotificationMode(current)
}
