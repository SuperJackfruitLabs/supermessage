package dev.supermessage

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import uniffi.supermessage_core.Membership
import uniffi.supermessage_core.RoomAffordance
import uniffi.supermessage_core.RoomIdentity
import uniffi.supermessage_core.RoomRow
import uniffi.supermessage_core.RoomStatus
import uniffi.supermessage_core.RoomSummary

/** [RoomHeader]: iOS `RoomScreen.swift:102-115, 199-220`. */
class RoomHeaderTest {
    @get:Rule val compose = createComposeRule()

    private fun rowNamed(name: String) = RoomRow(
        room = RoomSummary(
            id = "!room:example.org", name = name, avatarUrl = null, unread = 0uL, lastMessage = null,
            lastMessageIsOwn = false, lastMessageNamesSender = false, lastEventType = null,
            lastActivityMs = null, runtime = null, membership = Membership.JOINED,
        ),
        identity = RoomIdentity(glyph = null, name = name, role = null, initial = name.take(1)),
        preview = null,
        affordance = RoomAffordance.COMPOSE,
    )

    private fun show(
        name: String = "Design review",
        memberCount: ULong? = 4uL,
        fontScale: Float? = null,
        status: RoomStatus? = null,
        reduceMotion: Boolean? = null,
        onOpenInfo: () -> Unit = {},
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalReduceMotion provides reduceMotion) {
            SupermessageTheme {
                val base = LocalDensity.current
                val density = if (fontScale == null) base else Density(base.density, fontScale)
                CompositionLocalProvider(LocalDensity provides density) {
                    Box(Modifier.requiredWidth(411.dp)) {
                        RoomHeader(rowNamed(name), status, memberCount, onOpenInfo)
                    }
                }
            }
            }
        }
    }

    @Test
    fun headerNamesTheRoomAndCountsPeople() {
        show(memberCount = 4uL)
        compose.onNodeWithText("Design review").assertIsDisplayed()
        compose.onNodeWithText("4 members").assertIsDisplayed()
    }

    @Test
    fun theHeaderDrawsNoAvatar() {
        show()
        compose.onNodeWithTag("room-header").assertIsDisplayed()
        compose.onAllNodesWithTag("room-avatar", useUnmergedTree = true)
            .assertCountEquals(0)
    }

    @Test
    fun anAgentsRoomShowsNoCount() {
        show(memberCount = null)
        compose.onAllNodesWithText("members", substring = true).assertCountEquals(0)
    }

    @Test
    fun tappingTheHeaderOpensInfo() {
        var opened = 0
        show(onOpenInfo = { opened++ })
        compose.onNodeWithTag("room-header").performClick()
        assertEquals(1, opened)
        val description = compose.onNodeWithTag("room-header").fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.ContentDescription)
        assertEquals(listOf("Design review, about this room"), description)
    }

    @Test
    fun aLongNameEllipsizesAndTheHeaderStaysTappable() {
        var opened = 0
        val name = "A very long room name ".repeat(4).take(80)
        show(name = name, fontScale = 2f, onOpenInfo = { opened++ })
        val results = mutableListOf<TextLayoutResult>()
        compose.onNode(
            hasAnyAncestor(hasTestTag("room-header")).and(androidx.compose.ui.test.hasText(name)),
            useUnmergedTree = true,
        ).fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        assertEquals(1, results.single().lineCount)
        compose.onNodeWithTag("room-header").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun anAgentsRoomSaysWhatItIsDoing() {
        show(status = RoomStatus.WORKING, memberCount = null, reduceMotion = true)
        compose.onNodeWithText("Working").assertIsDisplayed()
        compose.onAllNodesWithText("members", substring = true).assertCountEquals(0)
    }

    @Test
    fun aRoomOfPeopleCountsInstead() {
        show(status = null, memberCount = 4uL)
        compose.onNodeWithText("4 members").assertIsDisplayed()
        compose.onAllNodesWithTag("status-pill", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun everyStatusSaysItsWord() {
        val words = mapOf(
            RoomStatus.WORKING to "Working", RoomStatus.NEEDS_YOU to "Needs you", RoomStatus.ACTIVE to "Active",
            RoomStatus.IDLE to "Idle", RoomStatus.QUIET to "Quiet",
        )
        var status by androidx.compose.runtime.mutableStateOf(RoomStatus.WORKING)
        compose.setContent {
            CompositionLocalProvider(LocalReduceMotion provides true) {
                SupermessageTheme { RoomHeader(rowNamed("Atlas"), status, null, {}) }
            }
        }
        for ((s, word) in words) {
            compose.runOnUiThread { status = s }
            compose.waitForIdle()
            compose.onNodeWithText(word).assertIsDisplayed()
        }
    }

    private fun pulsing(): Boolean? = compose.onNodeWithTag("status-dot", useUnmergedTree = true)
        .fetchSemanticsNode().config.getOrNull(PulsingKey)

    @Test
    fun workingPulsesUnlessReduced() {
        show(status = RoomStatus.WORKING, reduceMotion = false)
        assertEquals(true, pulsing())
    }

    @Test
    fun workingDoesNotPulseWhenMotionIsReduced() {
        show(status = RoomStatus.WORKING, reduceMotion = true)
        assertEquals(false, pulsing())
    }

    @Test
    fun activeDoesNotPulse() {
        show(status = RoomStatus.ACTIVE, reduceMotion = false)
        assertEquals(false, pulsing())
    }

    @Test
    fun theHeaderFitsAtFontScaleTwo() {
        val name = "A very long room name for a very busy agent".padEnd(60, 'x')
        show(name = name, status = RoomStatus.NEEDS_YOU, memberCount = null, fontScale = 2f, reduceMotion = true)
        val results = mutableListOf<TextLayoutResult>()
        compose.onNode(
            hasAnyAncestor(hasTestTag("room-header")).and(androidx.compose.ui.test.hasText(name)),
            useUnmergedTree = true,
        ).fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        assertEquals(1, results.single().lineCount)
        val header = compose.onNodeWithTag("room-header").fetchSemanticsNode().boundsInRoot
        val pill = compose.onNodeWithTag("status-pill", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals("the pill is inside the header: $pill in $header", true,
            pill.left >= header.left && pill.right <= header.right && pill.top >= header.top && pill.bottom <= header.bottom)
    }

    @Test
    fun theHeaderIsStillOneButton() {
        show(name = "Atlas", status = RoomStatus.ACTIVE, memberCount = null, reduceMotion = true)
        val description = compose.onNodeWithTag("room-header").fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.ContentDescription)
        assertEquals(listOf("Atlas, about this room"), description)
    }
}
