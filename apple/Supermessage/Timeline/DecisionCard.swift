import SupermessageFFI
import SupermessageKit
import SwiftUI

/// A suite event — a card, a turn's activity, or a permission request.
///
/// `view` is the whole fallback-chain decision from
/// `core::custom_events::resolve_custom_event`: this renders its three states
/// and never makes the choice itself. Every field is **text**, bounded and
/// validated on the Rust side before it crossed, and nothing read out of a
/// payload may be rendered as anything but text.
/// A decision field's label, rendered identically in both arrangements of
/// the `ViewThatFits` that draws a field row.
///
/// `@MainActor` because `metaFace()` is: the type ramp reads the colour
/// scheme, so a face is a main-actor decision like any other view modifier.
@MainActor
@ViewBuilder
private func fieldLabel(_ field: CustomEventField) -> some View {
    Text(field.label)
        .metaFace()
        .textCase(.uppercase)
        .foregroundStyle(Theme.contentMuted)
}

struct CustomEventCard: View {
    /// Whether the reader has chosen one of the accessibility text sizes.
    ///
    /// Read rather than guessed at: a fixed-width label column is a promise
    /// about text size, and this is the only thing that can say whether the
    /// promise still holds.
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    let view: CustomEventView
    /// What this kind of event is called — "Turn", "Permission" — decided by
    /// the renderer that drew the card, not by reading the schema address.
    let label: String
    let eventType: String
    let senderName: String
    /// Answering a decision. `nil` in contexts that only display — a preview,
    /// or a row whose event the homeserver has not acknowledged yet.
    var onDecide: ((GateAnswer) async -> Bool)?

    var body: some View {
        switch view {
        case let .rendered(fields, reasoning, newerVersion, decision, link):
            card(
                fields: fields, reasoning: reasoning, newerVersion: newerVersion,
                decision: decision, link: link)

        case let .fallbackBody(text):
            // A type nothing here can render, but which carried a plain-text
            // body as Matrix convention asks. Show what it said.
            VStack(alignment: .leading, spacing: 4) {
                Text(senderName).nameFace()
                Text(text).font(Theme.body)
            }
            .padding(.vertical, 6)

        case let .placeholder(text):
            // Not a card. A type we cannot render is not worth a bordered
            // object — it gets the same quiet centred line every other
            // unrenderable item gets.
            Text(text)
                .metaFace()
                .foregroundStyle(Theme.contentFaint)
                .frame(maxWidth: .infinity, alignment: .center)
                .padding(.vertical, 6)
        }
    }

