import Testing

@testable import SupermessageKit

struct AccountLabelTests {
    @Test("a Matrix id is named by its localpart")
    func localpart() {
        #expect(AccountLabel.name(of: "@rakesh:id.agentpod.dev") == "rakesh")
        #expect(AccountLabel.initial(of: "@rakesh:id.agentpod.dev") == "R")
    }

    @Test("before the account is known, nothing is guessed")
    func unknown() {
        #expect(AccountLabel.initial(of: nil) == "?")
        #expect(AccountLabel.name(of: nil) == "Signed in")
    }
}
