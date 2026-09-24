import Testing

@testable import SupermessageKit

struct SpaceNamesTests {
    @Test("an id-shaped space name reads as a runtime")
    func runtime() {
        #expect(SpaceNames.display("9247e5c1a0b34d2e") == "Runtime 9247e5")
        #expect(SpaceNames.display("9247e5c1-a0b3-4d2e-8f00-123456789abc") == "Runtime 9247e5")
        // Already cut short where it was named: the 2026-09-24 space.
        #expect(SpaceNames.display("9247e5…") == "Runtime 9247e5")
        #expect(SpaceNames.display("9247e5...") == "Runtime 9247e5")
    }

    @Test("a host keeps its name, without the mDNS suffix")
    func host() {
        #expect(SpaceNames.display("Rakeshs-MacBook-Pro.local") == "Rakeshs-MacBook-Pro")
        #expect(SpaceNames.display("molt-bot") == "molt-bot")
    }

    @Test("a chosen name is left exactly as it is")
    func chosen() {
        #expect(SpaceNames.display("Guild") == "Guild")
        #expect(SpaceNames.display("molt-bot") == "molt-bot")
        #expect(SpaceNames.display("Rakesh's MacBook Pro") == "Rakesh's MacBook Pro")
        // Hex letters alone are a word someone chose, not an id.
        #expect(SpaceNames.display("cafe-bead") == "cafe-bead")
        #expect(SpaceNames.display("  Ashram ") == "Ashram")
    }
}
