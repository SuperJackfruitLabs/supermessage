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

    /// A throwaway defaults suite, removed when the test ends.
    private func scratchDefaults() throws -> (UserDefaults, cleanup: () -> Void) {
        let suite = "safety-store-tests-\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suite))
        return (defaults, { defaults.removePersistentDomain(forName: suite) })
    }

    @Test("hidden messages survive a relaunch of the same account, and sign-out forgets them")
    func persistsUntilSignOut() throws {
        let (defaults, cleanup) = try scratchDefaults()
        defer { cleanup() }

        let first = SafetyStore(defaults: defaults)
        first.bind(owner: "@a:x.org", adoptLegacy: false)
        first.hide(eventId: "$b")
        first.apply(ignored: ["@troll:x.org"])

        let relaunched = SafetyStore(defaults: defaults)
        relaunched.bind(owner: "@a:x.org", adoptLegacy: true)
        #expect(relaunched.hiddenEvents == ["$b"], "a reported message came back after a relaunch")
        // The block list is the core's to send again; it is not persisted here.
        #expect(relaunched.blocked.isEmpty)

        relaunched.clear()
        let after = SafetyStore(defaults: defaults)
        after.bind(owner: "@a:x.org", adoptLegacy: true)
        #expect(after.hiddenEvents.isEmpty, "sign-out left hidden ids behind")
    }

    // Mutation (not run locally; iOS can't build here): making `bind` load
    // `Self.legacyKey` instead of `Self.key(for: owner)` should fail the final
    // `returning` assertion (`$a` lost), not the second account's first one,
    // since the legacy key is empty there.
    @Test("another account sees nothing of the first one's hidden messages")
    func anotherAccountSeesNothing() throws {
        let (defaults, cleanup) = try scratchDefaults()
        defer { cleanup() }

        let first = SafetyStore(defaults: defaults)
        first.bind(owner: "@a:x.org", adoptLegacy: false)
        first.hide(eventId: "$a")

        // The first account ended without a sign-out; another one signs in.
        let second = SafetyStore(defaults: defaults)
        second.bind(owner: "@b:x.org", adoptLegacy: false)
        #expect(second.hiddenEvents.isEmpty, "the next account saw the last one's hidden ids")
        second.hide(eventId: "$b")

        let returning = SafetyStore(defaults: defaults)
        returning.bind(owner: "@a:x.org", adoptLegacy: true)
        #expect(returning.hiddenEvents == ["$a"], "the first account lost, or gained, ids")
    }

    // Mutation (not run locally; iOS can't build here): dropping the `removeObject(forKey: legacyKey)`
    // in `bind` fails the second bind's assertion (the old ids are adopted
    // again by the account that restores next).
    @Test("the device-wide set an older build kept is adopted once, by the restored account")
    func legacySetIsAdoptedOnce() throws {
        let (defaults, cleanup) = try scratchDefaults()
        defer { cleanup() }
        defaults.set(["$old"], forKey: SafetyStore.legacyKey)

        let restored = SafetyStore(defaults: defaults)
        restored.bind(owner: "@a:x.org", adoptLegacy: true)
        #expect(restored.hiddenEvents == ["$old"])
        #expect(defaults.stringArray(forKey: SafetyStore.legacyKey) == nil, "the legacy key was left behind")

        let other = SafetyStore(defaults: defaults)
        other.bind(owner: "@b:x.org", adoptLegacy: true)
        #expect(other.hiddenEvents.isEmpty, "a second account adopted the legacy set too")

        // The adopted ids are now the account's own.
        let again = SafetyStore(defaults: defaults)
        again.bind(owner: "@a:x.org", adoptLegacy: true)
        #expect(again.hiddenEvents == ["$old"])
    }

    @Test("a fresh sign-in drops the legacy set instead of adopting it")
    func freshSignInDropsTheLegacySet() throws {
        let (defaults, cleanup) = try scratchDefaults()
        defer { cleanup() }
        defaults.set(["$old"], forKey: SafetyStore.legacyKey)

        let store = SafetyStore(defaults: defaults)
        store.bind(owner: "@a:x.org", adoptLegacy: false)
        #expect(store.hiddenEvents.isEmpty)
        #expect(defaults.stringArray(forKey: SafetyStore.legacyKey) == nil)
    }

    @Test("a report that finishes after the account changed hides nothing")
    func lateReportDoesNotLandInTheNextAccount() throws {
        let store = SafetyStore()
        store.bind(owner: "@a:x.org", adoptLegacy: false)
        let seen = store.generation
        store.clear()
        store.bind(owner: "@b:x.org", adoptLegacy: false)
        store.hide(eventId: "$late", generation: seen)
        #expect(store.hiddenEvents.isEmpty)
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
