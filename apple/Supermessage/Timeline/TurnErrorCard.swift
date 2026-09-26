import SupermessageFFI
import SupermessageKit
import SwiftUI

/// An agent's failed turn: what went wrong, in the provider's own words, and
/// the models it fell back to.
///
/// Drawn from `ItemView.turnError`, which `core::turn_error` parsed off the
/// hub's error message. Every string arrived decided — the kind's wording,
/// the headline, which attempts were the same and how many times — and
/// everything here is text: the card came from whoever sent the message.
///
/// **Not amber.** `Theme.signal` means a decision the reader owes, and only
/// `DecisionCard` may use it. A failure is `danger`, and only on the label and
/// the card's leading edge: the provider's words are read, not alarmed at.
///
/// The fallback chain shows its first line — the model that was asked for —
/// until the reader opens it; `TurnErrorPresentation` holds that rule, shared
/// with the web.
struct TurnErrorCardView: View {
    let card: TurnErrorCard
    @State private var expanded: Bool

    init(card: TurnErrorCard, expanded: Bool = false) {
        self.card = card
        // `expanded` exists for previews: the open chain is otherwise
        // reachable only through a tap.
        _expanded = State(initialValue: expanded)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            headline
            Text(card.message)
                .font(Theme.body)
                .foregroundStyle(Theme.content)
                .textSelection(.enabled)
                .fixedSize(horizontal: false, vertical: true)
            attempts
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.surfaceRaised, in: RoundedRectangle(cornerRadius: Metrics.radiusCard))
        .overlay(alignment: .leading) {
            // The leading edge, as the web's card has it. An overlay, not an
            // HStack sibling: a `Rectangle` in a stack takes every point of
            // height a self-sizing cell offers (see `ReplyQuote`).
            UnevenRoundedRectangle(
                topLeadingRadius: Metrics.radiusCard, bottomLeadingRadius: Metrics.radiusCard
            )
            .fill(Theme.danger)
            .frame(width: 3)
        }
        .overlay(RoundedRectangle(cornerRadius: Metrics.radiusCard).stroke(Theme.border, lineWidth: 1))
        .frame(maxWidth: MessageMeasure.card, alignment: .leading)
    }

    /// "Usage limit reached · kimi-coding / k2p6" — the label in `danger`, the
    /// model it happened on quiet beside it.
    private var headline: some View {
        HStack(alignment: .firstTextBaseline, spacing: 6) {
            Image(systemName: "exclamationmark.triangle")
                .foregroundStyle(Theme.danger)
                .accessibilityHidden(true)
            Text(headlineText)
                .fixedSize(horizontal: false, vertical: true)
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(card.headline)
    }

    /// One run of text, so the source wraps after the label as a sentence
    /// would rather than into a column of its own.
    private var headlineText: AttributedString {
        var label = AttributedString(card.label)
        label.foregroundColor = Theme.danger
        label.font = ThemeType.ui.weight(.semibold)
        guard let source = card.source else { return label }
        var rest = AttributedString(" · \(source)")
        rest.foregroundColor = Theme.contentMuted
        rest.font = ThemeType.ui
        return label + rest
    }

    @ViewBuilder
    private var attempts: some View {
        if !card.attempts.isEmpty {
            VStack(alignment: .leading, spacing: 3) {
                ForEach(
                    Array(TurnErrorPresentation.attemptsToShow(card, expanded: expanded).enumerated()),
                    id: \.offset
                ) { _, attempt in
                    // Open, each fallback also says why it failed — unless
                    // that is the card's own message, already said above.
                    AttemptLine(
                        attempt: attempt,
                        showsMessage: expanded && attempt.message != card.message)
                }
                if let more = TurnErrorPresentation.moreAttemptsLabel(card) {
                    // No animation: this is a self-sizing cell in the
                    // timeline's collection view, and an animated resize
                    // there re-lays its neighbours every frame.
                    Button {
                        expanded.toggle()
                    } label: {
                        Label(
                            expanded ? "Show fewer" : more,
                            systemImage: expanded ? "chevron.up" : "chevron.down"
                        )
                        .font(.footnote)
                    }
                    .buttonStyle(.borderless)
                    .tint(Theme.contentMuted)
                    .accessibilityHint(
                        Text(verbatim: expanded ? "Hides the fallbacks" : "Shows every model the agent tried"))
                }
            }
            .padding(.top, 2)
        }
    }
}

/// One model the turn tried: `provider / model · Request rejected ×4`.
private struct AttemptLine: View {
    let attempt: TurnErrorAttempt
    var showsMessage = false

    var body: some View {
        VStack(alignment: .leading, spacing: 1) {
            summary
            if showsMessage, !attempt.message.isEmpty {
                Text(attempt.message)
                    .font(.footnote)
                    .foregroundStyle(Theme.contentFaint)
                    .lineLimit(2)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .textSelection(.enabled)
        .accessibilityElement(children: .combine)
    }

    private var summary: some View {
        HStack(alignment: .firstTextBaseline, spacing: 6) {
            Text(attempt.source)
                .font(Theme.code)
                .foregroundStyle(Theme.content)
                .lineLimit(1)
                .truncationMode(.middle)
            Text(attempt.label)
                .metaFace()
                .foregroundStyle(Theme.contentMuted)
            if let count = TurnErrorPresentation.count(attempt) {
                Text(count)
                    .metaFace()
                    .monospacedDigit()
                    .foregroundStyle(Theme.contentMuted)
            }
        }
    }
}

#if DEBUG
// krishna's usage limit, with its fallback chain collapsed to the model that
// was asked for.
#Preview("Turn error") {
    PreviewGround(width: 390) {
        TurnErrorCardView(card: PreviewFixtures.turnError)
    }
}

// The same card opened: the fallbacks, with the four identical qwen attempts
// folded into one line.
#Preview("Turn error, every attempt") {
    PreviewGround(width: 390) {
        TurnErrorCardView(card: PreviewFixtures.turnError, expanded: true)
    }
}

// As a row: the agent's face and name above it, like any message of theirs.
#Preview("Turn error row") {
    PreviewGround(width: 390) {
        TimelineRowView(
            row: PreviewFixtures.turnErrorRow, attribution: "Krishna",
            media: PreviewFixtures.mediaCache(), faces: PreviewFixtures.faceCache())
    }
    .environment(\.rendersStill, true)
}
#endif
