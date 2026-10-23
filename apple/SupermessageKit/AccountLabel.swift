import Foundation
import SupermessageFFI

/// How the signed-in account is named in the app's own chrome.
public enum AccountLabel {
    /// The person behind the Matrix id — `Rakesh` for `@rakesh:id.agentpod.dev`.
    ///
    /// The core's `people_label`, which is `display_name::user_label` for one
    /// id, so the account is named the way Android and desktop name it (and
    /// the way a bridge's `@_agentpod_ganesha:…` reads `Ganesha`) rather than
    /// by a localpart this file cut out of the id itself. "Signed in" before
    /// the account is known.
    public static func name(of userId: String?) -> String {
        guard let userId else { return "Signed in" }
        return peopleLabel(userIds: [userId])
    }

    /// The first letter of `name`, capitalised; "?" before the account is known.
    public static func initial(of userId: String?) -> String {
        guard userId != nil, let first = name(of: userId).first else { return "?" }
        return String(first).uppercased()
    }
}
