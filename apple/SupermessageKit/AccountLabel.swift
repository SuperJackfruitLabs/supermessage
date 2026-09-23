import Foundation

/// How the signed-in account is named in the app's own chrome.
public enum AccountLabel {
    /// The localpart — `rakesh` for `@rakesh:id.agentpod.dev` — or the id
    /// as given when it is not a Matrix id.
    public static func name(of userId: String?) -> String {
        guard let id = userId, id.hasPrefix("@"), let colon = id.firstIndex(of: ":") else {
            return userId ?? "Signed in"
        }
        return String(id[id.index(after: id.startIndex)..<colon])
    }

    /// The first letter of `name`, capitalised; "?" before the account is known.
    public static func initial(of userId: String?) -> String {
        guard userId != nil, let first = name(of: userId).first else { return "?" }
        return String(first).uppercased()
    }
}
