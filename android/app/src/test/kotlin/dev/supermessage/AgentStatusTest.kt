package dev.supermessage

import dev.supermessage.kit.RosterArrangement
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test
import uniffi.supermessage_core.AgentState
import uniffi.supermessage_core.Membership
import uniffi.supermessage_core.RoomAffordance
import uniffi.supermessage_core.RoomIdentity
import uniffi.supermessage_core.RoomRow
import uniffi.supermessage_core.RoomStatus
import uniffi.supermessage_core.RoomSummary
import uniffi.supermessage_core.RosterRow
import uniffi.supermessage_core.RuntimeDto

/** `agentStatus` hands the core one live fact and nothing else. */
class AgentStatusTest {
    companion object {
        @BeforeClass
        @JvmStatic
        fun ensureHostCoreIsBuilt() = HostCore.ensureBuilt()

        private val now: Instant = Instant.ofEpochMilli(1_700_000_000_000L)

        private fun agent(id: String): RosterRow {
            val row = RoomRow(
                room = RoomSummary(
                    id = id, name = id, avatarUrl = null, unread = 0uL, lastMessage = "hi",
                    lastMessageIsOwn = false, lastMessageNamesSender = false, lastEventType = null,
                    lastActivityMs = (now.toEpochMilli() - 60_000).toULong(),
                    runtime = RuntimeDto("claude-code", "foundry"), membership = Membership.JOINED,
                ),
                identity = RoomIdentity(glyph = null, name = id, role = null, initial = "A"),
                preview = null,
                affordance = RoomAffordance.COMPOSE,
            )
            return RosterArrangement.agentDirectory(listOf(row), now).single()
        }
    }

    @Test
    fun onlyTheOpenRoomCanSayWorking() {
        val a = agent("!a:x")
        val b = agent("!b:x")
        assertEquals(AgentState.ACTIVE, a.state)
        assertEquals(RoomStatus.WORKING, agentStatus(a, openRoomId = "!a:x", openRoomInProgress = true))
        assertEquals(RoomStatus.ACTIVE, agentStatus(b, openRoomId = "!a:x", openRoomInProgress = true))
        assertEquals(RoomStatus.ACTIVE, agentStatus(a, openRoomId = "!a:x", openRoomInProgress = false))
    }
}
