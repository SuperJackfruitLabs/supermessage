import Testing

@testable import SupermessageKit

/// The three-target split is this app's central structural claim, and it is
/// worth one test that fails when it stops being true.
struct BuildTests {
    @Test("the Kit can see the generated bindings")
    func kitLinksTheCore() {
        // If the Swift-5 island is not linked into the Kit this does not
        // compile — which is the assertion. A constant of the Kit's own would
        // still build with the dependency removed and prove nothing.
        #expect(linkedCoreVersion.isEmpty == false)
    }
}
