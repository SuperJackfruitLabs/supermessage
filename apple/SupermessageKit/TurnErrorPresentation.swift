import SupermessageFFI

/// How a turn error card lays out its attempts.
///
/// Everything a reader is *told* — the kind's wording, the headline, which
/// attempts were the same and how many times — arrives decided on
/// `TurnErrorCard` from `core::turn_error`. What is left is presentation,
/// shared with the web's `turnErrorView.ts` and Android's `TurnErrorCard.kt`:
///
/// The headline already names the first attempt — the model that was asked
/// for. So under it go only the models the agent *fell back to*, behind a
/// disclosure; listing the first again said the same thing twice. When the
/// asked-for model was simply retried, that is said once ("Tried 3 times").
public enum TurnErrorPresentation {
    /// Whether the first attempt is the one the headline already names.
    static func headlineIsFirst(_ card: TurnErrorCard) -> Bool {
        guard let first = card.attempts.first, let source = card.source else { return false }
        return first.source == source
    }

    /// The attempts after the headline's own: the models the agent fell back to.
    public static func fallbacks(_ card: TurnErrorCard) -> [TurnErrorAttempt] {
        headlineIsFirst(card) ? Array(card.attempts.dropFirst()) : card.attempts
    }

    /// The attempts to draw: the fallbacks when open, none when closed.
    public static func attemptsToShow(_ card: TurnErrorCard, expanded: Bool) -> [TurnErrorAttempt] {
        expanded ? fallbacks(card) : []
    }

    /// The disclosure — "Fell back to 2 models" — or `nil` when there were none.
    public static func moreAttemptsLabel(_ card: TurnErrorCard) -> String? {
        let n = fallbacks(card).count
        guard n >= 1 else { return nil }
        return "Fell back to \(n) \(n == 1 ? "model" : "models")"
    }

    /// "Tried 3 times" when the headline's model was retried, else `nil`.
    public static func repeatsLabel(_ card: TurnErrorCard) -> String? {
        guard headlineIsFirst(card), let first = card.attempts.first, first.count > 1 else { return nil }
        return "Tried \(first.count) times"
    }

    /// "×4" for a folded line, `nil` for a single attempt.
    public static func count(_ attempt: TurnErrorAttempt) -> String? {
        attempt.count > 1 ? "×\(attempt.count)" : nil
    }
}
