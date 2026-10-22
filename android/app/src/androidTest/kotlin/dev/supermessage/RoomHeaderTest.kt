package dev.supermessage

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
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
        onOpenInfo: () -> Unit = {},
    ) {
        compose.setContent {
            SupermessageTheme {
                val base = LocalDensity.current
                val density = if (fontScale == null) base else Density(base.density, fontScale)
                CompositionLocalProvider(LocalDensity provides density) {
                    Box(Modifier.requiredWidth(411.dp)) {
                        RoomHeader(rowNamed(name), memberCount, onOpenInfo)
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
}
