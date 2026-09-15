package dev.supermessage.kit.stores

import dev.supermessage.kit.CoreClient
import dev.supermessage.kit.StubCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.supermessage_core.AccountDto
import uniffi.supermessage_core.NotificationMode
import uniffi.supermessage_core.PersonDto
import uniffi.supermessage_core.RoomInfoDto
import uniffi.supermessage_core.SearchResultDto
import uniffi.supermessage_core.SpaceSummary
import uniffi.supermessage_ffi.ConnectionState
import uniffi.supermessage_ffi.CoreInterface
import uniffi.supermessage_ffi.EventSink
import uniffi.supermessage_ffi.FfiException
import uniffi.supermessage_ffi.RoomsSnapshot
import uniffi.supermessage_ffi.StagedFile
import uniffi.supermessage_ffi.TimelineSnapshot

/**
 * `StagedAttachment.swift` has no Swift test — confirmed by grepping the
 * whole Swift test directory — so this is written new rather than ported.
 * The rules pinned below come straight from the source's own doc comments:
 * "one, not many", "replacing rather than queueing", and "consumes the
 * token".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StagedAttachmentTest {

    private fun client(fake: FakeCore) = CoreClient(core = fake, dispatcher = Dispatchers.Unconfined)

    private fun stagedFile(token: String = "tok-1") =
        StagedFile(token = token, filename = "photo.jpg", sizeBytes = 10u, mime = "image/jpeg", width = null, height = null)

    /** staging a file the core accepts holds it, and reports no failure */
    @Test
    fun `staging a file the core accepts holds it, and reports no failure`() = runTest {
        val fake = FakeCore(stageResult = { stagedFile("tok-1") })
        val staged = StagedAttachment(client(fake))

        val message = staged.stage(path = "/tmp/photo.jpg", roomId = "!a:x")

        assertNull(message)
        assertEquals("tok-1", staged.file.value?.token)
    }

    /**
     * "Replacing rather than queueing: discard whatever was staged first, so
     * a token cannot be orphaned in the core." — the source's own comment.
     */
    @Test
    fun `a second stage discards the first, so a token is never orphaned in the core`() = runTest {
        var call = 0
        val fake = FakeCore(stageResult = { call += 1; stagedFile("tok-$call") })
        val staged = StagedAttachment(client(fake))

        staged.stage(path = "/tmp/a.jpg", roomId = "!a:x")
        staged.stage(path = "/tmp/b.jpg", roomId = "!a:x")

        assertEquals(listOf("tok-1"), fake.discardedTokens)
        assertEquals("tok-2", staged.file.value?.token)
    }

    /** a refusal from the core is reported, and nothing is left staged */
    @Test
    fun `a refusal from the core is reported, and nothing is left staged`() = runTest {
        val fake = FakeCore(stageResult = { throw FfiException.Store("disk full") })
        val staged = StagedAttachment(client(fake))

        val message = staged.stage(path = "/tmp/photo.jpg", roomId = "!a:x")

        assertTrue(message != null && message.isNotEmpty())
        assertNull(staged.file.value)
    }

    /** sending consumes the token and clears what was staged */
    @Test
    fun `sending consumes the token and clears what was staged`() = runTest {
        val fake = FakeCore(stageResult = { stagedFile("tok-1") })
        val staged = StagedAttachment(client(fake))
        staged.stage(path = "/tmp/photo.jpg", roomId = "!a:x")

        val message = staged.send(roomId = "!a:x")

        assertNull(message)
        assertNull(staged.file.value)
        assertEquals(listOf("!a:x" to "tok-1"), fake.sentTokens)
    }

    /** sending with nothing staged does nothing, and asks the core for nothing */
    @Test
    fun `sending with nothing staged does nothing, and asks the core for nothing`() = runTest {
        val fake = FakeCore()
        val staged = StagedAttachment(client(fake))

        val message = staged.send(roomId = "!a:x")

        assertNull(message)
        assertTrue(fake.sentTokens.isEmpty())
    }

    /**
     * A send the core refuses leaves the file staged — so a retry, or an
     * explicit discard, is still possible rather than the attachment
     * silently vanishing on failure.
     */
    @Test
    fun `a refused send is reported, and the file stays staged`() = runTest {
        val fake = FakeCore(
            stageResult = { stagedFile("tok-1") },
            sendResult = { throw FfiException.Network("offline") },
        )
        val staged = StagedAttachment(client(fake))
        staged.stage(path = "/tmp/photo.jpg", roomId = "!a:x")

        val message = staged.send(roomId = "!a:x")

        assertTrue(message != null && message.isNotEmpty())
        assertEquals("tok-1", staged.file.value?.token)
    }

    /** discarding tells the core and clears the staged file */
    @Test
    fun `discarding tells the core and clears the staged file`() = runTest {
        val fake = FakeCore(stageResult = { stagedFile("tok-1") })
        val staged = StagedAttachment(client(fake))
        staged.stage(path = "/tmp/photo.jpg", roomId = "!a:x")

        staged.discard()

        assertNull(staged.file.value)
        assertEquals(listOf("tok-1"), fake.discardedTokens)
    }

    /**
     * Not from Swift, since there is no Swift test for this file at all:
     * `StateFlow` conflates equal values. `discard()` guards on `file` being
     * non-null before doing anything (mirroring the source's own `guard let
     * file else { return }`), so two discards in a row with nothing staged
     * change nothing and must not tell the core anything either.
     */
    @Test
    fun `discarding twice with nothing staged emits nothing new, and never calls the core`() = runTest {
        val fake = FakeCore()
        val staged = StagedAttachment(client(fake))
        val seen = mutableListOf<StagedFile?>()
        val job = launch { staged.file.collect { seen.add(it) } }

        staged.discard()
        staged.discard()
        advanceUntilIdle()

        // Only the initial `null` — two no-op discards produced no further
        // emission, because a `null`-to-`null` "change" is not a change.
        assertEquals(1, seen.size)
        assertTrue(fake.discardedTokens.isEmpty())
        job.cancel()
    }

    private class FakeCore(
        private val stageResult: () -> StagedFile = { throw NotImplementedError() },
        private val sendResult: (() -> Unit)? = null,
    ) : StubCore() {
        val discardedTokens = mutableListOf<String>()
        val sentTokens = mutableListOf<Pair<String, String>>()

        override fun attachmentDiscard(token: String) {
            discardedTokens.add(token)
        }

        override fun attachmentSend(roomId: String, token: String) {
            sendResult?.invoke()
            sentTokens.add(roomId to token)
        }

        override fun attachmentStagePath(roomId: String, path: String): StagedFile = stageResult()

    }
}
