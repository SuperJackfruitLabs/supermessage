package dev.supermessage

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uniffi.supermessage_core.ToolPhase
import uniffi.supermessage_core.TurnStep

/** "What I did": a finished turn's record in the answer's row — see `WhatIDid.kt`. */
class WhatIDidTest {
    @get:Rule val compose = createComposeRule()

    private val steps: List<TurnStep> = listOf(
        turnStep("a", title = "Read the roster"),
        turnStep("b", title = "Write the fix", phase = ToolPhase.FAILED, statusLabel = "Failed"),
    )

    private fun show(reduce: Boolean = true) = compose.setContent {
        CompositionLocalProvider(LocalReduceMotion provides reduce) {
            Box(Modifier.requiredWidth(411.dp)) {
                WhatIDid("Thought for 12s · 2 steps", "Because the roster was already sorted.", steps)
            }
        }
    }

    @Test
    fun theRecordIsTheCoresLabel() {
        show()
        compose.onNodeWithText("Thought for 12s · 2 steps").assertIsDisplayed()
        compose.onNodeWithTag("what-i-did-body").assertDoesNotExist()
    }

    @Test
    fun itOpensOntoTheReasoningAndSteps() {
        show()
        compose.onNodeWithTag("what-i-did-toggle").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("what-i-did-body").assertIsDisplayed()
        compose.onNodeWithText("Because the roster was already sorted.").assertIsDisplayed()
        compose.onNodeWithText("Read the roster").assertIsDisplayed()
        compose.onNodeWithText("Write the fix").assertIsDisplayed()
        compose.onNodeWithText("Failed").assertIsDisplayed()
    }

    /** The whole record's height: the body keeps its full size inside a container that is still opening. */
    private fun bodyHeight() = compose.onNodeWithTag("what-i-did").fetchSemanticsNode().size.height

    @Test
    fun itOpensWithoutMotionWhenReduced() {
        compose.mainClock.autoAdvance = true
        show(reduce = true)
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("what-i-did-toggle").performClick()
        compose.mainClock.advanceTimeByFrame()
        val afterOneFrame = bodyHeight()
        compose.mainClock.advanceTimeBy(1_000)
        val settled = bodyHeight()
        assertTrue("settled height $settled", settled > 0)
        assertEquals(settled, afterOneFrame)
    }

    @Test
    fun itOpensWithMotionOtherwise() {
        compose.mainClock.autoAdvance = true
        show(reduce = false)
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("what-i-did-toggle").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        val early = bodyHeight()
        compose.mainClock.advanceTimeBy(1_000)
        val settled = bodyHeight()
        assertTrue("early $early settled $settled", early < settled)
    }
}
