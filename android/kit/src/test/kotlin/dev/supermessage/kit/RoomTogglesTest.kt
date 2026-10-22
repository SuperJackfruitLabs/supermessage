package dev.supermessage.kit

import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.supermessage_core.NotificationMode

/** The rule is the core's; these pin that the host asks it rather than guessing. */
class RoomTogglesTest {
    @Test
    fun `muted goes back to default`() =
        assertEquals(NotificationMode.DEFAULT, RoomToggles.nextNotificationMode(NotificationMode.MUTED))

    @Test
    fun `default becomes muted`() =
        assertEquals(NotificationMode.MUTED, RoomToggles.nextNotificationMode(NotificationMode.DEFAULT))

    @Test
    fun `all messages becomes muted`() =
        assertEquals(NotificationMode.MUTED, RoomToggles.nextNotificationMode(NotificationMode.ALL_MESSAGES))

    @Test
    fun `mentions only becomes muted`() =
        assertEquals(NotificationMode.MUTED, RoomToggles.nextNotificationMode(NotificationMode.MENTIONS_ONLY))
}
