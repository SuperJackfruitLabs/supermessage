package dev.supermessage

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.supermessage.kit.CoreClient
import dev.supermessage.kit.Session
import dev.supermessage.kit.StubCore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import uniffi.supermessage_core.AccountDto
import uniffi.supermessage_core.Mentionable
import uniffi.supermessage_core.Membership
import uniffi.supermessage_core.PersonDto
import uniffi.supermessage_core.RoomAffordance
import uniffi.supermessage_core.RoomCast
import uniffi.supermessage_core.RoomIdentity
import uniffi.supermessage_core.RoomRow
import uniffi.supermessage_core.RoomSummary
import uniffi.supermessage_core.RuntimeDto
import uniffi.supermessage_core.SpaceSummary
import uniffi.supermessage_ffi.EventSink
import uniffi.supermessage_ffi.RoomsSnapshot
import uniffi.supermessage_ffi.TimelineSnapshot

/** The `@` picker (C4): `ComposerView.swift:59-67, 508-549`. */
class MentionPickerTest {
    @get:Rule val compose = createComposeRule()

    private val atlas = PersonDto("@atlas:x", "Atlas", "A", RuntimeDto("claude", "ci"), null)
    private val priya = PersonDto("@priya:x", "Priya", "P", null, null)

    private fun castOf(roomId: String, vararg people: PersonDto) = RoomCast(
        roomId = roomId,
        people = people.toList(),
        counterpart = null,
        agentIds = people.filter { it.runtime != null }.map { it.userId },
        addressee = null,
        mentionables = people.map { Mentionable(it.userId, it.name) },
    )

