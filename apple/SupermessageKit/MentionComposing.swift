import Foundation
import SupermessageFFI

/// The `@` picker's half of mentions: the query being typed, and inserting a
/// completion.
///
/// **Host-side on purpose**, as `core::mentions` records: the caret handling
/// is input UX and differs legitimately per platform. What goes on the wire —
/// who a finished message addresses — is `collectMentions`, and this type's
/// only obligation to it is to insert exactly the label it will look for.
public enum MentionComposing {
    /// An `@` being typed at the end of the field, and what follows it.
    public struct Query: Equatable, Sendable {
        /// What follows the `@` — empty straight after typing it.
        public let text: String
        /// Where the `@` is, as a UTF-16 offset, so the insertion replaces the
        /// query and nothing before it.
        public let atOffset: Int
    }

    /// The longest query still treated as a name being looked up. Past this
    /// it is a sentence with an `@` somewhere in it.
    static let longestQuery = 40

    /// The query at the end of `text`, or `nil` when the reader is not
    /// asking for anyone.
    ///
    /// The end, not the caret: SwiftUI's `TextField` does not report one, and
    /// an `@` is typed where the reader is writing — which is almost always
    /// the end. Three rules keep it from firing on things that are not
    /// mentions:
    ///
    /// - the `@` starts the field or follows whitespace, so `ana@example.org`
    ///   is an address, not a lookup;
    /// - nothing after it is whitespace, so once a completion is inserted
    ///   (with its trailing space) the picker closes;
    /// - it is short enough to be a name.
    public static func activeQuery(in text: String) -> Query? {
        guard let at = text.lastIndex(of: "@") else { return nil }
        if at > text.startIndex {
            let before = text[text.index(before: at)]
            guard before.isWhitespace else { return nil }
        }
        let after = text[text.index(after: at)...]
        guard !after.contains(where: \.isWhitespace), after.count <= longestQuery else {
            return nil
        }
        return Query(text: String(after), atOffset: text.utf16.distance(from: text.startIndex, to: at))
    }

    /// What the picker writes after the `@` for `person` — and therefore what
    /// `collectMentions` must be told they are called.
    public static func label(for person: PersonDto) -> String {
        person.name
    }

    /// `text` with `query` replaced by `person`'s mention and a space, so the
    /// reader carries on writing.
    public static func insert(_ person: PersonDto, for query: Query, in text: String) -> String {
        let utf16 = text.utf16
        guard let at = utf16.index(utf16.startIndex, offsetBy: query.atOffset, limitedBy: utf16.endIndex),
            at < text.endIndex
        else { return text }
        return String(text[..<at]) + "@" + label(for: person) + " "
    }
}
