package dev.supermessage

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.semantics.getOrNull
import org.junit.Test
import uniffi.supermessage_core.AgentState
import uniffi.supermessage_core.Membership
import uniffi.supermessage_core.RoomAffordance
import uniffi.supermessage_core.RoomIdentity
import uniffi.supermessage_core.RoomPreview
import uniffi.supermessage_core.RoomRow
import uniffi.supermessage_core.RoomSummary
import uniffi.supermessage_core.RuntimeDto

/**
 * One roster row, laid out exactly as
 * `apple/Supermessage/Rooms/RoomRowView.swift` describes it: this view
 * parses nothing and composes nothing, it lays out what the core already
 * decided. So these tests pin layout and visibility rules only — never a
 * derivation this view has no business making.
 */
class RoomRowTest {
    @get:Rule val compose = createComposeRule()

    /** Shaped like `RoomsStoreTest.row(...)`, extended with what this view reads. */
    private fun row(
        name: String = "Ganesha",
        preview: RoomPreview? = RoomPreview(text = "hey there", pending = false),
        affordance: RoomAffordance = RoomAffordance.COMPOSE,
        unread: ULong = 0uL,
        role: String? = null,
        runtime: RuntimeDto? = null,
        initial: String = "G",
        id: String = "!a:x",
    ): RoomRow = RoomRow(
        room = RoomSummary(
            id = id, name = name, avatarUrl = null, unread = unread, lastMessage = null,
            lastMessageIsOwn = false, lastMessageNamesSender = false, lastEventType = null,
            lastActivityMs = null, runtime = runtime, membership = Membership.JOINED,
        ),
        identity = RoomIdentity(glyph = null, name = name, role = role, initial = initial),
        preview = preview,
        affordance = affordance,
    )

    /** The name and the preview line are the core's, and both show up. */
    @Test
    fun theNameAndPreviewRender() {
        compose.setContent {
            RoomRow(
                row = row(name = "Ganesha", preview = RoomPreview(text = "hey there", pending = false)),
                avatarUri = null,
                state = AgentState.ACTIVE,
                `when` = "2m",
            )
        }
        compose.onNodeWithText("Ganesha").assertIsDisplayed()
        compose.onNodeWithText("hey there").assertIsDisplayed()
    }

    /** An invitation gets the badge — nothing else does. */
    @Test
    fun theInvitationBadgeShowsForAnInvitation() {
        compose.setContent {
            RoomRow(
                row = row(affordance = RoomAffordance.RESPOND_TO_INVITATION),
                avatarUri = null,
                state = AgentState.QUIET,
                `when` = "",
            )
        }
        compose.onNodeWithText("Invitation").assertIsDisplayed()
    }

    /** An ordinary, composable room draws no invitation badge. */
    @Test
    fun theInvitationBadgeIsAbsentForAnOrdinaryRoom() {
        compose.setContent {
            RoomRow(
                row = row(affordance = RoomAffordance.COMPOSE),
                avatarUri = null,
                state = AgentState.ACTIVE,
                `when` = "",
            )
        }
        compose.onNodeWithText("Invitation").assertDoesNotExist()
    }

    /** A reader can turn the state dot off. */
    @Test
    fun theStateDotShowsByDefault() {
        compose.setContent {
            RoomRow(row = row(), avatarUri = null, state = AgentState.NEEDS_YOU, `when` = "", showsState = true)
        }
        compose.onNodeWithTag("state-dot").assertIsDisplayed()
    }

    /** `showsState = false` hides the dot rather than merely recoloring it. */
    @Test
    fun showsStateFalseHidesTheDot() {
        compose.setContent {
            RoomRow(row = row(), avatarUri = null, state = AgentState.NEEDS_YOU, `when` = "", showsState = false)
        }
        compose.onNodeWithTag("state-dot").assertDoesNotExist()
    }

    /** A room of people does not say "idle" — the word describes agents. */
    @Test
    fun aRoomOfPeopleHidesTheActivityWord() {
        compose.setContent {
            RoomRow(row = row(), avatarUri = null, state = AgentState.IDLE, `when` = "", describesAgent = false)
        }
        compose.onNodeWithTag("state-dot").assertDoesNotExist()
    }

