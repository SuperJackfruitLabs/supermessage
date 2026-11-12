import Foundation
import SupermessageFFI

/// Linking this Matrix account to the person's Super Jackfruit account, so they
/// can approve requests from chat as themselves.
///
/// The core decides everything: whether this account has an Accounts to link
/// to (`Session.accountLinkOrigin`), the page to open with a fresh OpenID token
/// in its fragment (`Session.accountLinkURL`), and what the page's callback
/// means (`linkedMxid`). The app only opens the page in a web authentication
/// session and shows the result. The URL carries the token: never log it,
/// store it, or show it.
public enum AccountLink {
    /// The person's own Accounts address, when their homeserver is not one
    /// of the suite's. Blank means the core's default for the homeserver.
    public static let accountsURLKey = "accounts.url"
    /// The scheme the Accounts page returns to (`supermessage://linked?mxid=…`).
    public static let callbackScheme = "supermessage"

    /// The Matrix id the page reports linked, or `nil` for any other callback.
    public static func linkedMxid(from callback: URL) -> String? {
        accountLinkedMxid(callback: callback.absoluteString)
    }

    /// The person's setting as the core takes it: `nil` when blank.
    public static func override(_ stored: String) -> String? {
        let trimmed = stored.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
}

/// Why a link could not start, in words for the person.
public struct AccountLinkFailure: Error, Sendable {
    public let message: String
}
