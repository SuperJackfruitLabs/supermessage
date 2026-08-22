package dev.supermessage

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import androidx.compose.ui.test.onAllNodesWithText
import org.junit.Test
import uniffi.supermessage_core.CustomEventView
import uniffi.supermessage_core.ItemView
import uniffi.supermessage_core.MediaFileLabel
import uniffi.supermessage_core.ReactionDto
import uniffi.supermessage_core.RichBlock
import uniffi.supermessage_core.RichInline
import uniffi.supermessage_core.RichTableCell
import uniffi.supermessage_core.RichTableRow
import uniffi.supermessage_core.TimelineItemDto
import uniffi.supermessage_core.TimelineRow as TimelineRowDto

/**
 * One timeline row, drawn from the core's [ItemView] decision — see
 * `TimelineRow.kt`'s own KDoc and `apple/Supermessage/Timeline/TimelineRowView.swift`.
 *
 * `everyVariantIsHandled` is the important one: a `when` that silently misses
 * a variant renders an empty row, which looks like data loss and is invisible
 * to a test that only checks bubbles. It is not "renders something visible"
 * — `None` renders deliberately nothing (iOS returns `EmptyView()`), and
 * `UnreadMarker` renders a rule with no label on purpose. The property under
 * test is that no variant falls through unhandled, which with no `else`
 * branch is largely the compiler's job — so this asserts the nine visible
 * variants render their distinguishing content, and that `None` renders
 * nothing at all.
 */
class TimelineRowTest {
    @get:Rule val compose = createComposeRule()

    /** A fixed instant, so "Today" never depends on when this test runs. */
    private val now: Instant = Instant.parse("2024-01-15T12:00:00Z")

    private fun paragraph(text: String): List<RichBlock> =
        listOf(RichBlock.Paragraph(inlines = listOf(RichInline.Text(text))))

    private fun row(
        view: ItemView,
        senderName: String = "Sender",
        sender: String? = "@sender:example.org",
        isOwn: Boolean = false,
        body: String? = "hi",
        timestampMs: ULong? = now.toEpochMilli().toULong(),
        sendState: String? = null,
        reactions: List<ReactionDto> = emptyList(),
        readBy: List<String> = emptyList(),
    ): TimelineRowDto {
        val item = TimelineItemDto(
            id = "\$1",
            eventId = "\$1",
            kind = "message",
            msgtype = "m.text",
            detail = null,
            sender = sender,
            senderDisplayName = null,
            senderAvatar = null,
            body = body,
            formattedBody = null,
            media = null,
            customPayload = null,
            timestampMs = timestampMs,
            isOwn = isOwn,
            sendState = sendState,
            replyTo = null,
            edited = false,
            reactions = reactions,
            readBy = readBy,
            editable = true,
        )
        return TimelineRowDto(
            item = item,
            view = view,
            senderName = senderName,
            senderShort = senderName,
            membershipVerb = null,
            replyQuote = null,
            canReplyOrReact = true,
            replyPreview = null,
        )
    }

    /**
     * The [FontFamily] a real `Text` node actually laid its glyphs out
     * with — read back via the `GetTextLayoutResult` semantics action every
     * `Text` composable registers for accessibility, the same mechanism
     * `ThemeTest`'s own `onTextLayout` hook exercises, but without adding a
     * test-only parameter to production code: the action is already there
     * on the real, unmodified [TimelineRow] tree.
     */
    private fun resolvedFontFamily(node: SemanticsNode): FontFamily? {
        val results = mutableListOf<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        return results.firstOrNull()?.layoutInput?.style?.fontFamily
    }

    /**
     * Defect 2 (Task 5): `SupermessageTheme.typography.body` (serif, "what
     * an agent wrote") and `.own` (sans, "what the operator wrote") were
     * defined and unit-tested in `ThemeTest`, but nothing in the rendering
     * path ever read them — every bubble rendered in whatever face
     * `MaterialTheme.typography` defaulted to, identical for an agent's
     * message and the reader's own. `ThemeTest.ownAndBodyAreDifferentFaces`
     * passed the whole time it was broken, because it asserts the two
     * tokens differ, not that any composable resolves to either of them.
     *
     * These two tests go through the real, unmodified [TimelineRow] →
     * `MessageBlock` → `RichText` path, wrapped in the real
     * [SupermessageTheme] (the way `MainActivity` wraps the whole app), and
     * read the resolved face off the actual bubble text node —
     * [resolvedFontFamily], not a copy of the theme's own assertion.
     */
    @Test
    fun anAgentsMessageRendersInTheSerifFace() {
        compose.setContent {
            SupermessageTheme {
                TimelineRow(
                    row = row(view = ItemView.Bubble(muted = false, blocks = paragraph("an agent wrote this")), isOwn = false),
                    now = now,
                )
            }
        }
        val node = compose.onNodeWithText("an agent wrote this").fetchSemanticsNode()
        assertEquals(FontFamily.Serif, resolvedFontFamily(node))
    }