    @ViewBuilder
    private func card(
        fields: [CustomEventField], reasoning: String?, newerVersion: Bool,
        decision: CustomEventDecision?, link: String?
    ) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                // What it is, in the words the renderer uses — a reader
                // should not have to parse `dev.agentpod.turn.v1` to learn
                // they are looking at a turn. The schema address stays
                // available to accessibility for anyone debugging a room.
                Text(label)
                    .metaFace()
                    .textCase(.uppercase)
                    .foregroundStyle(Theme.contentMuted)
                    .accessibilityLabel("\(label), \(eventType)")
                Spacer()
                if newerVersion {
                    // Rendered best-effort against a newer minor schema. Said
                    // quietly rather than hidden, so a reader knows there may
                    // be more to this event than is shown.
                    Text("newer version").metaFace().foregroundStyle(Theme.contentFaint)
                }
            }

            ForEach(Array(fields.enumerated()), id: \.offset) { _, field in
                // Label beside value, or above it once the text is large.
                //
                // The column was a flat `.frame(width: 84)`, and at
                // `.accessibility3` a label broke *mid-word* inside it —
                // `REPOSITORY` rendered as `REPO SITO RY` down three lines —
                // while the value beside it had room to spare. A fixed column
                // is a promise about text size that nothing was keeping.
                //
                // `ViewThatFits` was the first attempt and is wrong here: a
                // fixed-width column *does* fit horizontally, the label just
                // wraps inside it, so it kept choosing the broken arm.
                // Fitting is precisely what `ViewThatFits` measures. The
                // accessibility boundary is the honest test, and it is
                // Apple's own rather than a number invented for this card.
                if dynamicTypeSize.isAccessibilitySize {
                    VStack(alignment: .leading, spacing: 2) {
                        fieldLabel(field)
                        Text(field.value).font(.callout)
                    }
                } else {
                    HStack(alignment: .firstTextBaseline, spacing: 10) {
                        fieldLabel(field).frame(width: 84, alignment: .leading)
                        Text(field.value).font(.callout)
                    }
                }
            }

            // How the agent got here, when it said. Collapsed: it is context,
            // not the conclusion, and a reader scanning a room wants the
            // conclusion first.
            //
            // This is the reasoning that *lasts*. The live card shows the
            // turn's reasoning while it is being written and until the next
            // turn replaces it; what arrives here is a room event, so it is
            // still in place tomorrow and on every other client.
            if let reasoning {
                DisclosureGroup {
                    Text(reasoning)
                        .font(.footnote)
                        .foregroundStyle(Theme.contentMuted)
                        .textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.top, 2)
                } label: {
                    Text("Reasoning").metaFace().foregroundStyle(Theme.contentMuted)
                }
            }

            // The one thing on this card that is not text.
            //
            // `link` arrived through `core::custom_events::safe_link`, which
            // accepts `https://` and printable ASCII and nothing else — so this
            // is the only place a payload may become somewhere a tap can go.
            // Before it existed the deep link was printed at the reader as
            // characters to retype, which looks like an affordance and is not.
            //
            // "the card", and it really is the card: superpipeline/#47 made cards
            // addressable. This label said "Open on the board" for a day while
            // pointing at an address that 404d, then "Open the board" while
            // pointing at the app root. A label must not promise what the other
            // end cannot keep — which is why it moved twice rather than once.
            if let link, let url = URL(string: link) {
                Link(destination: url) {
                    Label("Open the card", systemImage: "arrow.up.forward.square")
                        .font(.footnote)
                }
                .padding(.top, 2)
            }

            if let decision {
                DecisionButtons(decision: decision, onDecide: onDecide)
            }
        }
        .padding(12)
        .background(
            (decision == nil ? Color.clear : Theme.signal.opacity(0.10)),
            in: RoundedRectangle(cornerRadius: 8)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 8)
                .stroke(decision == nil ? Theme.border : Theme.signal, lineWidth: 1)
        )
        .padding(.vertical, 6)
    }
}

/// The answers to a pending decision.
///
/// **This is the only view in the app that may use `Theme.signal`.** Amber
/// means one thing: the operator owes someone an answer. Not a severity, not a
/// warning, not an error. If it is on screen anywhere else, that is a review
/// defect — see the console spec and `Theme.signal`'s own note.
private struct DecisionButtons: View {
    let decision: CustomEventDecision
    var onDecide: ((GateAnswer) async -> Bool)?

    /// The option awaiting a comment, if one is. Only `request_changes` ever
    /// sets this: approve and reject are decisions, and request-changes is
    /// feedback that becomes the rework's context — superpipeline merges it into
    /// the card's handoff, so an empty one costs the next agent the reason.
    @State private var commenting: CustomEventDecisionOption?
    @State private var comment = ""

    /// The option this reader chose, once it has actually landed.
    ///
    /// A gate is answered **once**. Leaving three live buttons after an answer
    /// invites a second tap that superpipeline refuses with GATE_NOT_PENDING — a
    /// round trip whose only outcome is a message explaining that nothing
    /// happened. Worse, it reads as though the first tap failed.
    ///
    /// Set only after the send succeeds, never optimistically: a card that
    /// says "Approved" when the send did not land is the one wrong answer
    /// here, because the reader stops trying.
    ///
    /// Per-device and per-view. It does not survive a scroll far enough to
    /// recycle the row, and the room still holds a gate event that looks
    /// pending — closing that properly means the bridge saying so in the room
    /// after it resolves, which is a separate change.
    @State private var answered: CustomEventDecisionOption?
    @State private var sending = false

    /// Answerable only when the renderer named what this decision resolves and
    /// someone is listening. A button that cannot resolve anything must not
    /// look pressable — the failure would otherwise surface as a tap that did
    /// nothing, by which point the reader believes they have approved.
    private var answerable: Bool { decision.subject != nil && onDecide != nil }

