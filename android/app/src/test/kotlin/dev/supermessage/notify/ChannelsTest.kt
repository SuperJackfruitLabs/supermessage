package dev.supermessage.notify

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChannelsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    @Test
    fun decisionsInterruptAndMessagesDoNot() {
        ensureChannels(context)

        val messages = manager.getNotificationChannel("messages")
        val decisions = manager.getNotificationChannel("decisions")
        assertNotNull(messages)
        assertNotNull(decisions)
        assertEquals("Messages", messages.name.toString())
        assertEquals("Decisions", decisions.name.toString())
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, messages.importance)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, decisions.importance)
    }

    @Test
    fun ensureChannelsTwiceChangesNothing() {
        ensureChannels(context)
        val first = manager.notificationChannels.map { Triple(it.id, it.name.toString(), it.importance) }.sortedBy { it.first }
        ensureChannels(context)
        val second = manager.notificationChannels.map { Triple(it.id, it.name.toString(), it.importance) }.sortedBy { it.first }

        assertEquals(first, second)
        assertEquals(listOf("agent-activity", "decisions", "messages"), second.map { it.first })
    }
}