    @Test
    fun yourOwnMessageRendersInTheSansFace() {
        compose.setContent {
            SupermessageTheme {
                TimelineRow(
                    row = row(view = ItemView.Bubble(muted = false, blocks = paragraph("the operator wrote this")), isOwn = true),
                    now = now,
                )
            }
        }
        val node = compose.onNodeWithText("the operator wrote this").fetchSemanticsNode()
        assertEquals(FontFamily.SansSerif, resolvedFontFamily(node))
    }

    @Test
    fun everyVariantIsHandled() {
        compose.setContent {
            Column {
                TimelineRow(row = row(view = ItemView.Bubble(muted = false, blocks = paragraph("bubble text"))), now = now)
                TimelineRow(row = row(view = ItemView.Emote, body = "waved"), now = now)
                TimelineRow(row = row(view = ItemView.System(text = "system text")), now = now)
                TimelineRow(row = row(view = ItemView.Placeholder(text = "placeholder text")), now = now)
                TimelineRow(row = row(view = ItemView.DateDivider), now = now)
                TimelineRow(row = row(view = ItemView.UnreadMarker), now = now)
                TimelineRow(row = row(view = ItemView.Image(alt = "a sunset", width = 100uL, height = 50uL)), now = now)
                TimelineRow(
                    row = row(
                        view = ItemView.MediaFile(
                            label = MediaFileLabel.FILE,
                            filename = "report.pdf",
                            size = 2048uL,
                            mimetype = null,
                        ),
                    ),
                    now = now,
                )
                TimelineRow(
                    row = row(
                        view = ItemView.CustomEvent(
                            view = CustomEventView.Placeholder(text = "nothing usable"),
                            label = "Turn",
                            eventType = "dev.agentpod.turn.v1",
                        ),
                    ),
                    now = now,
                )
                Box(Modifier.testTag("none-holder")) {
                    TimelineRow(row = row(view = ItemView.None), now = now)
                }
            }
        }

        compose.onNodeWithText("bubble text").assertIsDisplayed()
        compose.onNodeWithText("Sender waved").assertIsDisplayed()
        compose.onNodeWithText("system text").assertIsDisplayed()
        compose.onNodeWithText("placeholder text").assertIsDisplayed()
        compose.onNodeWithText("Today").assertIsDisplayed()
        compose.onNodeWithTag("unread-marker").assertIsDisplayed()
        compose.onNodeWithText("a sunset").assertIsDisplayed()
        compose.onNodeWithText("report.pdf").assertIsDisplayed()
        // `CustomEventView.Placeholder` renders its own quiet line rather
        // than the row's `label` — see `DecisionCard.kt` and its own tests
        // for the other two `CustomEventView` states.
        compose.onNodeWithText("nothing usable").assertIsDisplayed()
        // `None` occupies no layout space at all — not an empty box, nothing.
        compose.onNodeWithTag("none-holder").onChildren().assertCountEquals(0)
    }

    /** A muted bubble (m.notice) is visually distinct but still legible. */
    @Test
    fun aMutedBubbleStillShowsItsText() {
        compose.setContent {
            TimelineRow(
                row = row(view = ItemView.Bubble(muted = true, blocks = paragraph("automated notice text"))),
                now = now,
            )
        }
        compose.onNodeWithText("automated notice text").assertIsDisplayed()
    }

    /** Attribution comes from senderName; the row derives no names. */
    @Test
    fun attributionComesFromTheRow() {
        compose.setContent {
            TimelineRow(
                row = row(
                    view = ItemView.Bubble(muted = false, blocks = paragraph("hello")),
                    senderName = "Cleaner Cody",
                ),
                now = now,
                attribution = "",
            )
        }
        compose.onNodeWithText("Cleaner Cody").assertIsDisplayed()
    }

