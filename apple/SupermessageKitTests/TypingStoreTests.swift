import Testing

@testable import SupermessageKit

/// When the typing line should be on screen, and — the part that was missing —
/// when it should stop being.
@MainActor
struct TypingStoreTests {
    func store(room: String = "!r:x") -> TypingStore {
        let typing = TypingStore()
        typing.focus(room)
        return typing
    }

    @Test("a message from someone is proof they stopped typing")
    func aMessageEndsTyping() {
        // Reported from a phone: "ganesha is typing…" stayed on screen long
        // after ganesha's message had arrived and been read.
        //
        // Matrix typing notices expire on a server-side timeout, and a sender
        // that does not explicitly retract one leaves the line up for as long
        // as that timeout runs. But the client already has better evidence
        // than the timeout: the message itself. Someone who has spoken is no
        // longer about to speak.
        let typing = store()
        typing.handle(roomId: "!r:x", users: ["ganesha"])
        #expect(typing.line != nil)

        typing.messagesArrived(from: ["ganesha"])
        #expect(typing.line == nil, "the typing line outlived the message it predicted")
    }

    @Test("other people carry on typing")
    func onlyTheSenderStops() {
        let typing = store()
        typing.handle(roomId: "!r:x", users: ["ganesha", "krishna"])
        typing.messagesArrived(from: ["ganesha"])
        #expect(typing.line == "krishna is typing…")
    }

    @Test("a later notice can start the line again")
    func typingCanResume() {
        // Clearing on a message must not latch: an agent that sends one
        // message and starts writing the next is typing again, and the line
        // has to be able to come back.
        let typing = store()
        typing.handle(roomId: "!r:x", users: ["ganesha"])
        typing.messagesArrived(from: ["ganesha"])
        typing.handle(roomId: "!r:x", users: ["ganesha"])
        #expect(typing.line == "ganesha is typing…")
    }

    @Test("a message from a room nobody is typing in changes nothing")
    func quietRoomStaysQuiet() {
        let typing = store()
        typing.messagesArrived(from: ["ganesha"])
        #expect(typing.line == nil)
    }
}
