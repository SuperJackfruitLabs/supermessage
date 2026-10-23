package dev.supermessage.kit

/** Someone the reader can block: who, what to call them, and whether it is an agent. */
data class Blockable(val userId: String, val name: String, val isAgent: Boolean)

/**
 * What a report is about. Ported from `SafetyStore.swift:11-37`.
 *
 * [blockable] is the sender for a message, nobody for a room, and the person
 * for a user.
 */
sealed class ReportSubject {
    abstract val id: String
    abstract val blockable: Blockable?

    data class Message(
        val roomId: String,
        val eventId: String,
        val senderId: String,
        val senderName: String,
        val isAgent: Boolean,
    ) : ReportSubject() {
        override val id: String get() = "message:$roomId:$eventId"
        override val blockable: Blockable get() = Blockable(senderId, senderName, isAgent)
    }

    data class Room(val roomId: String, val name: String) : ReportSubject() {
        override val id: String get() = "room:$roomId"
        override val blockable: Blockable? get() = null
    }

    data class User(val userId: String, val name: String, val isAgent: Boolean) : ReportSubject() {
        override val id: String get() = "user:$userId"
        override val blockable: Blockable get() = Blockable(userId, name, isAgent)
    }
}
