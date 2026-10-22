package dev.supermessage

import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import uniffi.supermessage_core.Membership
import uniffi.supermessage_core.RoomIdentity
import uniffi.supermessage_core.SpaceSummary

/** [SpaceMenu], the roster's title (`Rooms/SpaceMenu.swift`). */
class SpaceMenuTest {
    @get:Rule val compose = createComposeRule()

    private fun space(id: String, name: String, children: ULong, membership: Membership = Membership.JOINED) =
        SpaceSummary(
            id = id, name = name, avatarUrl = null, childCount = children, membership = membership,
            identity = RoomIdentity(glyph = null, name = name, role = null, initial = name.take(1)),
        )

    private val spaces = listOf(
        space("!ops:x", "Ops", 3uL),
        space("!lab:x", "Lab", 0uL, Membership.INVITED),
    )

    private fun menu(
        list: List<SpaceSummary> = spaces,
        selected: String? = null,
        onSelect: (String?) -> Unit = {},
    ) = compose.setContent {
        SpaceMenu(
            spaces = list, selectedId = selected, allCount = 5,
            isInvitation = { it.membership == Membership.INVITED }, onSelect = onSelect,
        )
    }

    @Test
    fun noSpacesIsAPlainTitle() {
        menu(list = emptyList())
        compose.onNodeWithText("Chats").assertIsDisplayed().assertHasNoClickAction()
        compose.onNodeWithTag("space-menu").assertDoesNotExist()
    }

    @Test
    fun spacesOfferAllAndEach() {
        menu()
        compose.onNodeWithTag("space-menu").performClick()
        compose.onNodeWithText("All spaces · 5").assertIsDisplayed()
        compose.onNodeWithText("Ops · 3").assertIsDisplayed()
        compose.onNodeWithText("Lab · Invitation").assertIsDisplayed()
    }

    @Test
    fun choosingASpaceSelectsIt() {
        val chosen = mutableListOf<String?>()
        menu(onSelect = { chosen += it })
        compose.onNodeWithTag("space-menu").performClick()
        compose.onNodeWithTag("space-!ops:x").performClick()
        compose.onNodeWithTag("space-menu").performClick()
        compose.onNodeWithTag("space-all").performClick()
        assertEquals(listOf<String?>("!ops:x", null), chosen)
    }

    @Test
    fun theTitleNamesTheSelectedSpace() {
        menu(selected = "!ops:x")
        compose.onNodeWithTag("space-menu").assertContentDescriptionEquals("Space: Ops")
    }

    @Test
    fun noSelectionTitlesItChats() {
        menu()
        compose.onNodeWithTag("space-menu").assertContentDescriptionEquals("Space: Chats")
    }
}
