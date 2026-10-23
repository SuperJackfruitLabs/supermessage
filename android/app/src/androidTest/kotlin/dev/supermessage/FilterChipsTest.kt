package dev.supermessage

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uniffi.supermessage_core.RosterFilter
import uniffi.supermessage_core.RosterFilterChip

/** [FilterChips], the All · Unread row (`Rooms/RosterFilterChips.swift`). */
class FilterChipsTest {
    @get:Rule val compose = createComposeRule()

    private class RecordingHaptics : HapticFeedback {
        val played = mutableListOf<HapticFeedbackType>()
        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { played += hapticFeedbackType }
    }

    private fun chips(unread: UInt) = listOf(
        RosterFilterChip(RosterFilter.ALL, "All", 5u),
        RosterFilterChip(RosterFilter.UNREAD, "Unread", unread),
    )

    private val haptics = RecordingHaptics()

    private fun show(
        list: List<RosterFilterChip> = chips(2u),
        selected: RosterFilter = RosterFilter.ALL,
        fontScale: Float = 1f,
        onSelect: (RosterFilter) -> Unit = {},
    ) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalHapticFeedback provides haptics,
                LocalDensity provides Density(density.density, fontScale),
            ) {
                Box(Modifier.requiredWidth(411.dp)) { FilterChips(list, selected, onSelect) }
            }
        }
    }

    @Test
    fun twoChipsTheSecondCounted() {
        show()
        compose.onNodeWithTag("chip-all").assertIsSelected()
        compose.onNodeWithTag("chip-unread").assertIsNotSelected()
        compose.onNodeWithTag("chip-count-all", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("chip-count-unread", useUnmergedTree = true).assertTextEquals("2")
    }

    @Test
    fun aZeroCountIsNotShown() {
        show(chips(0u))
        compose.onNodeWithTag("chip-count-unread", useUnmergedTree = true).assertDoesNotExist()
        // It is still said: a chip that leaves nothing is worth knowing about before it is tried.
        compose.onNodeWithTag("chip-unread").assertContentDescriptionEquals("Unread, 0")
    }

    @Test
    fun theLabelSaysTheCount() {
        show()
        compose.onNodeWithTag("chip-unread").assertContentDescriptionEquals("Unread, 2")
        compose.onNodeWithTag("chip-all").assertContentDescriptionEquals("All, 5")
    }

    @Test
    fun choosingAChipIsASelectionHaptic() {
        val chosen = mutableListOf<RosterFilter>()
        show(onSelect = { chosen += it })
        compose.onNodeWithTag("chip-unread").performClick()
        assertEquals(listOf(RosterFilter.UNREAD), chosen)
        assertEquals(listOf(HapticFeedbackType.SegmentTick), haptics.played)
    }

    @Test
    fun choosingTheChipInForceDoesNothing() {
        val chosen = mutableListOf<RosterFilter>()
        show(onSelect = { chosen += it })
        compose.onNodeWithTag("chip-all").performClick()
        assertEquals(emptyList<RosterFilter>(), chosen)
        assertEquals(emptyList<HapticFeedbackType>(), haptics.played)
    }

    @Test
    fun theSelectedChipFollowsTheCaller() {
        var selected by mutableStateOf(RosterFilter.ALL)
        compose.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides haptics) {
                Box(Modifier.requiredWidth(411.dp)) { FilterChips(chips(2u), selected, { selected = it }) }
            }
        }
        compose.onNodeWithTag("chip-unread").performClick()
        compose.onNodeWithTag("chip-unread").assertIsSelected()
        compose.onNodeWithTag("chip-all").assertIsNotSelected()
    }

    /** RF2: at twice the text size the row scrolls rather than wrapping or cutting a chip. */
    @Test
    fun theRowScrollsAtFontScaleTwo() {
        show(fontScale = 2f)
        for (key in listOf("all", "unread")) {
            compose.onNodeWithTag("chip-$key").performScrollTo().assertIsDisplayed()
        }
        for (tag in listOf("chip-all", "chip-unread")) {
            val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
            assertTrue("$tag has a height", node.size.height > 0)
        }
        // Each word is a single line: the text nodes are the chips' children.
        val titles = compose.onNodeWithTag("chip-unread", useUnmergedTree = true)
            .fetchSemanticsNode().children.flatMap { it.children + it }
            .filter { it.config.getOrNull(SemanticsActions.GetTextLayoutResult) != null }
        assertTrue(titles.isNotEmpty())
        for (node in titles) {
            val results = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
            assertEquals(1, results.single().lineCount)
        }
    }
}
