import SupermessageFFI
import SupermessageKit
import SwiftUI

/// A decision field's label, rendered identically in both arrangements of
/// the row that draws a field.
///
/// `@MainActor` because `metaFace()` is: the type ramp reads the colour
/// scheme, so a face is a main-actor decision like any other view modifier.
@MainActor
@ViewBuilder
private func fieldLabel(_ field: CustomEventField) -> some View {
    Text(field.label)
        .metaFace()
        .foregroundStyle(Theme.contentMuted)
}

/// Field labels whose values are identifiers rather than prose — a branch,
/// a path, a hash — and so read better in the code face.
///
/// Chosen by the renderer's *label*, which the core sets, never by looking
/// at the value: sniffing a value for "looks like a path" is parsing, and
/// the app parses nothing.
private let codeLikeLabels: Set<String> = [
    "branch", "commit", "hash", "sha", "path", "file", "ref", "artifact", "repository",
]

@MainActor
@ViewBuilder
private func fieldValue(_ field: CustomEventField) -> some View {
    if codeLikeLabels.contains(field.label.lowercased()) {
        Text(field.value).font(Theme.code)
    } else {
        Text(field.value).font(.callout)
    }
}

/// A suite event — a card, a turn's activity, or a permission request.
///
/// `view` is the whole fallback-chain decision from
/// `core::custom_events::resolve_custom_event`: this renders its three states
/// and never makes the choice itself. Every field is **text**, bounded and
/// validated on the Rust side before it crossed, and nothing read out of a
/// payload may be rendered as anything but text.
///
/// A rendered event is drawn one of three ways:
///
/// - **pending** — it carries a decision nobody on this device has answered.
///   The only amber in the app.
/// - **receipt** — the decision was answered here and the answer landed, or
///   the room's own payload says it was decided (a `Decision` field). One
///   line saying what happened; no controls.
/// - **plain** — everything else: a turn, an artifact, a station.
struct CustomEventCard: View {
    /// Whether the reader has chosen one of the accessibility text sizes.
    ///
    /// Read rather than guessed at: a fixed-width label column is a promise
    /// about text size, and this is the only thing that can say whether the
    /// promise still holds.
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    let view: CustomEventView
    /// What this kind of event is called — "Turn", "Permission" — decided by
    /// the renderer that drew the card, not by reading the schema address.
    let label: String
    let eventType: String
    let senderName: String
    /// Answering a decision. `nil` in contexts that only display — a preview,
    /// or a row whose event the homeserver has not acknowledged yet.
    var onDecide: ((GateAnswer) async -> Bool)?

    /// The answer this reader gave, once it has actually landed.
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
    ///
    /// Held here rather than on the buttons because the whole card changes
    /// when it is set: the amber goes, not only the controls.
    @State private var answer: DecisionAnswerRecord?

    init(
        view: CustomEventView, label: String, eventType: String, senderName: String,
        onDecide: ((GateAnswer) async -> Bool)? = nil,
        answered: DecisionAnswerRecord? = nil
    ) {
        self.view = view
        self.label = label
        self.eventType = eventType
        self.senderName = senderName
        self.onDecide = onDecide
        // `answered` exists for previews: the receipt is otherwise reachable
        // only through a send that landed.
        _answer = State(initialValue: answered)
    }

