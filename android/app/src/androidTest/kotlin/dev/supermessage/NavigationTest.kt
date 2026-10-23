package dev.supermessage

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.supermessage.kit.CoreClient
import dev.supermessage.kit.Session
import dev.supermessage.kit.StubCore
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uniffi.supermessage_core.AccountDto
import uniffi.supermessage_core.NotificationMode
import uniffi.supermessage_core.RoomInfoDto
import uniffi.supermessage_core.RoomRow
import uniffi.supermessage_core.SearchResultDto
import uniffi.supermessage_core.SpaceSummary
import uniffi.supermessage_ffi.EventSink
import uniffi.supermessage_ffi.FfiEvent
import uniffi.supermessage_ffi.RoomDiffEnvelope
import uniffi.supermessage_ffi.RoomDiffOp
import uniffi.supermessage_ffi.RoomsSnapshot
import uniffi.supermessage_ffi.TimelineSnapshot
import uniffi.supermessage_ffi.displayInitial
import uniffi.supermessage_ffi.peopleLabel

/**
 * The four destinations (parity M5 Task 2): AppRoot over a stub core serving a fleet.
 *
 * Shells follow the plan's "Tablet tests": a width is required, never a height, and a
 * tablet shell starts at x = 0 and runs off the right of the window, so anything out
 * there is clicked through its semantics action rather than a pointer.
 */
class NavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    // MARK: fixtures

    private class Core(
        var rows: List<RoomRow>,
        var userId: String = "@me:example.org",
        private val gate: CountDownLatch? = null,
    ) : StubCore() {
        /** When set, `account()` waits for it: the window between signing in and learning who. */
        @Volatile var accountGate: CountDownLatch? = null
        var sink: EventSink? = null
        private var seq = 0uL
        val rosterReads = AtomicInteger()
        val avatarLoads = AtomicInteger()
        val searched = mutableListOf<Pair<String, String?>>()
        var hits: List<SearchResultDto> = emptyList()
        private var subscribed: String? = null

        /** What the core does when the roster changes: one Reset envelope on the diff channel. */
        fun deliver(next: List<RoomRow>) {
            rows = next
            seq += 1uL
            sink!!.onEvent(
                FfiEvent.RoomsDiff(RoomDiffEnvelope("sm://rooms/diff", "", seq, listOf(RoomDiffOp.Reset(next)))),
            )
        }

        override fun restoreSession(sink: EventSink): Boolean { this.sink = sink; return true }
        override fun login(homeserver: String, username: String, password: String, sink: EventSink) { this.sink = sink }
        override fun logout() {}
        override fun roomsSnapshot(): RoomsSnapshot {
            gate?.await(15, TimeUnit.SECONDS)
            rosterReads.incrementAndGet()
            return RoomsSnapshot(seq = seq, rooms = rows)
        }
        override fun spacesList(): List<SpaceSummary> = emptyList()
        override fun account(): AccountDto {
            accountGate?.await(15, TimeUnit.SECONDS)
            return AccountDto(userId = userId, homeserver = "https://example.org")
        }
        override fun ignoredUsers(): List<uniffi.supermessage_core.RoomMemberDto> = emptyList()
        override fun knownPeople(): List<uniffi.supermessage_core.PersonDto> = emptyList()
        override fun ensureRecovery(): String? = null
        override fun recoveryState(): String = "enabled"
        override fun roomAvatar(roomId: String): String? { avatarLoads.incrementAndGet(); return null }
        override fun searchMessages(term: String, roomId: String?): List<SearchResultDto> {
            searched += term to roomId
            return hits
        }
        override fun roomInfo(roomId: String) = RoomInfoDto(
            roomId = roomId, name = "Room", identity = uniffi.supermessage_core.RoomIdentity(null, "Room", null, "R"),
            topic = null, runtime = null, canonicalAlias = null, altAliases = emptyList(),
            activeMemberCount = 4uL, members = emptyList(), notifications = NotificationMode.DEFAULT, pinned = false,
        )
        override fun markRoomRead(roomId: String) {}
        override fun timelineSubscribe(roomId: String, sink: EventSink) { subscribed = roomId }
        override fun timelineResync(): TimelineSnapshot = TimelineSnapshot(roomId = subscribed.orEmpty(), seq = 0uL, items = emptyList())
        override fun timelinePaginateBack(roomId: String, count: UShort): Boolean = true
        override fun setHiddenEvents(eventIds: List<String>) {}
        override fun setTyping(roomId: String, typing: Boolean) {}
        override fun turn(roomId: String): uniffi.supermessage_core.TurnSnapshot? = null
        override fun acknowledgement(
            roomId: String, addressee: String?, agentIds: List<String>,
            typingAgents: List<String>, turnInProgress: Boolean,
        ): uniffi.supermessage_core.Acknowledgement? = null
        override fun roomCast(roomId: String) = uniffi.supermessage_core.RoomCast(
            roomId = roomId, people = emptyList(), counterpart = null,
            agentIds = emptyList(), addressee = null, mentionables = emptyList(),
        )
    }

    private class RecordingHaptics : HapticFeedback {
        val played = mutableListOf<HapticFeedbackType>()
        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { played += hapticFeedbackType }
    }

    private fun sessionOf(core: Core, dispatcher: CoroutineDispatcher = Dispatchers.Unconfined) =
        Session(client = CoreClient(core = core, dispatcher = dispatcher), scope = CoroutineScope(Dispatchers.Unconfined))

    private fun preferences(): RosterPreferences {
        val file = File.createTempFile("navigation", ".preferences_pb")
        return RosterPreferences(PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.Unconfined)) { file })
    }

    private fun phone(): Modifier = Modifier.requiredWidth(411.dp).fillMaxHeight()

    private fun tablet(width: Int): Modifier =
        Modifier.fillMaxWidth().wrapContentWidth(Alignment.Start, unbounded = true).requiredWidth(width.dp).fillMaxHeight()

    private fun show(
        core: Core,
        shell: Modifier = phone(),
        fontScale: Float = 1f,
        haptics: HapticFeedback? = null,
        dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
    ): Session {
        val session = sessionOf(core, dispatcher)
        val prefs = preferences()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalHapticFeedback provides (haptics ?: LocalHapticFeedback.current)) {
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    Box(shell.testTag("test-shell")) { AppRoot(session = session, prefs = prefs) }
                }
            }
        }
        compose.waitForIdle()
        return session
    }

    /** A click that does not need the node to be inside the window. */
    private fun SemanticsNodeInteraction.tap() = performSemanticsAction(SemanticsActions.OnClick)

    private fun select(destination: String) {
        compose.onNodeWithTag("destination-$destination").tap()
        compose.waitForIdle()
    }

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun exists(tag: String, unmerged: Boolean = false) =
        compose.onAllNodesWithTag(tag, useUnmergedTree = unmerged).fetchSemanticsNodes().isNotEmpty()

    private fun left(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.left
    private fun right(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.right
    private fun top(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top
    private fun bottom(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.bottom

    private fun fleet() = NavRows.fleet()
    private fun answered(rows: List<RoomRow>, id: String) =
        rows.map { if (it.room.id == id) it.copy(preview = it.preview?.copy(pending = false)) else it }

    // MARK: the bar and the rail

    @Test
    fun aPhoneHasABottomBarWithTheFourDestinations() {
        show(Core(fleet()))
        assertTrue(exists("nav-bar"))
        assertTrue(!exists("nav-rail"))
        val tags = listOf("destination-chats", "destination-needs-you", "destination-agents", "destination-search")
        val lefts = tags.map { left(it) }
        assertEquals("the order is Chats, Needs you, Agents, Search", lefts.sorted(), lefts)
        assertEquals(4, lefts.toSet().size)
        listOf("Chats", "Needs you", "Agents", "Search").forEach { title ->
            compose.onNode(hasText(title) and hasAnyAncestor(hasTestTag("nav-bar")), useUnmergedTree = true).assertIsDisplayed()
        }
        assertTrue("the bar sits under the roster", top("nav-bar") >= bottom("roster") - 1f)
    }

    @Test
    fun aTabletHasARail() {
        show(Core(fleet()), tablet(840))
        assertTrue(exists("nav-rail"))
        assertTrue(!exists("nav-bar"))
        assertTrue("the roster starts after the rail", left("roster") >= right("nav-rail") - 1f)
        assertTrue("two panes after the rail", exists("pane-timeline"))
    }

    @Test
    fun aWideTabletKeepsThreePanes() {
        show(Core(fleet()), tablet(1280))
        assertTrue(exists("nav-rail"))
        assertTrue(left("roster") >= right("nav-rail") - 1f)
        assertTrue(exists("pane-timeline"))
        compose.onNodeWithText("Design Review").tap()
        compose.waitForIdle()
        compose.onNodeWithTag("room-header").tap()
        compose.waitForIdle()
        assertTrue("room info sits in the third pane", exists("pane-info"))
        assertTrue(right("pane-info") <= 1280f * compose.density.density + 1f)
    }

    @Test
    fun theBarGoesInARoom() {
        show(Core(fleet()))
        compose.onNodeWithText("Design Review").performClick()
        compose.waitForIdle()
        assertTrue("the bar is hidden while a room is open", !exists("nav-bar"))
        back()
        assertTrue(exists("nav-bar"))
        compose.onNodeWithTag("destination-chats").assertIsSelected()
    }

    @Test
    fun backFromARoomReturnsToTheDestinationThatOpenedIt() {
        show(Core(fleet()))
        select("needs-you")
        compose.onNodeWithText("Atlas").performClick()
        compose.waitForIdle()
        back()
        compose.onNodeWithTag("destination-needs-you").assertIsSelected()
        compose.onNodeWithTag("needs-you-list").assertIsDisplayed()

        select("agents")
        compose.onNodeWithText("Sam").performClick()
        compose.waitForIdle()
        back()
        compose.onNodeWithTag("destination-agents").assertIsSelected()
        compose.onNodeWithTag("agents-list").assertIsDisplayed()
    }

    @Test
    fun backFromNeedsYouGoesToChatsFirst() {
        show(Core(fleet()))
        listOf("needs-you", "agents", "search").forEach { destination ->
            select(destination)
            back()
            compose.onNodeWithTag("destination-chats").assertIsSelected()
        }
        assertTrue("from Chats, Back is not intercepted", !compose.activity.onBackPressedDispatcher.hasEnabledCallbacks())
    }

    @Test
    fun aTabletKeepsTheRoomWhenTheDestinationChanges() {
        show(Core(fleet()), tablet(840))
        compose.onNodeWithText("Atlas").tap()
        compose.waitForIdle()
        select("agents")
        assertTrue("the room stays open", exists("room-header"))
        compose.onNode(hasText("Atlas") and hasAnyAncestor(hasTestTag("room-header")), useUnmergedTree = true).assertExists()
    }

    // MARK: the badge

    @Test
    fun theBadgeCountsDecisionsOnly() {
        show(Core(fleet()))
        compose.onNodeWithTag("needs-you-badge", useUnmergedTree = true).assertTextEquals("2")
        compose.onNodeWithTag("destination-needs-you").assertContentDescriptionEquals("Needs you, 2")
    }

    @Test
    fun anInvitationAloneLightsNoBadge() {
        show(Core(listOf(NavRows.row("!i:x", "Estate Planning", invited = true, minutesAgo = null))))
        assertTrue(!exists("needs-you-badge", unmerged = true))
        compose.onNodeWithTag("destination-needs-you").assertContentDescriptionEquals("Needs you")
    }

    @Test
    fun theBadgeGoesWhenTheDecisionIsAnswered() {
        val core = Core(fleet().filter { it.room.id != "!b:x" })
        show(core)
        compose.onNodeWithTag("needs-you-badge", useUnmergedTree = true).assertTextEquals("1")
        compose.runOnUiThread { core.deliver(answered(core.rows, "!a:x")) }
        compose.waitForIdle()
        assertTrue("the badge went in that update", !exists("needs-you-badge", unmerged = true))
    }

    @Test
    fun theBadgeIsAnnouncedAndTheCurrentDestinationIsSelected() {
        show(Core(fleet()))
        // A count that changes while the reader is here is spoken.
        compose.onNodeWithTag("destination-needs-you")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        compose.onNodeWithTag("destination-chats").assertIsSelected()
        compose.onNodeWithTag("destination-agents").assertIsNotSelected()
        select("agents")
        compose.onNodeWithTag("destination-agents").assertIsSelected()
        compose.onNodeWithTag("destination-chats").assertIsNotSelected()
    }

    // MARK: moving

    @Test
    fun aDestinationChangeIsASelectionHaptic() {
        val haptics = RecordingHaptics()
        show(Core(fleet()), haptics = haptics)
        select("agents")
        assertEquals(listOf(HapticFeedbackType.SegmentTick), haptics.played)
        select("agents")
        assertEquals("choosing the same place again is not a change", 1, haptics.played.size)
    }

    // MARK: search

    @Test
    fun searchIsADestinationWithNoCancel() {
        show(Core(fleet()))
        select("search")
        compose.onNodeWithTag("search-field").assertIsDisplayed()
        assertTrue(!exists("search-cancel"))
        assertTrue(!exists("search-scope-room"))
    }

    @Test
    fun searchOnATabletOffersTheOpenRoom() {
        show(Core(fleet()), tablet(840))
        compose.onNodeWithText("Atlas").tap()
        compose.waitForIdle()
        select("search")
        compose.onNodeWithTag("search-scope-room").assertTextEquals("Atlas").assertIsSelected()
    }

    @Test
    fun backFromASearchResultKeepsTheTerm() {
        val core = Core(fleet())
        core.hits = listOf(
            SearchResultDto(roomId = "!q:x", eventId = "\$e1", sender = "@a:x", body = "deploy done", timestampMs = 1uL),
        )
        show(core)
        select("search")
        compose.onNodeWithTag("search-field").performTextInput("deploy")
        compose.onNodeWithTag("search-field").performSemanticsAction(SemanticsActions.OnImeAction)
        compose.waitUntil(5_000) { exists("search-result-\$e1") }
        compose.onNodeWithTag("search-result-\$e1").performClick()
        compose.waitForIdle()
        back()
        compose.onNodeWithTag("destination-search").assertIsSelected()
        compose.onNodeWithTag("search-field").assert(
            SemanticsMatcher("reads deploy") { it.config.getOrNull(SemanticsProperties.EditableText)?.text == "deploy" },
        )
    }

    @Test
    fun theTermSurvivesAVisitElsewhere() {
        show(Core(fleet()))
        select("search")
        compose.onNodeWithTag("search-field").performTextInput("deploy")
        select("agents")
        select("search")
        compose.onNodeWithTag("search-field").assert(
            SemanticsMatcher("reads deploy") { it.config.getOrNull(SemanticsProperties.EditableText)?.text == "deploy" },
        )
    }

    // MARK: the toolbar

    @Test
    fun theChatsToolbarHasAccountAndNewConversation() {
        show(Core(fleet(), userId = "@rakesh:example.org"))
        val initial = displayInitial(peopleLabel(listOf("@rakesh:example.org")))
        compose.waitUntil(5_000) { initialIs(initial) }
        compose.onNodeWithTag("toolbar-account").assertContentDescriptionEquals("Account").assertTextEquals(initial)
        compose.onNodeWithTag("toolbar-new-conversation").assertContentDescriptionEquals("New conversation")
        compose.onNodeWithTag("toolbar-account").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("account").assertIsDisplayed()
        compose.onNodeWithTag("account-done").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("toolbar-new-conversation").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("pane-info").assertIsDisplayed()
        compose.onNodeWithTag("new-room-cancel").performClick()
        compose.waitForIdle()

        select("needs-you")
        assertTrue("a phone's toolbar is Chats' alone", !exists("toolbar-account") && !exists("toolbar-new-conversation"))
    }

    private fun initialIs(initial: String) = compose.onAllNodesWithTag("toolbar-account").fetchSemanticsNodes().firstOrNull()
        ?.config?.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text == initial

    @Test
    fun onARailTheToolbarStaysOnEveryDestination() {
        show(Core(fleet()), tablet(840))
        listOf("chats", "needs-you", "agents", "search").forEach { destination ->
            select(destination)
            assertTrue("account on $destination", exists("toolbar-account"))
            assertTrue("compose on $destination", exists("toolbar-new-conversation"))
        }
    }

    // MARK: sign-out

    @Test
    fun signingOutReturnsToChatsWithNoBadge() {
        val core = Core(fleet(), userId = "@first:example.org")
        show(core, tablet(840), dispatcher = Dispatchers.IO)
        compose.waitUntil(5_000) { exists("needs-you-badge", unmerged = true) }
        select("needs-you")
        compose.onNodeWithTag("needs-you-badge", useUnmergedTree = true).assertTextEquals("2")

        compose.onNodeWithTag("toolbar-account").tap()
        compose.waitForIdle()
        compose.onNodeWithTag("account-sign-out").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithTag("account-sign-out-confirm-button").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()

        // The next account owes nothing, and is somebody else.
        core.rows = listOf(NavRows.row("!q:x", "Design Review", minutesAgo = 90.0))
        core.userId = "@zed:example.org"
        // Who the new account is has not been answered yet: the toolbar must not say who left.
        val whoIsIt = CountDownLatch(1)
        core.accountGate = whoIsIt
        compose.onNodeWithTag("username").performTextInput("zed")
        compose.onNodeWithTag("password").performTextInput("pw")
        compose.onNodeWithTag("sign-in").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5_000) { exists("destination-chats") }
        compose.waitForIdle()

        compose.onNodeWithTag("destination-chats").assertIsSelected()
        assertTrue("no badge from the first account", !exists("needs-you-badge", unmerged = true))
        val first = displayInitial(peopleLabel(listOf("@first:example.org")))
        assertTrue("the previous account's initial is still showing", !initialIs(first))
        whoIsIt.countDown()
        val zed = displayInitial(peopleLabel(listOf("@zed:example.org")))
        compose.waitUntil(5_000) { initialIs(zed) }
    }

    // MARK: large text

    @Test
    fun theBarFitsAtFontScaleTwo() {
        show(Core(fleet()), fontScale = 2f)
        val tags = listOf("destination-chats", "destination-needs-you", "destination-agents", "destination-search")
        val titles = listOf("Chats", "Needs you", "Agents", "Search")
        titles.forEachIndexed { i, title ->
            val label = compose.onNode(hasText(title) and hasAnyAncestor(hasTestTag(tags[i])), useUnmergedTree = true)
            val results = mutableListOf<TextLayoutResult>()
            label.fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)!!.action!!.invoke(results)
            assertEquals("$title wraps", 1, results.single().lineCount)
            assertTrue("$title is cut off (ellipsized)", !results.single().isLineEllipsized(0))
        }
        val bounds = tags.map { compose.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot }
        bounds.zipWithNext().forEach { (a, b) -> assertTrue("items overlap: $a $b", a.right <= b.left + 1f) }
        compose.onNodeWithTag("needs-you-badge", useUnmergedTree = true).assertIsDisplayed()
    }

    // MARK: first read, scroll, stale

    @Test
    fun nothingIsSaidBeforeTheFirstReadLands() {
        val gate = CountDownLatch(1)
        show(Core(emptyList(), gate = gate), dispatcher = Dispatchers.IO)
        select("needs-you")
        assertTrue("caught up before the roster was read", !exists("caught-up"))
        select("agents")
        assertTrue("no agents before the roster was read", !exists("no-agents"))
        select("needs-you")
        gate.countDown()
        compose.waitUntil(5_000) { exists("caught-up") }
        select("agents")
        compose.waitUntil(5_000) { exists("no-agents") }
    }

    private fun manyAgents(n: Int) = (0 until n).map {
        NavRows.row("!r$it:x", "Room %02d".format(it), role = "Ops", pending = true, agent = true, minutesAgo = 1.0 + it)
    }

    @Test
    fun theRosterStaysAliveAcrossDestinationSwitches() {
        val core = Core(manyAgents(60))
        show(core)
        compose.onNodeWithTag("roster").performScrollToNode(hasText("Room 45"))
        compose.waitForIdle()
        compose.onNodeWithText("Room 45").assertIsDisplayed()
        val rosterReads = core.rosterReads.get()
        val avatars = core.avatarLoads.get()

        select("agents")
        select("needs-you")
        select("chats")

        compose.onNodeWithText("Room 45").assertIsDisplayed()
        assertEquals("the roster was read again", rosterReads, core.rosterReads.get())
        assertEquals("avatars were fetched again", avatars, core.avatarLoads.get())
    }

    @Test
    fun needsYouAndAgentsKeepTheirScrollPosition() {
        show(Core(manyAgents(60)))
        listOf("needs-you" to "needs-you-list", "agents" to "agents-list").forEach { (destination, list) ->
            select(destination)
            compose.onNodeWithTag(list).performScrollToNode(hasText("Room 45"))
            compose.waitForIdle()
            compose.onNodeWithText("Room 45").assertIsDisplayed()
            select("chats")
            select(destination)
            compose.onNodeWithText("Room 45").assertIsDisplayed()
        }
    }

    @Test
    fun theInboxIsNotCachedWhileAway() {
        val core = Core(fleet())
        show(core)
        select("needs-you")
        compose.onNodeWithText("Atlas").assertIsDisplayed()
        select("chats")
        compose.runOnUiThread { core.deliver(answered(core.rows, "!a:x")) }
        compose.waitForIdle()
        select("needs-you")
        assertEquals("an answered decision is still in the inbox", 0, compose.onAllNodesWithText("Atlas").fetchSemanticsNodes().size)
        compose.onNodeWithText("Sam").assertIsDisplayed()
    }
}
