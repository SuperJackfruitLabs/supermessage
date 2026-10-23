import Foundation
import SupermessageFFI
import Testing

@testable import SupermessageKit

/// Block and report's local half (issue #60): the block list as the core sends
/// it, and the messages this device has reported and hidden.
@MainActor
struct SafetyStoreTests {
    @Test("a reported message tells the core, with the whole set")
    func tellsTheCoreWhatWasHidden() {
        let safety = SafetyStore()
        var pushed: [Set<String>] = []
        safety.hiddenEventsDidChange = { pushed.append($0) }
        safety.hide(eventId: "$a")
        safety.hide(eventId: "$b")
        #expect(pushed == [["$a"], ["$a", "$b"]])
    }

    @Test("the core's block list replaces what was here, including optimistic notes")
    func theCoresListWins() {
        let safety = SafetyStore()
        safety.noteBlocked("@a:x")
        safety.apply(ignored: ["@b:x"])
        #expect(safety.blocked == ["@b:x"], "an optimistic block outlived the core's answer")
        safety.noteUnblocked("@b:x")
        #expect(!safety.isBlocked("@b:x"))
    }

    @Test("hidden messages survive a relaunch, and sign-out forgets them")
    func persistsUntilSignOut() throws {
        let suite = "safety-store-tests-\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }

        let first = SafetyStore(defaults: defaults)
        first.hide(eventId: "$b")
        first.apply(ignored: ["@troll:x.org"])

        let relaunched = SafetyStore(defaults: defaults)
        #expect(relaunched.hiddenEvents == ["$b"], "a reported message came back after a relaunch")
        // The block list is the core's to send again; it is not persisted here.
        #expect(relaunched.blocked.isEmpty)

        relaunched.clear()
        #expect(SafetyStore(defaults: defaults).hiddenEvents.isEmpty, "sign-out left hidden ids behind")
    }

    @Test("a message report offers to block its sender; a room report offers no one")
    func whoCanBeBlocked() {
        let message = ReportSubject.message(
            roomId: "!r:x", eventId: "$e", senderId: "@agent_atlas:x", senderName: "Atlas",
            isAgent: true)
        #expect(message.blockable?.userId == "@agent_atlas:x")
        #expect(message.blockable?.isAgent == true)
        #expect(ReportSubject.room(roomId: "!r:x", name: "Ops").blockable == nil)
        #expect(ReportSubject.user(userId: "@k:x", name: "K", isAgent: false).blockable?.name == "K")
    }
}
