package dev.supermessage.kit.notify

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.supermessage_core.NotificationAnswer

/** `NotificationAnswererTests.swift:58-127`, with a recording fake. */
class NotificationAnswererTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = scope.cancel()

    private open class Fake(var restored: Boolean = true, var refuses: Boolean = false) : NotificationAnswering {
        val calls = java.util.Collections.synchronizedList(mutableListOf<String>())
        override suspend fun restoreSessionQuietly(): Boolean {
            calls += "restore"
            return restored
        }

        override suspend fun sendPermissionAnswer(roomId: String, optionId: String) {
            calls += "permission $roomId $optionId"
            if (refuses) throw IllegalStateException("refused")
        }

        override suspend fun sendGateDecisionTo(
            roomId: String, gateId: String, optionId: String, comment: String?, inReplyTo: String, prompt: String,
        ) {
            calls += "gate $roomId $gateId $optionId $comment $inReplyTo $prompt"
            if (refuses) throw IllegalStateException("refused")
        }
    }

    private val permission = NotificationAnswer.Permission(roomId = "!r:x", optionId = "allow_once")
    private val gate = NotificationAnswer.Gate(
        roomId = "!r:x", gateEventId = "\$gate", gateId = "g1", optionId = "request_changes",
        comment = "needs tests", prompt = "Ship it?",
    )

    @Test
    fun aPermissionAnswerRestoresQuietlyThenSendsTheOption() = runBlocking {
        val fake = Fake()
        assertTrue(NotificationAnswerer.send(permission, fake, 1_000, scope))
        assertEquals(listOf("restore", "permission !r:x allow_once"), fake.calls)
    }

    @Test
    fun aGateAnswerSendsTheDecisionReferencingTheGateEvent() = runBlocking {
        val fake = Fake()
        assertTrue(NotificationAnswerer.send(gate, fake, 1_000, scope))
        assertEquals(
            listOf("restore", "gate !r:x g1 request_changes needs tests \$gate Ship it?"),
            fake.calls,
        )
    }

    @Test
    fun signedOutNothingIsSent() = runBlocking {
        val fake = Fake(restored = false)
        assertFalse(NotificationAnswerer.send(permission, fake, 1_000, scope))
        assertEquals(listOf("restore"), fake.calls)
    }

    @Test
    fun aRefusedSendIsNotSent() = runBlocking {
        assertFalse(NotificationAnswerer.send(gate, Fake(refuses = true), 1_000, scope))
    }

    @Test(timeout = 3_000)
    fun aSendThatHangsIsGivenUpOnWithinTheBudget() {
        val latch = CountDownLatch(1)
        val hanging = object : Fake() {
            override suspend fun sendPermissionAnswer(roomId: String, optionId: String) {
                // A blocking, uncancellable call, like the core's.
                latch.await()
            }
        }
        val started = System.nanoTime()
        val result = runBlocking { NotificationAnswerer.send(permission, hanging, 200, scope) }
        val tookMs = (System.nanoTime() - started) / 1_000_000
        latch.countDown()
        assertFalse(result)
        assertTrue("gave up after $tookMs ms", tookMs < 400)
    }

    @Test
    fun aQuickAnswerIsNotHeldUntilTheDeadline() {
        val started = System.nanoTime()
        assertTrue(runBlocking { NotificationAnswerer.send(permission, Fake(), 5_000, scope) })
        assertTrue((System.nanoTime() - started) / 1_000_000 < 1_000)
    }
}
