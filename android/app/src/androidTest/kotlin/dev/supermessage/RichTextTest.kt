package dev.supermessage

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import uniffi.supermessage_core.RichBlock
import uniffi.supermessage_core.RichInline
import uniffi.supermessage_core.RichListItem
import uniffi.supermessage_core.RichTableRow
import uniffi.supermessage_core.RichTableCell

/**
 * `RichText` renders the core's already-parsed block tree — see that
 * file's own KDoc — so these tests pin four shapes chosen because each is
 * one a careless renderer can make disappear entirely rather than merely
 * mis-style.
 */
class RichTextTest {
    @get:Rule val compose = createComposeRule()

    /**
     * Nested emphasis renders its innermost text — the case
     * `RichTextFolding` on iOS was found, by this project's own mutation
     * testing, to get wrong. Rendering only the first inline of an
     * `Emphasis`/`Strong` would drop this text silently rather than fail
     * loudly, which is exactly why this is pinned here.
     */
    @Test
    fun nestedEmphasisKeepsItsText() {
        compose.setContent {
            RichText(
                blocks = listOf(
                    RichBlock.Paragraph(
                        inlines = listOf(
                            RichInline.Text("a "),
                            RichInline.Strong(
                                inlines = listOf(
                                    RichInline.Text("b"),
                                    RichInline.Emphasis(inlines = listOf(RichInline.Text("c"))),
                                ),
                            ),
                        ),
                    ),
                ),
            )
        }
        compose.onNodeWithText("a bc").assertIsDisplayed()
    }

    /** An ordered list starting at something other than 1 respects `start`. */
    @Test
    fun anOrderedListHonoursItsStart() {
        compose.setContent {
            RichText(
                blocks = listOf(
                    RichBlock.ListBlock(
                        ordered = true,
                        start = 5u,
                        items = listOf(
                            RichListItem(
                                blocks = listOf(
                                    RichBlock.Paragraph(inlines = listOf(RichInline.Text("first"))),
                                ),
                            ),
                            RichListItem(
                                blocks = listOf(
                                    RichBlock.Paragraph(inlines = listOf(RichInline.Text("second"))),
                                ),
                            ),
                        ),
                    ),
                ),
            )
        }
        compose.onNodeWithText("5.").assertIsDisplayed()
        compose.onNodeWithText("6.").assertIsDisplayed()
    }

    /** A code block renders its text verbatim, including leading whitespace. */
    @Test
    fun aCodeBlockKeepsItsWhitespace() {
        compose.setContent {
            RichText(
                blocks = listOf(RichBlock.CodeBlock(language = null, text = "    indented line")),
            )
        }
        compose.onNodeWithText("    indented line").assertIsDisplayed()
    }

    /** A link renders its label, not its href. */
    @Test
    fun aLinkShowsItsLabelRatherThanItsHref() {
        compose.setContent {
            RichText(
                blocks = listOf(
                    RichBlock.Paragraph(
                        inlines = listOf(
                            RichInline.Link(
                                href = "https://example.org/secret-path",
                                inlines = listOf(RichInline.Text("click here")),
                            ),
                        ),
                    ),
                ),
            )
        }
        compose.onNodeWithText("click here").assertIsDisplayed()
        compose.onAllNodesWithText("https://example.org/secret-path", substring = true)
            .assertCountEquals(0)
    }

    /**
     * A table renders its header and every body row.
     *
     * Added after Task 2's review: `Table` was the one untested variant with
     * real branching logic, and it is half of this task's wide-content
     * requirement. A regression that dropped body rows, lost the header, or
     * moved `horizontalScroll` onto an individual `Row` — which would break
     * column alignment by letting each row scroll independently — compiles
     * cleanly and would otherwise ship unnoticed.
     */
    @Test
    fun aTableRendersItsHeaderAndEveryRow() {
        fun cell(text: String) =
            RichTableCell(inlines = listOf(RichInline.Text(text)))
        compose.setContent {
            RichText(
                blocks = listOf(
                    RichBlock.Table(
                        header = listOf(cell("Host"), cell("State")),
                        rows = listOf(
                            RichTableRow(cells = listOf(cell("guild"), cell("idle"))),
                            RichTableRow(cells = listOf(cell("ashram"), cell("busy"))),
                        ),
                    )
                )
            )
        }
        // Header, then both body rows: a renderer that emitted only the first
        // row would still pass a header-only assertion.
        compose.onNodeWithText("Host").assertIsDisplayed()
        compose.onNodeWithText("State").assertIsDisplayed()
        compose.onNodeWithText("guild").assertIsDisplayed()
        compose.onNodeWithText("idle").assertIsDisplayed()
        compose.onNodeWithText("ashram").assertIsDisplayed()
        compose.onNodeWithText("busy").assertIsDisplayed()
    }
}
