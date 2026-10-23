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

    private open class Recording(val userId: String = "@me:x", val refuses: Boolean = false) : StubCore() {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override fun restoreSessionQuietly(): Boolean = true.also { calls += "restore" }
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

    init {
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    private val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

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
        scope: kotlinx.coroutines.CoroutineScope = AppCore.scope,
        budgetMs: Long = dev.supermessage.notify.NotificationActionReceiver.BUDGET_MS,
    ) = runBlocking {
        AnswerFromWidget.run(
            app, room, event, option,
            open = { opened += it },
            feed = feed,
            core = { core },
            account = { account },
            reload = {},
            scope = scope,
            budgetMs = budgetMs,
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

    private fun failureNotice(room: String = "!b:hs") = shadowOf(manager).getNotification("$room#answer-failed", 1)

    /** I1: "Your answer wasn't sent" is M6's failure notice: the Messages channel, never amber. */
    @Test
    fun aFailedWidgetAnswerSaysSoWithoutAmber() = runBlocking {
        feed.apply(WidgetFixtures.gate())
        keyguard.setKeyguardLocked(false)
        assertEquals(WidgetAnswering.Outcome.FAILED, tap(core = Recording(refuses = true)))
        val notice = checkNotNull(failureNotice()) { "the reader is told" }
        assertEquals("Your answer wasn't sent", notice.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString())
        assertEquals("not the interrupting decisions channel", "messages", notice.channelId)
        assertEquals("not amber", android.app.Notification.COLOR_DEFAULT, notice.color)
    }

    /** I1: a send under way at sign-out that then fails says nothing: its account left. */
    @Test(timeout = 15_000)
    fun aLateFailureAfterSignOutSaysNothing() {
        runBlocking { feed.apply(WidgetFixtures.gate()) }
        keyguard.setKeyguardLocked(false)
        val gate = java.util.concurrent.CountDownLatch(1)
        val core = object : Recording(refuses = true) {
            @Volatile var signedOut = false
            private val restores = java.util.concurrent.atomic.AtomicInteger()
            override fun restoreSessionQuietly(): Boolean {
                calls += "restore"
                // The answer's own restore is held past the budget; the check after it is not.
                if (restores.getAndIncrement() == 0) {
                    gate.await()
                    return true
                }
                return !signedOut
            }
        }
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        assertEquals(WidgetAnswering.Outcome.PENDING, tap(core = core, scope = scope, budgetMs = 200))
        core.signedOut = true
        gate.countDown()
        val parent = scope.coroutineContext[kotlinx.coroutines.Job]!!
        runBlocking { while (parent.children.any()) parent.children.forEach { it.join() } }
        assertTrue("it was tried, and refused", core.calls.any { it.startsWith("gate") })
        assertNull("its account left: nothing goes up", failureNotice())
    }

    /** I4: the decision's notification (posted under its event id) goes once the widget's answer lands. */
    @Test
    fun aLandedWidgetAnswerWithdrawsTheDecisionsNotification() = runBlocking {
        feed.apply(WidgetFixtures.gate())
        dev.supermessage.notify.AndroidPoster(app, accountId = "@me:x").post(
            uniffi.supermessage_ffi.plannedFromDecided(WidgetFixtures.gate()),
        )
        assertNotNull("the decision's notification is up", shadowOf(manager).getNotification("\$g", 1))
        keyguard.setKeyguardLocked(false)
        assertEquals(WidgetAnswering.Outcome.SENT, tap())
        assertNull("its buttons cannot send a second answer", shadowOf(manager).getNotification("\$g", 1))
    }

    @Test
    fun aFailedWidgetAnswerLeavesTheNotification() = runBlocking {
        feed.apply(WidgetFixtures.gate())
        dev.supermessage.notify.AndroidPoster(app, accountId = "@me:x").post(
            uniffi.supermessage_ffi.plannedFromDecided(WidgetFixtures.gate()),
        )
        keyguard.setKeyguardLocked(false)
        assertEquals(WidgetAnswering.Outcome.FAILED, tap(core = Recording(refuses = true)))
        assertNotNull("nothing was sent: it may still be answered there", shadowOf(manager).getNotification("\$g", 1))
    }
}
