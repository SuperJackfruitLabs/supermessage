package dev.supermessage

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uniffi.supermessage_core.ToolPhase
import uniffi.supermessage_core.TurnEnding
import uniffi.supermessage_core.TurnStep
import uniffi.supermessage_core.TurnSummary

/**
 * The activity card — see `LiveTurn.kt` and `apple/Supermessage/Timeline/LiveTurnView.swift`.
 * Every word asserted here is the one the test handed in or the core's own
 * (`turnStatusWord`, `elapsedLabel`); the card decides none of them.
 */
class LiveTurnTest {
    @get:Rule val compose = createComposeRule()

    private fun show(
        summary: TurnSummary? = inProgress(),
        steps: List<TurnStep> = emptyList(),
        thought: String? = null,
        answer: String? = null,
        name: String = "Atlas",
        still: Boolean = false,
        reduce: Boolean = true,
        nowMs: () -> Long = { 8_000L },
    ) = compose.setContent {
        CompositionLocalProvider(LocalReduceMotion provides reduce) {
            Box(Modifier.requiredWidth(411.dp)) {
                LiveTurnCard(summary, steps, thought, answer, name, still, nowMs)
            }
        }
    }

    /** The text of the plain line still being written (`live-answer-tail`), or `null` if there is none. */
    private fun answerText(): String? {
        val nodes = compose.onAllNodesWithTag("live-answer-tail").fetchSemanticsNodes()
        if (nodes.isEmpty()) return null
        return nodes.first().config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
    }

