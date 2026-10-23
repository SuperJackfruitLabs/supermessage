package dev.supermessage

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.app.RemoteInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.supermessage.kit.StubCore
import dev.supermessage.notify.AndroidPoster
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.supermessage_core.AccountDto
import uniffi.supermessage_core.GateAnswers
import uniffi.supermessage_core.NotificationCategory
import uniffi.supermessage_core.PlannedNotification

/**
 * An answer through the real `PendingIntent` and the real receiver, against a
 * recording fake core. No real room or account is touched. A device with no
 * lock screen cannot show the unlock prompt; that is asserted by flag in
 * `AndroidPosterActionsTest` and verified by hand.
 */
@RunWith(AndroidJUnit4::class)
class NotificationActionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(NotificationManager::class.java)

    private class Recording : StubCore() {
        val calls = java.util.Collections.synchronizedList(mutableListOf<String>())
        override fun restoreSessionQuietly(): Boolean = true
        override fun account(): AccountDto = AccountDto(userId = "@me:x", homeserver = "https://x")
        override fun sendGateDecisionTo(
            roomId: String, gateId: String, optionId: String, comment: String?, inReplyTo: String, prompt: String,
        ) {
            calls += "gate $roomId $gateId $optionId $comment $inReplyTo $prompt"
        }
    }

    private val core = Recording()

    @Before
    fun setUp() {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        manager.cancelAll()
        AppCore.installForTest(core)
        AndroidPoster(context, accountId = "@me:x").post(
            PlannedNotification(
                id = "\$gate1", roomId = "!fake:x", eventId = "\$gate1", title = "Lyra", subtitle = null,
                body = "Ship it?", category = NotificationCategory.GATE, permission = null,
                gate = GateAnswers("g1", "Ship it?", listOf("approve", "request_changes", "reject")),
            ),
        )
    }

    @After
    fun tearDown() {
        AppCore.installForTest(null)
        manager.cancelAll()
    }

    private fun waitForCall() {
        val deadline = System.currentTimeMillis() + 15_000
        while (core.calls.isEmpty()) {
            check(System.currentTimeMillis() < deadline) { "the fake core was never called" }
            Thread.sleep(50)
        }
    }

    private fun shownActions(): Array<android.app.Notification.Action> {
        // The system takes a post asynchronously.
        val deadline = System.currentTimeMillis() + 10_000
        while (manager.activeNotifications.none { it.tag == "\$gate1" }) {
            check(System.currentTimeMillis() < deadline) { "the note never appeared" }
            Thread.sleep(50)
        }
        return manager.activeNotifications.single { it.tag == "\$gate1" }.notification.actions
    }

    @Test
    fun approvingFromTheNotificationShadeSendsTheGateDecision() {
        shownActions().first().actionIntent.send()
        waitForCall()
        assertEquals(listOf("gate !fake:x g1 approve null \$gate1 Ship it?"), core.calls.toList())
        val deadline = System.currentTimeMillis() + 10_000
        while (manager.activeNotifications.any { it.tag == "\$gate1" }) {
            check(System.currentTimeMillis() < deadline) { "the note was never withdrawn" }
            Thread.sleep(50)
        }
    }

    @Test
    fun requestChangesFromTheShadeCarriesTheText() {
        val action = shownActions().single { it.title == "Request changes" }
        val fillIn = Intent()
        RemoteInput.addResultsToIntent(
            arrayOf(RemoteInput.Builder(AndroidPoster.KEY_TEXT).build()),
            fillIn,
            Bundle().apply { putCharSequence(AndroidPoster.KEY_TEXT, " needs tests ") },
        )
        action.actionIntent.send(context, 0, fillIn)
        waitForCall()
        assertEquals(
            listOf("gate !fake:x g1 request_changes needs tests \$gate1 Ship it?"),
            core.calls.toList(),
        )
    }
}
