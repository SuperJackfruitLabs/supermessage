package dev.supermessage

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uniffi.supermessage_core.ItemView
import uniffi.supermessage_core.MembershipPlacement
import uniffi.supermessage_core.ReactionDto
import uniffi.supermessage_core.ReplyQuoteView
import uniffi.supermessage_core.ReplyToDto
import uniffi.supermessage_core.RichBlock
import uniffi.supermessage_core.RichInline
import uniffi.supermessage_core.SystemKind
import uniffi.supermessage_core.TimelineItemDto
import uniffi.supermessage_core.TimelineRow as TimelineRowDto
import uniffi.supermessage_ffi.groupTimelineRows

/**
 * The scroll container — see `Timeline.kt`'s own KDoc and
 * `apple/Supermessage/Timeline/TimelineCollectionView.swift`'s header.
 *
 * **Geometry, not existence** governs [theNewestMessageIsVisibleOnOpen], the
 * same idiom `RootScaffoldTest.assertWithinShell` established: a row present
 * in the tree but scrolled to the wrong end of the screen is exactly the
 * fault that idiom exists to catch.
 */
class TimelineTest {
    @get:Rule val compose = createComposeRule()

    private fun row(
        id: String,
        body: String?,
        timestampMs: ULong,
        canReplyOrReact: Boolean = true,
        editable: Boolean = false,
        isOwn: Boolean = false,
        reactions: List<ReactionDto> = emptyList(),
        quotes: String? = null,
        readBy: List<String> = emptyList(),
    ): TimelineRowDto {
        val item = TimelineItemDto(
            id = id, eventId = id, kind = "message", msgtype = "m.text", detail = null,
            sender = "@a:x", senderDisplayName = null, senderAvatar = null, body = body,
            formattedBody = null, media = null, customPayload = null, timestampMs = timestampMs,
            isOwn = isOwn, sendState = null, edited = false,
            replyTo = quotes?.let {
                ReplyToDto(eventId = it, available = true, sender = "@a:x", senderDisplayName = "Sender", excerpt = "quoted", label = null)
            },
            reactions = reactions, readBy = readBy, editable = editable, membershipSubject = null,
        )
        return TimelineRowDto(
            item = item,
            view = ItemView.Bubble(
                muted = false,
                blocks = listOf(RichBlock.Paragraph(inlines = listOf(RichInline.Text(body.orEmpty())))),
                voice = null,
            ),
            senderName = "Sender",
            senderShort = "Sender",
            senderInitial = "?",
            membershipVerb = null,
            replyQuote = quotes?.let {
                ReplyQuoteView.Available(sender = "Sender", excerpt = "quoted", label = null, senderId = "@a:x")
            },
            canReplyOrReact = canReplyOrReact,
            replyPreview = null,
        )
    }

    /** `count` rows, ascending timestamp — the same order `TimelineStore.items` holds. */
    private fun ascendingRows(count: Int): List<TimelineRowDto> =
        (1..count).map { row(id = "$it", body = "row $it", timestampMs = it.toULong() * 1_000uL) }

    /**
     * A membership row. Its [membership] placement is the core's to decide:
     * pass the rows through `groupTimelineRows` rather than setting it by hand,
     * unless a test is about one specific placement.
     */
    private fun membershipRow(
        id: String,
        sender: String,
        verb: String,
        timestampMs: ULong,
        membership: MembershipPlacement? = null,
    ): TimelineRowDto {
        val item = TimelineItemDto(
            id = id, eventId = id, kind = "membership", msgtype = null, detail = verb,
            sender = "@$sender:x", senderDisplayName = sender, senderAvatar = null, body = null,
            formattedBody = null, media = null, customPayload = null, timestampMs = timestampMs,
            isOwn = false, sendState = null, replyTo = null, edited = false,
            reactions = emptyList(), readBy = emptyList(), editable = false, membershipSubject = null,
        )
        return TimelineRowDto(
            item = item,
            view = ItemView.System(
                kind = SystemKind.MembershipChanged(who = sender, detail = verb),
                text = "$sender $verb",
            ),
            senderName = sender,
            senderShort = sender,
            senderInitial = "?",
            membershipVerb = verb,
            replyQuote = null,
            canReplyOrReact = false,
            replyPreview = null,
            membership = membership,
        )
    }