    /** Owing an answer shows in any room, agent or not. */
    @Test
    fun needsYouShowsInARoomOfPeople() {
        compose.setContent {
            RoomRow(row = row(), avatarUri = null, state = AgentState.NEEDS_YOU, `when` = "", describesAgent = false)
        }
        compose.onNodeWithTag("state-dot").assertIsDisplayed()
    }

    /** A null avatar still draws a row — the sigil fallback, not a blank. */
    @Test
    fun aNullAvatarRendersTheSigilFallbackRow() {
        compose.setContent {
            RoomRow(row = row(initial = "G"), avatarUri = null, state = AgentState.IDLE, `when` = "")
        }
        compose.onNodeWithTag("avatar").assertIsDisplayed()
        compose.onNodeWithText("G").assertIsDisplayed()
    }

    /**
     * The avatar is its own tap target: tapping it asks about the room, not
     * about the conversation. This view has no other click handler of its
     * own — the rest of the row's tap-to-open-the-conversation behaviour
     * belongs to whoever places this row in a list.
     */
    @Test
    fun tappingTheAvatarAsksAboutTheRoom() {
        var calls = 0
        compose.setContent {
            RoomRow(row = row(), avatarUri = null, state = AgentState.ACTIVE, `when` = "", onOpenInfo = { calls++ })
        }
        compose.onNodeWithTag("avatar").performClick()
        assertEquals(1, calls)
    }

    /**
     * A malformed `data:` URI must not take the row down — it decodes to
     * `null` and falls back to the sigil, exactly like a missing avatar.
     * Covers both failure shapes `decodeDataUri()` guards against: no comma
     * to split on, and a comma followed by bytes that are not valid Base64.
     */
    @Test
    fun aMalformedAvatarUriFallsBackRatherThanCrashing() {
        compose.setContent {
            RoomRow(row = row(initial = "G"), avatarUri = "not-a-data-uri", state = AgentState.IDLE, `when` = "")
        }
        compose.onNodeWithTag("avatar").assertIsDisplayed()
        compose.onNodeWithText("G").assertIsDisplayed()
    }

    @Test
    fun aDataUriWithInvalidBase64FallsBackRatherThanCrashing() {
        compose.setContent {
            RoomRow(
                row = row(initial = "G"),
                avatarUri = "data:image/png;base64,***not base64***",
                state = AgentState.IDLE,
                `when` = "",
            )
        }
        compose.onNodeWithTag("avatar").assertIsDisplayed()
        compose.onNodeWithText("G").assertIsDisplayed()
    }

    /**
     * A screen reader hears "3 unread", not a bare "3".
     *
     * Carried from Task 5's review as a deferred gap: the port dropped iOS's
     * `.accessibilityLabel("\(count) unread")`, leaving the badge announcing a
     * number with no noun. In a room list that is indistinguishable from a
     * timestamp or any other count.
     */
    @Test
    fun theUnreadBadgeAnnouncesWhatItCounts() {
        compose.setContent {
            RoomRow(row = row(unread = 3uL), avatarUri = null, state = AgentState.IDLE, `when` = "now")
        }
        compose.onNodeWithContentDescription("3 unread").assertExists()
    }

    /**
     * The avatar's tap target says where it goes.
     *
     * Also carried from Task 5's review: `onClickLabel` was a reasonable Compose
     * analogue to Swift's `.accessibilityLabel`, but nothing asserted it, so the
     * claim that the two tap targets are distinguishable rested on inspection.
     */
    @Test
    fun theAvatarsClickTargetIsLabelled() {
        compose.setContent {
            RoomRow(
                row = row(name = "Ganesha"), avatarUri = null, state = AgentState.IDLE,
                `when` = "now", onOpenInfo = {},
            )
        }
        compose.onNode(
            SemanticsMatcher("has an onClick label naming the room") { node ->
                node.config.getOrNull(SemanticsActions.OnClick)?.label == "About Ganesha"
            }
        ).assertExists()
    }

    private val tile = uniffi.supermessage_ffi.agentAvatarVariants()[0]

