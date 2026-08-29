package dev.supermessage

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.supermessage.kit.CoreClient
import dev.supermessage.kit.Session
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import uniffi.supermessage_core.AccountDto
import uniffi.supermessage_core.Membership
import uniffi.supermessage_core.NotificationMode
import uniffi.supermessage_core.PersonDto
import uniffi.supermessage_core.RoomAffordance
import uniffi.supermessage_core.RoomIdentity
import uniffi.supermessage_core.RoomInfoDto
import uniffi.supermessage_core.RoomRow
import uniffi.supermessage_core.RoomSummary
import uniffi.supermessage_core.SearchResultDto
import uniffi.supermessage_core.SpaceSummary
import uniffi.supermessage_ffi.ConnectionState
import uniffi.supermessage_ffi.CoreInterface
import uniffi.supermessage_ffi.EventSink
import uniffi.supermessage_ffi.RoomsSnapshot
import uniffi.supermessage_ffi.StagedFile
import uniffi.supermessage_ffi.TimelineSnapshot

/**
 * The defect the whole-branch review named the most consequential one: a
 * room could never be created on Android, and an invitation could be seen
 * (the roster's own "N invitations hidden" banner, and — once revealed —
 * `RoomAffordance.RESPOND_TO_INVITATION`'s badge) but never acted on.
 * `NewRoomPanel` and `InvitationView` each already had their own green,
 * isolated instrumented tests; neither was reachable from anywhere a real
 * tap could land, because `ExtraPanel` (`MainActivity.kt`) had no case for
 * either and the roster header had no button for the first.
 *
 * These tests drive [AppRoot] itself — the composable `MainActivity.onCreate`
 * hands its real, signed-in [Session] to — rather than constructing
 * [NewRoomPanel] or [InvitationView] directly. That distinction is the whole
 * point: a test that renders either panel in isolation already existed and
 * passed throughout this defect, proving only that the panel worked, never
 * that a reader could reach it. [AppRoot] is driven here with a real
 * [Session] backed by a fake [CoreInterface] — the same house pattern
 * [NewRoomTest] and [InvitationTest] already use for the panels themselves —
 * never this device's own signed-in one.
 */
class RosterReachabilityTest {
    @get:Rule val compose = createComposeRule()

    private fun sessionOf(core: CoreInterface): Session =
        Session(client = CoreClient(core = core, dispatcher = Dispatchers.Unconfined), scope = CoroutineScope(Dispatchers.Unconfined))

