package dev.supermessage

import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import dev.supermessage.RoomHarness.count
import dev.supermessage.RoomHarness.raiseKeyboard
import dev.supermessage.kit.Session
import dev.supermessage.previews.PreviewFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import uniffi.supermessage_core.TurnUpdate
import uniffi.supermessage_core.TypingUserDto
import uniffi.supermessage_ffi.FfiEvent

/**
 * The timeline follows its newest content while the reader is at it (the owner's report
 * on a OnePlus 8T: after sending, the agent's answer streamed in out of sight). What
 * "follows" means here is what iOS's inverted `TimelineCollectionView` does at
 * `contentOffset.y <= 0`: a new row, the live card arriving, and the card or the typing
 * pill growing all stay in view, flush with the bottom. A reader who has scrolled back is
 * left where they are.
 *
 * Driven through the real [AppRoot], [RoomPane] and [Timeline] over a fake core whose
 * `sendMessage` echoes the words as the reader's newest row, the way the real core's
 * local echo does.
 */
class StreamingFollowTest {
    @get:Rule val compose = createAndroidComposeRule<WindowLikeMainActivity>()

    private lateinit var core: RoomHarness.FakeCore
    private lateinit var session: Session
    private val density get() = compose.density.density

    private fun open(reduceMotion: Boolean, history: Int = 40) {
        core = RoomHarness.FakeCore(RoomHarness.history(history))
        session = RoomHarness.session(core)
        val prefs = RoomHarness.prefs()
        compose.setContent {
            CompositionLocalProvider(LocalReduceMotion provides reduceMotion) {
                Box(Modifier.fillMaxSize()) { AppRoot(session = session, prefs = prefs) }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText(RoomHarness.NAME).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun bounds(tag: String): Rect? =
        compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes()
            .firstOrNull()?.boundsInRoot

    private fun waitFor(what: String, cond: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMillis = 10_000) { cond() }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("timed out waiting for: $what", e)
        }
        compose.waitForIdle()
    }

    /**
     * The gap a list at its newest leaves below its newest item: its own bottom padding,
     * which is 8dp plus the reserve the dock and the pill float in. Measured on the room as
     * it opens, which is at its newest by construction.
     */
    private var restingGap = Float.NaN

    private fun measureRestingGap(tag: String) {
        val list = bounds("timeline-list") ?: throw AssertionError("no timeline")
        val item = bounds(tag) ?: throw AssertionError("$tag is not laid out as the room opens")
        restingGap = list.bottom - item.bottom
        assertTrue("the room opened away from its newest message (gap $restingGap)", restingGap >= 0f && restingGap < 160f * density)
    }

    /**
     * [tag] is the newest thing in the list and the reader can see all of it: its bottom
     * sits where an at-the-newest list puts its newest item (above the dock's and pill's
     * reserve), not lower and not higher, and its top is inside the list.
     */
    private fun assertNewestInView(tag: String, step: String) {
        val list = bounds("timeline-list") ?: throw AssertionError("$step: no timeline")
        val item = bounds(tag)
            ?: throw AssertionError("$step: $tag is not laid out at all — the list left it off screen")
        val expected = list.bottom - restingGap
        val covers = listOfNotNull(bounds("dock")?.top, bounds("typing-pill")?.top)
        // A row's node carries its share of the spacing between rows and the card's does
        // not, so "at the bottom" allows the card to end a few dp higher; never lower.
        assertTrue(
            "$step: $tag ends at ${item.bottom}; at the newest it would end at $expected (list ${list.top}..${list.bottom})",
            item.bottom <= expected + 2f && expected - item.bottom <= 12f * density,
        )
        covers.forEach { top ->
            assertTrue("$step: $tag ends at ${item.bottom}, under the dock or pill from $top", item.bottom <= top + 1f)
        }
        assertTrue("$step: $tag starts at ${item.top}, above the list's top ${list.top}", item.top >= list.top - 1f)
        assertEquals("$step: Jump to newest is offered, so the list thinks the reader left", 0, compose.count("jump-to-newest"))
    }

    private fun turnStarts() {
        val started = PreviewFixtures.turn(totalSteps = 1, completedSteps = 0, hasThought = true).copy(roomId = RoomHarness.ROOM)
        compose.runOnUiThread { core.event(FfiEvent.Turn(TurnUpdate(started, null))) }
        waitFor("the turn to be live") { session.live.summary.value != null }
    }

    private fun answer(seq: Int) {
        val text = (1..seq).joinToString(" ") { "Sentence $it of the answer, streaming in as the agent writes it." }
        compose.runOnUiThread { core.event(FfiEvent.Live(RoomHarness.ROOM, seq.toULong(), text, false)) }
        waitFor("answer $seq to reach the card") { session.live.answer.value == text }
    }

    private fun host(): View = compose.activity.window.decorView
        .findViewById<ViewGroup>(android.R.id.content).getChildAt(0)

    private fun imeVisible(): Boolean {
        var visible = false
        compose.runOnUiThread {
            visible = ViewCompat.getRootWindowInsets(host())?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        return visible
    }

    /** The real soft keyboard up ([up]) or down, and the layout settled after it. */
    private fun keyboard(up: Boolean) {
        if (up) {
            compose.raiseKeyboard()
        } else {
            compose.runOnUiThread {
                WindowCompat.getInsetsController(compose.activity.window, host()).hide(WindowInsetsCompat.Type.ime())
            }
            waitFor("the keyboard to go") { imeVisible() == false }
        }
        // Its inset animation: the list's bottom stops moving.
        var last = Float.NaN
        var steady = 0
        waitFor("the layout to settle after the keyboard") {
            Thread.sleep(100)
            val now = bounds("timeline-list")?.bottom ?: Float.NaN
            steady = if (now == last) steady + 1 else 0
            last = now
            steady >= 4
        }
    }

    /**
     * Sends, then streams an answer. [keyboardOpen]: the keyboard stays up throughout, as it
     * does for someone who has just sent; otherwise it is never raised.
     */
    private fun sendAndFollow(reduceMotion: Boolean, keyboardOpen: Boolean) {
        open(reduceMotion)
        measureRestingGap("row-\$h39")

        // Sending: the reader's own words land at the newest end, in view.
        compose.onNodeWithTag("composer-text").performClick()
        compose.onNodeWithTag("composer-text").performTextInput("Can you check the roster order?")
        if (keyboardOpen) {
            keyboard(up = true)
            val list = bounds("timeline-list")!!
            // A short phone with the keyboard up has almost no list left (1080x1280: 36px),
            // less than the dock's own reserve; there is nothing to follow in.
            assumeTrue(
                "the keyboard leaves the list ${list.height / density}dp, too short to hold a message",
                list.height - restingGap >= 160f * density,
            )
        }
        compose.onNodeWithTag("composer-send").performClick()
        waitFor("the echo") { core.rows.last().item.id == "\$sent1" }
        assertNewestInView("row-\$sent1", "after sending")

        // The agent starts: the live card arrives below everything, in view.
        turnStarts()
        assertNewestInView("live-turn", "when the turn starts")

        // Its thought and answer stream in; the card grows and stays in view.
        compose.runOnUiThread { core.event(FfiEvent.Thought(RoomHarness.ROOM, 1uL, "Looking at how the roster is sorted.", false)) }
        waitFor("the thought") { session.live.thought.value != null }
        assertNewestInView("live-turn", "after the thought")
        for (seq in 1..5) {
            answer(seq)
            assertNewestInView("live-turn", "after answer chunk $seq")
        }

        // Someone starts typing: the pill shows, and the card is still in view above it.
        compose.runOnUiThread {
            core.event(FfiEvent.Typing(RoomHarness.ROOM, listOf(TypingUserDto("@k:x", "Krishna", "Krishna"))))
        }
        waitFor("the pill") {
            compose.onAllNodes(hasText("Krishna is typing…"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertNewestInView("live-turn", "with the typing pill")
        answer(6)
        assertNewestInView("live-turn", "after answer chunk 6, with the pill")
    }

    @Test
    fun theNewestContentStaysInViewWhileAnAnswerStreams() = sendAndFollow(reduceMotion = true, keyboardOpen = false)

    @Test
    fun theNewestContentStaysInViewWhileAnAnswerStreamsWithMotion() = sendAndFollow(reduceMotion = false, keyboardOpen = false)

    @Test
    fun theNewestContentStaysInViewWithTheKeyboardOpen() = sendAndFollow(reduceMotion = false, keyboardOpen = true)

    /** Scrolled back to read, the reader is not moved by a send's echo, the card arriving or growing. */
    @Test
    fun aReaderWhoScrolledBackIsNotMoved() {
        open(reduceMotion = true)
        compose.onNodeWithTag("timeline-list").performScrollToIndex(25)
        compose.waitForIdle()
        waitFor("Jump to newest") { compose.count("jump-to-newest") > 0 }
        val anchor = "row-\$h14"
        val before = bounds(anchor) ?: throw AssertionError("$anchor is not on screen after scrolling back")

        compose.runOnUiThread { core.append(RoomHarness.row("\$a1", "A new message from Atlas.", own = false)) }
        compose.waitForIdle()
        turnStarts()
        for (seq in 1..4) answer(seq)

        val after = bounds(anchor) ?: throw AssertionError("$anchor left the screen: the reader was moved")
        assertEquals("the message the reader was reading moved", before.top, after.top, 1f)
        assertTrue("Jump to newest went away", compose.count("jump-to-newest") > 0)
    }
}