    private fun under(tag: String) = androidx.compose.ui.test.hasTestTag(tag).and(
        androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag("avatar")),
    )

    @Test
    fun anAgentsRowHasItsTileBadgeAndDot() {
        compose.setContent {
            RoomRow(row(), null, AgentState.ACTIVE, "2m", describesAgent = true, style = tile)
        }
        compose.onNode(under("agent-badge"), useUnmergedTree = true).assertIsDisplayed()
        compose.onNode(under("state-dot"), useUnmergedTree = true).assertIsDisplayed()
        val description = compose.onNodeWithTag("avatar").fetchSemanticsNode()
            .config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)
        assertEquals(listOf("Agent, active"), description)
    }

    @Test
    fun aPersonsRowHasNoBadge() {
        compose.setContent {
            RoomRow(row(), null, AgentState.IDLE, "2m", describesAgent = false)
        }
        compose.onNodeWithTag("agent-badge", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("state-dot", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun theRowIsTwoLines() {
        compose.setContent {
            RoomRow(
                row(
                    runtime = RuntimeDto(harness = "claude-code", host = "foundry"),
                    preview = RoomPreview(text = "a long preview ".repeat(20), pending = false),
                ),
                null, AgentState.ACTIVE, "2m", describesAgent = true, style = tile,
            )
        }
        compose.onNodeWithText("claude-code", substring = true).assertDoesNotExist()
        compose.onNodeWithText("foundry", substring = true).assertDoesNotExist()
        compose.onNodeWithText("active", ignoreCase = true).assertDoesNotExist()
        val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithTag("row-preview").fetchSemanticsNode().config
            .getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        assertEquals(1, results.single().lineCount)
    }

    @Test
    fun quietDrawsNoDot() {
        compose.setContent {
            RoomRow(row(), null, AgentState.QUIET, "3d", describesAgent = true, style = tile)
        }
        compose.onNodeWithTag("state-dot", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("agent-badge", useUnmergedTree = true).assertExists()
    }

    @Test
    fun aPeopleRoomShowsOnlyANeedsYouDot() {
        var state by androidx.compose.runtime.mutableStateOf(AgentState.IDLE)
        compose.setContent {
            RoomRow(row(), null, state, "2m", describesAgent = false)
        }
        compose.onNodeWithTag("state-dot", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnUiThread { state = AgentState.NEEDS_YOU }
        compose.waitForIdle()
        compose.onNodeWithTag("state-dot", useUnmergedTree = true).assertExists()
    }

    @Test
    fun theStateDotCanBeTurnedOff() {
        compose.setContent {
            RoomRow(row(), null, AgentState.ACTIVE, "2m", showsState = false, describesAgent = true, style = tile)
        }
        compose.onNodeWithTag("state-dot", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun theAvatarOpensInfo() {
        var opened = 0
        compose.setContent {
            RoomRow(row(name = "Atlas"), null, AgentState.ACTIVE, "2m", style = tile, onOpenInfo = { opened++ })
        }
        compose.onNode(
            SemanticsMatcher("About Atlas") { it.config.getOrNull(SemanticsActions.OnClick)?.label == "About Atlas" },
        ).performClick()
        assertEquals(1, opened)
    }

    @Test
    fun noPreviewShowsTheRole() {
        compose.setContent {
            RoomRow(row(preview = null, role = "Reviews the code"), null, AgentState.IDLE, "2m")
        }
        compose.onNodeWithText("Reviews the code").assertIsDisplayed()
    }

    @Test
    fun twoLinesFitAtFontScaleTwo() {
        compose.setContent {
            val base = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(base.density, 2f),
            ) {
                androidx.compose.foundation.layout.Box(
                    androidx.compose.ui.Modifier.requiredWidth(411.dp),
                ) {
                    RoomRow(
                        row(
                            name = "A long agent name that will not fit on one line", unread = 120uL,
                            preview = RoomPreview(text = "a long preview ".repeat(20), pending = false),
                            affordance = RoomAffordance.RESPOND_TO_INVITATION,
                        ),
                        null, AgentState.NEEDS_YOU, "2m", style = tile,
                    )
                }
            }
        }
        for (text in listOf("A long agent name that will not fit on one line")) {
            val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithText(text).fetchSemanticsNode().config
                .getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
            assertEquals(1, results.single().lineCount)
        }
        val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithTag("row-preview").fetchSemanticsNode().config
            .getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        assertEquals(1, results.single().lineCount)
        compose.onNodeWithTag("unread-badge").assertIsDisplayed()
        compose.onNodeWithText("99+").assertIsDisplayed()
    }
}
