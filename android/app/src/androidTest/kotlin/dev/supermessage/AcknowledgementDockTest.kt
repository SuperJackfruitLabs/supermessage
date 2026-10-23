package dev.supermessage

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uniffi.supermessage_core.Acknowledgement
import uniffi.supermessage_core.AcknowledgementKind

/** The dock and the typing pill (A3). The sentences are the core's; these tests pin how they are drawn. */
class AcknowledgementDockTest {
    @get:Rule val compose = createComposeRule()

    private fun sent(name: String) = Acknowledgement(AcknowledgementKind.SENT, listOf(name), "Sent to $name")
    private fun onIt(text: String, vararg names: String) = Acknowledgement(AcknowledgementKind.ON_IT, names.toList(), text)

    private fun show(reduce: Boolean = false, fontScale: Float? = null, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalReduceMotion provides reduce,
                LocalDensity provides if (fontScale == null) density else Density(density.density, fontScale),
            ) {
                SupermessageTheme { Box(Modifier.requiredWidth(411.dp).testTag("shell")) { content() } }
            }
        }
        compose.waitForIdle()
    }

    private fun pulsing(): Boolean? =
        compose.onNodeWithTag("dock-ellipsis", useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(PulsingKey)

    @Test
    fun sentSaysSentToTheAgent() {
        show { AcknowledgementDock(sent("Atlas")) }
        compose.onNodeWithText("Sent to Atlas").assertIsDisplayed()
        compose.onNodeWithTag("dock-sent", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("dock-working", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun onItSaysWhoIsOnIt() {
        show { AcknowledgementDock(onIt("Atlas is on it…", "Atlas")) }
        compose.onNodeWithText("Atlas is on it…").assertIsDisplayed()
        compose.onNodeWithTag("dock-working", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(true, pulsing())
    }

    @Test
    fun onItIsStillUnderReduceMotion() {
        show(reduce = true) { AcknowledgementDock(onIt("Atlas is on it…", "Atlas")) }
        compose.onNodeWithText("Atlas is on it…").assertIsDisplayed()
        assertEquals(false, pulsing())
    }

    @Test
    fun theDockIsOneAnnouncedElement() {
        show { AcknowledgementDock(sent("Atlas")) }
        val config = compose.onNodeWithTag("dock").fetchSemanticsNode().config
        assertEquals(listOf("Sent to Atlas"), config.getOrNull(SemanticsProperties.ContentDescription))
        assertTrue("announced as it changes", config.contains(SemanticsProperties.LiveRegion))
    }

    @Test
    fun theTypingPillSaysWhoIsTyping() {
        show { TypingPill("Krishna is typing…") }
        compose.onNodeWithTag("typing-pill").assertIsDisplayed()
        compose.onNodeWithText("Krishna is typing…").assertIsDisplayed()
    }

    @Test
    fun theAccessoriesShowNothingWithNothingToSay() {
        show { ComposerAccessories(ack = null, typingLine = null) }
        compose.onNodeWithTag("dock").assertDoesNotExist()
        compose.onNodeWithTag("typing-pill").assertDoesNotExist()
    }

    @Test
    fun theDockFitsAtFontScaleTwo() {
        show(reduce = true, fontScale = 2f) {
            Column {
                ComposerAccessories(
                    ack = onIt("Atlas, Quill and 2 others are on it…", "Atlas", "Quill"),
                    typingLine = "Krishna, Pat and 2 others are typing…",
                )
            }
        }
        val shell = compose.onNodeWithTag("shell").fetchSemanticsNode().boundsInRoot
        for (tag in listOf("dock", "typing-pill")) {
            val b = compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("$tag $b inside the screen $shell", b.left >= shell.left - 1f && b.right <= shell.right + 1f)
        }
        // One line, whatever the scale: the layout of the sentence has a single line (it may be cut with an ellipsis).
        val out = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText("Atlas, Quill and 2 others are on it…", useUnmergedTree = true)
            .fetchSemanticsNode().config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult)!!.action!!(out)
        assertEquals(1, out.first().lineCount)
    }
}
