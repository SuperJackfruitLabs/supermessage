package dev.supermessage

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import dev.supermessage.kit.stores.LiveStore

/**
 * An agent's turn while it is arriving — see `LiveTurn.kt`'s own KDoc and
 * `apple/Supermessage/Timeline/LiveTurnView.swift`.
 */
class LiveTurnTest {
    @get:Rule val compose = createComposeRule()

    /** The text currently on the `live-answer` node, or `null` if it is not composed. */
    private fun answerText(): String? {
        val nodes = compose.onAllNodesWithTag("live-answer").fetchSemanticsNodes()
        if (nodes.isEmpty()) return null
        return nodes.first().config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
    }

    /**
     * The reveal advances over time rather than appearing whole.
     *
     * A long answer takes many ticks of `StreamingText`'s pacer to land in
     * full — this waits for a moment where some, but not all, of it is on
     * screen, which is only observable if the pacing loop actually runs.
     */
    @Test
    fun theAnswerRevealsProgressively() {
        val answer = "word ".repeat(80).trim() // long enough to span many ticks

        compose.setContent {
            LiveTurn(answer = answer, thought = null, tools = emptyList(), finished = false)
        }

        // Somewhere in the middle of the reveal, less than the whole answer
        // is showing. If this never becomes true, the answer either never
        // renders anything or jumps straight to the end.
        compose.waitUntil(timeoutMillis = 10_000) {
            val shown = answerText()
            shown != null && shown.isNotEmpty() && shown.length < answer.length
        }

        // It does eventually catch up to the whole thing.
        compose.waitUntil(timeoutMillis = 10_000) { answerText() == answer }
    }

    /** A finished turn shows its whole answer, with no reveal left pending. */
    @Test
    fun aFinishedTurnIsFullyRevealed() {
        val answer = "The whole answer, landing at once."

        compose.setContent {
            LiveTurn(answer = answer, thought = null, tools = emptyList(), finished = true)
        }

        compose.waitUntil(timeoutMillis = 5_000) { answerText() == answer }
        assertEquals(answer, answerText())
    }

    /** Thought is collapsed by default and expands on tap. */
    @Test
    fun theThoughtStartsCollapsed() {
        compose.setContent {
            LiveTurn(
                answer = null,
                thought = "Because the file already imports that helper.",
                tools = emptyList(),
                finished = false,
            )
        }

        compose.onNodeWithTag("thought-body").assertDoesNotExist()

        compose.onNodeWithTag("thought-toggle").performClick()

        compose.onNodeWithText("Because the file already imports that helper.").assertIsDisplayed()
    }

    /** Tool calls are listed by name. */
    @Test
    fun toolCallsAreNamed() {
        val tool = LiveStore.ToolCall(
            id = "call-1",
            title = "Read src/main.ts",
            status = "completed",
            kind = null,
            locations = emptyList(),
            input = null,
            output = null,
        )

        compose.setContent {
            LiveTurn(answer = null, thought = null, tools = listOf(tool), finished = false)
        }

        compose.onNodeWithText("Read src/main.ts").assertIsDisplayed()
    }
}
