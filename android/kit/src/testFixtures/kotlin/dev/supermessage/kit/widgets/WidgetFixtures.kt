package dev.supermessage.kit.widgets

import uniffi.supermessage_core.ActivityKind
import uniffi.supermessage_core.GateAnswers
import uniffi.supermessage_core.Membership
import uniffi.supermessage_core.NotificationActivity
import uniffi.supermessage_core.NotificationCategory
import uniffi.supermessage_core.NotificationDto
import uniffi.supermessage_core.PermissionAnswers
import uniffi.supermessage_core.RoomAffordance
import uniffi.supermessage_core.RoomIdentity
import uniffi.supermessage_core.RoomPreview
import uniffi.supermessage_core.RoomRow
import uniffi.supermessage_core.RoomSummary
import uniffi.supermessage_core.TurnCounts
import uniffi.supermessage_core.NotificationSuppression

/**
 * Decisions as pushes the core would decide, and a snapshot store in memory:
 * what the widget tests feed the **real** core's `widget_*` merges
 * (`SupermessageKitTests/WidgetFeedTests.swift:28-64`).
 */
object WidgetFixtures {
    const val NOW = 1_800_000_000_000L
    const val MINUTE = 60_000L

    /** A [SnapshotStore] in memory. */
    class Memory(var json: String? = null) : SnapshotStore {
        override fun read(): String? = json
        override fun write(json: String) {
            this.json = json
        }
    }

    private fun activity(kind: ActivityKind, at: Long, gateId: String? = null, name: String = "Hermes", line: String? = null) =
        NotificationActivity(
            kind = kind, roomName = name, roomIsAgent = true, sender = "@agent_hermes:hs", atMs = at.toULong(),
            line = line, gateId = gateId, references = null, optionId = null,
        )

    fun permission(
        event: String = "\$p",
        room: String = "!r:hs",
        agent: String = "Hermes",
        question: String = "Run git push?",
        at: Long = NOW - MINUTE,
    ) = NotificationDto(
        roomId = room, eventId = event, title = agent, subtitle = "Permission", body = question,
        category = NotificationCategory.PERMISSION, permission = PermissionAnswers("Allow once", "Reject"),
        gate = null, threadId = room, suppress = null, fallbackTitle = null, fallbackBody = null,
        activity = activity(ActivityKind.DECISION, at, name = agent),
    )

    fun gate(
        event: String = "\$g",
        room: String = "!b:hs",
        agent: String = "Launch board",
        prompt: String = "Ship v2",
        at: Long = NOW - MINUTE,
    ) = NotificationDto(
        roomId = room, eventId = event, title = agent, subtitle = "Gate", body = "Approve \"$prompt\"?",
        category = NotificationCategory.GATE, permission = null,
        gate = GateAnswers("gate-9", prompt, listOf("approve", "request_changes", "reject")),
        threadId = room, suppress = null, fallbackTitle = null, fallbackBody = null,
        activity = activity(ActivityKind.DECISION, at, gateId = "gate-9", name = agent),
    )

    /** A decision the widget cannot answer: it must be read in the app (no inline option). */
    fun open(
        event: String = "\$d",
        room: String = "!o:hs",
        agent: String = "Atlas",
        question: String = "Which database should we use?",
        at: Long = NOW - MINUTE,
    ) = NotificationDto(
        roomId = room, eventId = event, title = agent, subtitle = null, body = question,
        category = NotificationCategory.DECISION, permission = null, gate = null, threadId = room,
        suppress = null, fallbackTitle = null, fallbackBody = null,
        activity = activity(ActivityKind.DECISION, at, name = agent),
    )

    /**
     * An agent's line, as a push; with [steps] and [failed] it is the answer that ends a turn
     * (the hub adds the turn's counts to that push), which the recap calls finished or failed.
     */
    fun said(
        room: String,
        event: String,
        agent: String,
        line: String,
        at: Long = NOW - MINUTE,
        steps: Int? = null,
        failed: Int = 0,
    ) = NotificationDto(
        roomId = room, eventId = event, title = agent, subtitle = null, body = line,
        category = NotificationCategory.MESSAGE, permission = null, gate = null, threadId = room,
        suppress = null, fallbackTitle = null, fallbackBody = null,
        activity = activity(ActivityKind.MESSAGE, at, name = agent, line = line),
        turn = steps?.let { TurnCounts(it.toUInt(), failed.toUInt()) },
    )

    /** The board's receipt for [gate]: what takes a gate off the widget. */
    fun outcome(room: String = "!b:hs") = NotificationDto(
        roomId = room, eventId = "\$o", title = "", subtitle = null, body = "New message",
        category = NotificationCategory.MESSAGE, permission = null, gate = null, threadId = room,
        suppress = NotificationSuppression.NOT_NEWS, fallbackTitle = "Launch board", fallbackBody = "posted an update",
        activity = activity(ActivityKind.GATE_OUTCOME, NOW, gateId = "gate-9"),
    )

    fun row(id: String, name: String, at: Long, says: String) = RoomRow(
        room = RoomSummary(
            id = id, name = name, avatarUrl = null, unread = 0uL, lastMessage = says,
            lastMessageIsOwn = false, lastMessageNamesSender = false, lastEventType = null,
            lastActivityMs = at.toULong(), runtime = null, membership = Membership.JOINED,
        ),
        identity = RoomIdentity(glyph = null, name = name, role = null, initial = name.take(1)),
        preview = RoomPreview(text = says, pending = false),
        affordance = RoomAffordance.COMPOSE,
    )

    /** A roster row the core counts as an agent's room (a glyph and a role in its name). */
    fun agentRow(id: String, name: String, at: Long, says: String) = row(id, name, at, says).let {
        it.copy(identity = RoomIdentity(glyph = "✳", name = name, role = "Platform", initial = name.take(1)))
    }

    /** A feed over [store] whose clock is [now]. */
    fun feed(store: SnapshotStore = Memory(), now: () -> Long = { NOW }) = WidgetFeed(store, now)
}
