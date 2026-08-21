package dev.supermessage.kit.stores

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import uniffi.supermessage_core.TypingUserDto

/**
 * Ported from `apple/SupermessageKitTests/TypingStoreTests.swift`.
 *
 * When the typing line should be on screen, and — the part that was
 * missing — when it should stop being.
 */
class TypingStoreTest {

    private fun store(room: String = "!r:x"): TypingStore {
        val typing = TypingStore()
        typing.focus(room)
        return typing
    }

    /** The core hands over a record per typist, not a name. */
    private fun user(id: String, label: String) =
        TypingUserDto(userId = id, displayName = label, label = label)

    /** "a message clears the line its sender's notice put up" */
    @Test
    fun aMessageStopsTheTyping() {
        // Matrix typing notices expire on a server-side timeout, and a
        // sender that does not explicitly retract one leaves the line up
        // for as long as that timeout runs. But the client already has
        // better evidence than the timeout: the message itself. Someone who
        // has spoken is no longer about to speak.
        val typing = store()
        typing.handle(roomId = "!r:x", users = listOf(user("@g:x", "Ganesha")))
        assertNotNull(typing.line)

        typing.messagesArrived(listOf("@g:x"))
        assertNull("the typing line outlived the message it predicted", typing.line)
    }

    /** "clearing matches on identity, not on what the two sides call someone" */
    @Test
    fun matchesOnIdentityNotName() {
        // **The bug, stated.** The store held whatever the profile said and
        // was handed the timeline's composed attribution — `Super Chotu` on
        // one side, `Super Chotu (Hermes on Guild)` on the other — so
        // nothing ever matched and the line sat there until the server
        // timed it out. Two strings describing the same person are not the
        // same string.
        val typing = store()
        typing.handle(roomId = "!r:x", users = listOf(user("@super-chotu:x", "Super Chotu")))

        typing.messagesArrived(listOf("Super Chotu (Hermes on Guild)"))
        assertNotNull("a name was accepted where an id belongs", typing.line)

        typing.messagesArrived(listOf("@super-chotu:x"))
        assertNull("the id did not clear the line", typing.line)
    }

    /** "other people carry on typing" */
    @Test
    fun onlyTheSenderStops() {
        val typing = store()
        typing.handle(
            roomId = "!r:x",
            users = listOf(user("@g:x", "Ganesha"), user("@k:x", "Krishna")),
        )
        typing.messagesArrived(listOf("@g:x"))
        assertEquals("Krishna is typing…", typing.line)
    }

    /** "a later notice can start the line again" */
    @Test
    fun typingCanResume() {
        // Clearing on a message must not latch: an agent that sends one
        // message and starts writing the next is typing again, and the
        // line has to be able to come back.
        val typing = store()
        typing.handle(roomId = "!r:x", users = listOf(user("@g:x", "Ganesha")))
        typing.messagesArrived(listOf("@g:x"))
        typing.handle(roomId = "!r:x", users = listOf(user("@g:x", "Ganesha")))
        assertEquals("Ganesha is typing…", typing.line)
    }

    /** "a message from a room nobody is typing in changes nothing" */
    @Test
    fun quietRoomStaysQuiet() {
        val typing = store()
        typing.messagesArrived(listOf("@g:x"))
        assertNull(typing.line)
    }

    /** "a notice for another room is not this room's business" */
    @Test
    fun otherRoomsAreIgnored() {
        val typing = store()
        typing.handle(roomId = "!other:x", users = listOf(user("@g:x", "Ganesha")))
        assertNull("another room's typing showed up here", typing.line)
    }
}
