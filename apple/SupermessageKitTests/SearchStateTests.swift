import Testing

@testable import SupermessageKit
import SupermessageFFI

/// A search that looks broken while working is the worst way to be wrong.
struct SearchStateTests {
    static func hit(_ id: String) -> SearchResultDto {
        SearchResultDto(
            eventId: id, roomId: "!r:x", sender: "@a:x", body: "hello", timestampMs: 1)
    }

    @Test("typing leaves the untouched empty state behind")
    func typingLeavesIdle() {
        // The bug. `searched` only became true after a query *ran*, so typing
        // left "Find a message across your rooms" on screen and a reader could
        // not tell thinking from ignoring.
        #expect(SearchState.idle.typed("hello") == .ready("hello"))
    }

    @Test("clearing the field goes back to the invitation")
    func clearingReturnsToIdle() {
        #expect(SearchState.ready("hello").typed("") == .idle)
        #expect(SearchState.ready("hello").typed("   ") == .idle)
    }

    @Test("editing a query does not throw away what you were reading")
    func resultsSurviveEditing() {
        // A list that empties on the first keystroke of a correction is a list
        // that discards the thing you were looking at.
        let found = SearchState.found([Self.hit("1")])
        #expect(found.typed("hell") == found)
    }

    @Test("a search with nothing in it still names what was searched for")
    func emptyNamesTheQuery() {
        // "No results" alone leaves a reader wondering which query it means,
        // which matters when the field still holds a half-typed correction.
        #expect(SearchState.empty("hello").query == "hello")
    }

    @Test("running is a state of its own")
    func searchingExists() {
        // It did not exist, and its absence is what made a working search look
        // like a broken one.
        #expect(SearchState.searching("hello").query == "hello")
        #expect(SearchState.searching("hello") != SearchState.ready("hello"))
    }

    @Test("a search that fails is not a search that found nothing")
    func failedIsNotEmpty() {
        // The defect this task fixes. `Failed` has to be distinguishable from
        // `Empty`, not just a message bolted onto it, or a reader is right
        // back to not being able to tell a refusal from zero hits.
        #expect(
            SearchState.empty("hello")
                != SearchState.failed(query: "hello", message: "Can't reach the homeserver."))
    }

    @Test("a failure still names what was searched for")
    func failedNamesTheQueryAndCarriesTheMessage() {
        let failed = SearchState.failed(query: "hello", message: "Can't reach the homeserver.")
        #expect(failed.query == "hello")
        if case let .failed(_, message) = failed {
            #expect(message == "Can't reach the homeserver.")
        } else {
            Issue.record("expected .failed")
        }
    }

    @Test("editing after a failure still leaves the field to correct")
    func typingAfterAFailureMovesOnToReady() {
        // A failure is not `.found`, so it does not stick the way a
        // non-empty result list does.
        let failed = SearchState.failed(query: "hello", message: "Can't reach the homeserver.")
        #expect(failed.typed("hell") == .ready("hell"))
    }
}