    /** An image with no loaded bytes shows its alt text rather than a blank box. */
    @Test
    fun anImageWithoutBytesShowsItsAlt() {
        compose.setContent {
            TimelineRow(
                row = row(view = ItemView.Image(alt = "a lighthouse", width = null, height = null)),
                now = now,
            )
        }
        compose.onNodeWithText("a lighthouse").assertIsDisplayed()
    }

    /**
     * Carried from Task 2's review: `RichText`'s `Table` scrolls inside
     * itself only if the container it sits in is bounded. A wide table in a
     * bubble must stay inside the bubble's own bounded width rather than
     * stretching the row.
     */
    @Test
    fun aWideTableStaysWithinTheBubblesWidth() {
        fun cell(text: String) = RichTableCell(inlines = listOf(RichInline.Text(text)))
        val blocks = listOf(
            RichBlock.Table(
                header = listOf(
                    cell("Column One Header"),
                    cell("Column Two Header"),
                    cell("Column Three Header"),
                    cell("Column Four Header"),
                ),
                rows = listOf(
                    RichTableRow(
                        cells = listOf(
                            cell("A very long cell value one"),
                            cell("A very long cell value two"),
                            cell("A very long cell value three"),
                            cell("A very long cell value four"),
                        ),
                    ),
                ),
            ),
        )
        compose.setContent {
            Box(Modifier.width(1000.dp)) {
                TimelineRow(row = row(view = ItemView.Bubble(muted = false, blocks = blocks)), now = now)
            }
        }
        val width = compose.onNodeWithTag("bubble").getUnclippedBoundsInRoot().width
        assertTrue("bubble should stay within its bounded width, was $width", width <= 320.dp)
    }

