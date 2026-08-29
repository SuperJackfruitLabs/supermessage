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
struct CustomEventCard: View {
    let view: CustomEventView
    /// What this kind of event is called — "Turn", "Permission" — decided by
    /// the renderer that drew the card, not by reading the schema address.
    let label: String
    let eventType: String
    let senderName: String
    /// Answering a decision. `nil` in contexts that only display — a preview,
    /// or a row whose event the homeserver has not acknowledged yet.
    var onDecide: ((GateAnswer) -> Void)?

    var body: some View {
        switch view {
        case let .rendered(fields, reasoning, newerVersion, decision):
            card(
                fields: fields, reasoning: reasoning, newerVersion: newerVersion,
                decision: decision)

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
                .foregroundStyle(.tertiary)
                .frame(maxWidth: .infinity, alignment: .center)
                .padding(.vertical, 6)
        }
    }

    @ViewBuilder
    private func card(
        fields: [CustomEventField], reasoning: String?, newerVersion: Bool,
        decision: CustomEventDecision?
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
                    .foregroundStyle(.secondary)
                    .accessibilityLabel("\(label), \(eventType)")
                Spacer()
                if newerVersion {
                    // Rendered best-effort against a newer minor schema. Said
                    // quietly rather than hidden, so a reader knows there may
                    // be more to this event than is shown.
                    Text("newer version").metaFace().foregroundStyle(.tertiary)
                }
            }

            ForEach(Array(fields.enumerated()), id: \.offset) { _, field in
                HStack(alignment: .firstTextBaseline, spacing: 10) {
                    Text(field.label)
                        .metaFace()
                        .textCase(.uppercase)
                        .foregroundStyle(.secondary)
                        .frame(width: 84, alignment: .leading)
                    Text(field.value).font(.callout)
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
                        .foregroundStyle(.secondary)
                        .textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.top, 2)
                } label: {
                    Text("Reasoning").metaFace().foregroundStyle(.secondary)
                }
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
                .stroke(decision == nil ? Color.secondary.opacity(0.35) : Theme.signal, lineWidth: 1)
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
    var onDecide: ((GateAnswer) -> Void)?

    /// The option awaiting a comment, if one is. Only `request_changes` ever
    /// sets this: approve and reject are decisions, and request-changes is
    /// feedback that becomes the rework's context — kaambaan merges it into
    /// the card's handoff, so an empty one costs the next agent the reason.
    @State private var commenting: CustomEventDecisionOption?
    @State private var comment = ""

    /// Answerable only when the renderer named what this decision resolves and
    /// someone is listening. A button that cannot resolve anything must not
    /// look pressable — the failure would otherwise surface as a tap that did
    /// nothing, by which point the reader believes they have approved.
    private var answerable: Bool { decision.subject != nil && onDecide != nil }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(decision.prompt).font(.system(.callout, weight: .semibold))
            HStack(spacing: 8) {
                ForEach(Array(decision.options.enumerated()), id: \.offset) { index, option in
                    Button(option.label) { tapped(option) }
                        .buttonStyle(.borderedProminent)
                        .tint(index == 0 ? Theme.signal : Color.secondary)
                        .disabled(!answerable)
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
        guard let subject = decision.subject else { return }
        let trimmed = comment?.trimmingCharacters(in: .whitespacesAndNewlines)
        onDecide?(
            GateAnswer(
                subject: subject,
                optionId: option.id,
                comment: (trimmed?.isEmpty ?? true) ? nil : trimmed,
                prompt: decision.prompt))
    }
}

/// One answer to a decision, on its way out of the card.
///
/// Carries `subject` — what the decision resolves, a kaambaan `gate_id` today —
/// because the card is the only place that knows it: the renderer read it out
/// of the payload, and the row above has only an event id.
struct GateAnswer {
    /// kaambaan's only option id that expects a comment.
    static let requestChanges = "request_changes"

    let subject: String
    let optionId: String
    let comment: String?
    let prompt: String
}
