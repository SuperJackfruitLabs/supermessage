import Foundation
import Testing

@testable import SupermessageKit

struct AccountLinkTests {
    @Test("only the Accounts page's own callback names a linked Matrix id")
    func callback() throws {
        let linked = try #require(URL(string: "supermessage://linked?mxid=%40ana%3Aid.agentpod.dev"))
        #expect(AccountLink.linkedMxid(from: linked) == "@ana:id.agentpod.dev")
        for other in [
            "supermessage://linked", "supermessage://other?mxid=%40ana%3Aid.agentpod.dev",
            "https://linked?mxid=%40ana%3Aid.agentpod.dev", "supermessage://linked?mxid=nobody",
        ] {
            #expect(AccountLink.linkedMxid(from: try #require(URL(string: other))) == nil, "\(other)")
        }
    }

    @Test("a blank Accounts setting is no setting")
    func override() {
        #expect(AccountLink.override("") == nil)
        #expect(AccountLink.override("  \n") == nil)
        #expect(AccountLink.override(" https://accounts.example ") == "https://accounts.example")
    }

    @Test("a seam without account linking offers nothing to link to")
    func defaultSeam() async throws {
        struct Nothing: AccountLinking {}
        #expect(try await Nothing().accountLinkOrigin(accountsUrl: nil) == nil)
        await #expect(throws: (any Error).self) { try await Nothing().accountLinkUrl(accountsUrl: nil) }
    }
}
