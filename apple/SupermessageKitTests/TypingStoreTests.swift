import Testing

@testable import SupermessageKit
import SupermessageFFI

/// When the typing line should be on screen, and — the part that was missing —
/// when it should stop being.
@MainActor
struct TypingStoreTests {
    func store(room: String = "!r:x") -> TypingStore {
        let typing = TypingStore()
        typing.focus(room)
        return typing
    }

    /// The core hands over a record per typist, not a name.
    static func user(_ id: String, _ label: String) -> TypingUserDto {
        TypingUserDto(userId: id, displayName: label, label: label)
    }

    @Test("a message clears the line its sender's notice put up")
    func aMessageStopsTheTyping() {
        // Matrix typing notices expire on a server-side timeout, and a sender
        // that does not explicitly retract one leaves the line up for as long
        // as that timeout runs. But the client already has better evidence
        // than the timeout: the message itself. Someone who has spoken is no
        // longer about to speak.
        let typing = store()
        typing.handle(roomId: "!r:x", users: [Self.user("@g:x", "Ganesha")])
        #expect(typing.line != nil)

        typing.messagesArrived(from: ["@g:x"])
        #expect(typing.line == nil, "the typing line outlived the message it predicted")
    }

    @Test("clearing matches on identity, not on what the two sides call someone")
    func matchesOnIdentityNotName() {
        // **The bug, stated.** The store held whatever the profile said and
        // was handed the timeline's composed attribution — `Super Chotu` on
        // one side, `Super Chotu (Hermes on Guild)` on the other — so nothing
        // ever matched and the line sat there until the server timed it out.
        // Two strings describing the same person are not the same string.
        let typing = store()
        typing.handle(
            roomId: "!r:x",
            users: [Self.user("@super-chotu:x", "Super Chotu")])

        typing.messagesArrived(from: ["Super Chotu (Hermes on Guild)"])
        #expect(typing.line != nil, "a name was accepted where an id belongs")

        typing.messagesArrived(from: ["@super-chotu:x"])
        #expect(typing.line == nil, "the id did not clear the line")
    }

    @Test("other people carry on typing")
    func onlyTheSenderStops() {
        let typing = store()
        typing.handle(
            roomId: "!r:x",
            users: [Self.user("@g:x", "Ganesha"), Self.user("@k:x", "Krishna")])
        typing.messagesArrived(from: ["@g:x"])
        #expect(typing.line == "Krishna is typing…")
    }

    @Test("a later notice can start the line again")
    func typingCanResume() {
        // Clearing on a message must not latch: an agent that sends one
        // message and starts writing the next is typing again, and the line
        // has to be able to come back.
        let typing = store()
        typing.handle(roomId: "!r:x", users: [Self.user("@g:x", "Ganesha")])
        typing.messagesArrived(from: ["@g:x"])
        typing.handle(roomId: "!r:x", users: [Self.user("@g:x", "Ganesha")])
        #expect(typing.line == "Ganesha is typing…")
    }

    @Test("a message from a room nobody is typing in changes nothing")
    func quietRoomStaysQuiet() {
        let typing = store()
        typing.messagesArrived(from: ["@g:x"])
        #expect(typing.line == nil)
    }

    @Test("a notice for another room is not this room's business")
    func otherRoomsAreIgnored() {
        let typing = store()
        typing.handle(roomId: "!other:x", users: [Self.user("@g:x", "Ganesha")])
        #expect(typing.line == nil, "another room's typing showed up here")
    }
}