    /**
     * Rule 1: the room opens at its newest message, fully visible.
     *
     * All three rows fit comfortably in the shell, so this is not about
     * whether the newest row exists — it is about WHERE it sits. The newest
     * message (highest timestamp) must be lowest on screen, the reading
     * position every chat opens at, and its own bounds must sit above the
     * shell's bottom edge rather than merely be present in the tree.
     */
    @Test
    fun theNewestMessageIsVisibleOnOpen() {
        val fixed = listOf(
            row(id = "1", body = "oldest", timestampMs = 1_000uL),
            row(id = "2", body = "middle", timestampMs = 2_000uL),
            row(id = "3", body = "newest", timestampMs = 3_000uL),
        )

        compose.setContent {
            Box(Modifier.requiredSize(320.dp, 640.dp).testTag("timeline-shell")) {
                Timeline(
                    rows = fixed,
                    revision = 0uL,
                    typingLine = null,
                    isPaginating = false,
                    canPaginate = false,
                    onPaginate = {},
                    onMarkRead = {},
                )
            }
        }

        val shell = compose.onNodeWithTag("timeline-shell").fetchSemanticsNode().boundsInRoot
        val oldest = compose.onNodeWithTag("row-1").fetchSemanticsNode().boundsInRoot
        val newest = compose.onNodeWithTag("row-3").fetchSemanticsNode().boundsInRoot

        assertTrue("newest row was not laid out: $newest", newest.width > 0f)
        assertTrue(
            "newest row's bounds $newest fall below the shell's own bottom edge ${shell.bottom}",
            newest.bottom <= shell.bottom,
        )
        assertTrue(
            "the newest message must sit lowest on screen; newest.top=${newest.top} oldest.top=${oldest.top}",
            newest.top > oldest.top,
        )
    }

    /**
     * Rule 3: `Timeline` keys its grouping and its mark-read effect off
     * `revision`, not off diffing `rows` — see `Timeline.kt`'s own note by
     * `displayRows` and the mark-read
     * `LaunchedEffect` for why.
     *
     * The version of this test that predates this task passed against the
     * defect it was named for: it asserted only that an unrelated
     * recomposition does not move the list, which plain structural
     * equality of `rows` also satisfies — a `remember(rows)` skips exactly
     * as well as a `remember(revision)` does when nothing changed. It could
     * not fail for the reason its name claimed.
     *
     * What actually distinguishes "keyed on revision" from "diffing rows"
     * is the opposite case: a revision bump against a list that is
     * *structurally unchanged* (a new object, `equals`-equal content — the
     * one shape a rows-diff cannot tell from "nothing happened"). Grouping
     * output is not a usable probe for that case: two equal lists group to
     * an equal result either way, so nothing about a rendered row can
     * distinguish "recomputed and got the same answer" from "skipped
     * recomputing." The mark-read effect can, though — it is a
     * `LaunchedEffect` that *restarts* (and so re-fires) whenever its key
     * changes, whether or not the recomputed grouping differs from the
     * last one. That restart is this task's answer to "is the distinction
     * observable through the public surface": it is, and this is the seam
     * that shows it, not the row content.
     */
    @Test
    fun theListFollowsRevisionRatherThanDiffingRows() {
        // A fresh object each time, `equals`-equal in content every time —
        // the shape that defeats a rows-only diff.
        fun sameSingleRow() = listOf(row(id = "1", body = "first", timestampMs = 1_000uL))

        var rows by mutableStateOf(sameSingleRow())
        var revision by mutableStateOf(0uL)
        var markReadCalls = 0

        compose.setContent {
            Timeline(
                rows = rows,
                revision = revision,
                typingLine = null,
                isPaginating = false,
                canPaginate = false,
                onPaginate = {},
                onMarkRead = { markReadCalls++ },
            )
        }
        compose.waitForIdle()
        val afterOpen = markReadCalls
        assertTrue("opening at the newest end should mark read", afterOpen > 0)

        // A structurally identical list, no revision bump: `TimelineStore`
        // never does this (revision cannot drift from `items`, by
        // construction — see `TimelineStore.replaceItems`), but the
        // container itself must not re-key on `rows` alone, or "diffing
        // rows" and "reading revision" would be indistinguishable from
        // here.
        rows = sameSingleRow()
        compose.waitForIdle()
        assertEquals(
            "an unchanged revision must not re-key, even against a new rows object",
            afterOpen,
            markReadCalls,
        )

        // The distinguishing case: a revision bump against a list that is
        // `equals`-equal to the one already on screen. A rows-diffing
        // implementation sees nothing here at all; a revision-keyed one
        // re-fires the mark-read effect regardless.
        rows = sameSingleRow()
        revision++
        compose.waitForIdle()
        assertTrue(
            "a revision bump must re-key even against an equal rows list",
            markReadCalls > afterOpen,
        )
    }