    var body: some View {
        switch view {
        case let .rendered(fields, reasoning, newerVersion, decision, link):
            rendered(
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

    // MARK: - Arrangement

    @ViewBuilder
    private func rendered(
        fields: [CustomEventField], reasoning: String?, newerVersion: Bool,
        decision: CustomEventDecision?, link: String?
    ) -> some View {
        if let decision {
            // Pending and its receipt share one slot, so the change between
            // them is a transition rather than one row swapped for another.
            ZStack(alignment: .top) {
                if let answer {
                    receiptCard(
                        outcome: answer.outcome, at: answer.at, headline: decision.prompt,
                        fields: fields, reasoning: reasoning, newerVersion: newerVersion,
                        link: link)
                        .transition(receiptTransition)
                } else {
                    pendingCard(
                        decision: decision, fields: fields, reasoning: reasoning,
                        newerVersion: newerVersion, link: link)
                        .transition(.opacity)
                }
            }
            // `answer` goes from nil to a value exactly once per card — when
            // a send lands — so this is the approve haptic and nothing else.
            .sensoryFeedback(.success, trigger: answer) { old, new in
                old == nil && new != nil
            }
        } else if let decided = fields.firstIndex(where: { $0.label.lowercased() == "decision" }) {
            // The room says it is over. The renderer handed the outcome over
            // as a field; a card with an outcome is a receipt, not a form.
            var rest = fields
            let outcome = rest.remove(at: decided).value
            receiptCard(
                outcome: outcome, at: nil, headline: nil, fields: rest, reasoning: reasoning,
                newerVersion: newerVersion, link: link)
        } else {
            plainCard(fields: fields, reasoning: reasoning, newerVersion: newerVersion, link: link)
        }
    }

    /// Settles rather than pops. Reduce Motion gets the fade alone.
    private var receiptTransition: AnyTransition {
        reduceMotion ? .opacity : .opacity.combined(with: .scale(scale: 0.97, anchor: .top))
    }

    /// What it is, in the words the renderer uses — a reader should not have
    /// to parse `dev.agentpod.turn.v1` to learn they are looking at a turn.
    /// The schema address stays available to accessibility for anyone
    /// debugging a room.
    @ViewBuilder
    private func header(newerVersion: Bool) -> some View {
        HStack {
            Text(label)
                .metaFace()
                .foregroundStyle(Theme.contentMuted)
                .accessibilityLabel("\(label), \(eventType)")
            Spacer()
            if newerVersion {
                // Rendered best-effort against a newer minor schema. Said
                // quietly rather than hidden, so a reader knows there may be
                // more to this event than is shown.
                Text("Newer version").metaFace().foregroundStyle(Theme.contentFaint)
            }
        }
    }

    // MARK: Pending

    private func pendingCard(
        decision: CustomEventDecision, fields: [CustomEventField], reasoning: String?,
        newerVersion: Bool, link: String?
    ) -> some View {
        let digest = FieldDigest(fields: fields, prompt: decision.prompt)
        return VStack(alignment: .leading, spacing: 10) {
            header(newerVersion: newerVersion)

            VStack(alignment: .leading, spacing: 4) {
                // The question itself, first and in plain language, allowed
                // the height to ask it.
                //
                // Nothing in this file sets a `lineLimit` on it, and at
                // `.accessibility3` the prompt once still rendered as `Merge
                // this branch i…` — the layout was giving it one line's height
                // and letting it truncate rather than wrap. `fixedSize
                // (vertical:)` says take the height you need, which for the one
                // sentence a reader has to answer is not negotiable.
                Text(decision.prompt)
                    .font(.headline)
                    .foregroundStyle(Theme.content)
                    .fixedSize(horizontal: false, vertical: true)
                // Who is asking. A decision from nobody in particular is one
                // a reader cannot weigh.
                Text("Asked by \(senderName)")
                    .metaFace()
                    .foregroundStyle(Theme.contentMuted)
            }

            summary(digest)
            details(digest.rest)
            reasoningDisclosure(reasoning)
            linkButton(link)

            DecisionButtons(decision: decision, onDecide: onDecide) { option in
                // Only reached once the send has landed. See `answer`.
                withAnimation(reduceMotion ? .easeOut(duration: 0.2) : .spring(duration: 0.4, bounce: 0.15)) {
                    answer = DecisionAnswerRecord(option: option, at: .now)
                }
            }
            .padding(.top, 2)
        }
        .padding(14)
        // The signature element, and the only amber in the app: the reader
        // owes someone an answer.
        .background(Theme.signal.opacity(0.10), in: RoundedRectangle(cornerRadius: Metrics.radiusCard))
        .overlay(
            RoundedRectangle(cornerRadius: Metrics.radiusCard).stroke(Theme.signal, lineWidth: 1))
        .padding(.vertical, 6)
    }

    // MARK: Receipt

    private func receiptCard(
        outcome: String, at: Date?, headline: String?, fields: [CustomEventField],
        reasoning: String?, newerVersion: Bool, link: String?
    ) -> some View {
        let digest = FieldDigest(fields: fields, prompt: headline ?? "")
        return VStack(alignment: .leading, spacing: 8) {
            header(newerVersion: newerVersion)
            if let headline {
                // Still said, because a receipt that does not say what was
                // decided is a checkmark about nothing — but muted, because
                // it is no longer a question.
                Text(headline)
                    .font(.subheadline)
                    .foregroundStyle(Theme.contentMuted)
                    .fixedSize(horizontal: false, vertical: true)
            }
            DecisionReceiptLine(outcome: outcome, at: at)
            summary(digest)
            details(digest.rest)
            reasoningDisclosure(reasoning)
            linkButton(link)
        }
        .padding(12)
        .overlay(RoundedRectangle(cornerRadius: Metrics.radiusCard).stroke(Theme.border, lineWidth: 1))
        .padding(.vertical, 6)
    }

    // MARK: Plain

    private func plainCard(
        fields: [CustomEventField], reasoning: String?, newerVersion: Bool, link: String?
    ) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            header(newerVersion: newerVersion)
            fieldRows(fields)
            reasoningDisclosure(reasoning)
            linkButton(link)
        }
        .padding(12)
        .overlay(RoundedRectangle(cornerRadius: Metrics.radiusCard).stroke(Theme.border, lineWidth: 1))
        .padding(.vertical, 6)
    }

    // MARK: - Parts

    /// The one line a reader scans before deciding whether to open Details.
    @ViewBuilder
    private func summary(_ digest: FieldDigest) -> some View {
        if let line = digest.summary {
            Text(line)
                .font(.callout)
                .foregroundStyle(Theme.content)
                // One line while it is a glance. At accessibility sizes one
                // line holds two words, which is not a summary.
                .lineLimit(dynamicTypeSize.isAccessibilitySize ? 4 : 1)
                .truncationMode(.middle)
        }
    }

    @ViewBuilder
    private func details(_ fields: [CustomEventField]) -> some View {
        if !fields.isEmpty {
            DisclosureGroup {
                VStack(alignment: .leading, spacing: 6) { fieldRows(fields) }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.top, 4)
            } label: {
                Text("Details").font(.footnote).foregroundStyle(Theme.contentMuted)
            }
            .tint(Theme.contentMuted)
        }
    }

