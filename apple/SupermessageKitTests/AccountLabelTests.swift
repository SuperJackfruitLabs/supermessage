import SupermessageFFI
import Testing

@testable import SupermessageKit

struct AccountLabelTests {
    @Test("a Matrix id is named the core's way, not by its raw localpart")
    func localpart() {
        #expect(AccountLabel.name(of: "@rakesh:id.agentpod.dev") == "Rakesh")
        #expect(AccountLabel.name(of: "@_agentpod_ganesha:id.agentpod.dev") == "Ganesha")
        #expect(AccountLabel.name(of: "@rakesh:id.agentpod.dev") == peopleLabel(userIds: ["@rakesh:id.agentpod.dev"]))
        #expect(AccountLabel.initial(of: "@rakesh:id.agentpod.dev") == "R")
    }

    @Test("before the account is known, nothing is guessed")
    func unknown() {
        #expect(AccountLabel.initial(of: nil) == "?")
        #expect(AccountLabel.name(of: nil) == "Signed in")
    }
}
