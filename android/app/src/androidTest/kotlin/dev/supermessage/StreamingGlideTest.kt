package dev.supermessage

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import dev.supermessage.previews.PreviewFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uniffi.supermessage_core.ItemView
import uniffi.supermessage_core.RichBlock
import uniffi.supermessage_core.RichInline
import uniffi.supermessage_core.TimelineRow as TimelineRowDto

/**
 * The history glide (owner decision Q5): while a turn streams, the card grows
 * unanimated and the history is drawn where it was and eased to where it is,
 * by transform alone (0.32 s, iOS's curve). Positions are read from the
 * semantics tree, which includes the layer transform, so what is measured is
 * where the row is drawn, not where layout put it.
 */
class StreamingGlideTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun message(n: Int): TimelineRowDto = PreviewFixtures.row(
        PreviewFixtures.item("\$m$n", body = "message $n", atMs = 1_757_700_000_000uL + n.toULong() * 1_000uL),
        ItemView.Bubble(
            muted = false,
            blocks = listOf(RichBlock.Paragraph(listOf(RichInline.Text("message $n")))),
            voice = null,
        ),
    )

    private var thought by mutableStateOf<String?>(null)
    private var steps by mutableStateOf(emptyList<uniffi.supermessage_core.TurnStep>())
    private var revision by mutableStateOf(0uL)
    private var room by mutableStateOf("!a:x")
    private var shown by mutableStateOf(emptyList<TimelineRowDto>())

    private fun show(rows: List<TimelineRowDto>, reduce: Boolean) {
        shown = rows
        compose.setContent {
            CompositionLocalProvider(LocalReduceMotion provides reduce) {
                Box(Modifier.requiredWidth(411.dp)) {
                    Timeline(
                        rows = shown, revision = revision, typingLine = null, isPaginating = false,
                        canPaginate = false, onPaginate = {}, onMarkRead = {}, roomId = room,
                        liveSummary = inProgress(), liveThought = thought, liveSteps = steps,
                        liveAnswer = null, liveName = "Atlas",
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(1_000)
    }

    private fun top(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top
    /** The card's own size: its bounds are clipped by the list while the glide has it drawn partly below the edge. */
    private fun cardHeight() = compose.onNodeWithTag("live-turn").fetchSemanticsNode().size.height.toFloat()

    /** Frames until the card has its new height, so the measurement is of the frame it landed in. */
    private fun untilTheCardGrows(before: Float) {
        var frames = 0
        do { compose.mainClock.advanceTimeByFrame(); frames++ } while (cardHeight() <= before + 1f && frames < 6)
    }

    private val three = (1..3).map(::message)

    @Test
    fun theHistoryGlidesUpWhenTheCardGrows() {
        show(three, reduce = false)
        val before = top("row-\$m3")
        val cardBefore = cardHeight()

        thought = "Checking whether the roster is already sorted."
        // The first frame in which the card has its new height.
        untilTheCardGrows(cardBefore)
        val oneFrame = top("row-\$m3")
        val cardOneFrame = cardHeight()
        compose.mainClock.advanceTimeBy(100)
        val midway = top("row-\$m3")
        compose.mainClock.advanceTimeBy(1_000)
        val settled = top("row-\$m3")
        val cardSettled = cardHeight()

        assertTrue("the card grew: $cardBefore to $cardSettled", cardSettled > cardBefore + 10f)
        assertTrue("the history ends up higher: $before to $settled", settled < before - 10f)
        // It was drawn where it was, not already at its new place.
        // Drawn where it was, give or take the first frame's step of the curve (under 10%).
        assertTrue("first frame: $oneFrame, was $before, ends $settled", before - oneFrame < 0.1f * (before - settled) + 1f)
        assertTrue("midway $midway is between $oneFrame and $settled", midway < oneFrame - 1f && midway > settled + 1f)
        // And the card's own resize never animated.
        assertEquals("the card is at its full height at once", cardSettled, cardOneFrame, 0.5f)
    }

    @Test
    fun theGlideTakesAboutThirtyTwoHundredthsOfASecond() {
        show(three, reduce = false)
        val before = top("row-\$m3")
        thought = "Checking."
        untilTheCardGrows(cardHeight())
        compose.mainClock.advanceTimeBy(250)
        val at250 = top("row-\$m3")
        compose.mainClock.advanceTimeBy(150)
        val at400 = top("row-\$m3")
        compose.mainClock.advanceTimeBy(1_000)
        val settled = top("row-\$m3")
        assertTrue("still gliding at ~270 ms: $at250 vs settled $settled", at250 > settled + 0.5f)
        assertEquals("done by ~420 ms", settled, at400, 0.5f)
        assertTrue(before > settled)
    }

    /** Leaving a room mid-glide: the next room's history is at rest, not carrying the old offset. */
    @Test
    fun switchingRoomsStopsTheGlide() {
        show(three, reduce = false)
        val cardBefore = cardHeight()
        thought = "Checking whether the roster is already sorted."
        untilTheCardGrows(cardBefore)
        compose.mainClock.advanceTimeBy(100) // mid-glide
        // The other room: its own rows, no turn.
        room = "!b:x"
        shown = (11..13).map(::message)
        revision += 1uL
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        val oneFrame = top("row-\$m13")
        compose.mainClock.advanceTimeBy(1_000)
        val settled = top("row-\$m13")
        assertEquals("the new room's history is at rest at once", settled, oneFrame, 0.5f)
    }

    @Test
    fun withMotionReducedTheHistoryStepsAtOnce() {
        show(three, reduce = true)
        val before = top("row-\$m3")
        val cardBefore = cardHeight()
        thought = "Checking."
        untilTheCardGrows(cardBefore)
        val oneFrame = top("row-\$m3")
        compose.mainClock.advanceTimeBy(1_000)
        val settled = top("row-\$m3")
        assertTrue(settled < before - 10f)
        assertEquals(settled, oneFrame, 0.5f)
    }

    @Test
    fun farBackTheHistoryStaysPut() {
        val many = (1..40).map(::message)
        show(many, reduce = false)
        compose.onNodeWithTag("timeline-list").performScrollToIndex(10)
        compose.mainClock.advanceTimeBy(500)
        val tag = "row-\$m30"
        val before = top(tag)
        thought = "Checking."
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        val oneFrame = top(tag)
        compose.mainClock.advanceTimeBy(100)
        val midway = top(tag)
        compose.mainClock.advanceTimeBy(1_000)
        val settled = top(tag)
        assertEquals("one frame in", before, oneFrame, 1f)
        assertEquals("midway", before, midway, 1f)
        assertEquals("settled", before, settled, 1f)
    }

    /** Off the newest message the list does not glide (iOS: "only at the newest message"). */
    @Test
    fun justOffTheNewestMessageTheHistoryIsNotGlided() {
        val many = (1..40).map(::message)
        show(many, reduce = false)
        compose.onNodeWithTag("timeline-list").performTouchInput { swipeDown(startY = centerY, endY = centerY + 60f) }
        compose.mainClock.advanceTimeBy(1_000)
        val tag = "row-\$m38"
        val cardBefore = cardHeight()
        val before = top(tag)
        thought = "Checking."
        untilTheCardGrows(cardBefore)
        val oneFrame = top(tag)
        compose.mainClock.advanceTimeBy(1_000)
        val settled = top(tag)
        assertTrue("the history moved at all: $before to $settled", settled < before - 10f)
        assertEquals("no glide: it is at its place in the first frame", settled, oneFrame, 0.5f)
    }

    /** The lesson from the desktop: a jump to history is not pulled back by the growing card. */
    @Test
    fun aQuoteJumpIsNotPulledBackByTheGrowingCard() {
        val rows = (0..39).map {
            val base = message(it)
            if (it == 39) base.copy(
                item = base.item.copy(replyTo = uniffi.supermessage_core.ReplyToDto("\$m10", true, "@a:x", "A", "quoted", null)),
                replyQuote = uniffi.supermessage_core.ReplyQuoteView.Available("A", "quoted", null, "@a:x"),
            ) else base
        }
        show(rows, reduce = true)
        compose.onNode(hasTestTag("reply-quote") and hasAnyAncestor(hasTestTag("row-\$m39")), useUnmergedTree = true).performClick()
        compose.mainClock.advanceTimeBy(2_000)
        val tag = "row-\$m10"
        val before = top(tag)
        thought = "Checking."
        steps = listOf(turnStep("a"))
        compose.mainClock.advanceTimeByFrame()
        assertEquals(before, top(tag), 1f)
        compose.mainClock.advanceTimeBy(2_000)
        assertEquals(before, top(tag), 1f)
    }

    /** The card is one cell with one key: its own state survives the growth. */
    @Test
    fun theCardIsNotRemountedAsItGrows() {
        thought = "Checking."
        show(three, reduce = true)
        compose.onNodeWithTag("thought-toggle").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("thought-body").assertExists()
        thought = "Checking. And then checking again, which makes the reasoning longer."
        steps = listOf(turnStep("a"), turnStep("b"))
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("thought-body").assertExists()
    }
}