    /// The options, rendered identically whichever way they are arranged.
    @ViewBuilder
    private var optionButtons: some View {
        ForEach(Array(decision.options.enumerated()), id: \.offset) { index, option in
            // One answer leads; the others are there but do not compete. Three
            // filled buttons of equal weight made "Reject" as loud as
            // "Approve", and their labels inherited the card's dark text —
            // dark on brown, dark on slate — which at a glance read as
            // disabled.
            //
            // The label colour is set rather than inherited. `signal-soft` on
            // `signal` is the pair the token contract holds at 6:1 in every
            // appearance, including dark, where `signal` is light amber and a
            // white label would vanish.
            if index == 0 {
                Button { tapped(option) } label: {
                    OptionLabel(text: option.label, colour: Theme.signalSoft)
                }
                .buttonStyle(.borderedProminent)
                .tint(Theme.signal)
                .disabled(!answerable || sending)
            } else {
                Button { tapped(option) } label: {
                    OptionLabel(text: option.label, colour: Theme.content)
                }
                .buttonStyle(.bordered)
                .tint(Theme.contentMuted)
                .disabled(!answerable || sending)
            }
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            // The question itself, allowed the height to ask it.
            //
            // Nothing in this file sets a `lineLimit`, and at
            // `.accessibility3` the prompt still rendered as `Merge this
            // branch i…` — the layout was giving it one line's height and
            // letting it truncate rather than wrap. `fixedSize(vertical:)`
            // says take the height you need, which for the one sentence a
            // reader has to answer is not negotiable.
            Text(decision.prompt)
                .font(.system(.callout, weight: .semibold))
                .fixedSize(horizontal: false, vertical: true)

            if let answered {
                // What was chosen, not a row of buttons that would refuse.
                Label(answered.label, systemImage: "checkmark.circle.fill")
                    .font(.system(.callout, weight: .semibold))
                    .foregroundStyle(Theme.signal)
            } else {
                // A row while the options fit, a column when they do not.
                //
                // At `.accessibility3` the row produced `Ap-prov e` over three
                // lines, `Re-ques t chan ges` over five, and three buttons of
                // three different heights — on the one control in this product
                // that must not be missed. Hyphenating a verb mid-word inside a
                // button is worse than a taller card.
                //
                // `ViewThatFits` rather than a size threshold: it picks the
                // first layout that actually fits, so there is no accessibility
                // step to guess wrong and no behaviour that only appears above
                // some boundary nobody previews.
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 8) { optionButtons }
                    VStack(alignment: .leading, spacing: 8) { optionButtons }
                }
            }
        }
        .alert(
            "Request changes",
            isPresented: Binding(
                get: { commenting != nil },
                set: { if !$0 { commenting = nil; comment = "" } })
        ) {
            TextField("What needs changing?", text: $comment)
            Button("Cancel", role: .cancel) { commenting = nil; comment = "" }
            Button("Send") {
                if let option = commenting { send(option, comment: comment) }
                commenting = nil
                comment = ""
            }
        } message: {
            Text("This goes back to the agent as the reason, so it can pick the work up again.")
        }
    }

    private func tapped(_ option: CustomEventDecisionOption) {
        guard answerable else { return }
        if option.id == GateAnswer.requestChanges {
            commenting = option
        } else {
            send(option, comment: nil)
        }
    }

    private func send(_ option: CustomEventDecisionOption, comment: String?) {
        guard let subject = decision.subject, let onDecide else { return }
        let trimmed = comment?.trimmingCharacters(in: .whitespacesAndNewlines)
        let answer = GateAnswer(
            subject: subject,
            optionId: option.id,
            comment: (trimmed?.isEmpty ?? true) ? nil : trimmed,
            prompt: decision.prompt)
        sending = true
        Task {
            let landed = await onDecide(answer)
            sending = false
            // Only on success. See `answered`.
            if landed { answered = option }
        }
    }
}

