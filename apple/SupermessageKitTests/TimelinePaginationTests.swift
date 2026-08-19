import Testing

@testable import SupermessageKit
import SupermessageFFI

/// What `paginate_backwards` actually returns, and what the store does with it.
///
/// `matrix_sdk_ui::Timeline::paginate_backwards` documents its `bool` as
/// "**whether we hit the start of the timeline**" — true means there is
/// nothing older left. The store read it as "there is more", which is the
/// opposite, so the first successful page (which does not reach the start of
/// a long room) switched pagination off permanently and no history older than
/// the initial screen could ever load.
@MainActor
struct TimelinePaginationTests {
    func store() -> TimelineStore {
        let client = CoreClient(dataDirectory: CoreClient.dataDirectory())
        return TimelineStore(client: client, sink: EventPump())
    }

    @Test("a page that did not reach the start leaves more to fetch")
    func keepsGoingMidHistory() {
        let timeline = store()
        // The ordinary case in any room with real history: twenty older
        // messages arrived and there are more behind them.
        timeline.applyPaginationResult(reachedStart: false)
        #expect(timeline.canPaginate)
    }

    @Test("reaching the start of the room stops further requests")
    func stopsAtTheStart() {
        let timeline = store()
        timeline.applyPaginationResult(reachedStart: true)
        #expect(!timeline.canPaginate)
    }

    @Test("a fresh room starts out willing to fetch history")
    func startsWilling() {
        #expect(store().canPaginate)
    }
}
