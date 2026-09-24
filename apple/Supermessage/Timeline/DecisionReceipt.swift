import SupermessageFFI
import SwiftUI

/// An answer this reader gave on this device, once it has actually landed.
///
/// Recorded only after `onDecide` reports success — see
/// `CustomEventCard.answer` — so its existence is the claim "the room has
/// this", and `at` is when that became true rather than when the tap began.
struct DecisionAnswerRecord: Equatable {
    let option: CustomEventDecisionOption
    let at: Date

    /// What happened, in a sentence the reader said.
    ///
    /// The three gate answers are superpipeline's fixed `GateDecision`
    /// vocabulary (`core::custom_events::GATE_OPTION_IDS`), so they can be
    /// said in the past tense without guessing. Anything else — a permission
    /// request's "Allow once", an id nothing here has heard of — is quoted
    /// back in the label the renderer chose, because inventing a past tense
    /// for a verb we do not know is how a receipt ends up saying something
    /// the reader did not do.
    var outcome: String {
        switch DecisionOptionRole(option) {
        case .approve: return "Approved by you"
        case .requestChanges: return "Changes requested by you"
        case .reject: return "Rejected by you"
        case .other: return "You chose \u{201C}\(option.label)\u{201D}"
        }
    }
}

/// Which part an option plays on the card.
///
/// Identified by **id**, never by label: the label is display text and may be
/// anything, the id is what is sent. `reject` is matched without regard to
/// case because a permission request's ids are the hub's option *names*
/// ("Reject") rather than superpipeline's lower-case ids — see
/// `PermissionRequestRenderer`. Anything unrecognised is `.other` and is drawn
/// as an ordinary secondary answer.
enum DecisionOptionRole {
    case approve, requestChanges, reject, other

    init(_ option: CustomEventDecisionOption) {
        switch option.id.lowercased() {
        case "approve": self = .approve
        case GateAnswer.requestChanges: self = .requestChanges
        case "reject": self = .reject
        default: self = .other
        }
    }
}

/// "✓ Approved by you · just now" — what a decision looks like once it is over.
///
/// A receipt, not a form: one line, no controls, and **no amber**. Amber is a
/// debt the reader owes; the moment the answer lands there is no debt, so a
/// receipt drawn in `signal` would be telling the reader they still owe it.
/// `ok` is the token for "this finished".
struct DecisionReceiptLine: View {
    let outcome: String
    /// When it landed, if this device knows. A receipt rendered from the
    /// room's own payload ("Decision: Approved by Rakesh") carries no time
    /// the core has handed over, so it shows none rather than a guessed one.
    var at: Date?

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 6) {
            Image(systemName: "checkmark.circle.fill")
                .foregroundStyle(Theme.ok)
                .accessibilityHidden(true)
            // One `Text` so the line wraps as a sentence at large sizes
            // rather than as two columns fighting for the width.
            if let at {
                SwiftUI.TimelineView(.periodic(from: at, by: 30)) { context in
                    sentence(Self.relative(at, now: context.date))
                }
            } else {
                sentence(nil)
            }
        }
        .accessibilityElement(children: .combine)
    }

    private func sentence(_ when: String?) -> some View {
        var line = Text(outcome)
            .font(.system(.subheadline, weight: .semibold))
            .foregroundColor(Theme.content)
        if let when {
            line = line
                + Text(" · \(when)").font(ThemeType.meta.monospacedDigit())
                .foregroundColor(Theme.contentMuted)
        }
        return line.fixedSize(horizontal: false, vertical: true)
    }

    /// "just now" for the first minute, then the system's own relative
    /// phrasing — "2 minutes ago" — so it is localised by the OS and not here.
    static func relative(_ date: Date, now: Date) -> String {
        if now.timeIntervalSince(date) < 60 { return "just now" }
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .full
        formatter.dateTimeStyle = .named
        return formatter.localizedString(for: date, relativeTo: now)
    }
}
