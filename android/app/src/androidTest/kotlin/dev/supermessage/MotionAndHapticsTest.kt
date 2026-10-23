package dev.supermessage

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import dev.supermessage.kit.NotePlayback
import dev.supermessage.previews.PreviewFixtures
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uniffi.supermessage_core.ItemView
import uniffi.supermessage_core.RichBlock
import uniffi.supermessage_core.RichInline
import uniffi.supermessage_core.SystemKind
import uniffi.supermessage_core.TimelineRow as TimelineRowDto
import uniffi.supermessage_core.TurnStep

/**
 * M1 motion and M2 haptics at the sites no earlier task covered, and the
 * system "remove animations" setting itself. Motion is observed as geometry
 * on a paused clock (`mainClock.autoAdvance = false`): where a row is a frame
 * after the change, against where it settles.
 */
class MotionAndHapticsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private class RecordingHaptics : HapticFeedback {
        val played = mutableListOf<HapticFeedbackType>()
        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
            played += hapticFeedbackType
        }
    }

    private val haptics = RecordingHaptics()

    private fun message(n: Int): TimelineRowDto = PreviewFixtures.row(
        PreviewFixtures.item("\$m$n", body = "message $n", atMs = 1_757_700_000_000uL + n.toULong() * 1_000uL),
        ItemView.Bubble(
            muted = false,
            blocks = listOf(RichBlock.Paragraph(listOf(RichInline.Text("message $n")))),
            voice = null,
        ),
    )

    private fun membership(n: Int): TimelineRowDto = PreviewFixtures.row(
        PreviewFixtures.item("\$j$n", kind = "membership", msgtype = null, body = null, atMs = 1_757_700_000_000uL + n.toULong() * 1_000uL),
        ItemView.System(kind = SystemKind.MembershipChanged(who = "w$n", detail = "joined"), text = "w$n joined"),
        membershipVerb = "joined",
        canReplyOrReact = false,
    )

    // ── The finished turn is a success haptic, once ─────────────────────

    @Test
    fun aFinishedTurnIsASuccessHapticOnce() {
        var finished by mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides haptics, LocalReduceMotion provides true) {
                Timeline(
                    rows = listOf(message(1)), revision = 0uL, typingLine = null,
                    isPaginating = false, canPaginate = false, onPaginate = {}, onMarkRead = {},
                    liveAnswer = null, liveThought = "thinking", liveFinished = finished,
                )
            }
        }
        compose.waitForIdle()
        assertEquals(emptyList<HapticFeedbackType>(), haptics.played)
        finished = true
        compose.waitForIdle()
        assertEquals(listOf(HapticFeedbackType.Confirm), haptics.played)
        // A recomposition that changes nothing about the turn plays nothing more.
        finished = true
        compose.waitForIdle()
        assertEquals(listOf(HapticFeedbackType.Confirm), haptics.played)
    }

    @Test
    fun aRoomOpenedOnAnAlreadyFinishedTurnPlaysNothing() {
        compose.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides haptics, LocalReduceMotion provides true) {
                Timeline(
                    rows = listOf(message(1)), revision = 0uL, typingLine = null,
                    isPaginating = false, canPaginate = false, onPaginate = {}, onMarkRead = {},
                    liveAnswer = null, liveThought = "thinking", liveFinished = true,
                )
            }
        }
        compose.waitForIdle()
        assertEquals(emptyList<HapticFeedbackType>(), haptics.played)
    }

    /**
     * A turn that goes away without ever finishing (the store was cleared:
     * the turn timed out, the room was left) is not a success.
     */
    @Test
    fun aTurnThatEndsWithoutFinishingPlaysNoHaptic() {
        var answer by mutableStateOf<String?>("writing")
        compose.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides haptics, LocalReduceMotion provides true) {
                Timeline(
                    rows = listOf(message(1)), revision = 0uL, typingLine = null,
                    isPaginating = false, canPaginate = false, onPaginate = {}, onMarkRead = {},
                    liveAnswer = answer, liveFinished = false,
                )
            }
        }
        compose.waitForIdle()
        answer = null
        compose.waitForIdle()
        assertEquals(emptyList<HapticFeedbackType>(), haptics.played)
    }

    // ── A voice note's play control is a selection ──────────────────────

    @Test
    fun playingAVoiceNoteIsASelection() {
        compose.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides haptics, LocalReduceMotion provides true) {
                AudioNote(
                    audio = PreviewFixtures.voiceView(), isOwn = false, playback = NotePlayback.Idle,
                    onToggle = {}, onSeek = null,
                )
            }
        }
        compose.onNodeWithTag("audio-button").performClick()
        compose.waitForIdle()
        assertEquals(listOf(HapticFeedbackType.SegmentTick), haptics.played)
    }

    // ── The live card's Reasoning disclosure ────────────────────────────

    private fun showReasoning(reduce: Boolean) {
        compose.mainClock.autoAdvance = true
        compose.setContent {
            CompositionLocalProvider(LocalReduceMotion provides reduce) {
                Box(Modifier.requiredWidth(411.dp)) {
                    LiveTurn(
                        answer = null,
                        thought = "A long line of reasoning that wraps onto several lines. ".repeat(6),
                        tools = emptyList<TurnStep>(),
                        finished = false,
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("thought-toggle").performClick()
    }

    private fun reasoningHeight() =
        compose.onNodeWithTag("thought-region").fetchSemanticsNode().size.height

    @Test
    fun reasoningExpandsWithoutMotionWhenReduced() {
        showReasoning(reduce = true)
        compose.mainClock.advanceTimeByFrame()
        val afterOneFrame = reasoningHeight()
        compose.mainClock.advanceTimeBy(1_000)
        val settled = reasoningHeight()
        assertTrue("settled height $settled", settled > 0)
        assertEquals(settled, afterOneFrame)
    }

    @Test
    fun reasoningAnimatesWhenNotReduced() {
        showReasoning(reduce = false)
        compose.mainClock.advanceTimeByFrame()
        val afterOneFrame = reasoningHeight()
        compose.mainClock.advanceTimeBy(1_000)
        val settled = reasoningHeight()
        assertTrue("one frame in: $afterOneFrame, settled: $settled", afterOneFrame < settled)
    }

    // ── Arrival and placement on the timeline ───────────────────────────

    private fun showTimeline(
        rows: () -> List<TimelineRowDto>,
        revision: () -> ULong,
        reduce: Boolean,
        streaming: Boolean = false,
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalReduceMotion provides reduce, LocalHapticFeedback provides haptics) {
                Box(Modifier.requiredWidth(411.dp)) {
                    Timeline(
                        rows = rows(), revision = revision(), typingLine = null,
                        isPaginating = false, canPaginate = false, onPaginate = {}, onMarkRead = {},
                        liveAnswer = if (streaming) "writing a reply" else null,
                        liveFinished = false,
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    private fun top(id: String) = compose.onNodeWithTag("row-$id").fetchSemanticsNode().boundsInRoot.top

    private fun animationDecision() =
        compose.onNodeWithTag("timeline-animation-decision").fetchSemanticsNode().config
            .let { it[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription].first() }

    /** Positions of [id] one frame after [change], and once everything has settled. */
    private fun earlyAndSettled(id: String, change: () -> Unit): Pair<Float, Float> {
        change()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        val early = top(id)
        compose.mainClock.advanceTimeBy(3_000)
        return early to top(id)
    }

    @Test
    fun anArrivingRowRisesIntoPlace() {
        var rows by mutableStateOf(listOf(message(1), message(2), message(3)))
        var revision by mutableStateOf(0uL)
        showTimeline({ rows }, { revision }, reduce = false)
        val (early, settled) = earlyAndSettled("\$m4") {
            rows = rows + message(4)
            revision++
        }
        // It starts below its place (16dp is ~42px at 420dpi) and ends at it.
        assertTrue("early $early settled $settled", early > settled)
        assertTrue("rise is bounded: early $early settled $settled", early - settled <= 16 * 2.7f + 2f)
    }

    @Test
    fun anArrivingRowIsInPlaceAtOnceWhenMotionIsReduced() {
        var rows by mutableStateOf(listOf(message(1), message(2), message(3)))
        var revision by mutableStateOf(0uL)
        showTimeline({ rows }, { revision }, reduce = true)
        val (early, settled) = earlyAndSettled("\$m4") {
            rows = rows + message(4)
            revision++
        }
        assertEquals(settled, early, 0.5f)
    }

    @Test
    fun olderRowsGlideUpWhenNoTurnIsStreaming() {
        var rows by mutableStateOf(listOf(message(1), message(2), message(3)))
        var revision by mutableStateOf(0uL)
        showTimeline({ rows }, { revision }, reduce = false)
        val (early, settled) = earlyAndSettled("\$m3") {
            rows = rows + message(4)
            revision++
        }
        // The control for the next test: placement does animate here.
        assertNotEquals(settled, early, 0.5f)
    }

    /**
     * Owner ruling: M4 ports iOS's 0.32 s history glide; until then history
     * rows keep still while a turn streams, so a row arriving does not make
     * the rows above it spring.
     */
    @Test
    fun historyRowsDoNotAnimatePlacementWhileATurnStreams() {
        var rows by mutableStateOf(listOf(message(1), message(2), message(3)))
        var revision by mutableStateOf(0uL)
        showTimeline({ rows }, { revision }, reduce = false, streaming = true)
        val (early, settled) = earlyAndSettled("\$m3") {
            rows = rows + message(4)
            revision++
        }
        assertEquals("history row moved by a spring: early $early settled $settled", settled, early, 0.5f)
    }

    /** Pagination: older rows prepended, at most three of them, are not arrivals. */
    @Test
    fun aPrependedPageNeverAnimates() {
        var rows by mutableStateOf(listOf(message(3), message(4), message(5)))
        var revision by mutableStateOf(0uL)
        showTimeline({ rows }, { revision }, reduce = false)
        rows = listOf(message(1), message(2)) + rows
        revision++
        compose.mainClock.advanceTimeByFrame()
        assertEquals("static", animationDecision())
    }

    /** The limit of three counts every new item, membership lines included. */
    @Test
    fun theArrivalLimitCountsRowsBeforeFilteringToMessages() {
        var rows by mutableStateOf(listOf(message(1), message(2), message(3)))
        var revision by mutableStateOf(0uL)
        showTimeline({ rows }, { revision }, reduce = false)
        rows = rows + listOf(message(4), message(5), membership(6), membership(7))
        revision++
        compose.mainClock.advanceTimeByFrame()
        assertEquals("static", animationDecision())
    }

    // ── The system setting ──────────────────────────────────────────────

    private fun shell(command: String): String {
        val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes().decodeToString() }.trim()
    }

    private var originalScale: String? = null

    @After
    fun restoreTheAnimatorScale() {
        originalScale?.let { shell("settings put global animator_duration_scale $it") }
    }

    @Test
    fun systemReduceMotionFollowsTheAnimatorScale() {
        originalScale = shell("settings get global animator_duration_scale").ifEmpty { "1.0" }
        shell("settings put global animator_duration_scale 1")
        compose.setContent { Text(reducesMotion().toString(), Modifier.testTag("reduces")) }
        compose.waitForIdle()
        compose.onNodeWithTag("reduces").assertTextEquals("false")

        shell("settings put global animator_duration_scale 0")
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        compose.onNodeWithTag("reduces").assertTextEquals("true")
    }
}