    /** Reads `Timeline`'s own Rule 2 decision off its test-only marker node. */
    private fun animationDecision(): String? =
        compose.onNodeWithTag("timeline-animation-decision")
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.ContentDescription)
            ?.firstOrNull()

    /**
     * Rule 2 (`TimelineAnimation.animates`): a handful of rows arriving into
     * an already-applied, on-screen, not-scrolled-away list animates; a
     * page of history landing at once does not.
     *
     * `TimelineAnimation` itself lived in `:kit` with six passing tests
     * throughout this defect — nothing in `:app` ever called it, so nothing
     * on the timeline animated. This test drives `Timeline` the way
     * `MainActivity` does (through `rows`), not `TimelineAnimation` again.
     *
     * Whether `Modifier.animateItem()` actually plays is not something a
     * Compose UI test can observe — there is no assertion for "did an
     * animation spec run." What is asserted instead is the *decision*
     * `Timeline` made for this update, exposed for exactly this purpose by
     * a zero-size node whose `contentDescription` names it, the same
     * "assert the decision, not the pixel" idiom `RootScaffoldTest` uses
     * for geometry.
     */
    @Test
    fun aHandfulOfArrivingRowsAnimatesAPageOfHistoryDoesNot() {
        var rows by mutableStateOf(ascendingRows(3))
        // `Timeline` latches Rule 2's decision on `revision`, not on `rows`
        // itself — see `Timeline.kt`'s own note on why — so this test bumps
        // both together, the way `TimelineStore.replaceItems` always does.
        var revision by mutableStateOf(0uL)

        compose.setContent {
            Timeline(
                rows = rows,
                revision = revision,
                typingLine = null,
                isPaginating = false,
                canPaginate = false,
                onPaginate = {},
                onMarkRead = {},
            )
        }
        compose.waitForIdle()

        // The room's first fill is the room appearing, not an arrival.
        assertEquals("static", animationDecision())

        // One row arriving into an already-applied, on-screen list: the
        // case Rule 2 exists for.
        rows = rows + row(id = "4", body = "row 4", timestampMs = 4_000uL)
        revision++
        compose.waitForIdle()
        assertEquals("animate", animationDecision())

        // A page of history — twenty rows at once — is not an arrival.
        rows = rows + (5..24).map { row(id = "$it", body = "row $it", timestampMs = it.toULong() * 1_000uL) }
        revision++
        compose.waitForIdle()
        assertEquals("static", animationDecision())
    }

    /** Pagination fires when the reader reaches the older end, and not before. */
    @Test
    fun reachingTheOlderEndAsksForMore() {
        val fixed = ascendingRows(40)
        var paginateCalls = 0

        compose.setContent {
            Box(Modifier.requiredSize(320.dp, 300.dp)) {
                Timeline(
                    rows = fixed,
                    revision = 0uL,
                    typingLine = null,
                    isPaginating = false,
                    canPaginate = true,
                    onPaginate = { paginateCalls++ },
                    onMarkRead = {},
                )
            }
        }

        // Not before: freshly opened, at the newest end.
        compose.waitForIdle()
        assertEquals(0, paginateCalls)

        // The older end, inverted, is the tail of the list this container
        // was handed — `fixed.lastIndex` in `newestFirst`'s own index space.
        compose.onNodeWithTag("timeline-list").performScrollToIndex(fixed.lastIndex)
        compose.waitForIdle()

        assertTrue("reaching the older end should have asked for more", paginateCalls > 0)
    }

    /** Pagination stops asking once the store says there is no more. */
    @Test
    fun nothingIsAskedForWhenThereIsNoMore() {
        val fixed = ascendingRows(40)
        var paginateCalls = 0

        compose.setContent {
            Box(Modifier.requiredSize(320.dp, 300.dp)) {
                Timeline(
                    rows = fixed,
                    revision = 0uL,
                    typingLine = null,
                    isPaginating = false,
                    canPaginate = false,
                    onPaginate = { paginateCalls++ },
                    onMarkRead = {},
                )
            }
        }

        compose.onNodeWithTag("timeline-list").performScrollToIndex(fixed.lastIndex)
        compose.waitForIdle()

        assertEquals(0, paginateCalls)
    }

    /** The jump-to-newest affordance appears only when away from the newest end. */
    @Test
    fun theWayBackAppearsOnlyWhenAway() {
        val fixed = ascendingRows(40)

        compose.setContent {
            Box(Modifier.requiredSize(320.dp, 300.dp)) {
                Timeline(
                    rows = fixed,
                    revision = 0uL,
                    typingLine = null,
                    isPaginating = false,
                    canPaginate = false,
                    onPaginate = {},
                    onMarkRead = {},
                )
            }
        }

        compose.onNodeWithTag("jump-to-newest").assertDoesNotExist()

        compose.onNodeWithTag("timeline-list").performScrollToIndex(20)
        compose.waitForIdle()
        compose.onNodeWithTag("jump-to-newest").assertIsDisplayed()

        compose.onNodeWithTag("jump-to-newest").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("jump-to-newest").assertDoesNotExist()
    }

    /** The typing line shows what TypingStore said, and nothing when it is null. */
    @Test
    fun theTypingLineComesFromTheStore() {
        var typingLine by mutableStateOf<String?>("Ganesha is typing…")

        compose.setContent {
            Timeline(
                rows = emptyList(),
                revision = 0uL,
                typingLine = typingLine,
                isPaginating = false,
                canPaginate = false,
                onPaginate = {},
                onMarkRead = {},
            )
        }

        compose.onNodeWithText("Ganesha is typing…").assertIsDisplayed()

        typingLine = null
        compose.waitForIdle()
        compose.onNodeWithTag("typing-line").assertDoesNotExist()
    }

    /**
     * A run of identical membership changes collapses into one line.
     *
     * Found on a device: eight consecutive "Annapurna … updated their
     * membership" rows filled the screen. `:kit` has collapsed these since
     * the port; the container simply never called it.
     */
    @Test
    fun aRunOfMembershipChangesCollapses() {
        val senders = listOf("Ganesha", "Krishna", "Annapurna", "Surya", "A5", "A6", "A7", "A8")
        val fixed = groupTimelineRows(
            senders.mapIndexed { index, sender ->
                membershipRow(
                    id = "${index + 1}",
                    sender = sender,
                    verb = "updated their membership",
                    timestampMs = (index + 1).toULong() * 1_000uL,
                )
            },
        )

        compose.setContent {
            Timeline(
                rows = fixed,
                revision = 0uL,
                typingLine = null,
                isPaginating = false,
                canPaginate = false,
                onPaginate = {},
                onMarkRead = {},
            )
        }

        // One collapsed line, not eight identical ones.
        compose.onNodeWithText("Ganesha, Krishna and 6 others updated their membership")
            .assertIsDisplayed()
        // The rows that fed the run are no longer drawn individually.
        compose.onNodeWithTag("row-2").assertDoesNotExist()
        compose.onNodeWithTag("row-8").assertDoesNotExist()
    }

    /**
     * Five changes make three sentences, which is a screenful: the core folds
     * them into one line, and tapping it shows each sentence.
     *
     * Full-text matchers only: a substring matcher would also match the
     * stretch line from the run lines.
     */
    @Test
    fun aCollapsedStretchOpensOnTap() {
        val stretch = "5 membership changes · Strategy Sam, Rakesh and 1 other"
        val fixed = groupTimelineRows(
            listOf(
                membershipRow(id = "1", sender = "Strategy Sam", verb = "was invited", timestampMs = 1_000uL),
                membershipRow(id = "2", sender = "Strategy Sam", verb = "joined the room", timestampMs = 2_000uL),
                membershipRow(id = "3", sender = "Rakesh", verb = "updated their membership", timestampMs = 3_000uL),
                membershipRow(id = "4", sender = "Annapurna", verb = "left the room", timestampMs = 4_000uL),
                membershipRow(id = "5", sender = "Annapurna", verb = "left the room", timestampMs = 5_000uL),
            ),
        )

        compose.setContent {
            Timeline(
                rows = fixed,
                revision = 0uL,
                typingLine = null,
                isPaginating = false,
                canPaginate = false,
                onPaginate = {},
                onMarkRead = {},
            )
        }

        compose.onNodeWithText(stretch).assertIsDisplayed()
        compose.onNodeWithText("Strategy Sam was invited, then joined the room").assertDoesNotExist()

        compose.onNodeWithText(stretch).performClick()

        compose.onNodeWithText("Strategy Sam was invited, then joined the room").assertIsDisplayed()
        compose.onNodeWithText("Rakesh updated their membership").assertIsDisplayed()
        compose.onNodeWithText("Annapurna left the room").assertIsDisplayed()
        compose.onNodeWithText(stretch).assertDoesNotExist()
    }

    /**
     * Runs break on a different verb — "three joined" and "one left" stay two
     * sentences rather than becoming one that is true of neither.
     */
    @Test
    fun aDifferentVerbStartsANewRun() {
        val fixed = groupTimelineRows(
            listOf(
                membershipRow(id = "1", sender = "Ganesha", verb = "joined the room", timestampMs = 1_000uL),
                membershipRow(id = "2", sender = "Krishna", verb = "left the room", timestampMs = 2_000uL),
            ),
        )

        compose.setContent {
            Timeline(
                rows = fixed,
                revision = 0uL,
                typingLine = null,
                isPaginating = false,
                canPaginate = false,
                onPaginate = {},
                onMarkRead = {},
            )
        }

        compose.onNodeWithText("Ganesha joined the room").assertIsDisplayed()
        compose.onNodeWithText("Krishna left the room").assertIsDisplayed()
    }

    /**
     * A long press on a row opens the menu for *that* row — tapping Reply
     * reports the row it was opened on, not another one.
     */
    @Test
    fun aLongPressOpensTheMenuForThatRow() {
        var replied: TimelineRowDto? = null
        val fixed = listOf(
            row(id = "1", body = "first", timestampMs = 1_000uL),
            row(id = "2", body = "second", timestampMs = 2_000uL),
        )

        compose.setContent {
            Timeline(
                rows = fixed,
                revision = 0uL,
                typingLine = null,
                isPaginating = false,
                canPaginate = false,
                onPaginate = {},
                onMarkRead = {},
                onReply = { replied = it },
            )
        }

        compose.onNodeWithTag("message-menu").assertDoesNotExist()
        compose.onNodeWithTag("row-1").performTouchInput { longClick() }
        compose.onNodeWithTag("message-menu").assertIsDisplayed()
        compose.onNodeWithTag("menu-reply").performClick()
        compose.waitForIdle()

        assertEquals("1", replied?.item?.id)
        compose.onNodeWithTag("message-menu").assertDoesNotExist()
    }

    /**
     * A row that cannot be replied to and cannot be edited gets no gesture
     * at all — see `Timeline`'s own class doc for why the gesture's very
     * *existence* is gated on those two flags, narrower than iOS's two-item
     * menu that is built unconditionally and merely omits an item.
     */
    @Test
    fun aRowWithNothingToOfferGetsNoLongPressGesture() {
        var pressed: TimelineRowDto? = null
        val fixed = listOf(
            row(id = "1", body = null, timestampMs = 1_000uL, canReplyOrReact = false, editable = false),
        )

        compose.setContent {
            Timeline(
                rows = fixed,
                revision = 0uL,
                typingLine = null,
                isPaginating = false,
                canPaginate = false,
                onPaginate = {},
                onMarkRead = {},
                onReply = { pressed = it },
            )
        }

        compose.onNodeWithTag("row-1").performTouchInput { longClick() }
        compose.waitForIdle()

        assertNull(pressed)
        compose.onNodeWithTag("message-menu").assertDoesNotExist()
    }

    /**
     * Tapping an existing reaction chip reports which row it belongs to
     * alongside the wire key — [Timeline] curries [TimelineRow]'s own
     * `onReact: ((String) -> Unit)?` with the row, since [TimelineRow] has
     * no notion of "which message is this from the caller's point of view."
     */
    @Test
    fun tappingAReactionChipReportsTheRowAndKey() {
        var reactedRow: TimelineRowDto? = null
        var reactedKey: String? = null
        val fixed = listOf(
            row(
                id = "1", body = "hello", timestampMs = 1_000uL,
                reactions = listOf(
                    ReactionDto(key = "👍", displayKey = "👍", count = 1u, byMe = false, senders = listOf("@a:x")),
                ),
            ),
        )

        compose.setContent {
            Timeline(
                rows = fixed,
                revision = 0uL,
                typingLine = null,
                isPaginating = false,
                canPaginate = false,
                onPaginate = {},
                onMarkRead = {},
                onReact = { row, key -> reactedRow = row; reactedKey = key },
            )
        }

        compose.onNodeWithTag("reaction-👍").performClick()
        compose.waitForIdle()

        assertEquals("1", reactedRow?.item?.id)
        assertEquals("👍", reactedKey)
    }

    // --- T5: tap a quote ---------------------------------------------------

    /** 40 rows, oldest `r0`; the newest, `r39`, quotes `quotedEventId`. */
    private fun quoting(quotedEventId: String): List<TimelineRowDto> =
        (0..39).map {
            row(
                id = "r$it", body = "row $it", timestampMs = (it + 1).toULong() * 1_000uL,
                quotes = if (it == 39) quotedEventId else null,
            )
        }

    private fun showQuoting(
        rows: List<TimelineRowDto>,
        onReply: (TimelineRowDto) -> Unit = {},
        onSwipeReply: (TimelineRowDto) -> Unit = {},
        liveAnswer: String? = null,
        liveFinished: Boolean = false,
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalReduceMotion provides true) {
                Box(Modifier.requiredWidth(411.dp)) {
                    Timeline(
                        rows = rows, revision = 0uL, typingLine = null, isPaginating = false,
                        canPaginate = false, onPaginate = {}, onMarkRead = {},
                        onReply = onReply, onSwipeReply = onSwipeReply,
                        liveAnswer = liveAnswer, liveFinished = liveFinished,
                    )
                }
            }
        }
    }

    private fun quoteIn(rowTag: String) =
        compose.onNode(hasTestTag("reply-quote") and hasAnyAncestor(hasTestTag(rowTag)), useUnmergedTree = true)

    @Test
    fun tappingAQuoteBringsItsParentIntoView() {
        showQuoting(quoting("r2"))
        compose.onNodeWithTag("row-r2").assertDoesNotExist()
        quoteIn("row-r39").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("row-r2").assertIsDisplayed()
        compose.onNode(hasTestTag("row-highlight") and hasAnyAncestor(hasTestTag("row-r2")), useUnmergedTree = true).assertExists()
    }

    @Test
    fun theHighlightFades() {
        showQuoting(quoting("r2"))
        compose.mainClock.autoAdvance = false
        quoteIn("row-r39").performClick()
        compose.mainClock.advanceTimeBy(300)
        compose.onNode(hasTestTag("row-highlight") and hasAnyAncestor(hasTestTag("row-r2")), useUnmergedTree = true).assertExists()
        compose.mainClock.advanceTimeBy(1_500)
        compose.onNodeWithTag("row-highlight", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun aQuoteWhoseParentIsNotLoadedOffersNoTap() {
        showQuoting(quoting("\$missing"))
        quoteIn("row-r39").assertHasNoClickAction()
    }

    @Test
    fun aLongPressOnAQuoteStillOpensTheMenu() {
        showQuoting(quoting("r2"))
        quoteIn("row-r39").performTouchInput { longClick() }
        compose.onNodeWithTag("message-menu").assertIsDisplayed()
        compose.onNodeWithTag("row-r2").assertDoesNotExist()
    }

    @Test
    fun aSwipeStartingOnAQuoteStillReplies() {
        val swiped = mutableListOf<String>()
        showQuoting(quoting("r2"), onSwipeReply = { swiped += it.item.id })
        quoteIn("row-r39").performTouchInput { swipeRight(startX = left + 20f, endX = left + 220f) }
        compose.waitForIdle()
        assertEquals(listOf("r39"), swiped)
        compose.onNodeWithTag("row-r2").assertDoesNotExist()
    }

    /** Timeline hands each row its readers, by id, with the face a sender has shown. */
    @Test
    fun timelineGivesEachRowItsReaders() {
        val ids = listOf("@a:example.org", "@b:example.org", "@c:example.org", "@d:example.org")
        showQuoting(
            listOf(row(id = "1", body = "mine", timestampMs = 1_000uL, isOwn = true, readBy = ids)),
        )
        compose.onNodeWithTag("reader-stack", useUnmergedTree = true)
            .assertContentDescriptionEquals("Read by " + uniffi.supermessage_ffi.peopleLabel(ids))
        compose.onAllNodesWithTag("reader-face", useUnmergedTree = true).assertCountEquals(3)
        compose.onNodeWithText("+1", useUnmergedTree = true).assertIsDisplayed()
    }

    /** The merged row is what TalkBack sees: the jump must be one of its actions, beside Reply. */
    @Test
    fun theQuoteJumpIsAnActionOnTheMergedRow() {
        showQuoting(quoting("r2"))
        val actions = compose.onNodeWithTag("row-r39").fetchSemanticsNode()
            .config[SemanticsActions.CustomActions]
        assertEquals(listOf("Reply", "Show original message"), actions.map { it.label })
        compose.runOnUiThread { actions.first { it.label == "Show original message" }.action() }
        compose.waitForIdle()
        compose.onNodeWithTag("row-r2").assertIsDisplayed()
    }

    @Test
    fun aRowWhoseParentIsNotLoadedHasNoJumpAction() {
        showQuoting(quoting("\$missing"))
        val labels = compose.onNodeWithTag("row-r39").fetchSemanticsNode()
            .config[SemanticsActions.CustomActions].map { it.label }
        assertEquals(listOf("Reply"), labels)
    }

    @Test
    fun enterOnTheFocusedQuoteJumps() {
        showQuoting(quoting("r2"))
        quoteIn("row-r39").requestFocus()
        quoteIn("row-r39").performKeyInput { pressKey(Key.Enter) }
        compose.waitForIdle()
        compose.onNodeWithTag("row-r2").assertIsDisplayed()
    }

    private fun assertCentred(rowTag: String) {
        val list = compose.onNodeWithTag("timeline-list").getUnclippedBoundsInRoot()
        val r = compose.onNodeWithTag(rowTag).getUnclippedBoundsInRoot()
        val offBy = ((r.top + r.bottom) / 2 - (list.top + list.bottom) / 2).value
        assertTrue("$rowTag centre is $offBy dp from the list centre", kotlin.math.abs(offBy) < ((r.bottom - r.top).value / 2))
    }

    @Test
    fun theJumpLandsOnTheRightRowBesideALiveTurn() {
        showQuoting(quoting("r10"), liveAnswer = "thinking")
        quoteIn("row-r39").performClick()
        compose.waitForIdle()
        assertCentred("row-r10")
    }

    @Test
    fun theJumpLandsOnTheRightRowBelowAFinishedLiveTurn() {
        showQuoting(quoting("r10"), liveAnswer = "done", liveFinished = true)
        quoteIn("row-r39").performClick()
        compose.waitForIdle()
        assertCentred("row-r10")
    }
}
