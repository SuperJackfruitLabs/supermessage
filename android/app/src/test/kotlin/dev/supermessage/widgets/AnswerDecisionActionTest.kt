package dev.supermessage.widgets

import android.app.Application
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.AppWidgetId
import androidx.test.core.app.ApplicationProvider
import dev.supermessage.AppCore
import dev.supermessage.kit.StubCore
import dev.supermessage.kit.widgets.WidgetAnswering
import dev.supermessage.kit.widgets.WidgetFixtures
import dev.supermessage.kit.widgets.WidgetFixtures.NOW
import java.util.Collections
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

/**
 * A widget button's tap, in the app's process, over a fake core and the real snapshot merges
 * (`AnswerDecisionIntent.swift`). **Nothing here sends anything real.**
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AnswerDecisionActionTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val keyguard = shadowOf(app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager)
    private val store = WidgetFixtures.Memory()
    private val feed = WidgetFixtures.feed(store)

    private class Recording(val userId: String = "@me:x") : StubCore() {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override fun restoreSessionQuietly(): Boolean = true.also { calls += "restore" }
        override fun account(): AccountDto = AccountDto(userId = userId, homeserver = "https://x")
        override fun sendPermissionAnswer(roomId: String, optionId: String) {
            calls += "permission $roomId $optionId"
        }

        override fun sendGateDecisionTo(
            roomId: String, gateId: String, optionId: String, comment: String?, inReplyTo: String, prompt: String,
        ) {
            calls += "gate $roomId $gateId $optionId $comment $inReplyTo $prompt"
        }
    }

    private val core = Recording()
    private val opened = mutableListOf<Intent>()

    @After
    fun forget() {
        AppCore.installForTest(null)
        AppCore.installWidgetsForTest(null)
    }

    private fun tap(
        account: String? = "@me:x",
        room: String = "!b:hs",
        event: String = "\$g",
        option: String = "approve",
        core: Recording = this.core,
    ) = runBlocking {
        AnswerFromWidget.run(
            app, room, event, option,
            open = { opened += it },
            feed = feed,
            core = { core },
            account = { account },
            reload = {},
        )
    }

    @Test
    fun aTapAnswersWhenUnlocked() = runBlocking {
        feed.apply(WidgetFixtures.gate())
        keyguard.setKeyguardLocked(false)
        assertEquals(WidgetAnswering.Outcome.SENT, tap())
        assertEquals(listOf("restore", "gate !b:hs gate-9 approve null \$g Ship v2"), core.calls.filter { it != "account" })
        assertEquals("Sent: Approve · waiting for the board", feed.view(NOW).decisions.single().decision.answered?.line)
        assertTrue("it stayed in the widget: nothing was opened", opened.isEmpty())
    }

    @Test
    fun aLockedDeviceOpensInsteadOfAnswering() = runBlocking {
        feed.apply(WidgetFixtures.gate())
        keyguard.setKeyguardLocked(true)
        assertNull(tap())
        assertTrue("nothing was sent", core.calls.isEmpty())
        assertNull("and the decision is still owed", feed.view(NOW).decisions.single().decision.answered)
        val intent = opened.single()
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("supermessage://open?room=%21b%3Ahs&event=%24g", intent.data.toString())
    }

    @Test
    fun aRefusedActivityStartOnALockedDeviceDoesNotCrashTheCallback() = runBlocking {
        feed.apply(WidgetFixtures.gate())
        keyguard.setKeyguardLocked(true)
        // Background-start rules, or no handler: the platform throws.
        val result = AnswerFromWidget.run(
            app, "!b:hs", "\$g", "approve",
            open = { throw android.content.ActivityNotFoundException("refused") },
            feed = feed,
            core = { core },
            account = { "@me:x" },
            reload = {},
        )
        assertNull(result)
        assertTrue("nothing was sent", core.calls.isEmpty())
    }

    @Test
    fun anotherAccountsSessionSendsNothing() = runBlocking {
        feed.apply(WidgetFixtures.gate())
        keyguard.setKeyguardLocked(false)
        val other = Recording(userId = "@someone-else:x")
        assertEquals(WidgetAnswering.Outcome.FAILED, tap(core = other))
        assertEquals("the session was restored and checked, not used", listOf("restore"), other.calls.filter { it == "restore" || it.startsWith("gate") })
        assertNull("owed again", feed.view(NOW).decisions.single().decision.answered)
        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertTrue("and no word about it", shadowOf(manager).allNotifications.isEmpty())
    }

    @Test
    fun noRecordedAccountIsNoMatch() = runBlocking {
        feed.apply(WidgetFixtures.gate())
        keyguard.setKeyguardLocked(false)
        assertEquals(WidgetAnswering.Outcome.FAILED, tap(account = null))
        assertTrue(core.calls.none { it.startsWith("gate") })
    }

    @Test
    fun theCallbackAnswersThroughTheProcessCoreAndTheSnapshotFile() = runBlocking {
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(app) // the redraw after a write
        AppCore.installForTest(core)
        AppCore.widgets(app).apply(WidgetFixtures.permission())
        AppCore.widgetAccount(app).write("@me:x")
        keyguard.setKeyguardLocked(false)
        AnswerDecisionAction().onAction(
            app, AppWidgetId(1),
            actionParametersOf(
                AnswerDecisionAction.RoomKey to "!r:hs",
                AnswerDecisionAction.EventKey to "\$p",
                AnswerDecisionAction.OptionKey to "Allow once",
            ),
        )
        assertTrue(core.calls.contains("permission !r:hs Allow once"))
        val answered = AppCore.widgets(app).view().decisions.single().decision.answered
        assertNotNull(answered)
        assertEquals("Sent: Allow once", answered!!.line)
    }
}
