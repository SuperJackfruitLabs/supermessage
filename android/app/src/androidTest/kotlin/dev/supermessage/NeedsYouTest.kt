package dev.supermessage

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
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
import dev.supermessage.kit.RosterArrangement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uniffi.supermessage_core.NeedsYouInbox

/** Needs you (N2). The inbox is the core's; these tests pin how it is drawn. */
class NeedsYouTest {
    @get:Rule val compose = createComposeRule()

    private fun inbox(rows: List<uniffi.supermessage_core.RoomRow> = NavRows.fleet()) =
        RosterArrangement.needsYouInbox(rows, NavRows.NOW)

    private fun show(
        inbox: () -> NeedsYouInbox,
        reduce: Boolean = true,
        fontScale: Float? = null,
        opened: MutableList<String> = mutableListOf(),
    ) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalReduceMotion provides reduce,
                LocalDensity provides if (fontScale == null) density else Density(density.density, fontScale),
            ) {
                SupermessageTheme {
                    Box(Modifier.requiredWidth(411.dp).testTag("shell")) {
                        NeedsYouPane(
                            inbox = inbox(),
                            now = NavRows.NOW,
                            showsState = true,
                            avatarUri = { null },
                            onLoadAvatar = {},
                            onOpenRoom = { opened += it },
                            onOpenInfo = {},
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun top(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top

    @Test
    fun decisionsAndInvitationsAreSectionedAndCounted() {
        show({ inbox() })
        compose.onNodeWithText("Waiting on you").assertIsDisplayed()
        compose.onNodeWithText("Invitations").assertIsDisplayed()
        compose.onNodeWithTag("needs-you-count-decisions", useUnmergedTree = true).assertTextEquals("2")
        compose.onNodeWithTag("needs-you-count-invitations", useUnmergedTree = true).assertTextEquals("1")
        val decisions = top("needs-you-header-decisions")
        val invitations = top("needs-you-header-invitations")
        val atlas = compose.onNodeWithText("Atlas").fetchSemanticsNode().boundsInRoot.top
        val sam = compose.onNodeWithText("Sam").fetchSemanticsNode().boundsInRoot.top
        val estate = compose.onNodeWithText("Estate Planning").fetchSemanticsNode().boundsInRoot.top
        assertTrue("decisions $decisions < Atlas $atlas, Sam $sam < invitations $invitations", decisions < atlas && atlas < invitations && decisions < sam && sam < invitations)
        assertTrue("the invitation $estate is under its header $invitations", estate > invitations)
        // Not in Needs you: a room that owes nothing.
        compose.onNodeWithText("Design Review").assertDoesNotExist()
    }

    @Test
    fun aSectionWithNoRowsIsLeftOut() {
        val rows = NavRows.fleet().filterNot { it.room.id == "!i:x" }
        show({ inbox(rows) })
        compose.onNodeWithText("Waiting on you").assertIsDisplayed()
        compose.onNodeWithText("Invitations").assertDoesNotExist()
        compose.onNodeWithTag("needs-you-header-invitations", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun anEmptyInboxIsCaughtUp() {
        show({ inbox(listOf(NavRows.row("!q:x", "Design Review"))) })
        compose.onNodeWithText("You're all caught up").assertIsDisplayed()
        compose.onNodeWithText("Decisions and invitations that need you land here.").assertIsDisplayed()
        compose.onNodeWithTag("caught-up").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithTag("roster-row").fetchSemanticsNodes().size)
        compose.onNodeWithTag("needs-you-header-decisions", useUnmergedTree = true).assertDoesNotExist()
    }

    /** RF4: the inbox is finishable; a row the core stops listing goes. */
    @Test
    fun aRowThatStopsNeedingYouLeaves() {
        val rows = NavRows.fleet().filterNot { it.room.id == "!i:x" }
        var current by mutableStateOf(inbox(rows))
        show({ current }, reduce = false)
        compose.onNodeWithText("Atlas").assertIsDisplayed()
        compose.onNodeWithText("Sam").assertIsDisplayed()

        // Atlas is answered: the core's next inbox has only Sam.
        current = inbox(rows.map { if (it.room.id == "!a:x") NavRows.row("!a:x", "Atlas", role = "Platform", agent = true) else it })
        compose.waitUntil(3_000) { compose.onAllNodesWithText("Atlas").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Atlas").assertDoesNotExist()
        compose.onNodeWithText("Sam").assertIsDisplayed()
        compose.onNodeWithTag("needs-you-count-decisions", useUnmergedTree = true).assertTextEquals("1")

        current = inbox(listOf(NavRows.row("!q:x", "Design Review")))
        compose.waitUntil(3_000) { compose.onAllNodesWithText("Sam").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("caught-up").assertIsDisplayed()
    }

    @Test
    fun caughtUpBouncesUnlessReduced() {
        val empty = { inbox(listOf(NavRows.row("!q:x", "Design Review"))) }
        show(empty, reduce = false)
        assertEquals(true, bounces())
    }

    @Test
    fun caughtUpDoesNotBounceUnderReduceMotion() {
        val empty = { inbox(listOf(NavRows.row("!q:x", "Design Review"))) }
        show(empty, reduce = true)
        assertEquals(false, bounces())
    }

    private fun bounces(): Boolean? =
        compose.onNodeWithTag("caught-up-seal", useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(BouncesKey)

    @Test
    fun tappingARowOpensIt() {
        val opened = mutableListOf<String>()
        show({ inbox() }, opened = opened)
        compose.onNodeWithText("Atlas").performClick()
        assertEquals(listOf("!a:x"), opened)
    }

    /** RF2: at twice the text size nothing is cut or pushed off the screen. */
    @Test
    fun theInboxFitsAtFontScaleTwo() {
        val longName = "A very long room name that keeps going until sixty chars"
        val name = longName.padEnd(60, 'x')
        val rows = listOf(NavRows.row("!a:x", name, pending = true, agent = true), NavRows.row("!i:x", "Estate", invited = true, minutesAgo = null))
        show({ inbox(rows) }, fontScale = 2f)
        val list = compose.onNodeWithTag("needs-you-list").fetchSemanticsNode().boundsInRoot
        for (tag in listOf("needs-you-count-decisions", "needs-you-count-invitations")) {
            val b = compose.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("$tag $b inside the list $list", b.right <= list.right + 1f && b.left >= list.left - 1f)
        }
    }

    @Test
    fun theCaughtUpWordsWrapAtFontScaleTwo() {
        show({ inbox(listOf(NavRows.row("!q:x", "Design Review"))) }, fontScale = 2f)
        for (text in listOf("You're all caught up", "Decisions and invitations that need you land here.")) {
            val out = mutableListOf<TextLayoutResult>()
            val node = compose.onNodeWithText(text, useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)!!.action!!(out)
            // The title (305dp of 347) still fits on one line at twice the size; the sentence under it cannot.
            if (text.startsWith("Decisions")) assertTrue("$text lines ${out.first().lineCount}", out.first().lineCount >= 2)
            // Every character is drawn. (`hasVisualOverflow` reads true for centred text whose box
            // shrank to its content, so the last visible character is checked instead.)
            val layout = out.first()
            assertFalse("$text is cut", layout.multiParagraph.didExceedMaxLines || layout.getLineEnd(layout.lineCount - 1, visibleEnd = true) < text.length)
        }
    }
}