    /** A composer holding its own text, offering [people] to any query. */
    private fun setComposer(
        people: List<PersonDto>,
        initial: String = "",
        keyboard: Boolean = false,
        onText: (String) -> Unit = {},
    ) {
        compose.setContent {
            // A hardware keyboard puts the window in keyboard input mode, where
            // buttons and rows can take focus; the emulator starts in touch mode.
            if (keyboard) {
                val modes = androidx.compose.ui.platform.LocalInputModeManager.current
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    modes.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard)
                }
            }
            CompositionLocalProvider(LocalReduceMotion provides true) {
                var text by androidx.compose.runtime.remember { mutableStateOf(initial) }
                Box(Modifier.requiredWidth(411.dp).testTag("shell")) {
                    Composer(
                        text = text,
                        onTextChange = { text = it; onText(it) },
                        onSend = {},
                        mentionCandidates = { people },
                    )
                }
            }
        }
    }

    @Test
    fun typingAtOffersTheRoomsPeopleAgentsFirst() {
        setComposer(listOf(atlas, priya))
        compose.onNodeWithTag("composer-text").performTextInput("@a")
        compose.onNodeWithTag("mention-picker").assertIsDisplayed()
        compose.onNodeWithTag("mention-@atlas:x").assertIsDisplayed()
        compose.onNode(androidx.compose.ui.test.hasText("claude on ci"), useUnmergedTree = true).assertIsDisplayed()
        compose.onNode(androidx.compose.ui.test.hasText("Agent"), useUnmergedTree = true).assertIsDisplayed()
        compose.onNode(hasContentDescription("Atlas, agent")).assertExists()
        compose.onNode(hasContentDescription("Priya")).assertExists()
    }

    @Test
    fun pickingInsertsTheNameAndCloses() {
        setComposer(listOf(atlas, priya))
        compose.onNodeWithTag("composer-text").performTextInput("@a")
        compose.onNodeWithTag("mention-@atlas:x").performClick()
        compose.onNodeWithTag("composer-text").assertTextEquals("@Atlas ")
        compose.onAllNodesWithTag("mention-picker").assertCountEquals(0)
        // The caret is after the inserted space, so the reader carries on typing there.
        compose.onNodeWithTag("composer-text").performTextInput("hi")
        compose.onNodeWithTag("composer-text").assertTextEquals("@Atlas hi")
    }

    /** Focus moving from the field into the list must not close it before Enter picks. */
    @Test
    fun enterOnAFocusedRowPicks() {
        setComposer(listOf(atlas, priya), keyboard = true)
        compose.onNodeWithTag("composer-text").performTextInput("@a")
        compose.onNodeWithTag("mention-@atlas:x").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        compose.onNodeWithTag("mention-picker").assertIsDisplayed()
        compose.onNodeWithTag("mention-@atlas:x").performKeyInput { pressKey(Key.Enter) }
        compose.waitForIdle()
        compose.onNodeWithTag("composer-text").assertTextEquals("@Atlas ")
        compose.onAllNodesWithTag("mention-picker").assertCountEquals(0)
    }

    /** The real hardware path: Shift+Tab from the field lands on the list, and Enter picks. */
    @Test
    fun shiftTabFromTheFieldReachesTheListAndEnterPicks() {
        setComposer(listOf(atlas, priya), keyboard = true)
        compose.onNodeWithTag("composer-text").performTextInput("@a")
        // Back past the Attach button (it precedes the field), then onto the last row.
        repeat(2) {
            compose.onNodeWithTag("composer-text").performKeyInput {
                keyDown(Key.ShiftLeft); pressKey(Key.Tab); keyUp(Key.ShiftLeft)
            }
            compose.waitForIdle()
        }
        compose.onNodeWithTag("mention-picker").assertIsDisplayed()
        compose.onNodeWithTag("mention-@priya:x").performKeyInput { pressKey(Key.Enter) }
        compose.waitForIdle()
        compose.onNodeWithTag("composer-text").assertTextEquals("@Priya ")
    }

    /** The list is announced when it opens, with how many it holds. */
    @Test
    fun theListIsAnnouncedWhenItOpens() {
        setComposer(listOf(atlas, priya))
        compose.onNodeWithTag("composer-text").performTextInput("@")
        compose.onNode(
            androidx.compose.ui.test.SemanticsMatcher.expectValue(
                SemanticsProperties.PaneTitle, "2 suggestions to mention",
            ),
        ).assertExists()
    }

    @Test
    fun anEmailAddressOpensNothing() {
        setComposer(listOf(atlas, priya))
        compose.onNodeWithTag("composer-text").performTextInput("ana@ex")
        compose.onAllNodesWithTag("mention-picker").assertCountEquals(0)
    }

    @Test
    fun noCandidatesOpensNothing() {
        setComposer(emptyList())
        compose.onNodeWithTag("composer-text").performTextInput("@")
        compose.onAllNodesWithTag("mention-picker").assertCountEquals(0)
    }

    @Test
    fun anAtInAFieldWithoutFocusOpensNothing() {
        setComposer(listOf(atlas), initial = "hi @a")
        compose.onAllNodesWithTag("mention-picker").assertCountEquals(0)
    }

    @Test
    fun theCastReloadsForTheNewRoom() {
        val fake = FakeCore(
            casts = mapOf("!a:x" to castOf("!a:x", atlas), "!b:x" to castOf("!b:x", priya)),
        )
        val session = sessionOf(fake)
        showApp(session)
        openRoom("Alpha")
        compose.onNodeWithTag("composer-text").performTextInput("@")
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("mention-@atlas:x").fetchSemanticsNodes().isNotEmpty() }

        runBlocking { session.open("!b:x") }
        compose.waitForIdle()
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasContentDescription("Bravo, about this room")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("composer-text").performTextInput("@")
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("mention-@priya:x").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithTag("mention-@atlas:x").assertCountEquals(0)
    }

    @Test
    fun aPickedMentionIsSent() {
        val fake = FakeCore(casts = mapOf("!a:x" to castOf("!a:x", atlas, priya)))
        showApp(sessionOf(fake))
        openRoom("Alpha")
        compose.onNodeWithTag("composer-text").performTextInput("@a")
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("mention-@atlas:x").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("mention-@atlas:x").performClick()
        compose.onNodeWithTag("composer-text").performTextInput("hi")
        compose.onNodeWithTag("composer-send").performClick()
        compose.waitUntil(5_000) { fake.mentions.isNotEmpty() }
        assertEquals(listOf("@atlas:x"), fake.mentions.single())
    }

    // --- AppRoot plumbing ---

    private fun sessionOf(core: StubCore) = Session(
        client = CoreClient(core = core, dispatcher = Dispatchers.Unconfined),
        scope = CoroutineScope(Dispatchers.Unconfined),
    )

    private fun showApp(session: Session) {
        val file = File.createTempFile("mention-picker", ".preferences_pb")
        val prefs = RosterPreferences(PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.Unconfined)) { file })
        compose.setContent {
            CompositionLocalProvider(LocalReduceMotion provides true) {
                Box(Modifier.requiredWidth(800.dp).fillMaxHeight().testTag("test-shell")) {
                    AppRoot(session = session, prefs = prefs)
                }
            }
        }
        compose.waitForIdle()
    }

    private fun openRoom(name: String) {
        compose.onNodeWithText(name).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun joinedRow(roomId: String, name: String) = RoomRow(
        room = RoomSummary(
            id = roomId, name = name, avatarUrl = null, unread = 0uL, lastMessage = null,
            lastMessageIsOwn = false, lastMessageNamesSender = false, lastEventType = null,
            lastActivityMs = null, runtime = null, membership = Membership.JOINED,
        ),
        identity = RoomIdentity(glyph = null, name = name, role = null, initial = name.take(1)),
        preview = null,
        affordance = RoomAffordance.COMPOSE,
    )

    /** Two joined rooms, a cast per room, and a record of what `sendMessage` was given. */
    private inner class FakeCore(private val casts: Map<String, RoomCast>) : StubCore() {
        /** The session pushes the hidden set at sign-in; these tests hide nothing. */
        override fun setHiddenEvents(eventIds: List<String>) {}

        val mentions = mutableListOf<List<String>>()
        private var subscribed: String? = null

        override fun sendMessage(roomId: String, body: String, mentions: List<String>) {
            this.mentions += mentions
        }

        override fun roomCast(roomId: String): RoomCast = casts.getValue(roomId)
        override fun restoreSession(sink: EventSink): Boolean = true
        override fun roomsSnapshot() = RoomsSnapshot(
            seq = 0uL,
            rooms = listOf(joinedRow("!a:x", "Alpha"), joinedRow("!b:x", "Bravo")),
        )
        override fun spacesList(): List<SpaceSummary> = emptyList()
        override fun account() = AccountDto(userId = "@me:x", homeserver = "https://x")
        override fun timelineSubscribe(roomId: String, sink: EventSink) { subscribed = roomId }
        override fun timelineResync() = TimelineSnapshot(roomId = subscribed.orEmpty(), seq = 0uL, items = emptyList())
        override fun timelinePaginateBack(roomId: String, count: UShort): Boolean = true
        override fun setTyping(roomId: String, typing: Boolean) = Unit
        override fun roomAvatar(roomId: String): String? = null
        override fun markRoomRead(roomId: String) = Unit
        override fun roomInfo(roomId: String) = uniffi.supermessage_core.RoomInfoDto(
            roomId = roomId, name = "Room", identity = RoomIdentity(null, "Room", null, "R"),
            topic = null, runtime = null, canonicalAlias = null, altAliases = emptyList(),
            activeMemberCount = 2uL, members = emptyList(),
            notifications = uniffi.supermessage_core.NotificationMode.DEFAULT, pinned = false,
        )
    }
}
