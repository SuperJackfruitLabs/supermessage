import Foundation
import SupermessageFFI

/// Where a search has got to.
///
/// Modelled as states rather than a pair of booleans because the booleans were
/// what went wrong: `searched` only became true *after* a query ran, so typing
/// left the untouched empty state on screen — the magnifying glass and "Find a
/// message across your rooms" — and a reader could not tell whether the app was
/// thinking, had found nothing, or had ignored them.
public enum SearchState: Equatable, Sendable {
    /// Nothing typed. The only state that may show the invitation to search.
    case idle
    /// Something typed, not yet run. Says how to run it rather than pretending
    /// nothing has happened.
    case ready(String)
    /// Running. **The state that did not exist**, and the reason a working
    /// search looked broken.
    case searching(String)
    case found([SearchResultDto])
    /// Ran, and there is nothing. Names the query, because "no results" alone
    /// leaves a reader wondering which query it means.
    case empty(String)

    /// What typing does, from wherever we are.
    ///
    /// Deliberately keeps results on screen while the query is being edited: a
    /// list that empties on the first keystroke of a correction is a list that
    /// throws away what you were looking at.
    public func typed(_ query: String) -> SearchState {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return .idle }
        if case let .found(results) = self, !results.isEmpty { return self }
        return .ready(trimmed)
    }

    public var query: String {
        switch self {
        case .idle: return ""
        case let .ready(q), let .searching(q), let .empty(q): return q
        case .found: return ""
        }
    }
}
