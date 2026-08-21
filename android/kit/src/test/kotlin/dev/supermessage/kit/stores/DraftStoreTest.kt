package dev.supermessage.kit.stores

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ported from `apple/SupermessageKitTests/ComposerStateTests.swift`'s
 * `DraftStoreTests` — that file is misnamed (there is no Swift
 * `ComposerState`), but its rules are real and are carried across whole.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DraftStoreTest {

    /** "a draft is kept per room, so switching away does not lose work" */
    @Test
    fun `a draft is kept per room, so switching away does not lose work`() {
        val drafts = DraftStore()
        drafts.set("half a thought", roomId = "!a:x")
        drafts.set("something else", roomId = "!b:x")

        assertEquals("half a thought", drafts.draft(roomId = "!a:x"))
        assertEquals("something else", drafts.draft(roomId = "!b:x"))
    }

    /** "a draft never follows the reader into another room" */
    @Test
    fun `a draft never follows the reader into another room`() {
        // The desktop learned this one the hard way: a draft that followed
        // the reader put a half-written message in front of the wrong agent.
        val drafts = DraftStore()
        drafts.set("for ganesha only", roomId = "!a:x")
        assertTrue(drafts.draft(roomId = "!b:x").isEmpty())
    }

    /** "emptying a draft forgets it rather than storing a blank" */
    @Test
    fun `emptying a draft forgets it rather than storing a blank`() {
        val drafts = DraftStore()
        drafts.set("typed", roomId = "!a:x")
        drafts.set("", roomId = "!a:x")
        assertTrue(drafts.draft(roomId = "!a:x").isEmpty())

        // "forgets it rather than storing a blank" is a claim about the
        // underlying map, not just about what `draft(roomId)` reads back —
        // reading a *missing* key and reading a key mapped to `""` produce
        // the same empty string either way, so a mutation that stopped
        // removing the key and stored `""` instead would pass the assertion
        // above undetected. Asserting the key is actually gone is what
        // catches that.
        assertFalse(drafts.drafts.value.containsKey("!a:x"))
    }

    /**
     * Not from Swift: `StateFlow` conflates equal values in a way an
     * `@Observable` property does not need pinning for, since Swift has no
     * analogous "did this actually re-emit" question. A view driven by
     * [DraftStore.drafts] depends on this, so it is asserted rather than
     * assumed.
     */
    @Test
    fun `setting the same draft twice emits only once`() = runTest {
        val drafts = DraftStore()
        val seen = mutableListOf<Map<String, String>>()
        val job = launch { drafts.drafts.collect { seen.add(it) } }
        runCurrent() // let the collector attach and receive the initial value first

        drafts.set("half a thought", roomId = "!a:x")
        drafts.set("half a thought", roomId = "!a:x")
        advanceUntilIdle()

        // The initial empty map, then one real change. The repeated,
        // identical `set` produced no second emission.
        assertEquals(2, seen.size)
        job.cancel()
    }
}
