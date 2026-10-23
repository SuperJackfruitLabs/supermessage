package dev.supermessage

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.supermessage.kit.RosterArrangement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uniffi.supermessage_core.RoomRow

/** Agents (N3). The directory and each status are the core's; these tests pin how they are drawn. */
class AgentsTest {
    @get:Rule val compose = createComposeRule()

    private fun directory(rows: List<RoomRow>) = RosterArrangement.agentDirectory(rows, NavRows.NOW)

    private fun atlas(name: String = "Atlas", role: String? = "Platform") =
        NavRows.row("!a:x", name, role = role, agent = true, minutesAgo = 3.0)

    private fun show(
        rows: List<RoomRow>,
        openRoomId: String? = null,
        inProgress: Boolean = false,
        fontScale: Float? = null,
        opened: MutableList<String> = mutableListOf(),
        unread: Boolean = false,
    ) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalReduceMotion provides true,
                LocalDensity provides if (fontScale == null) density else Density(density.density, fontScale),
            ) {
                SupermessageTheme {
                    Box(Modifier.requiredWidth(411.dp).testTag("shell")) {
                        AgentsPane(
                            agents = if (unread) null else directory(rows),
                            now = NavRows.NOW,
                            openRoomId = openRoomId,
                            openRoomInProgress = inProgress,
                            avatarUri = { null },
                            onLoadAvatar = {},
                            onOpenRoom = { opened += it },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun inRow(tag: String, id: String = "!a:x") =
        compose.onNode(hasTestTag(tag) and hasAnyAncestor(hasTestTag("agent-row-$id")), useUnmergedTree = true)

    /** A directory not yet read is not an empty one: "No agents yet" waits for the roster. */
    @Test
    fun anUnreadDirectoryIsNotNoAgentsYet() {
        show(emptyList(), unread = true)
        compose.onNodeWithTag("no-agents").assertDoesNotExist()
        compose.onNodeWithText("No agents yet").assertDoesNotExist()
    }

    @Test
    fun anAgentRowSaysWhoWhatAndWhen() {
        show(listOf(atlas()))
        compose.onNodeWithTag("agent-row-!a:x").assertIsDisplayed()
        for (text in listOf("Atlas", "Platform", "3m")) {
            compose.onNode(hasText(text) and hasAnyAncestor(hasTestTag("agent-row-!a:x")), useUnmergedTree = true).assertIsDisplayed()
        }
        inRow("status-pill").assertContentDescriptionEquals("Active")
    }

    @Test
    fun theOpenRoomWithALiveTurnSaysWorking() {
        show(
            listOf(atlas(), NavRows.row("!b:x", "Quill", role = "Writing", agent = true, minutesAgo = 4.0)),
            openRoomId = "!a:x", inProgress = true,
        )
        inRow("status-pill", "!a:x").assertContentDescriptionEquals("Working")
        inRow("status-pill", "!b:x").assertContentDescriptionEquals("Active")
    }

    @Test
    fun noAgentsYet() {
        show(listOf(NavRows.row("!p:x", "Friends")))
        compose.onNodeWithText("No agents yet").assertIsDisplayed()
        compose.onNodeWithText("Rooms with an agent in them appear here.").assertIsDisplayed()
        compose.onNodeWithTag("no-agents").assertIsDisplayed()
    }

    @Test
    fun theFaceIs44() {
        show(listOf(atlas()))
        inRow("avatar").assertWidthIsEqualTo(44.dp)
    }

    @Test
    fun theRowIsOneTap() {
        val opened = mutableListOf<String>()
        show(listOf(atlas()), opened = opened)
        val node = compose.onNodeWithTag("agent-row-!a:x").fetchSemanticsNode()
        assertTrue("the row has a click action", node.config.getOrNull(SemanticsActions.OnClick) != null)
        compose.onNodeWithTag("agent-row-!a:x").performClick()
        assertEquals(listOf("!a:x"), opened)
        // The face is not a second target here (Agents has no "about" tap).
        inRow("avatar").fetchSemanticsNode().let { assertTrue(it.config.getOrNull(SemanticsActions.OnClick) == null) }
    }

    @Test
    fun agentsFitAtFontScaleTwo() {
        val name = "An agent whose name goes on and on and on until eighty characters are used up!!!"
        assertEquals(80, name.length)
        show(listOf(atlas(name = name, role = "A role that is also rather long indeed, to be sure")), fontScale = 2f)
        val out = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(name, useUnmergedTree = true).fetchSemanticsNode()
            .config.getOrNull(SemanticsActions.GetTextLayoutResult)!!.action!!(out)
        assertEquals(1, out.first().lineCount)
        val row = compose.onNodeWithTag("agent-row-!a:x").fetchSemanticsNode().boundsInRoot
        val pill = inRow("status-pill").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("pill $pill inside row $row", pill.right <= row.right + 1f && pill.left >= row.left - 1f)
    }
}
