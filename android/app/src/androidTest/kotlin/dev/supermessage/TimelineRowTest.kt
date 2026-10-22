package dev.supermessage

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onAllNodesWithTag
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
import uniffi.supermessage_core.DeliveryState
import uniffi.supermessage_core.ItemView
import uniffi.supermessage_core.MediaFileLabel
import uniffi.supermessage_core.ReactionDto
import uniffi.supermessage_core.RichBlock
import uniffi.supermessage_core.RichInline
import uniffi.supermessage_core.RichTableCell
import uniffi.supermessage_core.RichTableRow
import uniffi.supermessage_core.TimelineItemDto
import uniffi.supermessage_core.TimelineRow as TimelineRowDto
import uniffi.supermessage_core.PlaceholderKind
import uniffi.supermessage_core.SystemKind

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
        view: ItemView = ItemView.Bubble(muted = false, blocks = paragraph("hi"), voice = null),
        senderName: String = "Sender",
        /** What the face shows. Decided by the core — see `sender_initial`. */
        senderInitial: String = "?",
        sender: String? = "@sender:example.org",
        isOwn: Boolean = false,
        body: String? = "hi",
        timestampMs: ULong? = now.toEpochMilli().toULong(),
        sendState: DeliveryState? = null,
        reactions: List<ReactionDto> = emptyList(),
        readBy: List<String> = emptyList(),
        senderAvatar: String? = null,
    ): TimelineRowDto {
        val item = TimelineItemDto(
            id = "\$1",
            eventId = "\$1",
            kind = "message",
            msgtype = "m.text",
            detail = null,
            sender = sender,
            senderDisplayName = null,
            senderAvatar = senderAvatar,
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
            editable = true, membershipSubject = null,
        )
        return TimelineRowDto(
            item = item,
            view = view,
            senderName = senderName,
            senderShort = senderName,
            senderInitial = senderInitial,
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
    fun anAgentsMessageRendersInTheConversationFace() {
        compose.setContent {
            SupermessageTheme {
                TimelineRow(
                    row = row(view = ItemView.Bubble(muted = false, blocks = paragraph("an agent wrote this"), voice = null), isOwn = false),
                    now = now,
                )
            }
        }
        val node = compose.onNodeWithText("an agent wrote this").fetchSemanticsNode()
        assertEquals(FontFamily.SansSerif, resolvedFontFamily(node))
    }

    @Test
    fun yourOwnMessageRendersInTheSansFace() {
        compose.setContent {
            SupermessageTheme {
                TimelineRow(
                    row = row(view = ItemView.Bubble(muted = false, blocks = paragraph("the operator wrote this"), voice = null), isOwn = true),
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
                TimelineRow(row = row(view = ItemView.Bubble(muted = false, blocks = paragraph("bubble text"), voice = null)), now = now)
                TimelineRow(row = row(view = ItemView.Emote, body = "waved"), now = now)
                TimelineRow(row = row(view = ItemView.System(kind = SystemKind.EncryptionEnabled, text = "system text")), now = now)
                TimelineRow(row = row(view = ItemView.Placeholder(kind = PlaceholderKind.Redacted, text = "placeholder text")), now = now)
                TimelineRow(row = row(view = ItemView.DateDivider), now = now)
                TimelineRow(row = row(view = ItemView.UnreadMarker), now = now)
                TimelineRow(row = row(view = ItemView.Image(alt = "a sunset", width = 100uL, height = 50uL, caption = null)), now = now)
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
                row = row(view = ItemView.Bubble(muted = true, blocks = paragraph("automated notice text"), voice = null)),
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
                    view = ItemView.Bubble(muted = false, blocks = paragraph("hello"), voice = null),
                    senderName = "Cleaner Cody",
                ),
                now = now,
                attribution = "",
            )
        }
        compose.onNodeWithText("Cleaner Cody").assertIsDisplayed()
    }

    /** A 1x1 PNG, as the core hands pictures over: a `data:` URI. */
    private val pngUri =
        "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="

    /** The cache has the picture: it is drawn, not described. */
    @Test
    fun imageRowDrawsThePictureWhenTheCacheHasIt() {
        compose.setContent {
            TimelineRow(
                row = row(view = ItemView.Image(alt = "a sunset", width = 100uL, height = 50uL, caption = null)),
                now = now,
                imageUri = { pngUri },
            )
        }
        compose.onNodeWithTag("timeline-image").assertExists()
    }

    /** The fetch failed or has not landed: the alt box stays, and nothing spins. */
    @Test
    fun imageRowFallsBackToAltWhenNoPicture() {
        compose.setContent {
            TimelineRow(
                row = row(view = ItemView.Image(alt = "a sunset", width = 100uL, height = 50uL, caption = null)),
                now = now,
                imageUri = { null },
            )
        }
        compose.onNodeWithText("a sunset").assertIsDisplayed()
        compose.onAllNodesWithTag("timeline-image").assertCountEquals(0)
    }

    /** Faces are keyed by the `mxc:` avatar, as on iOS, not by user id. */
    @Test
    fun senderFaceUsesTheMxcAvatar() {
        compose.setContent {
            TimelineRow(
                row = row(senderAvatar = "mxc://x/y"),
                now = now,
                faceUri = { mxc -> if (mxc == "mxc://x/y") pngUri else null },
            )
        }
        compose.onNodeWithTag("sender-face-image").assertExists()
    }

    /** An image with no loaded bytes shows its alt text rather than a blank box. */
    @Test
    fun anImageWithoutBytesShowsItsAlt() {
        compose.setContent {
            TimelineRow(
                row = row(view = ItemView.Image(alt = "a lighthouse", width = null, height = null, caption = null)),
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
                TimelineRow(row = row(view = ItemView.Bubble(muted = false, blocks = blocks, voice = null)), now = now)
            }
        }
        val width = compose.onNodeWithTag("bubble").getUnclippedBoundsInRoot().width
        assertTrue("bubble should stay within its bounded width, was $width", width <= 320.dp)
    }

    /** Existing reactions are drawn on the bubble's corner; the receipt is a stack of faces. */
    @Test
    fun reactionsAndReadReceiptsRenderOnTheCorner() {
        val readers = listOf("@a:example.org", "@b:example.org").map { ReaderFace(it, null, null) }
        compose.setContent {
            TimelineRow(
                row = row(
                    view = ItemView.Bubble(muted = false, blocks = paragraph("done"), voice = null),
                    isOwn = true,
                    readBy = readers.map { it.userId },
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
                readers = readers,
            )
        }
        compose.onNodeWithText("✅", substring = true).assertIsDisplayed()
        // The chip is one accessible node (it carries the who-reacted long press), so
        // its count is part of its merged text rather than a child node.
        compose.onNodeWithTag("reaction-✅").assert(hasText("2")).assertIsDisplayed()
        compose.onNodeWithTag("reader-stack").assertIsDisplayed()
        // The chip hangs off the bubble: its top is above the bubble's bottom.
        val bubble = compose.onNodeWithTag("bubble").getUnclippedBoundsInRoot()
        val chip = compose.onNodeWithTag("reaction-✅").getUnclippedBoundsInRoot()
        assertTrue("chip ${chip.top} should overlap the bubble bottom ${bubble.bottom}", chip.top < bubble.bottom)
    }

    /** Read receipts name people, not Matrix ids: the stack's label comes from the core's `peopleLabel`. */
    @Test
    fun readReceiptsNamePeopleRatherThanIds() {
        val ids = listOf("@_agentpod_ganesha:id.agentpod.dev")
        compose.setContent {
            TimelineRow(
                row = row(
                    view = ItemView.Bubble(muted = false, blocks = paragraph("hello"), voice = null),
                    isOwn = true,
                    readBy = ids,
                ),
                now = now,
                readers = ids.map { ReaderFace(it, null, null) },
            )
        }
        compose.onNodeWithTag("reader-stack").assertContentDescriptionEquals("Read by Ganesha")
        compose.onAllNodesWithText("@_agentpod_ganesha:id.agentpod.dev", substring = true)
            .assertCountEquals(0)
    }

    @Test
    fun fourReadersShowThreeFacesAndACount() {
        val ids = listOf("@a:example.org", "@b:example.org", "@c:example.org", "@d:example.org")
        compose.setContent {
            TimelineRow(
                row = row(isOwn = true, readBy = ids),
                now = now,
                readers = ids.map { ReaderFace(it, null, "A") },
            )
        }
        compose.onNodeWithText("+1", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("reader-stack")
            .assertContentDescriptionEquals("Read by " + uniffi.supermessage_ffi.peopleLabel(ids))
        compose.onAllNodesWithTag("reader-face", useUnmergedTree = true).assertCountEquals(3)
    }

    @Test
    fun aReaderWithNoMessageGetsNoInitial() {
        val talked = row(sender = "@talker:example.org", senderInitial = "T")
        val faces = readerFaces(listOf(talked))
        assertEquals("T", faces["@talker:example.org"]?.initial)
        assertEquals(null, faces["@silent:example.org"])
        // A sender whose only row is not a message gets no face either.
        val joined = row(sender = "@joiner:example.org", senderInitial = "J").let {
            it.copy(item = it.item.copy(kind = "membership"))
        }
        assertEquals(null, readerFaces(listOf(joined))["@joiner:example.org"])
    }

    @Test
    fun theUnreadLineSaysNew() {
        compose.setContent { TimelineRow(row = row(view = ItemView.UnreadMarker), now = now) }
        compose.onNodeWithText("New", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("unread-marker").assertContentDescriptionEquals("New messages")
    }

    @Test
    fun anUndecryptableMessageOffersRecovery() {
        compose.setContent {
            TimelineRow(
                row = row(
                    view = ItemView.Placeholder(PlaceholderKind.UnableToDecrypt, "Unable to decrypt"),
                    senderName = "Ganesha",
                ),
                now = now,
            )
        }
        compose.onNodeWithText("Ganesha").assertIsDisplayed()
        compose.onNodeWithText("Unable to decrypt").assertIsDisplayed()
        compose.onNodeWithText("Encryption recovery in your account can restore older messages.").assertIsDisplayed()
    }

    @Test
    fun otherPlaceholdersStayQuietLines() {
        compose.setContent {
            TimelineRow(
                row = row(view = ItemView.Placeholder(PlaceholderKind.Redacted, "Message deleted")),
                now = now,
            )
        }
        compose.onNodeWithText("Message deleted").assertIsDisplayed()
        compose.onAllNodesWithText("Encryption recovery", substring = true).assertCountEquals(0)
    }

    @Test
    fun continuedRowsSitTwoApartAndNewTurnsTwelve() {
        compose.setContent {
            Column(Modifier.width(360.dp)) {
                TimelineRow(row = row(), now = now, continuesRun = false)
                TimelineRow(row = row(), now = now, continuesRun = true)
                // Own and without a meta line, so no header or time sits in the gap.
                TimelineRow(row = row(isOwn = true), now = now, continuesRun = false, endsRun = false)
            }
        }
        val bubbles = compose.onAllNodesWithTag("bubble")
        val first = bubbles[0].getUnclippedBoundsInRoot()
        val second = bubbles[1].getUnclippedBoundsInRoot()
        val third = bubbles[2].getUnclippedBoundsInRoot()
        val run = second.top - first.bottom
        val turn = third.top - second.bottom
        // 0.2dp of slack: the 2dp gap is rounded to whole pixels at this density.
        assertTrue("a continued row sits 2-4dp below, was $run", run >= 1.8.dp && run <= 4.dp)
        assertTrue("a new turn sits 12dp below, was $turn", turn >= 11.8.dp)
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
                    view = ItemView.Bubble(muted = false, blocks = paragraph("done"), voice = null),
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
                    view = ItemView.Bubble(muted = false, blocks = paragraph("done"), voice = null),
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
                    view = ItemView.Bubble(muted = false, blocks = paragraph("done"), voice = null),
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
     * a reaction that is not already on the message. A tap on the
     * "add reaction" affordance opens exactly the quick set iOS and desktop
     * already agree on — `quickReactions`, verbatim from
     * `TimelineRowView.swift:22` — not a set invented here.
     */
    @Test
    fun tappingAddReactionOffersExactlyTheQuickSet() {
        compose.setContent {
            TimelineRow(
                row = row(view = ItemView.Bubble(muted = false, blocks = paragraph("hi"), voice = null)),
                now = now,
                onReact = {},
            )
        }
        compose.onNodeWithTag("add-reaction").performClick()
        quickReactions.forEach { emoji ->
            compose.onNodeWithTag("quick-reaction-$emoji").assertIsDisplayed()
        }
        compose.onAllNodes(hasTestTagPrefix("quick-reaction-")).assertCountEquals(quickReactions.size)
    }

    /** The chip's long press says who reacted, by `peopleLabel` (`TimelineRowView.swift:575-593`). */
    @Test
    fun longPressingAChipSaysWhoReacted() {
        val senders = listOf("@ganesha:x", "@priya:x")
        compose.setContent {
            TimelineRow(
                row = row(reactions = listOf(ReactionDto("👍", "👍", 2u, false, senders))),
                now = now,
                onReact = {},
            )
        }
        compose.onNodeWithTag("reaction-who").assertDoesNotExist()
        compose.onNodeWithTag("reaction-👍").performTouchInput { longClick() }
        compose.onNodeWithTag("reaction-who").assertIsDisplayed()
        compose.onNodeWithText(uniffi.supermessage_ffi.peopleLabel(senders)).assertIsDisplayed()
    }

    @Test
    fun aChipNamesItsCountAndWhetherItIsYours() {
        compose.setContent {
            TimelineRow(
                row = row(
                    reactions = listOf(
                        ReactionDto("👍", "👍", 2u, true, listOf("@me:x", "@a:x")),
                        ReactionDto("👀", "👀", 1u, false, listOf("@a:x")),
                    ),
                ),
                now = now,
                onReact = {},
            )
        }
        compose.onNodeWithTag("reaction-👍").assertContentDescriptionEquals("👍, 2, including yours")
        compose.onNodeWithTag("reaction-👀").assertContentDescriptionEquals("👀, 1")
    }

    /** Tapping one of the offered quick reactions asks `onReact` to add it. */
    @Test
    fun tappingAnOfferedQuickReactionCallsOnReactWithItsKey() {
        val reacted = mutableListOf<String>()
        compose.setContent {
            TimelineRow(
                row = row(view = ItemView.Bubble(muted = false, blocks = paragraph("hi"), voice = null)),
                now = now,
                onReact = { reacted += it },
            )
        }
        compose.onNodeWithTag("add-reaction").performClick()
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
                row = row(view = ItemView.Bubble(muted = false, blocks = paragraph("hi"), voice = null))
                    .let { it.copy(canReplyOrReact = false) },
                now = now,
                onReact = {},
            )
        }
        compose.onNodeWithTag("add-reaction").assertDoesNotExist()
    }
    /**
     * The face shows the core's initial, and the name beside it shows no
     * glyph.
     *
     * **This is the bug that shipped for as long as nobody looked.** The face
     * took the first character of the attribution, which for an agent is the
     * glyph, and the name beside it kept the glyph too — so every message in
     * an agent room read `✳ ✳ Atlas — Platform`. It was found by rendering an
     * iOS preview to an image, and Android had it as well, with a second
     * defect underneath: the face did `initial.firstOrNull()`, and a Kotlin
     * `Char` is a UTF-16 code unit, so an astral glyph rendered as half a
     * surrogate pair.
     *
     * The assertion is deliberately about the *disc*, not about counting
     * glyphs on screen. Counting would not catch a revert: with the core now
     * stripping the glyph out of `senderName`, a host that went back to
     * `initial = named` would show `A` in the disc and still have exactly one
     * `✳` in the frame. What distinguishes the two is which character the
     * face chose.
     */
    @Test
    fun theFaceShowsTheGlyphAndTheNameDoesNot() {
        compose.setContent {
            TimelineRow(
                row = row(
                    senderName = "Atlas — Platform",
                    senderInitial = "✳",
                ),
                now = now,
            )
        }

        compose.onNodeWithText("✳").assertIsDisplayed()
        compose.onNodeWithText("Atlas — Platform").assertIsDisplayed()
        compose.onAllNodesWithText("✳ Atlas — Platform").assertCountEquals(0)
    }

}