    private fun text(tag: String) =
        compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }

    // ── Header ──────────────────────────────────────────────────────────

    @Test
    fun theCardSaysWhoAndHowLong() {
        show(summary = inProgress(startedAtMs = 1_000_000L, lastActivityMs = 1_064_000L), nowMs = { 1_064_000L })
        compose.onNodeWithText("Atlas").assertIsDisplayed()
        assertEquals("Working", text("live-turn-state"))
        assertEquals("· 1m 04s", text("live-elapsed"))
    }

    @Test
    fun theElapsedTimeTicks() {
        compose.mainClock.autoAdvance = false
        show(
            summary = inProgress(startedAtMs = 1_000_000L, lastActivityMs = 1_008_000L),
            nowMs = { 1_008_000L + compose.mainClock.currentTime },
        )
        compose.mainClock.advanceTimeByFrame()
        assertEquals("· 8s", text("live-elapsed"))
        compose.mainClock.advanceTimeBy(2_100)
        assertEquals("· 10s", text("live-elapsed"))
    }

    @Test
    fun aStillCardMeasuresToTheLastEventNotTheClock() {
        show(summary = inProgress(startedAtMs = 0L, lastActivityMs = 8_000L), still = true, nowMs = { 999_000_000L })
        assertEquals("· 8s", text("live-elapsed"))
    }

    @Test
    fun aFinishedCardSaysDone() {
        show(summary = ended(TurnEnding.DONE), nowMs = { 999_000_000L })
        assertEquals("Done", text("live-turn-state"))
        assertEquals("· 12s", text("live-elapsed"))
    }

    /** Owner ruling: a timed-out turn is "Stopped", not "Done". */
    @Test
    fun aTurnTheCoreGaveUpOnSaysStopped() {
        show(summary = ended(TurnEnding.TIMED_OUT))
        assertEquals("Stopped", text("live-turn-state"))
    }

    // ── The current step ────────────────────────────────────────────────

    @Test
    fun theCurrentStepIsTheCoresTitle() {
        var summary by mutableStateOf(inProgress(currentStepTitle = "Run tests", stepIsTool = true))
        compose.setContent {
            CompositionLocalProvider(LocalReduceMotion provides true) {
                Box(Modifier.requiredWidth(411.dp)) {
                    LiveTurnCard(summary, emptyList(), null, "The answer is already on screen.", "Atlas", still = true)
                }
            }
        }
        // A tool is running: the line stays even though the answer is showing.
        compose.onNodeWithText("Run tests").assertIsDisplayed()
        // Not a tool, and the answer is showing: the line says nothing the text does not.
        summary = inProgress(currentStepTitle = "Writing the answer", stepIsTool = false)
        compose.waitForIdle()
        compose.onNodeWithTag("live-current-step").assertDoesNotExist()
    }

    @Test
    fun theCurrentStepShowsBeforeAnyAnswerTextIs() {
        show(summary = inProgress(currentStepTitle = "Thinking"), answer = null)
        compose.onNodeWithTag("live-current-step").assertIsDisplayed()
        compose.onNodeWithText("Thinking").assertIsDisplayed()
    }

    @Test
    fun aFinishedCardShowsNoCurrentStep() {
        show(summary = ended(TurnEnding.DONE))
        compose.onNodeWithTag("live-current-step").assertDoesNotExist()
    }

    @Test
    fun reduceMotionStillsTheSpinner() {
        show(reduce = true)
        compose.onNodeWithTag("live-progress-still", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("live-progress", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun withMotionTheSpinnerTurns() {
        compose.mainClock.autoAdvance = false
        show(reduce = false)
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("live-progress", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("live-progress-still", useUnmergedTree = true).assertDoesNotExist()
    }

    // ── Steps ───────────────────────────────────────────────────────────

    @Test
    fun stepsDoneAndTheFailureAreCounted() {
        var summary by mutableStateOf(inProgress(totalSteps = 3, completedSteps = 2, failedStep = "Lint"))
        compose.setContent {
            CompositionLocalProvider(LocalReduceMotion provides true) {
                Box(Modifier.requiredWidth(411.dp)) {
                    LiveTurnCard(summary, listOf(turnStep("a"), turnStep("b")), null, null, "Atlas")
                }
            }
        }
        compose.onNodeWithText("2 steps done").assertIsDisplayed()
        compose.onNodeWithText("Lint failed").assertIsDisplayed()
        summary = inProgress(totalSteps = 1, completedSteps = 1)
        compose.waitForIdle()
        compose.onNodeWithText("1 step done").assertIsDisplayed()
        compose.onNodeWithTag("live-failed-step").assertDoesNotExist()
    }

    @Test
    fun theStepsOpenOntoTheirDetail() {
        val steps = listOf(
            turnStep("a", title = "Read a.rs"),
            turnStep("b", title = "Run tests", hasDetail = true, input = "cargo test", output = "ok", locations = listOf("src/a.rs", "src/b.rs")),
            turnStep("c", title = "Write c.rs"),
        )
        show(summary = inProgress(totalSteps = 3, completedSteps = 3), steps = steps)
        compose.onNodeWithText("All 3 steps").assertIsDisplayed()
        compose.onAllNodesWithTag("tool-row").assertCountEquals(0)
        compose.onNodeWithTag("steps-toggle").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithTag("tool-row").assertCountEquals(3)
        compose.onAllNodesWithTag("tool-detail").assertCountEquals(0)
        compose.onAllNodesWithTag("tool-row")[1].performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("tool-detail").assertIsDisplayed()
        compose.onNodeWithText("Input").assertIsDisplayed()
        compose.onNodeWithText("cargo test").assertIsDisplayed()
        compose.onNodeWithText("Touched").assertIsDisplayed()
        compose.onNodeWithText("Output").assertIsDisplayed()
    }

    @Test
    fun aStepWithNothingBehindItDoesNotOpen() {
        show(summary = inProgress(totalSteps = 1), steps = listOf(turnStep("a", title = "Read a.rs")))
        compose.onNodeWithTag("steps-toggle").performClick()
        compose.onNodeWithTag("tool-row").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("tool-detail").assertDoesNotExist()
    }

    @Test
    fun oneStepSaysOneStep() {
        show(summary = inProgress(totalSteps = 1), steps = listOf(turnStep("a")))
        compose.onNodeWithText("1 step").assertIsDisplayed()
        compose.onAllNodesWithText("All 1 steps").assertCountEquals(0)
    }

    @Test
    fun toolCallsAreNamedWithTheCoresWord() {
        show(
            summary = inProgress(totalSteps = 1),
            steps = listOf(turnStep("a", title = "Read src/main.ts", phase = ToolPhase.FAILED, statusLabel = "Failed")),
        )
        compose.onNodeWithTag("steps-toggle").performClick()
        compose.onNodeWithText("Read src/main.ts").assertIsDisplayed()
        compose.onNodeWithText("Failed").assertIsDisplayed()
    }

    // ── Reasoning ───────────────────────────────────────────────────────

    @Test
    fun theThoughtStartsCollapsed() {
        show(thought = "Because the file already imports that helper.")
        compose.onNodeWithTag("thought-body").assertDoesNotExist()
        compose.onNodeWithTag("thought-toggle").performClick()
        compose.onNodeWithText("Because the file already imports that helper.").assertIsDisplayed()
    }

    // ── The answer ──────────────────────────────────────────────────────

    /** A bold line is drawn bold while it streams, not as raw `**` markup. */
    @Test
    fun aBoldLineIsBoldWhileStreaming() {
        show(answer = "**Observed:** ok\nnext", still = true)
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Observed:", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Observed:", substring = true).assertIsDisplayed()
        compose.onAllNodesWithText("**", substring = true).assertCountEquals(0)
        compose.onNodeWithTag("live-answer-tail").assertTextEquals("next")
    }

    /** The reveal advances over time rather than appearing whole. */
    @Test
    fun theAnswerRevealsProgressively() {
        val answer = "word ".repeat(80).trim()
        show(answer = answer)
        compose.waitUntil(timeoutMillis = 10_000) {
            val shown = answerText()
            shown != null && shown.isNotEmpty() && shown.length < answer.length
        }
        compose.waitUntil(timeoutMillis = 10_000) { answerText() == answer }
    }

    @Test
    fun aFinishedCardShowsItsWholeAnswer() {
        val answer = "The whole answer, landing at once."
        show(summary = ended(TurnEnding.DONE), answer = answer)
        compose.waitUntil(timeoutMillis = 5_000) { answerText() == answer }
    }

    // ── Large text (RF3) ────────────────────────────────────────────────

    private fun lineCount(tag: String): Int {
        val node = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        compose.runOnUiThread {
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        }
        return results.first().lineCount
    }

    @Test
    fun theCardFitsAtFontScaleTwo() {
        compose.setContent {
            CompositionLocalProvider(
                LocalReduceMotion provides true,
                LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f),
            ) {
                Box(Modifier.requiredWidth(411.dp)) {
                    LiveTurnCard(
                        inProgress(
                            totalSteps = 3, completedSteps = 1,
                            failedStep = "Run the integration suite against staging",
                        ),
                        listOf(turnStep("a")), null, null,
                        name = "A".repeat(60),
                    )
                }
            }
        }
        compose.waitForIdle()
        assertEquals("the name stays on one line", 1, lineCount("live-name"))
        val word = compose.onNodeWithTag("live-turn-state", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val card = compose.onNodeWithTag("live-turn").fetchSemanticsNode().boundsInRoot
        assertTrue("Working ends at ${word.right}, card at ${card.right}", word.right <= card.right)
        assertTrue("Working is drawn, not squeezed: width ${word.width}", word.width > 0f)
        assertTrue("the failed step takes at most two lines", lineCount("live-failed-step-text") <= 2)
    }
}