    @ViewBuilder
    private func fieldRows(_ fields: [CustomEventField]) -> some View {
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
            // wraps inside it, so it kept choosing the broken arm. The
            // accessibility boundary is the honest test, and it is Apple's
            // own rather than a number invented for this card.
            if dynamicTypeSize.isAccessibilitySize {
                VStack(alignment: .leading, spacing: 2) {
                    fieldLabel(field)
                    fieldValue(field)
                }
            } else {
                HStack(alignment: .firstTextBaseline, spacing: 10) {
                    fieldLabel(field).frame(width: 84, alignment: .leading)
                    fieldValue(field)
                }
            }
        }
    }

    /// How the agent got here, when it said. Collapsed: it is context, not
    /// the conclusion, and a reader scanning a room wants the conclusion
    /// first.
    ///
    /// This is the reasoning that *lasts*. The live card shows the turn's
    /// reasoning while it is being written and until the next turn replaces
    /// it; what arrives here is a room event, so it is still in place
    /// tomorrow and on every other client.
    @ViewBuilder
    private func reasoningDisclosure(_ reasoning: String?) -> some View {
        if let reasoning {
            DisclosureGroup {
                Text(reasoning)
                    .font(.footnote)
                    .foregroundStyle(Theme.contentMuted)
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.top, 2)
            } label: {
                Text("Reasoning").font(.footnote).foregroundStyle(Theme.contentMuted)
            }
            .tint(Theme.contentMuted)
        }
    }

    /// The one thing on this card that is not text.
    ///
    /// `link` arrived through `core::custom_events::safe_link`, which accepts
    /// `https://` and printable ASCII and nothing else — so this is the only
    /// place a payload may become somewhere a tap can go. Before it existed
    /// the deep link was printed at the reader as characters to retype, which
    /// looks like an affordance and is not.
    ///
    /// "the card", and it really is the card: superpipeline/#47 made cards
    /// addressable. This label said "Open on the board" for a day while
    /// pointing at an address that 404d, then "Open the board" while pointing
    /// at the app root. A label must not promise what the other end cannot
    /// keep — which is why it moved twice rather than once.
    @ViewBuilder
    private func linkButton(_ link: String?) -> some View {
        if let link, let url = URL(string: link) {
            Link(destination: url) {
                Label("Open the card", systemImage: "arrow.up.forward.square")
                    .font(.footnote)
            }
            .padding(.top, 2)
        }
    }
}

/// A card's fields split into the one line worth reading first and the rest.
///
/// Presentation only: which fields exist, their order and their labels are
/// the renderer's. The renderers lead with the field that names the thing —
/// `Card`, `Wants to`, a repository — so the summary is the leading fields'
/// values, skipping any the prompt already says word for word (a permission
/// request's prompt is `Allow {title}?`, and repeating the title beneath it
/// is noise). Nothing is dropped that is not already on screen.
private struct FieldDigest {
    let summary: String?
    let rest: [CustomEventField]

