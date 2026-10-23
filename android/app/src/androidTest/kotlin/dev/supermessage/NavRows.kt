package dev.supermessage

import java.time.Instant
import uniffi.supermessage_core.Membership
import uniffi.supermessage_core.RoomAffordance
import uniffi.supermessage_core.RoomIdentity
import uniffi.supermessage_core.RoomPreview
import uniffi.supermessage_core.RoomRow
import uniffi.supermessage_core.RoomSummary
import uniffi.supermessage_core.RuntimeDto

/**
 * Rooms for the Needs you and Agents tests. They are inputs to the core
 * (`rosterNeedsYouInbox`, `rosterAgentDirectory`), never its answers: a test
 * that wrote the inbox by hand would be testing its own copy of the rule.
 */
object NavRows {
    val NOW: Instant = Instant.ofEpochMilli(1_757_700_120_000L)

    fun row(
        id: String,
        name: String,
        role: String? = null,
        pending: Boolean = false,
        invited: Boolean = false,
        agent: Boolean = false,
        minutesAgo: Double? = 3.0,
        preview: String? = "hello",
        unread: Int = 0,
    ): RoomRow = RoomRow(
        room = RoomSummary(
            id = id, name = name, avatarUrl = null, unread = unread.toULong(), lastMessage = preview,
            lastMessageIsOwn = false, lastMessageNamesSender = false, lastEventType = null,
            lastActivityMs = minutesAgo?.let { (NOW.toEpochMilli() - (it * 60_000).toLong()).toULong() },
            runtime = if (agent) RuntimeDto("claude-code", "foundry") else null,
            membership = if (invited) Membership.INVITED else Membership.JOINED,
        ),
        identity = RoomIdentity(glyph = null, name = name, role = role, initial = name.take(1)),
        preview = if (invited || preview == null) null else RoomPreview(preview, pending),
        affordance = if (invited) RoomAffordance.RESPOND_TO_INVITATION else RoomAffordance.COMPOSE,
    )

    /** Two decisions, one invitation, one quiet room: the inbox the brief names. */
    fun fleet(): List<RoomRow> = listOf(
        row("!a:x", "Atlas", role = "Platform", pending = true, agent = true, minutesAgo = 3.0),
        row("!b:x", "Sam", role = "Strategy", pending = true, agent = true, minutesAgo = 40.0),
        row("!i:x", "Estate Planning", invited = true, minutesAgo = null),
        row("!q:x", "Design Review", minutesAgo = 90.0),
    )
}
