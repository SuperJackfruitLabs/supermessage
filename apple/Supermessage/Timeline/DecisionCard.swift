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
                DecisionButtons(decision: decision)
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

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(decision.prompt).font(.system(.callout, weight: .semibold))
            HStack(spacing: 8) {
                ForEach(Array(decision.options.enumerated()), id: \.offset) { index, option in
                    Button(option.label) {
                        // Deliberately inert in this build, exactly as on the
                        // desktop. Answering means *sending a Matrix event* as
                        // this account — not an HTTP call to a gate — because
                        // the suite's separation-of-duties check refuses a
                        // decision whose author it cannot attribute. Wiring it
                        // to anything else would be the wrong shape, and a
                        // wrong shape here approves things.
                        //
                        // `option.id` is what gets sent, verbatim, and it
                        // carries the option's *name* rather than its
                        // machine id: the room transcript is a shared human
                        // record.
                        _ = option.id
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(index == 0 ? Theme.signal : Color.secondary)
                    .disabled(true)
                }
            }
        }
    }
}
