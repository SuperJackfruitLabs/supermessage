package dev.supermessage.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.supermessage.GeneratedThemeTokens
import dev.supermessage.kit.notify.TurnNotice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import androidx.compose.ui.graphics.toArgb

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TurnNotifierTest {
    private val app: Context = ApplicationProvider.getApplicationContext()
    private val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val notifier = TurnNotifier(app)

    @Before
    fun allow() {
        shadowOf(app as android.app.Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun notice(room: String = "!r:x", headline: String = "Reading the logs", done: Int = 1, total: Int = 3) =
        TurnNotice(room, headline, "Lyra · step $done of $total", startedAtMs = 1_700_000_000_000L, totalSteps = total, completedSteps = done)

    private fun shown(room: String = "!r:x"): Notification? = shadowOf(manager).getNotification(TurnNotifier.tag(room), TurnNotifier.ID)

    @Test
    fun aRunningTurnIsOngoingWithAChronometerFromItsStart() {
        notifier.show(notice())
        val n = shown()!!
        assertTrue("ongoing", n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue("chronometer", n.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertEquals(1_700_000_000_000L, n.`when`)
        assertEquals("Reading the logs", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("Lyra · step 1 of 3", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(3, n.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertEquals(1, n.extras.getInt(Notification.EXTRA_PROGRESS))
        assertEquals("agent-activity", n.group)
        assertTrue("only alerts once", n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
    }

    @Test
    fun noStepsHasNoProgressBar() {
        notifier.show(notice(done = 0, total = 0))
        assertEquals(false, shown()!!.extras.containsKey(Notification.EXTRA_PROGRESS_MAX) && shown()!!.extras.getInt(Notification.EXTRA_PROGRESS_MAX) > 0)
    }

    @Test
    fun aRunningTurnTimesOutAfterThirtyMinutes() {
        notifier.show(notice())
        assertEquals(30 * 60_000L, shown()!!.timeoutAfter)
    }

    @Test
    fun anUpdateReplacesInPlaceUnderTheRoomsTag() {
        notifier.show(notice(headline = "One"))
        notifier.show(notice(headline = "Two", done = 2))
        assertEquals(1, shadowOf(manager).allNotifications.size)
        assertEquals("Two", shown()!!.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
    }

    @Test
    fun aTapOpensTheRoom() {
        notifier.show(notice(room = "!r:x"))
        val intent = shadowOf(shown()!!.contentIntent).savedIntent
        assertEquals(AndroidPoster.openRoomUri("!r:x"), intent.data)
        assertEquals(android.content.Intent.ACTION_VIEW, intent.action)
    }

    @Test
    fun itIsNeverAmber() {
        notifier.show(notice())
        val n = shown()!!
        assertNotEquals(GeneratedThemeTokens.light.signal.toArgb(), n.color)
        assertNotEquals(GeneratedThemeTokens.dark.signal.toArgb(), n.color)
        assertTrue("no buttons", n.actions == null || n.actions.isEmpty())
    }

    @Test
    fun itIsQuiet() {
        notifier.show(notice())
        val n = shown()!!
        assertEquals(Channels.AGENT_ACTIVITY, n.channelId)
        val channel = manager.getNotificationChannel(Channels.AGENT_ACTIVITY)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertNull("no sound", channel.sound)
        assertEquals("no badge", false, channel.canShowBadge())
    }

    @Test
    fun cancellingOneRoomLeavesTheOther() {
        notifier.show(notice(room = "!a:x"))
        notifier.show(notice(room = "!b:x"))
        notifier.cancel("!a:x")
        assertNull(shown("!a:x"))
        assertTrue(shown("!b:x") != null)
    }

    @Test
    fun signingOutCancelsEveryTurnNotificationAndOnlyThose() {
        notifier.show(notice(room = "!a:x"))
        notifier.show(notice(room = "!b:x"))
        manager.notify("other", 1, android.app.Notification.Builder(app, Channels.MESSAGES).setSmallIcon(dev.supermessage.R.drawable.ic_stat_supermessage).build())
        notifier.cancelAll()
        assertNull(shown("!a:x"))
        assertNull(shown("!b:x"))
        assertEquals("a message note is not a turn", 1, shadowOf(manager).allNotifications.size)
    }

    @Test
    fun withoutPermissionNothingIsPosted() {
        shadowOf(app as android.app.Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifier.show(notice())
        assertNull(shown())
    }
}