/// An option's label, in the colour its button needs.
///
/// Set explicitly, which means it no longer dims on its own when the button
/// is disabled — so it reads `isEnabled` and dims itself. A button that
/// cannot resolve anything must not look pressable.
private struct OptionLabel: View {
    let text: String
    let colour: Color
    @Environment(\.isEnabled) private var isEnabled

    var body: some View {
        Text(text)
            .foregroundStyle(colour)
            .opacity(isEnabled ? 1 : 0.45)
    }
}

/// One answer to a decision, on its way out of the card.
///
/// Carries `subject` — what the decision resolves, a superpipeline `gate_id` today —
/// because the card is the only place that knows it: the renderer read it out
/// of the payload, and the row above has only an event id.
struct GateAnswer {
    /// superpipeline's only option id that expects a comment.
    static let requestChanges = "request_changes"

    let subject: String
    let optionId: String
    let comment: String?
    let prompt: String
}

#if DEBUG
// The signature element, and the only place amber appears in this app.
#Preview("Pending decision") {
    PreviewGround {
        CustomEventCard(
            view: PreviewFixtures.cardPending, label: "Gate",
            eventType: "dev.superpipeline.gate.v1", senderName: "Superpipeline — Delivery",
            onDecide: { _ in true })
    }
}

// Answered: the amber is gone and the buttons have settled.
//
// The pair is the preview, not either half — the difference between these two
// frames is the entire visual grammar of "this needs you" in the product.
#Preview("Answered") {
    PreviewGround {
        CustomEventCard(
            view: PreviewFixtures.cardAnswered, label: "Gate",
            eventType: "dev.superpipeline.gate.v1", senderName: "Superpipeline — Delivery")
    }
}

#Preview("With reasoning") {
    PreviewGround {
        CustomEventCard(
            view: PreviewFixtures.cardWithReasoning, label: "Turn",
            eventType: "dev.agentpod.turn.v1", senderName: "Atlas — Platform")
    }
}

// A field value that is one 71-character unbroken run.
//
// Every value on a card is arbitrary JSON from anyone who can send to the
// room, so this is the shape that finds a missing wrap guard. The web story
// for this rendered 1147pt wide on its first attempt while claiming to show
// the guard holding — which is worse than having no story at all, and is why
// this one is framed at a phone's width rather than left to fill the canvas.
#Preview("Unbreakable value") {
    PreviewGround(width: 360) {
        CustomEventCard(
            view: PreviewFixtures.cardLongValue, label: "Artifact",
            eventType: "dev.agentpod.artifact.v1", senderName: "Atlas — Platform")
    }
}

// A schema this build is too old to render fully, and one with nothing
// structured left at all.
//
// Both are the core telling the host that the sender knows more about this
// event type than it does. They are previewed together because the question
// is whether the three fallback states are visibly *different* — a host that
// draws them identically has made the fallback chain pointless.
#Preview("Fallback chain") {
    ScrollView {
        PreviewGround {
            VStack(spacing: 16) {
                CustomEventCard(
                    view: PreviewFixtures.cardNewerVersion, label: "Station",
                    eventType: "dev.agentpod.station.v2", senderName: "Atlas — Platform")
                CustomEventCard(
                    view: PreviewFixtures.cardFallback, label: "Station",
                    eventType: "dev.agentpod.station.v2", senderName: "Atlas — Platform")
                CustomEventCard(
                    view: PreviewFixtures.cardPlaceholder, label: "Event",
                    eventType: "dev.agentpod.unknown.v1", senderName: "Atlas — Platform")
            }
        }
    }
}

// A sender-controlled event type carrying a right-to-left override.
//
// `ItemView.customEvent`'s own doc comment is explicit that this string is
// hostile: truncate from the left, never the right, and never render it with
// an RTL base direction, because the obvious approach hands the bidi
// algorithm a crafted string and lets a type reorder itself on screen. This
// is the preview where that would be visible, and it is the reason it exists
// — nothing else in the catalogue would show it.
#Preview("Hostile event type") {
    PreviewGround(width: 360) {
        CustomEventCard(
            view: PreviewFixtures.cardAnswered, label: "Station",
            eventType: PreviewFixtures.hostileEventType, senderName: "Atlas — Platform")
    }
}
#endif
