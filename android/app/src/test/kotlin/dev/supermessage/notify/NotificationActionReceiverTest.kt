package dev.supermessage.notify

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.app.RemoteInput
import androidx.test.core.app.ApplicationProvider
import dev.supermessage.AppCore
import dev.supermessage.kit.StubCore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import uniffi.supermessage_core.AccountDto
import uniffi.supermessage_core.GateAnswers
import uniffi.supermessage_core.NotificationCategory
import uniffi.supermessage_core.PermissionAnswers
import uniffi.supermessage_core.PlannedNotification

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotificationActionReceiverTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private class Recording(val userId: String = "@me:x", val restores: Boolean = true, val refuses: Boolean = false) : StubCore() {
        val calls = java.util.Collections.synchronizedList(mutableListOf<String>())
        fun sent() = calls.filter { it != "restore" }
        override fun restoreSessionQuietly(): Boolean {
            calls += "restore"
            return restores
        }
        override fun account(): AccountDto = AccountDto(userId = userId, homeserver = "https://x")
        override fun sendPermissionAnswer(roomId: String, optionId: String) {
            calls += "permission $roomId $optionId"
            if (refuses) throw IllegalStateException("refused")
        }

        override fun sendGateDecisionTo(
            roomId: String, gateId: String, optionId: String, comment: String?, inReplyTo: String, prompt: String,
        ) {
            calls += "gate $roomId $gateId $optionId $comment $inReplyTo $prompt"
            if (refuses) throw IllegalStateException("refused")
        }
    }

    private lateinit var core: Recording

    init {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @After
    fun forget() = AppCore.installForTest(null)

    private fun install(core: Recording = Recording()) {
        this.core = core
        AppCore.installForTest(core)
    }

    private fun note(category: NotificationCategory, options: List<String> = listOf("approve", "request_changes", "reject")) =
        PlannedNotification(
            id = "\$e1", roomId = "!r:x", eventId = "\$e1", title = "Lyra", subtitle = null, body = "ship?",
            category = category,
            permission = if (category == NotificationCategory.PERMISSION) PermissionAnswers("allow_once", "reject_once") else null,
            gate = if (category == NotificationCategory.GATE) GateAnswers("g1", "Ship it?", options) else null,
        )

    /** The intent the poster's own PendingIntent for [title] carries. */
    private fun press(
        note: PlannedNotification,
        title: String,
        account: String? = "@me:x",
        typed: String? = null,
    ) {
        AndroidPoster(app, accountId = account).post(note)
        val shown = shadowOf(manager).getNotification(note.id, 1)
        val action = shown.actions.single { it.title == title }
        val intent = Intent(shadowOf(action.actionIntent).savedIntent)
        if (typed != null) {
            val input = androidx.core.app.RemoteInput.Builder(AndroidPoster.KEY_TEXT).build()
            RemoteInput.addResultsToIntent(arrayOf(input), intent, Bundle().apply { putCharSequence(AndroidPoster.KEY_TEXT, typed) })
        }
        runBlocking { NotificationActionReceiver().handle(app, intent).join() }
    }

    private fun others(): List<Notification> =
        shadowOf(manager).allNotifications

    @Test
    fun allowOnceSendsThePermissionOptionToItsRoom() {
        install()
        press(note(NotificationCategory.PERMISSION), "Allow once")
        assertEquals(listOf("permission !r:x allow_once"), core.sent())
    }

    @Test
    fun approveSendsTheGateDecisionReferencingTheGateEvent() {
        install()
        press(note(NotificationCategory.GATE), "Approve")
        assertEquals(listOf("gate !r:x g1 approve null \$e1 Ship it?"), core.sent())
    }

    @Test
    fun requestChangesSendsWhatWasTyped() {
        install()
        press(note(NotificationCategory.GATE), "Request changes", typed = " needs tests ")
        assertEquals(listOf("gate !r:x g1 request_changes needs tests \$e1 Ship it?"), core.sent())
    }

    @Test
    fun requestChangesWithNothingTypedSendsNothingAndRepostsTheNotification() {
        install()
        press(note(NotificationCategory.GATE), "Request changes", typed = "   ")
        assertEquals(emptyList<String>(), core.sent())
        val back = shadowOf(manager).getNotification("\$e1", 1)
        assertNotNull("posted again for the reader to tap", back)
        assertEquals("Lyra", back.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(listOf("Approve", "Request changes", "Reject"), back.actions.map { it.title.toString() })
    }

    @Test
    fun anOptionTheGateDoesNotOfferSendsNothing() {
        install()
        press(note(NotificationCategory.GATE, options = listOf("approve", "request_changes")), "Reject")
        assertEquals(emptyList<String>(), core.sent())
        assertNotNull(shadowOf(manager).getNotification("\$e1", 1))
    }

    @Test
    fun theNotificationIsWithdrawnTheMomentItIsAnswered() {
        install()
        press(note(NotificationCategory.PERMISSION), "Reject")
        assertNull(shadowOf(manager).getNotification("\$e1", 1))
    }

    @Test
    fun aRefusedAnswerPostsYourAnswerWasntSent() {
        install(Recording(refuses = true))
        press(note(NotificationCategory.PERMISSION), "Allow once")
        val failed = shadowOf(manager).getNotification("!r:x#answer-failed", 1)
        assertNotNull(failed)
        assertEquals("Your answer wasn't sent", failed.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("Open the conversation to answer it there.", failed.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals("only the failure notice is up", 1, others().size)
    }

    @Test
    fun anAnswerNeedsNoActivity() {
        install()
        press(note(NotificationCategory.PERMISSION), "Allow once")
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test
    fun anotherAccountsNotificationNeverActsOnThisOne() {
        install(Recording(userId = "@other:x"))
        press(note(NotificationCategory.PERMISSION), "Allow once", account = "@me:x")
        assertEquals(emptyList<String>(), core.sent())
        assertTrue("dropped without a word", others().isEmpty())
    }

    @Test
    fun aNotificationWithNoRecordedAccountNeverAnswers() {
        install()
        press(note(NotificationCategory.PERMISSION), "Allow once", account = null)
        assertEquals("the core is not even touched", emptyList<String>(), core.calls)
    }
}
