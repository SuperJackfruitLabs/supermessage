package dev.supermessage.notify

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import uniffi.supermessage_core.GateAnswers
import uniffi.supermessage_core.NotificationCategory
import uniffi.supermessage_core.PermissionAnswers
import uniffi.supermessage_core.PlannedNotification

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidPosterActionsTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private val poster = AndroidPoster(app, accountId = "@me:x")

    private fun note(category: NotificationCategory) = PlannedNotification(
        id = "\$e1", roomId = "!r:x", eventId = "\$e1", title = "Lyra", subtitle = null, body = "may I?",
        category = category,
        permission = if (category == NotificationCategory.PERMISSION) PermissionAnswers("allow_once", "reject_once") else null,
        gate = if (category == NotificationCategory.GATE) GateAnswers("g1", "Ship it?", listOf("approve", "request_changes", "reject")) else null,
    )

    private fun actionsOf(category: NotificationCategory): Array<Notification.Action> {
        poster.post(note(category))
        return shadowOf(manager).getNotification("\$e1", 1).actions ?: emptyArray()
    }

    private fun titles(category: NotificationCategory) = actionsOf(category).map { it.title.toString() }

    @Test
    fun aPermissionOffersAllowOnceAndReject() {
        assertEquals(listOf("Allow once", "Reject"), titles(NotificationCategory.PERMISSION))
    }

    @Test
    fun aGateOffersApproveRequestChangesRejectAndTheTapOpens() {
        assertEquals(listOf("Approve", "Request changes", "Reject"), titles(NotificationCategory.GATE))
        val intent = shadowOf(shadowOf(manager).getNotification("\$e1", 1).contentIntent).savedIntent
        assertEquals("supermessage://open?room=%21r%3Ax", intent.data.toString())
    }

    @Test
    fun aDecisionOffersOnlyOpen() {
        assertEquals(listOf("Open"), titles(NotificationCategory.DECISION))
    }

    @Test
    fun aMessageHasNoButtons() {
        assertEquals(emptyList<String>(), titles(NotificationCategory.MESSAGE))
    }

    @Test
    fun everyAnswerRequiresUnlock() {
        val all = actionsOf(NotificationCategory.PERMISSION).toList()
        poster.post(note(NotificationCategory.GATE).copy(id = "\$g"))
        val gate = shadowOf(manager).getNotification("\$g", 1).actions.toList()
        poster.post(note(NotificationCategory.DECISION).copy(id = "\$d"))
        val open = shadowOf(manager).getNotification("\$d", 1).actions.single()
        val answers = all + gate
        assertEquals(5, answers.size)
        assertTrue(answers.all { it.isAuthenticationRequired })
        assertFalse("launching an Activity already needs an unlocked device", open.isAuthenticationRequired)
    }

    @Test
    fun requestChangesTakesTextLabelledWhatShouldChange() {
        val actions = actionsOf(NotificationCategory.GATE)
        val input = actions.single { it.title == "Request changes" }.remoteInputs.single()
        assertEquals("What should change?", input.label.toString())
        assertNull(actions.single { it.title == "Approve" }.remoteInputs)
        assertNotNull(input.resultKey)
    }
}
