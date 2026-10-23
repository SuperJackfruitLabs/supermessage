package dev.supermessage

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.supermessage.kit.CoreClient
import dev.supermessage.kit.Session
import dev.supermessage.kit.StubCore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uniffi.supermessage_core.AccountDto
import dev.supermessage.kit.RosterChoice
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

    private fun sessionOf(core: CoreInterface, dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Unconfined): Session =
        Session(client = CoreClient(core = core, dispatcher = dispatcher), scope = CoroutineScope(Dispatchers.Unconfined))

    /** A `RosterPreferences` over a real, throwaway `DataStore` — no `Context` needed. */
    private fun preferences(): RosterPreferences {
        val file = File.createTempFile("roster-reachability", ".preferences_pb")
        return RosterPreferences(PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.Unconfined)) { file })
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
        compose.onNodeWithTag("toolbar-new-conversation").performClick()
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

        // The badge the roster already renders for this row (Roster.kt /
        // RoomRow.kt), proving the row reached the screen at all before it
        // is tapped.
                // Scroll first: the roster is a LazyColumn, so whether a given row is
        // on screen depends on the device's height. These two passed on a
        // 2400px phone and failed at 1280px — the same layout-luck dependency
        // that cost three CI round-trips in RoomInfoTest.
        compose.onNodeWithTag("invitations-row").performClick()
        compose.waitForIdle()
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

        // The roster itself already reads identity.name (Roster.kt:114) —
        // this is the row this test taps next, confirmed on screen under
        // the identity-resolved name before the invitation view is asked to
        // agree with it.
                // Scroll first: the roster is a LazyColumn, so whether a given row is
        // on screen depends on the device's height. These two passed on a
        // 2400px phone and failed at 1280px — the same layout-luck dependency
        // that cost three CI round-trips in RoomInfoTest.
        compose.onNodeWithTag("invitations-row").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Workspace").performScrollTo().assertIsDisplayed()

        compose.onNodeWithTag("roster-row").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("invitation-message").assertTextEquals("You have been invited to Workspace.")
    }

    /**
     * The header's pill follows the live turn (A1): an agent's room that is
     * running a turn says Working, and Active once the turn ends. The turn is
     * pushed into the live store the way `Session` does for `FfiEvent.Turn`
     * (`SessionTest` covers that route).
     */
    @Test
    fun theStatusFollowsTheTurn() {
        val atlas = RoomRow(
            room = RoomSummary(
                id = "!atlas:example.org", name = "Atlas", avatarUrl = null, unread = 0uL, lastMessage = null,
                lastMessageIsOwn = false, lastMessageNamesSender = false, lastEventType = null,
                lastActivityMs = System.currentTimeMillis().toULong() - 60_000uL,
                runtime = uniffi.supermessage_core.RuntimeDto("claude-code", "Ashram"),
                membership = Membership.JOINED,
            ),
            identity = RoomIdentity(glyph = null, name = "Atlas", role = null, initial = "A"),
            preview = null,
            affordance = RoomAffordance.COMPOSE,
        )
        val session = sessionOf(FakeCore(roomsSnapshotResult = { RoomsSnapshot(seq = 0uL, rooms = listOf(atlas)) }))
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalReduceMotion provides true) {
                Box(Modifier.requiredWidth(411.dp).fillMaxHeight().testTag("test-shell")) {
                    AppRoot(session = session, prefs = preferences())
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Atlas").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("room-header").assertIsDisplayed()
        compose.onAllNodesWithText("Working").assertCountEquals(0)

        val thought = uniffi.supermessage_core.TurnInput.Thought(1uL, "hmm", false, 500uL)
        val started = uniffi.supermessage_ffi.foldTurn("!atlas:example.org", listOf(thought))!!
        compose.runOnUiThread { session.live.apply(uniffi.supermessage_core.TurnUpdate(started.summary, null)) }
        compose.waitForIdle()
        compose.onNodeWithTag("status-pill", useUnmergedTree = true).assertIsDisplayed()
        compose.onNode(hasTestTag("room-header")).assertExists()
        compose.onNode(hasText("Working") and hasAnyAncestor(hasTestTag("room-header")), useUnmergedTree = true)
            .assertIsDisplayed()

        val ended = uniffi.supermessage_ffi.foldTurn(
            "!atlas:example.org",
            listOf(thought, uniffi.supermessage_core.TurnInput.Answer(1uL, true, 1_000uL)),
        )!!
        compose.runOnUiThread { session.live.apply(uniffi.supermessage_core.TurnUpdate(ended.summary, null)) }
        compose.waitForIdle()
        compose.onNode(hasText("Active") and hasAnyAncestor(hasTestTag("room-header")), useUnmergedTree = true)
            .assertIsDisplayed()
        compose.onAllNodesWithText("Working", useUnmergedTree = true).assertCountEquals(0)
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

    private fun infoOf(roomId: String, members: ULong) = RoomInfoDto(
        roomId = roomId, name = "Room", identity = RoomIdentity(null, "Room", null, "R"),
        topic = null, runtime = null, canonicalAlias = null, altAliases = emptyList(),
        activeMemberCount = members, members = emptyList(),
        notifications = NotificationMode.DEFAULT, pinned = false,
    )

    /** The header is the way into room info (iOS `RoomScreen.swift:102-115`). */
    @Test
    fun theRoomHeaderOpensRoomInfo() {
        val fake = FakeCore(
            roomsSnapshotResult = { RoomsSnapshot(seq = 0uL, rooms = listOf(joinedRow("!a:example.org", "Alpha"))) },
            roomInfoResult = { infoOf(it, 4uL) },
        )
        val session = sessionOf(fake)
        val prefs = preferences()

        compose.setContent {
            Box(Modifier.requiredWidth(411.dp).fillMaxHeight().testTag("test-shell")) {
                AppRoot(session = session, prefs = prefs)
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("Alpha").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("room-header").assertIsDisplayed().performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("pane-info").assertIsDisplayed()
        compose.onNodeWithTag("room-info-done").assertIsDisplayed()
    }

    private fun runtimeRow(roomId: String, name: String) = joinedRow(roomId, name).let {
        it.copy(room = it.room.copy(runtime = uniffi.supermessage_core.RuntimeDto("claude-code", "foundry")))
    }

    /** iOS asks for a count only of a room of people (`RoomScreen.swift:116-123`). */
    @Test
    fun anAgentsRoomNeverAsksForOrShowsAMemberCount() {
        val fake = FakeCore(
            roomsSnapshotResult = { RoomsSnapshot(seq = 0uL, rooms = listOf(runtimeRow("!g:example.org", "Gamma"))) },
            roomInfoResult = { infoOf(it, 4uL) },
        )
        val session = sessionOf(fake)
        val prefs = preferences()
        compose.setContent {
            Box(Modifier.requiredWidth(411.dp).fillMaxHeight().testTag("test-shell")) {
                AppRoot(session = session, prefs = prefs)
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Gamma").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("room-header").assertIsDisplayed()
        compose.onAllNodesWithText("members", substring = true).assertCountEquals(0)
        assertEquals(emptyList<String>(), fake.roomInfoCalls)
    }

    @Test
    fun anInvitationNeverAsksForOrShowsAMemberCount() {
        val fake = FakeCore(
            roomsSnapshotResult = { RoomsSnapshot(seq = 0uL, rooms = listOf(invitationRow())) },
            roomInviterResult = { null },
            roomInfoResult = { infoOf(it, 4uL) },
        )
        val session = sessionOf(fake)
        val prefs = preferences()
        compose.setContent {
            Box(Modifier.requiredWidth(411.dp).fillMaxHeight().testTag("test-shell")) {
                AppRoot(session = session, prefs = prefs)
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("invitations-row").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Ops Room").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("room-header").assertIsDisplayed()
        compose.onAllNodesWithText("members", substring = true).assertCountEquals(0)
        assertEquals(emptyList<String>(), fake.roomInfoCalls)
    }

    /** iOS displays `describesAgent ? nil : memberCount` (`RoomScreen.swift`), so a flip while open hides it. */
    @Test
    fun aRoomThatBecomesAnAgentsWhileOpenDropsItsCount() {
        var agent = false
        val fake = FakeCore(
            roomsSnapshotResult = {
                val row = if (agent) runtimeRow("!a:example.org", "Alpha") else joinedRow("!a:example.org", "Alpha")
                RoomsSnapshot(seq = 0uL, rooms = listOf(row))
            },
            roomInfoResult = { infoOf(it, 4uL) },
        )
        val session = sessionOf(fake)
        val prefs = preferences()
        compose.setContent {
            Box(Modifier.requiredWidth(800.dp).fillMaxHeight().testTag("test-shell")) {
                AppRoot(session = session, prefs = prefs)
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Alpha").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("4 members").assertIsDisplayed()

        agent = true
        runBlocking { session.scenePhaseChanged(true) }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("members", substring = true).fetchSemanticsNodes().isEmpty()
        }
        compose.onAllNodesWithText("members", substring = true).assertCountEquals(0)
    }


    private fun ownMessage(id: String, body: String) = uniffi.supermessage_core.TimelineRow(
        item = uniffi.supermessage_core.TimelineItemDto(
            id = id, eventId = id, kind = "message", msgtype = "m.text", detail = null,
            sender = "@me:example.org", senderDisplayName = null, senderAvatar = null, body = body,
            formattedBody = null, media = null, customPayload = null, timestampMs = 1_000uL,
            isOwn = true, sendState = null, replyTo = null, edited = false,
            reactions = emptyList(), readBy = emptyList(), editable = true, membershipSubject = null,
        ),
        view = uniffi.supermessage_core.ItemView.Bubble(
            muted = false,
            blocks = listOf(
                uniffi.supermessage_core.RichBlock.Paragraph(listOf(uniffi.supermessage_core.RichInline.Text(body))),
            ),
            voice = null,
        ),
        senderName = "Me", senderShort = "Me", senderInitial = "M", membershipVerb = null,
        replyQuote = null, canReplyOrReact = true, replyPreview = null,
    )

    private fun otherMessage(id: String, body: String) = ownMessage(id, body).let { mine ->
        mine.copy(
            item = mine.item.copy(sender = "@sam:example.org", isOwn = false, editable = false),
            senderName = "Sam", senderShort = "Sam", senderInitial = "S",
        )
    }

    private fun openAlphaWith(fake: FakeCore) {
        val session = sessionOf(fake)
        val prefs = preferences()
        compose.setContent {
            Box(Modifier.requiredWidth(411.dp).fillMaxHeight().testTag("test-shell")) {
                AppRoot(session = session, prefs = prefs)
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Alpha").performScrollTo().performClick()
        compose.waitForIdle()
    }

    private class RecordingHaptics : androidx.compose.ui.hapticfeedback.HapticFeedback {
        val played = mutableListOf<androidx.compose.ui.hapticfeedback.HapticFeedbackType>()
        override fun performHapticFeedback(hapticFeedbackType: androidx.compose.ui.hapticfeedback.HapticFeedbackType) {
            played += hapticFeedbackType
        }
    }

    private fun typeAndSend(fake: FakeCore, haptics: RecordingHaptics) {
        val session = sessionOf(fake)
        val prefs = preferences()
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalHapticFeedback provides haptics,
            ) {
                Box(Modifier.requiredWidth(411.dp).fillMaxHeight().testTag("test-shell")) {
                    AppRoot(session = session, prefs = prefs)
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Alpha").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("composer-text").performTextInput("hello there")
        compose.onNodeWithTag("composer-send").performClick()
        compose.waitForIdle()
    }

    /**
     * Opening a room hides the list (its rows measure zero wide on a phone) and
     * nothing in it may run: a Leading anchor at width 0 settles on Closed and
     * would run Mark read for the open room.
     */
    @Test
    fun openingARoomRunsNoSwipeAction() {
        val haptics = RecordingHaptics()
        val fake = FakeCore(
            roomsSnapshotResult = { RoomsSnapshot(seq = 0uL, rooms = listOf(joinedRow("!a:example.org", "Alpha"))) },
            roomInfoResult = { infoOf(it, 4uL) },
        )
        val session = sessionOf(fake)
        val prefs = preferences()
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalHapticFeedback provides haptics,
            ) {
                Box(Modifier.requiredWidth(411.dp).fillMaxHeight().testTag("test-shell")) {
                    AppRoot(session = session, prefs = prefs)
                }
            }
        }
        compose.waitForIdle()
        assertEquals(emptyList<androidx.compose.ui.hapticfeedback.HapticFeedbackType>(), haptics.played)
        compose.onNodeWithText("Alpha").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(emptyList<androidx.compose.ui.hapticfeedback.HapticFeedbackType>(), haptics.played)
        // Only the two marks the open itself makes (the pane's, and the
        // timeline's at its newest end), none from the hidden list's rows.
        assertEquals(listOf("!a:example.org", "!a:example.org"), fake.markReadCalls)
    }

    /** iOS buzzes lightly for a send that reached the room, and not otherwise (`ComposerView.swift:110-113`). */
    @Test
    fun aDeliveredSendIsALightHaptic() {
        val haptics = RecordingHaptics()
        val fake = FakeCore(
            roomsSnapshotResult = { RoomsSnapshot(seq = 0uL, rooms = listOf(joinedRow("!a:example.org", "Alpha"))) },
            roomInfoResult = { infoOf(it, 4uL) },
        )
        typeAndSend(fake, haptics)
        // The send runs off the UI thread; waitForIdle does not wait for it (it failed once as `expected [hello there] but was []`).
        compose.waitUntil(5_000) { fake.sent.isNotEmpty() && haptics.played.isNotEmpty() }
        assertEquals(listOf("hello there"), fake.sent)
        assertEquals(listOf(androidx.compose.ui.hapticfeedback.HapticFeedbackType.ContextClick), haptics.played)
    }

    @Test
    fun aSendThatFailedIsNoHaptic() {
        val haptics = RecordingHaptics()
        val fake = FakeCore(
            roomsSnapshotResult = { RoomsSnapshot(seq = 0uL, rooms = listOf(joinedRow("!a:example.org", "Alpha"))) },
            roomInfoResult = { infoOf(it, 4uL) },
            sendResult = { throw IllegalStateException("offline") },
        )
        typeAndSend(fake, haptics)
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("composer-failure").fetchSemanticsNodes().isNotEmpty() }
        // Exists, not displayed: the typing raised a real keyboard, which at a
        // short screen pans this activity's window (it is not edge to edge).
        compose.onNodeWithTag("composer-failure").assertExists()
        assertEquals(emptyList<androidx.compose.ui.hapticfeedback.HapticFeedbackType>(), haptics.played)
    }

    /** The menu's Delete redacts *that* event in *that* room, through the pane's session. */
    @Test
    fun deletingFromTheMenuRedactsThatEventInThatRoom() {
        val fake = FakeCore(
            roomsSnapshotResult = { RoomsSnapshot(seq = 0uL, rooms = listOf(joinedRow("!a:example.org", "Alpha"))) },
            roomInfoResult = { infoOf(it, 4uL) },
            timelineRows = listOf(ownMessage("\$one", "first"), ownMessage("\$two", "second")),
        )
        openAlphaWith(fake)
        compose.onNodeWithTag("row-\$one").performTouchInput { longClick() }
        compose.onNodeWithTag("menu-delete").performClick()
        compose.onNodeWithTag("delete-confirm-button").performClick()
        compose.waitForIdle()
        assertEquals(listOf("!a:example.org" to "\$one"), fake.deleted)
    }

    /**
     * The whole route a reader takes: long-press someone else's message,
     * Report…, Send, confirm. The core sees the report, and the message is
     * gone from the list once the core is handed the hidden set and marks it.
     */
    @Test
    fun reportingAMessageHidesIt() {
        val fake = FakeCore(
            roomsSnapshotResult = { RoomsSnapshot(seq = 0uL, rooms = listOf(joinedRow("!a:example.org", "Alpha"))) },
            roomInfoResult = { infoOf(it, 4uL) },
            timelineRows = listOf(otherMessage("\$one", "first words"), otherMessage("\$two", "second words")),
        )
        openAlphaWith(fake)
        compose.onNodeWithText("first words").assertIsDisplayed()
        compose.onNodeWithTag("row-\$one").performTouchInput { longClick() }
        compose.onNodeWithTag("menu-report").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("report-send").performClick()
        compose.onNodeWithTag("report-confirm-send").performClick()
        compose.waitForIdle()

        assertEquals(listOf(Triple("!a:example.org", "\$one", "")), fake.reportedEvents)
        compose.onAllNodesWithText("first words").assertCountEquals(0)
        compose.onNodeWithText("second words").assertIsDisplayed()
        compose.onAllNodesWithTag("report-sheet").assertCountEquals(0)
        // The blocked sender: the toggle was on by default.
        assertEquals(listOf("@sam:example.org"), fake.ignored)
    }

    /** Cancelling reports nothing, hides nothing, and focus comes back to the row. */
    @Test
    fun cancellingAReportLeavesTheMessageAndReturnsFocus() {
        val fake = FakeCore(
            roomsSnapshotResult = { RoomsSnapshot(seq = 0uL, rooms = listOf(joinedRow("!a:example.org", "Alpha"))) },
            roomInfoResult = { infoOf(it, 4uL) },
            timelineRows = listOf(otherMessage("\$one", "first words")),
        )
        openAlphaWith(fake)
        compose.onNodeWithTag("row-\$one").performTouchInput { longClick() }
        compose.onNodeWithTag("menu-report").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("report-reason").performTextInput("rude")
        compose.onNodeWithTag("report-send").performClick()
        compose.onNodeWithTag("report-confirm-cancel").performClick()
        compose.onNodeWithTag("report-cancel").performClick()
        compose.waitForIdle()

        assertTrue(fake.reportedEvents.isEmpty())
        compose.onNodeWithText("first words").assertIsDisplayed()
        compose.onNodeWithTag("row-\$one").assertIsFocused()
    }

    /** A report on an invitation names the room and never blocks anyone. */
    @Test
    fun reportingAnInvitationReportsTheRoom() {
        val fake = FakeCore(
            roomsSnapshotResult = { RoomsSnapshot(seq = 0uL, rooms = listOf(invitationRow())) },
            roomInviterResult = { null },
        )
        val session = sessionOf(fake)
        compose.setContent {
            Box(Modifier.requiredWidth(411.dp).fillMaxHeight().testTag("test-shell")) {
                AppRoot(session = session, prefs = preferences())
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("invitations-row").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Ops Room").performScrollTo()
        compose.onNodeWithTag("roster-row").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("invitation-report").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Report room").assertIsDisplayed()
        compose.onNodeWithTag("report-also-block").assertDoesNotExist()
        compose.onNodeWithTag("report-send").performClick()
        compose.onNodeWithTag("report-confirm-send").performClick()
        compose.waitForIdle()
        assertEquals(listOf("!room:example.org" to ""), fake.reportedRooms)
        assertTrue(fake.ignored.isEmpty())
        compose.onNodeWithTag("invitation-report").assertIsFocused()
    }

    /** A delete the core refuses says so under the row (R1). */
    @Test
    fun aRefusedDeleteSaysSoUnderTheRow() {
        val fake = FakeCore(
            roomsSnapshotResult = { RoomsSnapshot(seq = 0uL, rooms = listOf(joinedRow("!a:example.org", "Alpha"))) },
            roomInfoResult = { infoOf(it, 4uL) },
            timelineRows = listOf(ownMessage("\$one", "first")),
            deleteResult = { throw IllegalStateException("offline") },
        )
        openAlphaWith(fake)
        compose.onNodeWithTag("row-\$one").performTouchInput { longClick() }
        compose.onNodeWithTag("menu-delete").performClick()
        compose.onNodeWithTag("delete-confirm-button").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Not sent. Try again.").assertIsDisplayed()
    }

    /** TalkBack hears the refusal, like the composer's and the chip's failures. */
    @Test
    fun aRefusedDeleteIsAnnouncedPolitely() {
        val fake = FakeCore(
            roomsSnapshotResult = { RoomsSnapshot(seq = 0uL, rooms = listOf(joinedRow("!a:example.org", "Alpha"))) },
            roomInfoResult = { infoOf(it, 4uL) },
            timelineRows = listOf(ownMessage("\$one", "first")),
            deleteResult = { throw IllegalStateException("offline") },
        )
        openAlphaWith(fake)
        compose.onNodeWithTag("row-\$one").performTouchInput { longClick() }
        compose.onNodeWithTag("menu-delete").performClick()
        compose.onNodeWithTag("delete-confirm-button").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("delete-failed").assert(
            androidx.compose.ui.test.SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.LiveRegion,
                androidx.compose.ui.semantics.LiveRegionMode.Polite,
            ),
        )
    }

    /** Reply and Edit from the menu reach the composer, through the pane's stores. */
    @Test
    fun replyAndEditFromTheMenuReachTheComposer() {
        val fake = FakeCore(
            roomsSnapshotResult = { RoomsSnapshot(seq = 0uL, rooms = listOf(joinedRow("!a:example.org", "Alpha"))) },
            roomInfoResult = { infoOf(it, 4uL) },
            timelineRows = listOf(ownMessage("\$one", "first")),
        )
        openAlphaWith(fake)
        compose.onNodeWithTag("row-\$one").performTouchInput { longClick() }
        compose.onNodeWithTag("menu-reply").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("composer-reply-banner").assertIsDisplayed()

        compose.onNodeWithTag("row-\$one").performTouchInput { longClick() }
        compose.onNodeWithTag("menu-edit").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("composer-edit-banner").assertIsDisplayed()
    }

    /** iOS resets the count on `roomId` (`RoomScreen.swift:35-39`). */
    @Test
    fun memberCountResetsWhenTheRoomChanges() {
        val stalled = java.util.concurrent.CountDownLatch(1)
        val fake = FakeCore(
            roomsSnapshotResult = {
                RoomsSnapshot(
                    seq = 0uL,
                    rooms = listOf(joinedRow("!a:example.org", "Alpha"), joinedRow("!b:example.org", "Bravo")),
                )
            },
            // The second room never answers (the core call blocks on a worker
            // thread, as a stalled network would): a stale count must not
            // outlive the switch while it waits.
            roomInfoResult = { id ->
                if (id == "!a:example.org") infoOf(id, 4uL) else { stalled.await(); throw IllegalStateException("offline") }
            },
        )
        val session = sessionOf(fake, Dispatchers.IO)
        val prefs = preferences()

        compose.setContent {
            // Two panes: the roster stays beside the room, so the pane is
            // reused across the switch and only the room id changes. On a
            // phone the pane leaves composition and forgets anything.
            Box(Modifier.requiredWidth(800.dp).fillMaxHeight().testTag("test-shell")) {
                AppRoot(session = session, prefs = prefs)
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("Alpha").performScrollTo().performClick()
        compose.waitForIdle()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("4 members").fetchSemanticsNodes().isNotEmpty() }

        // The roster is still on screen: switch rooms in place.
        compose.onNodeWithText("Bravo").performScrollTo().performClick()
        compose.waitForIdle()

        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Bravo, about this room"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        compose.onAllNodesWithText("4 members").assertCountEquals(0)
        stalled.countDown()
    }

    /**
     * Opens Alpha, runs [open] on its first message, then switches to Bravo under
     * it (two panes, so the pane is reused and only the room id changes).
     * Both rooms hold a message with the same id, the case where "the id left
     * the list" cannot be what closes the previous room's state.
     */
    private fun switchRoomsAfter(open: () -> Unit) {
        val reacted = ownMessage("\$one", "first").let {
            it.copy(
                item = it.item.copy(
                    reactions = listOf(
                        uniffi.supermessage_core.ReactionDto("👍", "👍", 1u, false, listOf("@priya:example.org")),
                    ),
                ),
            )
        }
        val fake = FakeCore(
            roomsSnapshotResult = {
                RoomsSnapshot(
                    seq = 0uL,
                    rooms = listOf(joinedRow("!a:example.org", "Alpha"), joinedRow("!b:example.org", "Bravo")),
                )
            },
            roomInfoResult = { infoOf(it, 4uL) },
            timelineRows = listOf(reacted),
        )
        val session = sessionOf(fake)
        val prefs = preferences()
        compose.setContent {
            Box(Modifier.requiredWidth(800.dp).fillMaxHeight().testTag("test-shell")) {
                AppRoot(session = session, prefs = prefs)
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Alpha").performScrollTo().performClick()
        compose.waitForIdle()
        open()
        // Not a tap on the roster: the menu is modal, and a tap outside it
        // would dismiss it. A room opened from elsewhere (a notification, a
        // link) switches under it the way this does.
        runBlocking { session.open("!b:example.org") }
        compose.waitForIdle()
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Bravo, about this room"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
    }

    /** Review Focus 4: an open message menu does not follow the reader into the next room. */
    @Test
    fun anOpenMessageMenuDoesNotSurviveARoomSwitch() {
        switchRoomsAfter {
            compose.onNodeWithTag("row-\$one").performTouchInput { longClick() }
            compose.onNodeWithTag("message-menu").assertIsDisplayed()
        }
        compose.onNodeWithTag("row-\$one").assertIsDisplayed()
        compose.onAllNodesWithTag("message-menu").assertCountEquals(0)
    }

    /** The who-reacted popover, likewise. */
    @Test
    fun aWhoReactedPopoverDoesNotSurviveARoomSwitch() {
        switchRoomsAfter {
            compose.onNodeWithTag("reaction-👍").performTouchInput { longClick() }
            compose.onNodeWithTag("reaction-who").assertIsDisplayed()
        }
        compose.onAllNodesWithTag("reaction-who").assertCountEquals(0)
        // The new room is showing the same chip (it is only off-screen to the
        // right: the shell is wider than the display), so "gone" is not an
        // empty room.
        compose.onNodeWithTag("reaction-👍").assertExists()
    }

    private fun spaceOf(id: String, name: String) = SpaceSummary(
        id = id, name = name, avatarUrl = null, childCount = 3uL, membership = Membership.JOINED,
        identity = RoomIdentity(glyph = null, name = name, role = null, initial = name.take(1)),
        displayName = name,
    )

    @Test
    fun theSpacesMenuFiltersTheRosterThroughTheCore() {
        val fake = FakeCore(spacesListResult = { listOf(spaceOf("!ops:x", "Ops")) })
        val session = sessionOf(fake)
        compose.setContent {
            Box(Modifier.requiredWidth(411.dp).fillMaxHeight().testTag("test-shell")) {
                AppRoot(session = session, prefs = preferences())
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("space-menu").performClick()
        compose.onNodeWithTag("space-!ops:x").performClick()
        compose.waitForIdle()
        assertEquals(listOf<String?>("!ops:x"), fake.spaceSelected)
    }

    // (Search with a room open offers that room: NavigationTest.searchOnATabletOffersTheOpenRoom.)

    /** With nothing open, search offers no scope at all. */
    @Test
    fun searchOpenedWithNoRoomOffersNoScope() {
        val session = sessionOf(FakeCore())
        compose.setContent {
            Box(Modifier.requiredWidth(800.dp).fillMaxHeight().testTag("test-shell")) {
                AppRoot(session = session, prefs = preferences())
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("destination-search").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("search-field").assertIsDisplayed()
        compose.onAllNodesWithTag("search-scope-room").assertCountEquals(0)
    }

    /** O4: one connection line, under the roster title (like iOS). */
    @Test
    fun theConnectionLineSitsUnderTheRosterTitle() {
        val fake = FakeCore()
        val session = sessionOf(fake)
        session.connection.apply(ConnectionState("offline", null))
        compose.setContent {
            Box(Modifier.requiredWidth(411.dp).fillMaxHeight().testTag("test-shell")) {
                AppRoot(session = session, prefs = preferences())
            }
        }
        compose.waitForIdle()
        compose.onNode(
            androidx.compose.ui.test.hasTestTag("connection-bar") and
                androidx.compose.ui.test.hasAnyDescendant(androidx.compose.ui.test.hasText("Offline")),
        ).assertIsDisplayed()
        val titleBottom = compose.onNodeWithTag("roster-title").fetchSemanticsNode().boundsInRoot.bottom
        val barTop = compose.onNodeWithTag("connection-bar").fetchSemanticsNode().boundsInRoot.top
        assertTrue("bar top $barTop must be at or below the title bottom $titleBottom", barTop >= titleBottom)
    }

    @Test
    fun noConnectionLineWhenLive() {
        val session = sessionOf(FakeCore())
        session.connection.apply(ConnectionState("live", null))
        compose.setContent {
            Box(Modifier.requiredWidth(411.dp).fillMaxHeight().testTag("test-shell")) {
                AppRoot(session = session, prefs = preferences())
            }
        }
        compose.waitForIdle()
        compose.onAllNodesWithTag("connection-bar").assertCountEquals(0)
    }

    /** The open state is not stored (`RoomListView.swift:27-30`): a fresh start finds it closed. */
    @Test
    fun invitationsOpenAfterRestartAreClosed() {
        val fake = FakeCore(
            roomsSnapshotResult = { RoomsSnapshot(seq = 0uL, rooms = listOf(invitationRow())) },
            roomInviterResult = { null },
        )
        val session = sessionOf(fake)
        val file = File.createTempFile("roster-restart", ".preferences_pb")
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.Unconfined)) { file }
        var generation by androidx.compose.runtime.mutableStateOf(0)
        compose.setContent {
            androidx.compose.runtime.key(generation) {
                val prefs = androidx.compose.runtime.remember { RosterPreferences(store) }
                Box(Modifier.requiredWidth(411.dp).fillMaxHeight().testTag("test-shell")) {
                    AppRoot(session = session, prefs = prefs)
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("invitations-row").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Ops Room").assertIsDisplayed()

        generation++ // a fresh AppRoot over the same store
        compose.waitForIdle()
        compose.onNodeWithTag("invitations-row").assertIsDisplayed()
        compose.onNodeWithText("Ops Room").assertDoesNotExist()
    }

    /**
     * A Machine arrangement left by an older build is no longer offered: the
     * roster reads it as Waiting (no host sections) and the panel marks Waiting.
     */
    @Test
    fun aStoredMachineArrangementReadsAsWaiting() {
        val hosted = RoomRow(
            room = RoomSummary(
                id = "!atlas:example.org", name = "Atlas", avatarUrl = null, unread = 0uL, lastMessage = null,
                lastMessageIsOwn = false, lastMessageNamesSender = false, lastEventType = null,
                lastActivityMs = null, runtime = uniffi.supermessage_core.RuntimeDto("claude-code", "Ashram"),
                membership = Membership.JOINED,
            ),
            identity = RoomIdentity(glyph = null, name = "Atlas", role = null, initial = "A"),
            preview = null,
            affordance = RoomAffordance.COMPOSE,
        )
        val session = sessionOf(FakeCore(roomsSnapshotResult = { RoomsSnapshot(seq = 0uL, rooms = listOf(hosted)) }))
        val prefs = preferences()
        kotlinx.coroutines.runBlocking { prefs.setView(RosterChoice.MACHINE) }

        compose.setContent {
            Box(Modifier.requiredWidth(411.dp).fillMaxHeight().testTag("test-shell")) {
                AppRoot(session = session, prefs = prefs)
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("Atlas").assertIsDisplayed()
        // Machine would have headed the room with its host.
        compose.onAllNodesWithTag("section-header").assertCountEquals(0)

        compose.onNodeWithTag("toolbar-account").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("account-order-waiting").performScrollTo().assertIsSelected()
    }

    /**
     * The Account panel reached through [AppRoot] writes to the real stores:
     * an accent tap lands in [AppearancePreferences] and an order tap in
     * [RosterPreferences]. Every other AppRoot test passes no appearance, so
     * nothing else drives the wiring between the panel and the stores.
     */
    @Test
    fun theAccountPanelWritesTheStoresThroughAppRoot() {
        val session = sessionOf(FakeCore(roomsSnapshotResult = { RoomsSnapshot(seq = 0uL, rooms = emptyList()) }))
        val prefs = preferences()
        val file = File.createTempFile("appearance-reachability", ".preferences_pb")
        val appearance = AppearancePreferences(
            PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.Unconfined)) { file },
        )

        compose.setContent {
            Box(Modifier.requiredWidth(411.dp).fillMaxHeight().testTag("test-shell")) {
                AppRoot(session = session, prefs = prefs, appearance = appearance)
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("toolbar-account").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("accent-pink").performScrollTo().performClick()
        compose.onNodeWithTag("account-order-recent").performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals("pink", runBlocking { appearance.choice.first() }.accent)
        assertEquals(RosterChoice.RECENT, runBlocking { prefs.view.first() })
    }

    /**
     * On a phone: list -> room -> back must leave the list as it was. The
     * invitations row stays open ("not stored" means across a restart, not
     * across opening a room), a swipe's Mute/Unmute label is not forgotten, and
     * the open room is cleared.
     */
    @Test
    fun theListSurvivesOpeningARoomAndGoingBackOnAPhone() {
        val alpha = RoomRow(
            room = RoomSummary(
                id = "!alpha:example.org", name = "Alpha", avatarUrl = null, unread = 0uL, lastMessage = null,
                lastMessageIsOwn = false, lastMessageNamesSender = false, lastEventType = null,
                lastActivityMs = null, runtime = null, membership = Membership.JOINED,
            ),
            identity = RoomIdentity(glyph = null, name = "Alpha", role = null, initial = "A"),
            preview = null,
            affordance = RoomAffordance.COMPOSE,
        )
        val fake = FakeCore(
            roomsSnapshotResult = { RoomsSnapshot(seq = 0uL, rooms = listOf(invitationRow(), alpha)) },
            roomInviterResult = { null },
            roomInfoResult = { id ->
                RoomInfoDto(
                    roomId = id, name = "Alpha", identity = RoomIdentity(null, "Alpha", null, "A"),
                    topic = null, runtime = null, canonicalAlias = null, altAliases = emptyList(),
                    activeMemberCount = 2uL, members = emptyList(),
                    notifications = uniffi.supermessage_core.NotificationMode.DEFAULT, pinned = false,
                )
            },
        )
        val session = sessionOf(fake)
        val prefs = preferences()
        var backDispatcher: androidx.activity.OnBackPressedDispatcher? = null
        val haptics = RecordingHaptics()
        compose.setContent {
            backDispatcher = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalHapticFeedback provides haptics,
            ) {
                Box(Modifier.requiredWidth(411.dp).fillMaxHeight().testTag("test-shell")) {
                    AppRoot(session = session, prefs = prefs)
                }
            }
        }
        compose.waitForIdle()
        // Composing the roster runs no swipe action.
        assertEquals(emptyList<androidx.compose.ui.hapticfeedback.HapticFeedbackType>(), haptics.played)

        val alphaRow = hasTestTag("roster-row") and hasText("Alpha")
        compose.onNodeWithTag("invitations-row").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Ops Room").assertIsDisplayed()

        compose.onNode(alphaRow).performTouchInput { swipeLeft(endX = right - 160.dp.toPx()) }
        compose.onNodeWithTag("swipe-Mute").performClick()
        compose.waitForIdle()

        compose.onNode(alphaRow).performClick()
        compose.waitForIdle()
        assertEquals("!alpha:example.org", session.rooms.selectedRow?.room?.id)

        // Back goes through the activity's OnBackPressedDispatcher, which is
        // what the app's BackHandler registers with. An injected KEYCODE_BACK
        // also depends on the window manager routing it to this window, and
        // on the CI AVD (API 36, predictive back, headless) it never arrived:
        // the room stayed open for the full 10 s wait. Key routing is the
        // platform's, not ours; the BackHandler -> closeRoom path is.
        compose.runOnUiThread { backDispatcher!!.onBackPressed() }
        compose.waitUntil(timeoutMillis = 10_000) { session.rooms.selectedRow == null }
        compose.waitForIdle()

        assertEquals(null, session.rooms.selectedRow)
        compose.onNodeWithText("Ops Room").assertIsDisplayed()
        compose.onNode(alphaRow).performTouchInput { swipeLeft(endX = right - 160.dp.toPx()) }
        compose.waitForIdle()
        compose.onNodeWithTag("swipe-Unmute").assertIsDisplayed()
        // The one Mute landed. Hiding the list for the room (its rows measure
        // zero wide) and showing it again ran nothing: no Mark read on return.
        assertEquals(listOf(androidx.compose.ui.hapticfeedback.HapticFeedbackType.Confirm), haptics.played)
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
        private val roomInfoResult: (String) -> RoomInfoDto = { throw NotImplementedError() },
        private val timelineRows: List<uniffi.supermessage_core.TimelineRow> = emptyList(),
        private val deleteResult: () -> Unit = {},
        private val sendResult: () -> Unit = {},
    ) : StubCore() {
        private var timelineSink: EventSink? = null

        /**
         * Does what the core does with the pushed set: sends a `Set` for each
         * row whose event is in it, marked `hidden`.
         */
        override fun setHiddenEvents(eventIds: List<String>) {
            val sink = timelineSink ?: return
            val ops = timelineRows.mapIndexedNotNull { i, row ->
                if (row.item.eventId in eventIds) {
                    uniffi.supermessage_ffi.TimelineDiffOp.Set(i.toUInt(), row.copy(hidden = true))
                } else {
                    null
                }
            }
            if (ops.isEmpty()) return
            sink.onEvent(
                uniffi.supermessage_ffi.FfiEvent.TimelineDiff(
                    uniffi.supermessage_ffi.TimelineDiffEnvelope(
                        channel = "timeline", subject = subscribedRoomId.orEmpty(), seq = 2uL, ops = ops,
                    ),
                ),
            )
        }

        val sent = mutableListOf<String>()

        override fun sendMessage(roomId: String, body: String, mentions: List<String>) {
            sendResult()
            sent += body
        }

        val reportedEvents = mutableListOf<Triple<String, String, String>>()
        val reportedRooms = mutableListOf<Pair<String, String>>()
        val ignored = mutableListOf<String>()

        override fun reportEvent(roomId: String, eventId: String, reason: String) {
            reportedEvents += Triple(roomId, eventId, reason)
        }

        override fun reportRoom(roomId: String, reason: String) {
            reportedRooms += roomId to reason
        }

        override fun ignoreUser(userId: String) {
            ignored += userId
        }

        val roomInviterCalls = mutableListOf<String>()
        val spaceSelected = mutableListOf<String?>()

        override fun spaceSelect(spaceId: String?) {
            spaceSelected += spaceId
        }
        val deleted = mutableListOf<Pair<String, String>>()

        // The pane paginates when a short timeline is fully visible; "hit the start".
        override fun timelinePaginateBack(roomId: String, count: UShort): Boolean = true

        override fun deleteMessage(roomId: String, eventId: String) {
            deleted += roomId to eventId
            deleteResult()
        }
        private var subscribedRoomId: String? = null

        // The composer asks for the room's cast on opening it; nobody here is mentioned.
        override fun turn(roomId: String): uniffi.supermessage_core.TurnSnapshot? = null

        // The pane asks the core what the dock says; these tests have nothing to say.
        override fun acknowledgement(
            roomId: String, addressee: String?, agentIds: List<String>,
            typingAgents: List<String>, turnInProgress: Boolean,
        ): uniffi.supermessage_core.Acknowledgement? = null

        override fun roomCast(roomId: String) = uniffi.supermessage_core.RoomCast(
            roomId = roomId, people = emptyList(), counterpart = null,
            agentIds = emptyList(), addressee = null, mentionables = emptyList(),
        )

        override fun restoreSession(sink: EventSink): Boolean = true
        override fun roomsSnapshot(): RoomsSnapshot = roomsSnapshotResult()
        override fun spacesList(): List<SpaceSummary> = spacesListResult()
        override fun account(): AccountDto = accountResult()
        override fun ignoredUsers(): List<uniffi.supermessage_core.RoomMemberDto> = emptyList()
        override fun knownPeople(): List<PersonDto> = knownPeopleResult()

        // Reached whenever a room is opened (`Session.open` ->
        // `TimelineStore.subscribeTo`) — every roster row this file hands
        // back is one, since selecting either the new-room result or an
        // invitation row opens it.
        override fun timelineSubscribe(roomId: String, sink: EventSink) {
            subscribedRoomId = roomId
            timelineSink = sink
            if (timelineRows.isNotEmpty()) {
                sink.onEvent(
                    uniffi.supermessage_ffi.FfiEvent.TimelineDiff(
                        uniffi.supermessage_ffi.TimelineDiffEnvelope(
                            channel = "timeline", subject = roomId, seq = 1uL,
                            ops = listOf(uniffi.supermessage_ffi.TimelineDiffOp.Append(timelineRows)),
                        ),
                    ),
                )
            }
        }

        // Reached on every foreground (`Session.scenePhaseChanged` ->
        // `TimelineStore.seed`), which the app now reports from MainActivity.
        // An empty timeline for the room last opened is all these tests need.
        override fun timelineResync(): TimelineSnapshot =
            TimelineSnapshot(roomId = subscribedRoomId.orEmpty(), seq = 0uL, items = emptyList())

        // Reached when the app goes to the background with a room open
        // (`Session.scenePhaseChanged(false)` clears the typing notice).
        override fun setTyping(roomId: String, typing: Boolean): Unit = Unit

        // Reached by `Roster`'s own `LaunchedEffect(roomId) { onLoadAvatar
        // (roomId) }` for every row on screen — including the invitation
        // row above.
        override fun roomAvatar(roomId: String): String? = null

        val roomInfoCalls = mutableListOf<String>()

        override fun roomInfo(roomId: String): RoomInfoDto {
            roomInfoCalls += roomId
            return roomInfoResult(roomId)
        }

        // Reached on opening any joined room (the pane marks it read). The
        // real core marks only the open room and refuses every other, so the
        // fake does too, and records each ask either way.
        val markReadCalls = mutableListOf<String>()

        override fun markRoomRead(roomId: String) {
            markReadCalls += roomId
            check(roomId == subscribedRoomId) { "not the open room" }
        }

        override fun setRoomNotifications(roomId: String, mode: uniffi.supermessage_core.NotificationMode): Unit = Unit

        override fun roomInviter(roomId: String): String? {
            roomInviterCalls += roomId
            return roomInviterResult(roomId)
        }

        // Recovery is not what these tests are about; they fail loudly rather
        // than pretending, like every other unused member of this fake.
    }
}
