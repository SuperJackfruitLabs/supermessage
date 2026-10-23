package dev.supermessage

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uniffi.supermessage_core.RichBlock
import uniffi.supermessage_core.RichInline

/** The reading view (`LongReadSheet`): serif, titled, closed by Done. Run at 1080x2400 and 1080x1280. */
class LongReadTest {
    @get:Rule val compose = createComposeRule()

    private fun paragraphs(n: Int) =
        (1..n).map { RichBlock.Paragraph(inlines = listOf(RichInline.Text("Paragraph number $it of the report."))) }

    private fun show(n: Int = 40, onDone: () -> Unit = {}) {
        compose.setContent {
            SupermessageTheme {
                Box(Modifier.fillMaxSize()) { LongReadView(title = "Atlas — Platform", blocks = paragraphs(n), onDone = onDone) }
            }
        }
        compose.waitForIdle()
    }

    private fun fontOf(node: SemanticsNode): FontFamily? {
        val out = mutableListOf<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(out)
        return out.firstOrNull()?.layoutInput?.style?.fontFamily
    }

    @Test
    fun theReadingViewIsSerif() {
        show()
        val text = compose.onNode(hasText("Paragraph number 1 of the report.") and hasAnyAncestor(hasTestTag("long-read-view")), useUnmergedTree = true)
        assertEquals(ThemeType.longread, fontOf(text.fetchSemanticsNode()))
        assertTrue("the reading face is not the body face", ThemeType.longread != SupermessageThemeFonts.body)
    }

    @Test
    fun theReadingViewFitsAtTheShortHeight() {
        show()
        val title = compose.onNodeWithText("Atlas — Platform")
        title.assertIsDisplayed()
        compose.onNodeWithText("Done").assertIsDisplayed()
        compose.onNode(hasText("Paragraph number 1 of the report.") and hasAnyAncestor(hasTestTag("long-read-view")), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun doneAsksToClose() {
        var done = 0
        show(onDone = { done++ })
        compose.onNodeWithText("Done").performClick()
        assertEquals(1, done)
    }
}
