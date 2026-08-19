import Testing

@testable import SupermessageKit
import SupermessageFFI

@MainActor
struct ConnectionStoreTests {
    @Test("the core's vocabulary maps to a state, including the error one")
    func mapsEveryState() {
        // "error" was missing and fell through to `.unknown`, which put the
        // bare word "error" on screen with no explanation beside it.
        let store = ConnectionStore()
        for (raw, expected) in [
            ("live", ConnectionStore.Connection.live),
            ("connecting", .connecting),
            ("offline", .offline),
            ("error", .error),
        ] {
            store.apply(ConnectionState(state: raw, message: nil))
            #expect(store.state == expected, "for \(raw)")
        }
    }

    @Test("a word this build has not been taught is carried, not crashed on")
    func unknownIsCarried() {
        // The vocabulary is the core's, so it can gain a value without this
        // app failing to build. One branch is the price.
        let store = ConnectionStore()
        store.apply(ConnectionState(state: "hibernating", message: nil))
        #expect(store.state == .unknown("hibernating"))
    }

    @Test("live is the quiet case and shows no bar")
    func liveIsQuiet() {
        let store = ConnectionStore()
        store.apply(ConnectionState(state: "live", message: nil))
        #expect(!store.isWorthShowing)
        store.apply(ConnectionState(state: "error", message: "error sending request for url"))
        #expect(store.isWorthShowing)
    }

    @Test("recovering clears the message as well as the state")
    func recoveryClearsTheMessage() {
        // The bug the reader hit: an error that never went away. Half of that
        // was the core never retrying; this is the other half — the store must
        // not keep the old message when a healthy state finally arrives.
        let store = ConnectionStore()
        store.apply(ConnectionState(state: "error", message: "error sending request for url"))
        #expect(store.message != nil)

        store.apply(ConnectionState(state: "live", message: nil))
        #expect(store.message == nil, "a stale error message outlived the recovery")
        #expect(!store.isWorthShowing)
    }
}