    /** A `RosterPreferences` over a real, throwaway `DataStore` — no `Context` needed. */
    private fun preferences(showsInvitations: Boolean = false): RosterPreferences {
        val file = File.createTempFile("roster-reachability", ".preferences_pb")
        val prefs = RosterPreferences(PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.Unconfined)) { file })
        if (showsInvitations) runBlocking { prefs.setShowsInvitations(true) }
        return prefs
    }

    // roomName and identityName default to the same string, so every
    // existing call (`invitationRow()`) is unaffected — only
    // [invitationNamesTheRoomTheWayTheRosterDoes] passes them apart.
    private fun invitationRow(
        roomId: String = "!room:example.org",
        roomName: String = "Ops Room",
        identityName: String = roomName,
    ) = RoomRow(
        room = RoomSummary(
            id = roomId, name = roomName, avatarUrl = null, unread = 0uL, lastMessage = null,
            lastMessageIsOwn = false, lastMessageNamesSender = false, lastEventType = null,
            lastActivityMs = null, runtime = null, membership = Membership.INVITED,
        ),
        identity = RoomIdentity(glyph = null, name = identityName, role = null, initial = identityName.take(1)),
        preview = null,
        affordance = RoomAffordance.RESPOND_TO_INVITATION,
    )

    /**
     * Test 1 of the brief's Step 1: a new-room affordance exists, and
     * reaching it shows `NewRoomPanel`.
     *
     * Forced to a 411dp (phone) shell, the same width
     * `RootScaffoldTest.aPhoneShowsTheRosterAndNoInfoPane` pins as fitting
     * every device this suite runs on — so `assertIsDisplayed()` below is
     * both meaningful and device-independent, exactly as that suite's own
     * class doc explains.
     */
    @Test
    fun tappingNewRoomShowsTheNewRoomPanel() {
        val fake = FakeCore()
        val session = sessionOf(fake)
        val prefs = preferences()

        compose.setContent {
            // Width is required — it is what `paneCountFor` reads, so a phone
            // shell must be a phone width. Height follows the device: a
            // `requiredSize(_, 800.dp)` box is taller than a short screen and
            // gets clipped by the window, which puts roster rows off-screen
            // where no amount of `performScrollTo` reaches them.
            Box(Modifier.requiredWidth(411.dp).fillMaxHeight().testTag("test-shell")) {
                AppRoot(session = session, prefs = prefs)
            }
        }
        compose.waitForIdle()

        // Before this task: no button existed for this at all.
        compose.onNodeWithTag("roster-open-new-room").performClick()
        compose.waitForIdle()

        // The real `NewRoomPanel`, not a stand-in — reached through the
        // affordance rather than composed directly.
        compose.onNodeWithTag("new-room-query").assertIsDisplayed()
    }

    /**
     * Test 2 of the brief's Step 1: selecting a room whose affordance is
     * `RESPOND_TO_INVITATION` shows `InvitationView`, from which it can be
     * accepted or declined.
     *
     * The core is the one thing deciding this room is an invitation — the
     * fake hands back a single `RoomRow` with
     * `affordance = RoomAffordance.RESPOND_TO_INVITATION` set on it by this
     * test, and `AppRoot`'s own detail-pane branch reads that affordance
     * back rather than reclassifying the room by any other rule. Prefs are
     * seeded with `showsInvitations = true` so the row is not filtered out
     * of the roster before it can be tapped at all — the reveal affordance
     * itself is a separate, narrower concern from this task's own two
     * mandated tests.
     */
    @Test
    fun selectingAnInvitationRowShowsInvitationView() {
        val fake = FakeCore(
            roomsSnapshotResult = { RoomsSnapshot(seq = 0uL, rooms = listOf(invitationRow())) },
            roomInviterResult = { "@cody:example.org" },
        )
        val session = sessionOf(fake)
        val prefs = preferences(showsInvitations = true)

        compose.setContent {
            // Width is required — it is what `paneCountFor` reads, so a phone
            // shell must be a phone width. Height follows the device: a
            // `requiredSize(_, 800.dp)` box is taller than a short screen and
            // gets clipped by the window, which puts roster rows off-screen
            // where no amount of `performScrollTo` reaches them.
            Box(Modifier.requiredWidth(411.dp).fillMaxHeight().testTag("test-shell")) {
                AppRoot(session = session, prefs = prefs)
            }
        }
        compose.waitForIdle()

        // The badge the roster already renders for this row (Roster.kt /
        // RoomRow.kt), proving the row reached the screen at all before it
        // is tapped.
                // Scroll first: the roster is a LazyColumn, so whether a given row is
        // on screen depends on the device's height. These two passed on a
        // 2400px phone and failed at 1280px — the same layout-luck dependency
        // that cost three CI round-trips in RoomInfoTest.
        compose.onNodeWithText("Ops Room").performScrollTo().assertIsDisplayed()

        compose.onNodeWithTag("roster-row").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("invitation").assertIsDisplayed()
        compose.onNodeWithTag("invitation-accept").assertIsDisplayed()
        compose.onNodeWithTag("invitation-decline").assertIsDisplayed()
        compose.onNodeWithTag("invitation-inviter").assertIsDisplayed()
        assertEquals(listOf("!room:example.org"), fake.roomInviterCalls)
    }

    /**
     * Fix 4's own blocker: `MainActivity.kt` used to pass the invitation
     * view raw `room.name` while every other site in the app —
     * `RoomRow.kt:77`, `RoomInfo.kt:256`, `Roster.kt:114` — reads
     * `identity.name` instead. This row is built with the two deliberately
     * different (`roomName` lowercase, `identityName` the resolved display
     * form) so a fix that reverts to `room.name` fails this test rather
     * than passing it by coincidence — the shape of the defect fix 2's own
     * device log caught: the roster said "Workspace", the invitation beside
     * it said "invited to workspace.".
     */
    @Test
    fun invitationNamesTheRoomTheWayTheRosterDoes() {
        val fake = FakeCore(
            roomsSnapshotResult = {
                RoomsSnapshot(
                    seq = 0uL,
                    rooms = listOf(invitationRow(roomName = "workspace", identityName = "Workspace")),
                )
            },
            roomInviterResult = { null },
        )
        val session = sessionOf(fake)
        val prefs = preferences(showsInvitations = true)

        compose.setContent {
            // Width is required — it is what `paneCountFor` reads, so a phone
            // shell must be a phone width. Height follows the device: a
            // `requiredSize(_, 800.dp)` box is taller than a short screen and
            // gets clipped by the window, which puts roster rows off-screen
            // where no amount of `performScrollTo` reaches them.
            Box(Modifier.requiredWidth(411.dp).fillMaxHeight().testTag("test-shell")) {
                AppRoot(session = session, prefs = prefs)
            }
        }
        compose.waitForIdle()

        // The roster itself already reads identity.name (Roster.kt:114) —
        // this is the row this test taps next, confirmed on screen under
        // the identity-resolved name before the invitation view is asked to
        // agree with it.
                // Scroll first: the roster is a LazyColumn, so whether a given row is
        // on screen depends on the device's height. These two passed on a
        // 2400px phone and failed at 1280px — the same layout-luck dependency
        // that cost three CI round-trips in RoomInfoTest.
        compose.onNodeWithText("Workspace").performScrollTo().assertIsDisplayed()

        compose.onNodeWithTag("roster-row").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("invitation-message").assertTextEquals("You have been invited to Workspace.")
    }

    /**
     * A [CoreInterface] tailored to what this file drives, in the house
     * pattern [NewRoomTest] and [InvitationTest] already set: every method
     * the fake does not configure throws, so a test that accidentally
     * depends on an unconfigured path fails loudly rather than silently.
     */
    private class FakeCore(
        private val roomsSnapshotResult: () -> RoomsSnapshot = { RoomsSnapshot(seq = 0uL, rooms = emptyList()) },
        private val spacesListResult: () -> List<SpaceSummary> = { emptyList() },
        private val accountResult: () -> AccountDto = {
            AccountDto(userId = "@me:example.org", homeserver = "https://example.org")
        },
        private val knownPeopleResult: () -> List<PersonDto> = { emptyList() },
        private val roomInviterResult: (String) -> String? = { throw NotImplementedError() },
    ) : CoreInterface {
        val roomInviterCalls = mutableListOf<String>()

        override fun restoreSession(sink: EventSink): Boolean = true
        override fun roomsSnapshot(): RoomsSnapshot = roomsSnapshotResult()
        override fun spacesList(): List<SpaceSummary> = spacesListResult()
        override fun account(): AccountDto = accountResult()
        override fun knownPeople(): List<PersonDto> = knownPeopleResult()

        // Reached whenever a room is opened (`Session.open` ->
        // `TimelineStore.subscribeTo`) — every roster row this file hands
        // back is one, since selecting either the new-room result or an
        // invitation row opens it.
        override fun timelineSubscribe(roomId: String, sink: EventSink): Unit = Unit

        // Reached by `Roster`'s own `LaunchedEffect(roomId) { onLoadAvatar
        // (roomId) }` for every row on screen — including the invitation
        // row above.
        override fun roomAvatar(roomId: String): String? = null

        override fun roomInviter(roomId: String): String? {
            roomInviterCalls += roomId
            return roomInviterResult(roomId)
        }

        override fun attachmentDiscard(token: String): Unit = throw NotImplementedError()
        override fun attachmentSend(roomId: String, token: String): Unit = throw NotImplementedError()
        override fun attachmentStagePath(roomId: String, path: String): StagedFile = throw NotImplementedError()
        override fun connectionState(): ConnectionState = throw NotImplementedError()
        override fun createRoom(name: String, invite: List<String>, isDirect: Boolean): String = throw NotImplementedError()
        override fun deleteMessage(roomId: String, eventId: String): Unit = throw NotImplementedError()
        override fun directRoomWith(userId: String): String? = throw NotImplementedError()
        override fun editMessage(roomId: String, eventId: String, body: String): Unit = throw NotImplementedError()
        override fun inviteUser(roomId: String, userId: String): Unit = throw NotImplementedError()
        override fun joinRoom(roomId: String): Unit = throw NotImplementedError()
        override fun joinRoomByAlias(aliasOrId: String): String = throw NotImplementedError()
        override fun leaveRoom(roomId: String): Unit = throw NotImplementedError()
        override fun login(homeserver: String, username: String, password: String, sink: EventSink): Unit =
            throw NotImplementedError()
        override fun logout(): Unit = throw NotImplementedError()
        override fun markRoomRead(roomId: String): Unit = throw NotImplementedError()
        override fun mediaFetch(eventId: String): String? = throw NotImplementedError()
        override fun memberAvatar(mxcUri: String): String? = throw NotImplementedError()
        override fun roomAvatarFull(roomId: String): String? = throw NotImplementedError()
        override fun roomInfo(roomId: String): RoomInfoDto = throw NotImplementedError()
        override fun searchMessages(term: String, roomId: String?): List<SearchResultDto> = throw NotImplementedError()
        override fun sendMessage(roomId: String, body: String, mentions: List<String>): Unit = throw NotImplementedError()
        override fun sendReply(roomId: String, body: String, inReplyTo: String): Unit = throw NotImplementedError()
        override fun setRoomNotifications(roomId: String, mode: NotificationMode): Unit = throw NotImplementedError()
        override fun setRoomPinned(roomId: String, pinned: Boolean): Unit = throw NotImplementedError()
        override fun setTyping(roomId: String, typing: Boolean): Unit = throw NotImplementedError()
        override fun spaceSelect(spaceId: String?): Unit = throw NotImplementedError()
        override fun timelinePaginateBack(roomId: String, count: UShort): Boolean = throw NotImplementedError()
        override fun timelineResync(): TimelineSnapshot = throw NotImplementedError()
        override fun toggleReaction(roomId: String, eventId: String, key: String): Boolean = throw NotImplementedError()
    }
}