    init(fields: [CustomEventField], prompt: String) {
        let saidAlready: (CustomEventField) -> Bool = { field in
            !field.value.isEmpty && prompt.contains(field.value)
        }
        let candidates = fields.enumerated().filter { !saidAlready($0.element) }
        // Up to two values, the second only when short enough that the pair
        // still reads as one line on a phone.
        var used: [Int] = []
        if let first = candidates.first {
            used.append(first.offset)
            if candidates.count > 1, candidates[1].element.value.count <= 32,
                first.element.value.count <= 40
            {
                used.append(candidates[1].offset)
            }
        }
        summary =
            used.isEmpty ? nil : used.map { fields[$0].value }.joined(separator: " · ")
        rest = fields.enumerated()
            .filter { !used.contains($0.offset) && !saidAlready($0.element) }
            .map(\.element)
    }
}

/// The answers to a pending decision.
///
/// **This is the only view in the app that may use `Theme.signal`.** Amber
/// means one thing: the operator owes someone an answer. Not a severity, not a
/// warning, not an error. If it is on screen anywhere else, that is a review
/// defect — see the console spec and `Theme.signal`'s own note.
///
/// One answer leads, full width, in amber. Every other answer is a bordered
/// secondary; a rejection comes last, as quiet danger-coloured text — present
/// and plainly labelled, but never as loud as the answer that moves work on.
private struct DecisionButtons: View {
    let decision: CustomEventDecision
    var onDecide: ((GateAnswer) async -> Bool)?
    /// Told once, and only once the answer has landed.
    let onLanded: (CustomEventDecisionOption) -> Void

    /// The option awaiting a comment, if one is. Only `request_changes` ever
    /// sets this: approve and reject are decisions, and request-changes is
    /// feedback that becomes the rework's context — superpipeline merges it into
    /// the card's handoff, so an empty one costs the next agent the reason.
    @State private var commenting: CustomEventDecisionOption?
    @State private var comment = ""
    @State private var sending = false
    /// Bumped when a send does not land, which is what the error haptic
    /// watches. A count rather than a flag so two failures in a row are two.
    @State private var failures = 0

    /// Answerable only when the renderer named what this decision resolves and
    /// someone is listening. A button that cannot resolve anything must not
    /// look pressable — the failure would otherwise surface as a tap that did
    /// nothing, by which point the reader believes they have approved.
    /// Answerable when someone is listening. A gate carries a `subject` and
    /// resolves through the structured sender; a permission request has none
    /// and is answered as a plain reply naming the option — the desktop's
    /// `decisionReply.ts` does the same — so it is answerable too. It used to
    /// require a subject, which left every permission request on iOS with
    /// buttons that could never be pressed.
    private var answerable: Bool { onDecide != nil }

    private var primary: CustomEventDecisionOption? {
        decision.options.first { DecisionOptionRole($0) != .reject }
    }
    private var secondaries: [CustomEventDecisionOption] {
        decision.options.filter { DecisionOptionRole($0) != .reject && $0 != primary }
    }
    private var rejections: [CustomEventDecisionOption] {
        decision.options.filter { DecisionOptionRole($0) == .reject }
    }

    var body: some View {
        VStack(spacing: 8) {
            if let primary {
                // The label colour is set rather than inherited. `signal-soft`
                // on `signal` is the pair the token contract holds at 6:1 in
                // every appearance, including dark, where `signal` is light
                // amber and a white label would vanish.
                Button { tapped(primary) } label: {
                    OptionLabel(text: primary.label, colour: Theme.signalSoft)
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .tint(Theme.signal)
                .disabled(!answerable || sending)
            }

            if !secondaries.isEmpty || !rejections.isEmpty {
                // A row while the rest fit, a column when they do not.
                //
                // At `.accessibility3` a row produced `Re-ques t chan ges` over
                // five lines — on the one control in this product that must not
                // be missed. Hyphenating a verb mid-word inside a button is
                // worse than a taller card.
                //
                // `ViewThatFits` rather than a size threshold: it picks the
                // first layout that actually fits, so there is no accessibility
                // step to guess wrong.
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 8) {
                        secondaryButtons
                        rejectButtons
                    }
                    VStack(spacing: 4) {
                        secondaryButtons
                        rejectButtons
                    }
                }
            }
        }
        .sensoryFeedback(.error, trigger: failures)
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

