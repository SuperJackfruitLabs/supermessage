package dev.supermessage

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** [SwipeActionsRow] on its own: what its anchors follow. */
class SwipeActionsRowTest {
    @get:Rule val compose = createComposeRule()

    private val ran = mutableListOf<String>()
    private fun action(label: String) = SwipeAction(label, Color.Gray) { ran += label; true }

    private var leading by mutableStateOf<SwipeAction?>(null)
    private var trailing by mutableStateOf(listOf(action("One")))

    private fun show() {
        compose.setContent {
            CompositionLocalProvider(LocalReduceMotion provides true) {
                Box(Modifier.requiredWidth(411.dp)) {
                    SwipeActionsRow(leading = leading, trailing = trailing) { semantics ->
                        Box(Modifier.fillMaxWidth().height(60.dp).then(semantics).testTag("content")) { Text("row") }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /** A leading action that appears later is reachable although the size never changed. */
    @Test
    fun aLeadingActionAddedLaterCanBeSwipedTo() {
        show()
        leading = action("Lead")
        compose.waitForIdle()
        compose.onNodeWithTag("content").performTouchInput { swipeRight() }
        compose.waitForIdle()
        assertEquals(listOf("Lead"), ran)
    }

    /** More trailing buttons reveal more width, not the width of the first set. */
    @Test
    fun moreTrailingActionsRevealTheirFullWidth() {
        show()
        trailing = listOf(action("One"), action("Two"))
        compose.waitForIdle()
        compose.onNodeWithTag("content").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(-144f, compose.onNodeWithTag("content").getUnclippedBoundsInRoot().left.value, 1f)
    }
}
