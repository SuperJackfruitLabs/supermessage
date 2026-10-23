package dev.supermessage.notify

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ApplicationProvider
import dev.supermessage.GeneratedThemeTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import uniffi.supermessage_core.NotificationCategory
import uniffi.supermessage_core.PlannedNotification

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidPosterTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val poster: AndroidPoster

    init {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        poster = AndroidPoster(app, accountId = "@me:x")
    }

    private fun note(
        id: String = "\$e1",
        roomId: String = "!r:x",
        category: NotificationCategory = NotificationCategory.MESSAGE,
        subtitle: String? = null,
    ) = PlannedNotification(
        id = id, roomId = roomId, eventId = "\$e1", title = "Lyra", subtitle = subtitle, body = "hello there",
        category = category, permission = null, gate = null,
    )

    private fun shown(id: String): Notification = shadowOf(manager).getNotification(id, 1)

    @Test
    fun aMessageGoesToMessagesAndADecisionToDecisions() {
        poster.post(note(id = "m"))
        poster.post(note(id = "g", category = NotificationCategory.GATE))
        poster.post(note(id = "d", category = NotificationCategory.DECISION))
        assertEquals("messages", shown("m").channelId)
        assertEquals("decisions", shown("g").channelId)
        assertEquals("decisions", shown("d").channelId)
    }

    @Test
    fun postingTheSameIdReplaces() {
        poster.post(note(id = "a"))
        poster.post(note(id = "b"))
        poster.post(note(id = "a"))
        assertEquals("two ids, so two notifications; the repeat replaced", 2, shadowOf(manager).allNotifications.size)
    }

    @Test
    fun theTapOpensTheRoom() {
        poster.post(note(roomId = "!r:x"))
        val intent = shadowOf(shown("\$e1").contentIntent).savedIntent
        assertEquals("supermessage://open?room=%21r%3Ax", intent.data.toString())
        assertEquals("!r:x", intent.getStringExtra(AndroidPoster.EXTRA_ROOM))
        assertEquals("@me:x", intent.getStringExtra(AndroidPoster.EXTRA_ACCOUNT))
    }

    @Test
    fun onlyADecisionIsAmber() {
        poster.post(note(id = "m"))
        poster.post(note(id = "g", category = NotificationCategory.GATE))
        assertEquals(GeneratedThemeTokens.light.signal.toArgb(), shown("g").color)
        assertEquals(Notification.COLOR_DEFAULT, shown("m").color)
    }

    @Test
    fun theSubtitleIsTheCores() {
        poster.post(note(id = "a", subtitle = "Atlas"))
        poster.post(note(id = "b", subtitle = null))
        assertEquals("Atlas", shown("a").extras.getCharSequence(Notification.EXTRA_SUB_TEXT).toString())
        assertNull(shown("b").extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
        assertEquals("Lyra", shown("a").extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("hello there", shown("a").extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }

    @Test
    fun aPostCreatesItsChannelWithNoActivityHavingRun() {
        assertNull(manager.getNotificationChannel("messages"))
        poster.post(note())
        assertTrue(manager.getNotificationChannel("messages") != null)
    }

    @Test
    fun aRefusedPermissionPostsNothing() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        poster.post(note())
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test
    fun theTapReusesTheRunningActivity() {
        poster.post(note())
        val intent = shadowOf(shown("\$e1").contentIntent).savedIntent
        assertTrue(
            "CLEAR_TOP alone finishes and rebuilds a standard-mode Activity",
            intent.flags and android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP != 0,
        )
    }
}