    /** Existing reactions and read receipts are drawn, read-only, in this phase. */
    @Test
    fun reactionsAndReadReceiptsRenderReadOnly() {
        compose.setContent {
            TimelineRow(
                row = row(
                    view = ItemView.Bubble(muted = false, blocks = paragraph("done")),
                    isOwn = true,
                    readBy = listOf("@a:example.org", "@b:example.org"),
                    reactions = listOf(
                        ReactionDto(
                            key = "✅",
                            displayKey = "✅",
                            count = 2u,
                            byMe = true,
                            senders = listOf("@sender:example.org", "@a:example.org"),
                        ),
                    ),
                ),
                now = now,
            )
        }
        compose.onNodeWithText("✅", substring = true).assertIsDisplayed()
        compose.onNodeWithText("2", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Read by", substring = true).assertIsDisplayed()
    }

    /**
     * Read receipts name people, not Matrix ids.
     *
     * The first implementation of this row reimplemented the core's
     * `people_label` locally, because it looked for free functions in
     * `supermessage_core.kt` and found none — they live in
     * `supermessage_ffi.kt`. The grouping it wrote was right; the fallback was
     * not, and a reader saw `@_agentpod_ganesha:id.agentpod.dev` under their
     * own message instead of `Ganesha`.
     *
     * The binding's own doc says why it exists: "naming is a core decision
     * rather than something each host re-invents in its own idiom." This test
     * is what stops the next host re-inventing it.
     */
    @Test
    fun readReceiptsNamePeopleRatherThanIds() {
        compose.setContent {
            TimelineRow(
                row = row(
                    view = ItemView.Bubble(muted = false, blocks = paragraph("hello")),
                    isOwn = true,
                    readBy = listOf("@_agentpod_ganesha:id.agentpod.dev"),
                ),
                now = now,
            )
        }
        compose.onNodeWithText("Read by Ganesha").assertExists()
        compose.onAllNodesWithText("@_agentpod_ganesha:id.agentpod.dev", substring = true)
            .assertCountEquals(0)
    }

    /**
     * Tapping a reaction chip this account has *not* reacted with yet asks
     * [TimelineRow]'s `onReact` to add it — the "add" half of the toggle.
     */
    @Test
    fun tappingANotYetMineReactionChipCallsOnReactToAddIt() {
        val reacted = mutableListOf<String>()
        compose.setContent {
            TimelineRow(
                row = row(
                    view = ItemView.Bubble(muted = false, blocks = paragraph("done")),
                    reactions = listOf(
                        ReactionDto(
                            key = "✅",
                            displayKey = "✅",
                            count = 1u,
                            byMe = false,
                            senders = listOf("@a:example.org"),
                        ),
                    ),
                ),
                now = now,
                onReact = { reacted += it },
            )
        }
        compose.onNodeWithTag("reaction-✅").performClick()
        assertEquals(listOf("✅"), reacted)
    }

    /**
     * Tapping a chip this account *already* reacted with still calls
     * `onReact` with the same key — the "remove" half of the toggle, and the
     * one a UI that can only add would silently drop. Actually deciding
     * add-vs-remove is the core's `toggleReaction` (Task 6 wires that); this
     * row's job is only to ask the same way every time.
     */
    @Test
    fun tappingAnAlreadyMineReactionChipCallsOnReactToRemoveIt() {
        val reacted = mutableListOf<String>()
        compose.setContent {
            TimelineRow(
                row = row(
                    view = ItemView.Bubble(muted = false, blocks = paragraph("done")),
                    reactions = listOf(
                        ReactionDto(
                            key = "👍",
                            displayKey = "👍",
                            count = 1u,
                            byMe = true,
                            senders = listOf("@sender:example.org"),
                        ),
                    ),
                ),
                now = now,
                onReact = { reacted += it },
            )
        }
        compose.onNodeWithTag("reaction-👍").performClick()
        assertEquals(listOf("👍"), reacted)
    }

    /**
     * `onReact` carries the wire `key`, never the bounded `displayKey` — the
     * same rule `ReactionsRow` follows for what it sends the homeserver via
     * the core. A key/displayKey mismatch (bounded/truncated display form)
     * would otherwise land a different reaction than the reader meant.
     */
    @Test
    fun onReactReceivesTheWireKeyNotTheDisplayKey() {
        var received: String? = null
        compose.setContent {
            TimelineRow(
                row = row(
                    view = ItemView.Bubble(muted = false, blocks = paragraph("done")),
                    reactions = listOf(
                        ReactionDto(
                            key = "long-custom-key-truncated-for-display",
                            displayKey = "long…",
                            count = 1u,
                            byMe = false,
                            senders = listOf("@a:example.org"),
                        ),
                    ),
                ),
                now = now,
                onReact = { received = it },
            )
        }
        compose.onNodeWithTag("reaction-long-custom-key-truncated-for-display").performClick()
        assertEquals("long-custom-key-truncated-for-display", received)
    }

    /**
     * The gap Phase B left: existing reaction chips toggle, but nothing adds
     * a reaction that is not already on the message. A long press on the
     * "add reaction" affordance opens exactly the quick set iOS and desktop
     * already agree on — `quickReactions`, verbatim from
     * `TimelineRowView.swift:22` — not a set invented here.
     */
    @Test
    fun longPressingAddReactionOffersExactlyTheQuickSet() {
        compose.setContent {
            TimelineRow(
                row = row(view = ItemView.Bubble(muted = false, blocks = paragraph("hi"))),
                now = now,
                onReact = {},
            )
        }
        compose.onNodeWithTag("add-reaction").performTouchInput { longClick() }
        quickReactions.forEach { emoji ->
            compose.onNodeWithTag("quick-reaction-$emoji").assertIsDisplayed()
        }
    }

    /** Tapping one of the offered quick reactions asks `onReact` to add it. */
    @Test
    fun tappingAnOfferedQuickReactionCallsOnReactWithItsKey() {
        val reacted = mutableListOf<String>()
        compose.setContent {
            TimelineRow(
                row = row(view = ItemView.Bubble(muted = false, blocks = paragraph("hi"))),
                now = now,
                onReact = { reacted += it },
            )
        }
        compose.onNodeWithTag("add-reaction").performTouchInput { longClick() }
        compose.onNodeWithTag("quick-reaction-👀").performClick()
        assertEquals(listOf("👀"), reacted)
    }

    /**
     * A message the server has not acknowledged offers no way to react to
     * it — mirrors iOS's own guard ("Nothing is offered against a message
     * the server has not acknowledged... The core decides that").
     */
    @Test
    fun aMessageThatCannotBeReactedToOffersNoAddAffordance() {
        compose.setContent {
            TimelineRow(
                row = row(view = ItemView.Bubble(muted = false, blocks = paragraph("hi")))
                    .let { it.copy(canReplyOrReact = false) },
                now = now,
                onReact = {},
            )
        }
        compose.onNodeWithTag("add-reaction").assertDoesNotExist()
    }
}
