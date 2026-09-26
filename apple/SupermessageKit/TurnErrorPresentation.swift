import SupermessageFFI

/// How a turn error card lays out its fallback chain.
///
/// Everything a reader is *told* — the kind's wording, the headline, which
/// attempts were the same and how many times — arrives decided on
/// `TurnErrorCard` from `core::turn_error`. What is left is presentation: the
/// chain shows its first line, the model that was asked for, until the reader
/// opens it. The web's `turnErrorView.ts` follows the same three rules, so the
/// two say the same thing about the same card.
public enum TurnErrorPresentation {
    /// All the attempts when open, otherwise only the first.
    public static func attemptsToShow(_ card: TurnErrorCard, expanded: Bool) -> [TurnErrorAttempt] {
        expanded ? card.attempts : Array(card.attempts.prefix(1))
    }

    /// What the disclosure says — "2 more attempts" — or `nil` when there is
    /// nothing to disclose. Counts *lines*, because lines are what it reveals:
    /// a folded "×4" is one of them.
    public static func moreAttemptsLabel(_ card: TurnErrorCard) -> String? {
        let hidden = card.attempts.count - 1
        guard hidden >= 1 else { return nil }
        return "\(hidden) more \(hidden == 1 ? "attempt" : "attempts")"
    }

    /// "×4" for a folded line, `nil` for a single attempt.
    public static func count(_ attempt: TurnErrorAttempt) -> String? {
        attempt.count > 1 ? "×\(attempt.count)" : nil
    }
}