    @ViewBuilder
    private var secondaryButtons: some View {
        ForEach(Array(secondaries.enumerated()), id: \.offset) { _, option in
            Button { tapped(option) } label: {
                OptionLabel(text: option.label, colour: Theme.content)
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .controlSize(.large)
            .tint(Theme.contentMuted)
            .disabled(!answerable || sending)
        }
    }

    @ViewBuilder
    private var rejectButtons: some View {
        ForEach(Array(rejections.enumerated()), id: \.offset) { _, option in
            Button(role: .destructive) { tapped(option) } label: {
                OptionLabel(text: option.label, colour: Theme.danger)
                    .padding(.horizontal, 12)
                    // The tap target a text button does not get for free.
                    .frame(minHeight: 44)
            }
            .buttonStyle(.borderless)
            .disabled(!answerable || sending)
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
        guard let onDecide else { return }
        let trimmed = comment?.trimmingCharacters(in: .whitespacesAndNewlines)
        let answer = GateAnswer(
            subject: decision.subject,
            optionId: option.id,
            comment: (trimmed?.isEmpty ?? true) ? nil : trimmed,
            prompt: decision.prompt)
        sending = true
        Task {
            let landed = await onDecide(answer)
            sending = false
            // Only on success. See `CustomEventCard.answer`.
            if landed { onLanded(option) } else { failures += 1 }
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
        // Disabled, a button loses its tint and sits on the system's own
        // disabled fill — where `signal-soft`, chosen for amber, all but
        // vanished. So a disabled label drops to content, dimmed: still
        // legible, plainly not pressable.
        Text(text)
            .foregroundStyle(isEnabled ? colour : Theme.content)
            .multilineTextAlignment(.center)
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

    /// The gate this resolves, or `nil` for a permission request, which is
    /// answered as a plain reply instead.
    let subject: String?
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

// Answered, as the room reports it: the amber is gone and what is left is a
// receipt rather than a form.
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

// Answered on this device, the moment the send landed.
#Preview("Receipt, just answered") {
    PreviewGround {
        CustomEventCard(
            view: PreviewFixtures.cardPending, label: "Gate",
            eventType: "dev.superpipeline.gate.v1", senderName: "Superpipeline — Delivery",
            onDecide: { _ in true },
            answered: DecisionRevampFixtures.answeredApprove)
    }
}

// Changes requested, a while ago — the relative time past "just now".
#Preview("Receipt, changes requested") {
    PreviewGround(width: 360) {
        CustomEventCard(
            view: PreviewFixtures.cardPending, label: "Gate",
            eventType: "dev.superpipeline.gate.v1", senderName: "Superpipeline — Delivery",
            answered: DecisionRevampFixtures.answeredRequestChanges)
    }
}

// A receipt from the room with more to it than the outcome.
#Preview("Receipt from the room") {
    PreviewGround(width: 360) {
        CustomEventCard(
            view: DecisionRevampFixtures.gateDecided, label: "Approval",
            eventType: "dev.superpipeline.gate.v1", senderName: "Superpipeline — Delivery")
    }
}

#Preview("Receipt, accessibility3") {
    PreviewGround {
        VStack(spacing: 12) {
            CustomEventCard(
                view: PreviewFixtures.cardPending, label: "Gate",
                eventType: "dev.superpipeline.gate.v1", senderName: "Superpipeline — Delivery",
                answered: DecisionRevampFixtures.answeredApprove)
            CustomEventCard(
                view: PreviewFixtures.cardAnswered, label: "Gate",
                eventType: "dev.superpipeline.gate.v1", senderName: "Superpipeline — Delivery")
        }
    }
    .environment(\.dynamicTypeSize, .accessibility3)
}

// A gate with its handoff, a deep link and every detail tucked away.
#Preview("Gate with handoff") {
    PreviewGround(width: 360) {
        CustomEventCard(
            view: DecisionRevampFixtures.gateWithHandoff, label: "Approval",
            eventType: "dev.superpipeline.gate.v1", senderName: "Superpipeline — Delivery",
            onDecide: { _ in true })
    }
}

// A permission request: ids that are the hub's names, not a gate's, and no
// subject — so every answer is drawn and none is pressable.
#Preview("Permission request") {
    PreviewGround(width: 360) {
        CustomEventCard(
            view: DecisionRevampFixtures.permissionPending, label: "Permission",
            eventType: "dev.agentpod.permission.v1", senderName: "Atlas — Platform",
            onDecide: { _ in true })
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
