import Testing

@testable import SupermessageKit
import SupermessageFFI

/// The counter that lets the list tell "new token" from "new message".
///
/// The timeline view's `updateUIView` runs on every SwiftUI update, and while
/// an agent is writing that is many times a second — the live turn is
/// observable state and any read of it re-runs the update. Without a way to
/// answer "did the history actually change" in constant time, every one of
/// those updates re-ran the grouping pass over the whole room and rebuilt the
/// hosting configuration of every visible cell. That was the jitter.
@MainActor
struct TimelineRevisionTests {
    func store() -> TimelineStore {
        TimelineStore(
            client: CoreClient(dataDirectory: CoreClient.dataDirectory()), sink: EventPump())
    }

    @Test("a fresh store has a revision to compare against")
    func startsAtZero() {
        #expect(store().revision == 0)
    }

    @Test("replacing the history moves the revision")
    func changesOnWrite() {
        // `clear()` replaces `items`, which is a change like any other: a
        // list that skipped the rebuild here would keep drawing the previous
        // room's messages.
        let timeline = store()
        let before = timeline.revision
        timeline.clear()
        #expect(timeline.revision != before, "a write left the revision behind")
    }

    @Test("the revision only ever moves forward")
    func neverGoesBackwards() {
        // The comparison is `!=` at the call site, but a counter that wrapped
        // or reset would eventually collide with a value already applied, and
        // the list would skip a rebuild it needed. Monotonic is what makes
        // the comparison safe.
        let timeline = store()
        var seen: [UInt64] = [timeline.revision]
        for _ in 0..<3 {
            timeline.clear()
            seen.append(timeline.revision)
        }
        #expect(seen == seen.sorted(), "the revision went backwards")
        #expect(Set(seen).count == seen.count, "the revision repeated a value")
    }
}
