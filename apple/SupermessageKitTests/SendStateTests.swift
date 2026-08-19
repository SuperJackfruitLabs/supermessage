import Testing

@testable import SupermessageKit

/// The one place a chat app must not be ambiguous.
struct SendStateTests {
    @Test("the core's vocabulary reads across")
    func readsTheWire() {
        #expect(SendState("notSentYet") == .sending)
        #expect(SendState("sendingFailed") == .failed)
        #expect(SendState("sent") == .sent)
    }

    @Test("a message with no send state has arrived")
    func nilIsSent() {
        // Every message a peer sent carries `nil` here — it is on the server by
        // definition. Reading that as "unknown" would put a marker under every
        // incoming message in the room.
        #expect(SendState(nil) == .sent)
    }

    @Test("a state this build has not been taught is not guessed at")
    func unknownStaysUnknown() {
        #expect(SendState("somethingNew") == .unknown)
        #expect(!SendState("somethingNew").isWorthShowing)
    }

    @Test("a failed message always says so")
    func failureShows() {
        // The whole point. A message sitting on this phone looks exactly like
        // one that landed unless something says otherwise.
        #expect(SendState.failed.isWorthShowing)
        #expect(SendState.failed.label == "Not sent")
    }

    @Test("an ordinary sent message says nothing")
    func successIsQuiet() {
        // A tick under every bubble is chrome on the unremarkable case.
        #expect(!SendState.sent.isWorthShowing)
        #expect(SendState.sent.label == nil)
    }
}
