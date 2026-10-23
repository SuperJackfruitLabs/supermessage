package dev.supermessage.kit.widgets

import dev.supermessage.kit.notify.NotificationAnswering
import dev.supermessage.kit.widgets.WidgetFixtures.NOW
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.supermessage_core.PlannedNotification

/**
 * A widget's Allow / Reject / Approve, answered with no room open
 * (`SupermessageKitTests/WidgetAnsweringTests.swift:61-140`), over the real
 * core's snapshot merges and a recording client.
 */
class WidgetAnsweringTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = scope.cancel()

    private class Client(
        var fails: Boolean = false,
        /** Holds the send until completed, so a test can outrun the budget. */
        val hold: CompletableDeferred<Unit>? = null,
    ) : NotificationAnswering {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override suspend fun restoreSessionQuietly(): Boolean {
            calls += "restore"
            return true
        }

        override suspend fun sendPermissionAnswer(roomId: String, optionId: String) {
            calls += "permission $roomId $optionId"
            hold?.await()
            if (fails) throw IllegalStateException("refused")
        }

        override suspend fun sendGateDecisionTo(
            roomId: String, gateId: String, optionId: String, comment: String?, inReplyTo: String, prompt: String,
        ) {
            calls += "gate $roomId $gateId $optionId $comment $inReplyTo $prompt"
            hold?.await()
            if (fails) throw IllegalStateException("refused")
        }
    }

    private val store = WidgetFixtures.Memory()
    private val feed = WidgetFixtures.feed(store)
    private val reloads = AtomicInteger()
    private val notices: MutableList<PlannedNotification> = Collections.synchronizedList(mutableListOf())

    private fun answer(
        room: String,
        event: String,
        option: String,
        client: NotificationAnswering,
        budgetMs: Long = 5_000,
    ) = runBlocking {
        WidgetAnswering.answer(
            room, event, option, feed, client, budgetMs, scope,
            reload = { reloads.incrementAndGet() }, failedNotice = { notices += it }, sent = { landed.incrementAndGet() },
        )
    }

    /** How many times the answer was reported landed (the decision's notification is withdrawn then). */
    private val landed = AtomicInteger()

    private fun decision(event: String) = feed.view(NOW).decisions.map { it.decision }.single { it.eventId == event }

    @Test(timeout = 60_000)
    fun allowSendsThePermissionsOptionAndTheWidgetSaysSent() = runBlocking {
        feed.apply(WidgetFixtures.permission())
        val client = Client()
        assertEquals(WidgetAnswering.Outcome.SENT, answer("!r:hs", "\$p", "Allow once", client))
        assertEquals(listOf("restore", "permission !r:hs Allow once"), client.calls)
        val view = feed.view(NOW).decisions.single()
        assertEquals("Sent: Allow once", view.decision.answered?.line)
        assertTrue("sent has no buttons", view.buttons.isEmpty())
        assertTrue("the widgets were redrawn", reloads.get() >= 1)
    }

    @Test(timeout = 60_000)
    fun approveSendsTheGateDecisionAgainstTheGateEventAndIsNotCalledApproved() = runBlocking {
        feed.apply(WidgetFixtures.gate())
        val client = Client()
        assertEquals(WidgetAnswering.Outcome.SENT, answer("!b:hs", "\$g", "approve", client))
        assertEquals(listOf("restore", "gate !b:hs gate-9 approve null \$g Ship v2"), client.calls)
        val line = decision("\$g").answered!!.line
        assertEquals("Sent: Approve · waiting for the board", line)
        assertFalse(line.contains("Approved"))
    }

    @Test(timeout = 60_000)
    fun aGateTheBoardResolvedSendsNothing() = runBlocking {
        feed.apply(WidgetFixtures.gate())
        feed.apply(WidgetFixtures.outcome())
        val client = Client()
        assertEquals(WidgetAnswering.Outcome.NOT_OWED, answer("!b:hs", "\$g", "approve", client))
        assertTrue("not even a restore", client.calls.isEmpty())
        assertEquals("the stale widget is redrawn", 1, reloads.get())
    }

    @Test(timeout = 60_000)
    fun aSecondTapSendsNothing() = runBlocking {
        feed.apply(WidgetFixtures.gate())
        answer("!b:hs", "\$g", "approve", Client())
        val second = Client()
        assertEquals(WidgetAnswering.Outcome.NOT_OWED, answer("!b:hs", "\$g", "reject", second))
        assertTrue(second.calls.isEmpty())
    }

    @Test(timeout = 60_000)
    fun requestChangesIsNeverSentFromAButton() = runBlocking {
        feed.apply(WidgetFixtures.gate())
        val client = Client()
        assertEquals(WidgetAnswering.Outcome.NOT_OWED, answer("!b:hs", "\$g", "request_changes", client))
        assertTrue(client.calls.isEmpty())
    }

    @Test(timeout = 60_000)
    fun aFailedSendLeavesTheDecisionOwedAgainAndSaysSo() = runBlocking {
        feed.apply(WidgetFixtures.permission())
        assertEquals(WidgetAnswering.Outcome.FAILED, answer("!r:hs", "\$p", "Reject", Client(fails = true)))
        val view = feed.view(NOW).decisions.single()
        assertNull(view.decision.answered)
        assertEquals(listOf("Allow once", "Reject"), view.buttons.map { it.id })
        val notice = notices.single()
        assertEquals("!r:hs#answer-failed", notice.id)
        assertEquals("Your answer wasn't sent", notice.title)
    }

    @Test(timeout = 60_000)
    fun aSendThatOutrunsTheBudgetStaysSentAndOnlyALaterFailureClearsIt() = runBlocking {
        feed.apply(WidgetFixtures.permission())
        val hold = CompletableDeferred<Unit>()
        val client = Client(fails = true, hold = hold)
        assertEquals(WidgetAnswering.Outcome.PENDING, answer("!r:hs", "\$p", "Allow once", client, budgetMs = 100))
        assertEquals("still marked sent: it may yet land", "Sent: Allow once", decision("\$p").answered?.line)
        assertTrue("and nothing is said yet", notices.isEmpty())
        hold.complete(Unit)
        val deadline = System.currentTimeMillis() + 10_000
        while (notices.isEmpty()) {
            check(System.currentTimeMillis() < deadline) { "the late failure was never reported" }
            Thread.sleep(20)
        }
        assertNull("it failed after all: owed again", decision("\$p").answered)
    }

    @Test(timeout = 60_000)
    fun aSendThatOutrunsTheBudgetAndLandsSaysNothing() = runBlocking {
        feed.apply(WidgetFixtures.permission())
        val hold = CompletableDeferred<Unit>()
        val client = Client(hold = hold)
        assertEquals(WidgetAnswering.Outcome.PENDING, answer("!r:hs", "\$p", "Allow once", client, budgetMs = 100))
        hold.complete(Unit)
        Thread.sleep(300)
        assertTrue(notices.isEmpty())
        assertEquals("Sent: Allow once", decision("\$p").answered?.line)
    }

    @Test(timeout = 120_000)
    fun twoTapsAtOnceSendOnce() = runBlocking {
        repeat(20) { i ->
            val event = "\$p$i"
            // Newer each time: the snapshot keeps the six latest decisions.
            feed.apply(WidgetFixtures.permission(event = event, at = NOW - WidgetFixtures.MINUTE + i))
            val client = Client()
            val start = CountDownLatch(1)
            val outcomes = (1..2).map {
                async(Dispatchers.Default) {
                    start.await()
                    WidgetAnswering.answer(
                        "!r:hs", event, "Allow once", feed, client, 5_000, scope,
                        reload = {}, failedNotice = { notices += it },
                    )
                }
            }
            start.countDown()
            val results = outcomes.awaitAll()
            assertEquals("exactly one sent ($results)", 1, client.calls.count { it.startsWith("permission") })
        }
    }

    /** I4: once the answer lands, the decision's other offer (its notification) is withdrawn. */
    @Test(timeout = 60_000)
    fun aLandedAnswerIsReportedSoTheNotificationGoes() = runBlocking {
        feed.apply(WidgetFixtures.permission())
        assertEquals(WidgetAnswering.Outcome.SENT, answer("!r:hs", "\$p", "Allow once", Client()))
        assertEquals(1, landed.get())
    }

    @Test(timeout = 60_000)
    fun aFailedAnswerIsNotReportedLanded() = runBlocking {
        feed.apply(WidgetFixtures.permission())
        assertEquals(WidgetAnswering.Outcome.FAILED, answer("!r:hs", "\$p", "Allow once", Client(fails = true)))
        assertEquals("the notification stays: nothing was sent", 0, landed.get())
    }

    @Test(timeout = 60_000)
    fun aLateLandingIsReportedToo() = runBlocking {
        feed.apply(WidgetFixtures.permission())
        val hold = CompletableDeferred<Unit>()
        assertEquals(WidgetAnswering.Outcome.PENDING, answer("!r:hs", "\$p", "Allow once", Client(hold = hold), budgetMs = 100))
        assertEquals(0, landed.get())
        hold.complete(Unit)
        val deadline = System.currentTimeMillis() + 10_000
        while (landed.get() == 0) {
            check(System.currentTimeMillis() < deadline) { "the late landing was never reported" }
            Thread.sleep(20)
        }
    }

    /** I4: the decision's notification is answering it: the widget sends nothing meanwhile. */
    @Test(timeout = 60_000)
    fun aDecisionAnsweredFromItsNotificationIsNotSentAgainFromTheWidget() = runBlocking {
        feed.apply(WidgetFixtures.permission())
        assertTrue(DecisionAnswersUnderWay.claim("!r:hs", "\$p"))
        try {
            val client = Client()
            assertEquals(WidgetAnswering.Outcome.NOT_OWED, answer("!r:hs", "\$p", "Allow once", client))
            assertTrue("nothing went to the room", client.calls.isEmpty())
        } finally {
            DecisionAnswersUnderWay.release("!r:hs", "\$p")
        }
        // Once that answer is done (here: it failed), the widget may answer.
        assertEquals(WidgetAnswering.Outcome.SENT, answer("!r:hs", "\$p", "Allow once", Client()))
    }
}
