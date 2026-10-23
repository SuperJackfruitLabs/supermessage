package dev.supermessage

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.supermessage.kit.CoreClient
import dev.supermessage.kit.Session
import dev.supermessage.kit.StubCore
import dev.supermessage.previews.PreviewFixtures
import java.io.File
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uniffi.supermessage_core.Acknowledgement
import uniffi.supermessage_core.AcknowledgementKind
import uniffi.supermessage_core.AccountDto
import uniffi.supermessage_core.Counterpart
import uniffi.supermessage_core.ItemView
import uniffi.supermessage_core.Membership
import uniffi.supermessage_core.PersonDto
import uniffi.supermessage_core.RichBlock
import uniffi.supermessage_core.RichInline
import uniffi.supermessage_core.RoomAffordance
import uniffi.supermessage_core.RoomCast
import uniffi.supermessage_core.RoomIdentity
import uniffi.supermessage_core.RoomRow
import uniffi.supermessage_core.RoomSummary
import uniffi.supermessage_core.RuntimeDto
import uniffi.supermessage_core.SpaceSummary
import uniffi.supermessage_core.TimelineRow
import uniffi.supermessage_core.TurnEnding
import uniffi.supermessage_core.TurnUpdate
import uniffi.supermessage_core.TypingUserDto
import uniffi.supermessage_ffi.EventSink
import uniffi.supermessage_ffi.FfiEvent
import uniffi.supermessage_ffi.RoomsSnapshot
import uniffi.supermessage_ffi.TimelineDiffEnvelope
import uniffi.supermessage_ffi.TimelineDiffOp
import uniffi.supermessage_ffi.TimelineSnapshot

/**
 * The agent experience end to end on the host side (A3, RF1, RF2, RF5): the
 * real [AppRoot] and [Session] over a fake core that serves rows, a cast,
 * typing and turn events, and answers `acknowledgement` from what it was asked.
 * The *rule* (Sent while the newest row is your own, nothing once an agent row
 * follows) is the core's, pinned in Rust by M1a Task 9; what these pin is that
 * the host asks again when the facts change and draws what comes back.
 */
class AgentExperienceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val atlasRoom = "!atlas:x"
    private val otherRoom = "!other:x"

    private fun agentRoom(id: String, name: String) = RoomRow(
        room = RoomSummary(
            id = id, name = name, avatarUrl = null, unread = 0uL, lastMessage = null,
            lastMessageIsOwn = false, lastMessageNamesSender = false, lastEventType = null,
            lastActivityMs = System.currentTimeMillis().toULong() - 60_000uL,
            runtime = RuntimeDto("claude-code", "Ashram"), membership = Membership.JOINED,
        ),
        identity = RoomIdentity(glyph = null, name = name, role = null, initial = name.take(1)),
        preview = null,
        affordance = RoomAffordance.COMPOSE,
    )

    private fun row(id: String, body: String, own: Boolean, sender: String = if (own) "@me:x" else "@atlas:x", edited: Boolean = false, name: String = "Atlas"): TimelineRow =
        PreviewFixtures.row(
            PreviewFixtures.item(id, sender = sender, body = body, isOwn = own, edited = edited),
            ItemView.Bubble(muted = false, blocks = listOf(RichBlock.Paragraph(listOf(RichInline.Text(body)))), voice = null),
            senderName = if (own) "You" else name, senderShort = name, senderInitial = name.take(1),
        )

    private fun nameOf(id: String) = id.removePrefix("@").substringBefore(":").replaceFirstChar { it.uppercase() }

    private class Ask(
        val roomId: String, val addressee: String?, val agentIds: List<String>,
        val typingAgents: List<String>, val turnInProgress: Boolean,
    )

    private inner class FakeCore(
        private val rooms: List<RoomRow>,
        val cast: (String) -> RoomCast,
    ) : StubCore() {
        val rows = mutableMapOf<String, MutableList<TimelineRow>>()
        val asks = java.util.Collections.synchronizedList(mutableListOf<Ask>())
        /** Holds an answer for this room until released, to be late. */
        var gateFor: String? = null
        val gate = CountDownLatch(1)
        private var sink: EventSink? = null
        private var seq = 0uL
        private var subscribed: String? = null

        override fun setHiddenEvents(eventIds: List<String>) {}
        override fun restoreSession(sink: EventSink): Boolean = true
        override fun roomsSnapshot() = RoomsSnapshot(seq = 0uL, rooms = rooms)
        override fun spacesList() = emptyList<SpaceSummary>()
        override fun account() = AccountDto(userId = "@me:x", homeserver = "https://x")
        override fun knownPeople() = emptyList<PersonDto>()
        override fun roomAvatar(roomId: String): String? = null
        override fun turn(roomId: String): uniffi.supermessage_core.TurnSnapshot? = null
        override fun roomCast(roomId: String) = cast(roomId)
        override fun markRoomRead(roomId: String) {}
        override fun timelinePaginateBack(roomId: String, count: UShort) = true
        override fun setTyping(roomId: String, typing: Boolean) {}
        override fun timelineResync() = TimelineSnapshot(roomId = subscribed.orEmpty(), seq = 0uL, items = emptyList())
        override fun logout() {}

        override fun timelineSubscribe(roomId: String, sink: EventSink) {
            this.sink = sink
            subscribed = roomId
            seq = 0uL
            push(roomId, TimelineDiffOp.Append(rows[roomId].orEmpty().toList()))
        }

        private fun push(roomId: String, op: TimelineDiffOp) {
            seq += 1uL
            sink!!.onEvent(FfiEvent.TimelineDiff(TimelineDiffEnvelope(channel = "timeline", subject = roomId, seq = seq, ops = listOf(op))))
        }

        fun append(roomId: String, row: TimelineRow) {
            rows.getOrPut(roomId) { mutableListOf() } += row
            push(roomId, TimelineDiffOp.Append(listOf(row)))
        }

        fun edit(roomId: String, index: Int, row: TimelineRow) {
            rows[roomId]!![index] = row
            push(roomId, TimelineDiffOp.Set(index.toUInt(), row))
        }

        fun event(e: FfiEvent) = sink!!.onEvent(e)

        override fun acknowledgement(
            roomId: String, addressee: String?, agentIds: List<String>,
            typingAgents: List<String>, turnInProgress: Boolean,
        ): Acknowledgement? {
            asks += Ask(roomId, addressee, agentIds, typingAgents, turnInProgress)
            if (roomId == gateFor) gate.await()
            val newestIsOwn = rows[roomId]?.lastOrNull()?.item?.isOwn == true
            return when {
                typingAgents.isNotEmpty() ->
                    Acknowledgement(AcknowledgementKind.ON_IT, typingAgents, typingAgents.joinToString(" and ") + " are on it…")
                newestIsOwn && addressee != null ->
                    Acknowledgement(AcknowledgementKind.SENT, listOf(nameOf(addressee)), "Sent to ${nameOf(addressee)}")
                else -> null
            }
        }
    }

    private fun cast(roomId: String, agents: List<String>, counterpart: String?, addressee: String?, people: List<String> = agents) = RoomCast(
        roomId = roomId,
        people = people.map { PersonDto(it, nameOf(it), nameOf(it).take(1), null, null) },
        counterpart = counterpart?.let { Counterpart(it, nameOf(it)) },
        agentIds = agents, addressee = addressee, mentionables = emptyList(),
    )

    private lateinit var core: FakeCore
    private lateinit var session: Session

    private fun open(
        roomsList: List<RoomRow> = listOf(agentRoom(atlasRoom, "Atlas")),
        castOf: (String) -> RoomCast = { cast(it, listOf("@atlas:x"), "@atlas:x", "@atlas:x") },
        initial: Map<String, List<TimelineRow>> = mapOf(atlasRoom to listOf(row("\$ls", "ls", own = true))),
        io: Boolean = false,
        openName: String? = "Atlas",
        fontScale: Float? = null,
    ) {
        core = FakeCore(roomsList, castOf).also { c -> initial.forEach { (k, v) -> c.rows[k] = v.toMutableList() } }
        session = Session(
            client = CoreClient(core = core, dispatcher = if (io) Dispatchers.IO else Dispatchers.Unconfined),
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        val file = File.createTempFile("agent-experience", ".preferences_pb")
        val prefs = RosterPreferences(PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.Unconfined)) { file })
        compose.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current
            CompositionLocalProvider(
                LocalReduceMotion provides true,
                androidx.compose.ui.platform.LocalDensity provides if (fontScale == null) density else androidx.compose.ui.unit.Density(density.density, fontScale),
            ) {
                Box(Modifier.requiredWidth(411.dp).fillMaxHeight().testTag("test-shell")) { AppRoot(session = session, prefs = prefs) }
            }
        }
        compose.waitForIdle()
        if (openName != null) {
            compose.onNodeWithText(openName).performScrollTo().performClick()
            compose.waitForIdle()
        }
    }

    private fun gone(tag: String) = compose.onAllNodesWithTagCount(tag) == 0
    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onAllNodesWithTagCount(tag: String) =
        onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().size

    private fun waitFor(what: String, cond: () -> Boolean) {
        try { compose.waitUntil(timeoutMillis = 10_000) { cond() } } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("timed out waiting for: $what", e)
        }
        compose.waitForIdle()
    }

    private fun textShown(text: String) = compose.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun headerSays(text: String) = compose.onAllNodes(hasText(text) and hasAnyAncestor(hasTestTag("room-header")), useUnmergedTree = true)
        .fetchSemanticsNodes().isNotEmpty()

    // -- RF1 ------------------------------------------------------------------

    @Test
    fun aProgressMessageTheAgentEditsEndsTheDock() {
        open()
        waitFor("Sent to Atlas") { textShown("Sent to Atlas") }
        compose.onNodeWithTag("dock-sent", useUnmergedTree = true).assertExists()

        // The bridge's progress message, then its edit: no deltas, so no card at any point.
        compose.runOnUiThread { core.append(atlasRoom, row("\$p", "🔎 Searching files…", own = false)) }
        waitFor("the dock to go at the agent's first message") { !textShown("Sent to Atlas") }
        assertTrue("no live card without deltas", gone("live-turn"))
        compose.runOnUiThread { core.edit(atlasRoom, 1, row("\$p", "🔎 Searched 12 files", own = false, edited = true)) }
        compose.waitForIdle()
        assertTrue(gone("dock"))
        assertTrue("no live card without deltas", gone("live-turn"))
        assertTrue("it re-asked for the appended row and the edit", core.asks.size >= 3)
    }

    @Test
    fun aTurnThatStartedEndsWhenTheAgentAnswers() {
        open()
        val started = PreviewFixtures.turn(totalSteps = 1, completedSteps = 0, hasThought = true).copy(roomId = atlasRoom)
        compose.runOnUiThread { core.event(FfiEvent.Turn(TurnUpdate(started, null))) }
        waitFor("the card") { !gone("live-turn") }
        waitFor("Working in the header") { headerSays("Working") }

        compose.runOnUiThread { core.append(atlasRoom, row("\$a", "Done: 12 files.", own = false)) }
        val ended = PreviewFixtures.turn(endedBy = TurnEnding.REPLY, recordLabel = "1 step", totalSteps = 1, completedSteps = 1)
            .copy(roomId = atlasRoom)
        compose.runOnUiThread { core.event(FfiEvent.Turn(TurnUpdate(ended, null))) }
        waitFor("the card to go") { gone("live-turn") }
        waitFor("Active in the header") { headerSays("Active") && !headerSays("Working") }
        compose.onNode(hasTestTag("what-i-did") and hasAnyAncestor(hasTestTag("row-\$a")), useUnmergedTree = true).assertExists()
        compose.onNodeWithText("1 step", useUnmergedTree = true).assertExists()
    }

    /** The dock and the pill float over the timeline: showing or hiding them never moves the newest message. */
    @Test
    fun theDockAndPillDoNotMoveTheNewestMessages() {
        open(castOf = { cast(it, listOf("@atlas:x"), "@atlas:x", "@atlas:x", people = listOf("@atlas:x", "@k:x")) })
        waitFor("Sent to Atlas") { textShown("Sent to Atlas") }
        val top = { compose.onNodeWithTag("row-\$ls").fetchSemanticsNode().boundsInRoot.top }
        val withDock = top()
        compose.runOnUiThread { core.event(FfiEvent.Typing(atlasRoom, listOf(TypingUserDto("@k:x", "Krishna", "Krishna")))) }
        waitFor("the pill") { textShown("Krishna is typing…") }
        assertEquals("the pill moved the newest message", withDock, top(), 0.5f)
        compose.runOnUiThread { core.append(atlasRoom, row("\$p", "On it", own = false)) }
        waitFor("the dock to go") { !textShown("Sent to Atlas") }
        compose.runOnUiThread { core.event(FfiEvent.Typing(atlasRoom, emptyList())) }
        waitFor("the pill to go") { !textShown("Krishna is typing…") }
        val bottom = compose.onNodeWithTag("row-\$p").fetchSemanticsNode().boundsInRoot.bottom
        val shell = compose.onNodeWithTag("timeline-list").fetchSemanticsNode().boundsInRoot.bottom
        assertTrue("the newest row ends at the timeline's bottom edge ($bottom vs $shell)", bottom <= shell + 1f)
    }

    /** Newest row's bottom above the top of the dock and the pill; Jump to newest too (the list reserves their height). */
    private fun assertNothingIsCovered() {
        val newest = compose.onNodeWithTag("row-\$ls").fetchSemanticsNode().boundsInRoot
        val dock = compose.onNodeWithTag("dock", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val pill = compose.onNodeWithTag("typing-pill", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val top = minOf(dock.top, pill.top)
        assertTrue("the newest row ends at ${newest.bottom}, under the dock/pill that start at $top", newest.bottom <= top + 0.5f)
    }

    private fun theDockAndPillCoverNothing(fontScale: Float?) {
        val older = (0 until 40).map { row("\$r$it", "older message $it", own = false) }
        open(
            castOf = { cast(it, listOf("@atlas:x"), "@atlas:x", "@atlas:x", people = listOf("@atlas:x", "@k:x")) },
            initial = mapOf(atlasRoom to older + row("\$ls", "ls", own = true)),
            fontScale = fontScale,
        )
        waitFor("Sent to Atlas") { textShown("Sent to Atlas") }
        compose.runOnUiThread { core.event(FfiEvent.Typing(atlasRoom, listOf(TypingUserDto("@k:x", "Krishna", "Krishna")))) }
        waitFor("the pill") { textShown("Krishna is typing…") }
        assertNothingIsCovered()

        // Scrolled back, the way home must not sit under the dock either.
        compose.onNodeWithTag("timeline-list").performScrollToIndex(30)
        waitFor("Jump to newest") { !gone("jump-to-newest") }
        val jump = compose.onNodeWithTag("jump-to-newest", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val dock = compose.onNodeWithTag("dock", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val pill = compose.onNodeWithTag("typing-pill", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("Jump to newest ends at ${jump.bottom}, under the dock/pill from ${minOf(dock.top, pill.top)}", jump.bottom <= minOf(dock.top, pill.top) + 0.5f)
    }

    @Test
    fun theDockAndPillCoverNeitherTheNewestMessageNorJumpToNewest() = theDockAndPillCoverNothing(null)

    @Test
    fun theDockAndPillCoverNothingAtFontScaleTwo() = theDockAndPillCoverNothing(2f)

    // -- RF2 ------------------------------------------------------------------

    @Test
    fun aRoomWithSeveralAgentsNamesThemAll() {
        open(
            roomsList = listOf(agentRoom(atlasRoom, "Ops room")),
            castOf = { cast(it, agents = listOf("@atlas:x", "@quill:x"), counterpart = null, addressee = null, people = listOf("@atlas:x", "@quill:x", "@k:x")) },
            openName = "Ops room",
        )
        compose.runOnUiThread {
            core.event(
                FfiEvent.Typing(
                    atlasRoom,
                    listOf(
                        TypingUserDto("@atlas:x", "atlas-bot", "atlas-bot"),
                        TypingUserDto("@quill:x", "Quill", "Quill"),
                        TypingUserDto("@k:x", "Krishna", "Krishna"),
                    ),
                ),
            )
        }
        waitFor("the dock names the working agents") { textShown("atlas-bot and Quill are on it…") }
        waitFor("the pill names the person") { textShown("Krishna is typing…") }

        val last = core.asks.last { it.typingAgents.isNotEmpty() }
        assertNull("nobody is the single addressee", last.addressee)
        assertEquals(listOf("atlas-bot", "Quill"), last.typingAgents)
        assertEquals(listOf("@atlas:x", "@quill:x"), last.agentIds)
        compose.onNodeWithTag("dock-working", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("typing-pill", useUnmergedTree = true).assertExists()
        assertTrue("no Sent to", !textShown("Sent to Atlas"))
        assertTrue(headerSays("Ops room"))
        assertTrue("the header still carries a status", headerSays("Active"))

        // P2: the card names the agent the turn carries, not the room's first.
        val quill = PreviewFixtures.turn(hasThought = true).copy(roomId = atlasRoom, agentId = "@quill:x")
        compose.runOnUiThread { core.event(FfiEvent.Turn(TurnUpdate(quill, null))) }
        waitFor("the card") { !gone("live-turn") }
        compose.onNode(hasText("Quill") and hasAnyAncestor(hasTestTag("live-turn")), useUnmergedTree = true).assertExists()
    }

    // -- stale answers (RF5) --------------------------------------------------

    @Test
    fun switchingRoomsDropsTheDock() {
        open(
            roomsList = listOf(agentRoom(atlasRoom, "Atlas"), agentRoom(otherRoom, "Quill")),
            castOf = { id ->
                if (id == atlasRoom) cast(id, listOf("@atlas:x"), "@atlas:x", "@atlas:x")
                else cast(id, listOf("@quill:x"), "@quill:x", null)
            },
            initial = mapOf(atlasRoom to listOf(row("\$ls", "ls", own = true)), otherRoom to listOf(row("\$q", "hello", own = true))),
            io = true,
            openName = null,
        )
        core.gateFor = atlasRoom
        compose.onNodeWithText("Atlas").performScrollTo().performClick()
        compose.waitForIdle()
        waitFor("the ask for the first room") { core.asks.any { it.roomId == atlasRoom } }

        // Back to the roster and into the other room while the first answer is still out.
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithText("Quill").performScrollTo().performClick()
        compose.waitForIdle()
        waitFor("the other room") { core.asks.any { it.roomId == otherRoom } }

        core.gate.countDown()
        Thread.sleep(500)
        compose.waitForIdle()
        assertTrue("a late answer for the first room is shown in the second", gone("dock") && !textShown("Sent to Atlas"))
    }

    @Test
    fun signingOutLeavesNoDock() {
        open()
        waitFor("Sent to Atlas") { textShown("Sent to Atlas") }
        compose.runOnUiThread { kotlinx.coroutines.runBlocking { session.signOut() } }
        compose.waitForIdle()
        assertTrue(gone("dock"))
        assertNull(session.dock.held.value)
    }
}
