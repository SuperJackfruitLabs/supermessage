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
/// - **resolved** — the room says the board accepted an answer: the hub's
///   gate outcome is in the timeline and the core handed it over as
///   `outcome`, naming who answered. No controls, and it survives a recycled
///   row or a re-entered room because it comes from the room, not from here.
/// - **answered** — the reader answered on this device and the send landed,
///   but the board has not said so yet. No controls, and says it is waiting.
///   Or the room's own payload says it was decided (a `Decision` field).
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
    /// recycle the row — and does not need to: once superpipeline accepts the
    /// answer the hub says so in the room, and the core hands this card an
    /// `outcome`, which does survive. Until then this is only "answered", and
    /// the card says it is waiting for the board rather than claiming more.
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
        case let .rendered(fields, reasoning, newerVersion, decision, link, outcome):
            rendered(
                fields: fields, reasoning: reasoning, newerVersion: newerVersion,
                decision: decision, link: link, outcome: outcome)

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
        decision: CustomEventDecision?, link: String?, outcome: CustomEventOutcome?
    ) -> some View {
        if let outcome {
            // The room says it is over, and who ended it. First, because it
            // is the strongest fact this card can have: whatever this device
            // remembers answering, the board has the answer.
            receiptCard(
                outcome: outcome.summary, at: nil, headline: outcome.prompt, fields: fields,
                reasoning: reasoning, newerVersion: newerVersion, link: link, awaitingBoard: false)
                .transition(receiptTransition)
        } else if let decision {
            // Pending and its receipt share one slot, so the change between
            // them is a transition rather than one row swapped for another.
            ZStack(alignment: .top) {
                if let answer {
                    receiptCard(
                        outcome: answer.outcome, at: answer.at, headline: decision.prompt,
                        fields: fields, reasoning: reasoning, newerVersion: newerVersion,
                        link: link, awaitingBoard: true)
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
            let said = rest.remove(at: decided).value
            receiptCard(
                outcome: said, at: nil, headline: nil, fields: rest, reasoning: reasoning,
                newerVersion: newerVersion, link: link, awaitingBoard: false)
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

    /// "Asked by Hermes", and the decision's deadline after it when it has
    /// one: "Asked by Hermes · Escalates in 3 h".
    static func askedBy(_ senderName: String, deadline: DecisionDeadline?, now: Date = Date()) -> String {
        let asked = "Asked by \(senderName)"
        guard let deadline,
              let line = SupermessageFFI.decisionDeadlineLine(
                  deadline: deadline, nowMs: Int64((now.timeIntervalSince1970 * 1000).rounded()))
        else { return asked }
        return "\(asked) · \(line)"
    }

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
                // A question.v2 carries its whole question; the prompt is the
                // same sentence cut at 300 characters.
                Text(decision.questionV2?.question ?? decision.prompt)
                    .font(.headline)
                    .foregroundStyle(Theme.content)
                    .fixedSize(horizontal: false, vertical: true)
                // Who is asking. A decision from nobody in particular is one
                // a reader cannot weigh. Then, when the stage has a timeout,
                // when it escalates — worded by the core against this
                // device's clock (`core::decision_timeout::deadline_line`).
                Text(Self.askedBy(senderName, deadline: decision.deadline))
                    .metaFace()
                    .foregroundStyle(Theme.contentMuted)
            }

            summary(digest)
            details(digest.rest)
            if let gate = decision.gateV2 ?? decision.deliveryV2 {
                // What is being approved, exactly — before the buttons that
                // approve it. The core withheld every approving option when
                // this could not be drawn in full, and says so. A delivery
                // follow-up shows the post it is about the same way.
                GatePreviewBlocks(blocks: gate.preview)
                if let withheld = gate.approvalsWithheld {
                    Text(withheld)
                        .font(.footnote)
                        .foregroundStyle(Theme.contentMuted)
                        .fixedSize(horizontal: false, vertical: true)
                        .accessibilityIdentifier("decision-approvals-withheld")
                }
            }
            if let subject = decision.questionV2?.subject ?? decision.permissionV2?.subject {
                // A strict decision's operation, a harness permission's
                // request: drawn verbatim before the buttons, by the same
                // rule as a gate's preview.
                GatePreviewBlocks(blocks: subject.preview)
            }
            if let withheld = decision.questionV2?.approvalsWithheld
                ?? decision.permissionV2?.approvalsWithheld
            {
                Text(withheld)
                    .font(.footnote)
                    .foregroundStyle(Theme.contentMuted)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("decision-approvals-withheld")
            }
            reasoningDisclosure(reasoning)
            linkButton(link)

            DecisionButtons(decision: decision, onDecide: onDecide) { choice in
                // Only reached once the send has landed. See `answer`.
                withAnimation(reduceMotion ? .easeOut(duration: 0.2) : .spring(duration: 0.4, bounce: 0.15)) {
                    answer = DecisionAnswerRecord(
                        option: choice.record, at: .now, role: choice.role,
                        permission: decision.permissionV2 != nil, scope: choice.scope,
                        attention: decision.attentionV2 != nil)
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

    /// `awaitingBoard`: answered on this device, not yet confirmed by the
    /// room. The decision event landing is not the board accepting it — the
    /// two came apart for hours on 2026-09-28 — so this card does not say
    /// more than it knows.
    private func receiptCard(
        outcome: String, at: Date?, headline: String?, fields: [CustomEventField],
        reasoning: String?, newerVersion: Bool, link: String?, awaitingBoard: Bool
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
            if awaitingBoard {
                Text("Waiting for the board to confirm")
                    .metaFace()
                    .foregroundStyle(Theme.contentMuted)
                    .accessibilityIdentifier("decision-awaiting-board")
            }
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
    let onLanded: (DecisionChoice) -> Void

    /// The option awaiting a comment, if one is. A v1 gate's
    /// `request_changes` asks for one and sends without it if left empty; a
    /// gate.v2 option with `needsComment` cannot be sent empty — the core
    /// refuses it too. Request-changes is feedback that becomes the rework's
    /// context — superpipeline merges it into the card's handoff, so an empty
    /// one costs the next agent the reason.
    @State private var commenting: DecisionChoice?
    @State private var comment = ""
    /// The option awaiting its input — a question's typed answer, a
    /// follow-up's live link. Never sent while `inputProblem` says why not.
    @State private var inputting: DecisionChoice?
    @State private var inputText = ""
    /// A free-text question's answer, typed in the card.
    @State private var freeText = ""
    /// The version picked on a gate that offers a choice of them
    /// (`GateV2Decision.variants`, charter 2026-11-09). None until the reader
    /// picks one: an approval names exactly what they chose, never a default.
    @State private var chosenVariant: String?
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

    /// Every answer, as the card draws it. A gate.v2 is drawn from its own
    /// options, by meaning; anything else from the legacy list, by id.
    private var choices: [DecisionChoice] {
        if let gate = decision.gateV2 { return gate.options.map(DecisionChoice.init) }
        if let question = decision.questionV2 { return question.options.map(DecisionChoice.init) }
        if let delivery = decision.deliveryV2 { return delivery.options.map(DecisionChoice.init) }
        if let permission = decision.permissionV2 {
            return permission.options.map(DecisionChoice.init)
        }
        if let attention = decision.attentionV2 {
            return attention.options.map(DecisionChoice.init)
        }
        return decision.options.map(DecisionChoice.init(legacy:))
    }
    /// A gate's choice of versions; empty for any other decision.
    private var variants: [GateVariantV2] { decision.gateV2?.variants ?? [] }
    /// Whether an approving option may be sent: always, unless the gate
    /// offers versions — then only once one the core marked approvable is
    /// picked. Request changes and reject never wait on it. The core refuses
    /// anything else (`gate_v2::decision_request`); this only keeps a button
    /// that would be refused from looking pressable.
    private var approvalReady: Bool {
        guard !variants.isEmpty else { return true }
        return variants.contains { $0.id == chosenVariant && $0.approvable }
    }
    /// Drawn from the decision envelope — by meaning, never by id.
    private var isEnvelope: Bool {
        decision.gateV2 != nil || decision.questionV2 != nil || decision.deliveryV2 != nil
            || decision.permissionV2 != nil || decision.attentionV2 != nil
    }
    /// The main action. A gate.v2 can have more than one — a publishing gate
    /// approves automatically or manually — and each is a main action. A
    /// legacy decision keeps its one: the first answer that is not a
    /// rejection.
    private var primaries: [DecisionChoice] {
        if isEnvelope { return choices.filter { $0.role == .approve } }
        return choices.first { $0.role != .reject }.map { [$0] } ?? []
    }
    private var secondaries: [DecisionChoice] {
        let primaries = primaries
        return choices.filter { $0.role != .reject && !primaries.contains($0) }
    }
    private var rejections: [DecisionChoice] {
        choices.filter { $0.role == .reject }
    }

    var body: some View {
        VStack(spacing: 8) {
            if let free = decision.questionV2?.freeText {
                freeTextBox(maxLength: Int(free.maxLength))
            }
            if !variants.isEmpty {
                // What each version is, exactly, before the buttons that
                // approve one. No editing here: a change is "Request changes".
                VariantPicker(variants: variants, selection: $chosenVariant)
                    .disabled(sending)
            }
            ForEach(Array(primaries.enumerated()), id: \.offset) { _, primary in
                // The label colour is set rather than inherited. `signal-soft`
                // on `signal` is the pair the token contract holds at 6:1 in
                // every appearance, including dark, where `signal` is light
                // amber and a white label would vanish.
                Button { tapped(primary) } label: {
                    OptionLabel(
                        text: primary.label, colour: Theme.signalSoft, caption: primary.qualifier
                    )
                    .font(.headline)
                    .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .tint(Theme.signal)
                .disabled(!answerable || sending || !approvalReady)
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
            commenting?.label ?? "Request changes",
            isPresented: Binding(
                get: { commenting != nil },
                set: { if !$0 { commenting = nil; comment = "" } })
        ) {
            TextField("What needs changing?", text: $comment)
            Button("Cancel", role: .cancel) { commenting = nil; comment = "" }
            Button("Send") {
                if let choice = commenting, !choice.lacksComment(comment) {
                    send(choice, comment: comment)
                }
                commenting = nil
                comment = ""
            }
            // A required comment is required: the core refuses it empty, and a
            // Send that does nothing reads as a failure.
            .disabled(commenting?.lacksComment(comment) ?? false)
        } message: {
            if commenting?.comment == .required {
                Text("This answer needs a comment. It goes back to the agent as the reason.")
            } else {
                Text("This goes back to the agent as the reason, so it can pick the work up again.")
            }
        }
        // On its own view: two alerts on one view do not both present.
        .background {
            Color.clear.alert(
                inputting?.input?.label ?? "",
                isPresented: Binding(
                    get: { inputting != nil },
                    set: { if !$0 { inputting = nil; inputText = "" } })
            ) {
                if inputting?.input?.kind == .url {
                    TextField("https://", text: $inputText)
                        .keyboardType(.URL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                } else {
                    TextField(inputting?.input?.label ?? "", text: $inputText)
                }
                Button("Cancel", role: .cancel) { inputting = nil; inputText = "" }
                Button("Send") {
                    if let choice = inputting, choice.inputProblem(inputText) == nil {
                        send(choice, comment: nil, text: inputText)
                    }
                    inputting = nil
                    inputText = ""
                }
                // The core refuses the same things; a Send that does nothing
                // reads as a failure.
                .disabled(inputting?.inputProblem(inputText) != nil)
            } message: {
                Text(inputting?.inputProblem(inputText) ?? inputting?.label ?? "")
            }
        }
    }

    /// A question with nothing to choose: the answer is typed here.
    @ViewBuilder
    private func freeTextBox(maxLength: Int) -> some View {
        let trimmed = freeText.trimmingCharacters(in: .whitespacesAndNewlines)
        let over = freeText.count > maxLength
        VStack(alignment: .trailing, spacing: 6) {
            TextField("Your answer", text: $freeText, axis: .vertical)
                .lineLimit(2...6)
                .textFieldStyle(.roundedBorder)
                .accessibilityIdentifier("question-free-text")
            HStack {
                Text(over ? "At most \(maxLength) characters" : "\(freeText.count)/\(maxLength)")
                    .metaFace()
                    .foregroundStyle(over ? Theme.danger : Theme.contentMuted)
                Spacer()
                Button {
                    send(DecisionChoice(freeText: trimmed), comment: nil, text: trimmed)
                } label: {
                    OptionLabel(text: "Send answer", colour: Theme.signalSoft)
                        .font(.headline)
                }
                .buttonStyle(.borderedProminent)
                .tint(Theme.signal)
                .disabled(!answerable || sending || trimmed.isEmpty || over)
            }
        }
    }

    @ViewBuilder
    private var secondaryButtons: some View {
        ForEach(Array(secondaries.enumerated()), id: \.offset) { _, option in
            Button { tapped(option) } label: {
                OptionLabel(text: option.label, colour: Theme.content, caption: option.qualifier)
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
                OptionLabel(text: option.label, colour: Theme.danger, caption: option.qualifier)
                    .padding(.horizontal, 12)
                    // The tap target a text button does not get for free.
                    .frame(minHeight: 44)
            }
            .buttonStyle(.borderless)
            .disabled(!answerable || sending)
        }
    }

    private func tapped(_ choice: DecisionChoice) {
        guard answerable else { return }
        if choice.input != nil {
            inputting = choice
        } else if choice.comment != .none {
            commenting = choice
        } else {
            send(choice, comment: nil)
        }
    }

    private func send(_ choice: DecisionChoice, comment: String?, text: String? = nil) {
        guard let onDecide else { return }
        let trimmed = comment?.trimmingCharacters(in: .whitespacesAndNewlines)
        let typed = text?.trimmingCharacters(in: .whitespacesAndNewlines)
        let answer = GateAnswer(
            subject: decision.subject,
            optionId: choice.id,
            comment: (trimmed?.isEmpty ?? true) ? nil : trimmed,
            prompt: decision.prompt,
            gateV2: decision.gateV2,
            questionV2: decision.questionV2,
            deliveryV2: decision.deliveryV2,
            permissionV2: decision.permissionV2,
            attentionV2: decision.attentionV2,
            text: (typed?.isEmpty ?? true) ? nil : typed,
            // Only an approval carries a version; the set is sent back or
            // rejected whole.
            chosenVariant: choice.role == .approve && !variants.isEmpty ? chosenVariant : nil)
        sending = true
        Task {
            let landed = await onDecide(answer)
            sending = false
            // Only on success. See `CustomEventCard.answer`.
            if landed {
                if choice.id.isEmpty { freeText = "" }
                onLanded(choice)
            } else {
                failures += 1
            }
        }
    }
}

/// One answer as the card draws it.
///
/// From a gate.v2 option it is drawn by **meaning** (`GateOptionMeaning`),
/// never by id; from a legacy option by id, exactly as before. Presentation
/// only — which options exist, and which a reader may approve, is the core's.
struct DecisionChoice: Equatable {
    enum CommentAsk: Equatable {
        /// Sent as tapped.
        case none
        /// A v1 `request_changes`: asked for, sent without if left empty.
        case optional
        /// A gate.v2 `needsComment`: never sent empty.
        case required
    }

    let id: String
    let label: String
    let role: DecisionOptionRole
    let comment: CommentAsk
    /// What this option asks for before it is sent — a question's typed
    /// answer, a follow-up's live link. `nil` on a gate and a legacy option.
    let input: OptionInput?
    /// How far the option reaches, when it said — a strict decision's or a
    /// harness permission's option. Shown, never judged: the producer does.
    var scope: OptionScope? = nil
    /// Who may take it, when it said. Shown as `scope` is.
    var requires: OptionRequires? = nil

    init(legacy option: CustomEventDecisionOption) {
        id = option.id
        label = option.label
        role = DecisionOptionRole(option)
        comment = option.id == GateAnswer.requestChanges ? .optional : .none
        input = nil
    }

    init(_ option: GateOptionV2) {
        id = option.id
        label = option.label
        role = DecisionOptionRole(option.meaning)
        comment = option.needsComment ? .required : .none
        input = option.input
        scope = option.scope
        requires = option.requires
    }

    /// A free-text answer: no option, so an empty id, and the text itself as
    /// what the receipt names.
    init(freeText text: String) {
        id = ""
        label = text
        role = .other
        comment = .none
        input = nil
    }

    /// The option's reach as a caption under its label — "Once", "For this
    /// run", "Always", "Admins only" — or `nil` when it said neither.
    var qualifier: String? {
        var parts: [String] = []
        switch scope {
        case .once?: parts.append("Once")
        case .run?: parts.append("For this run")
        case .always?: parts.append("Always")
        case nil: break
        }
        if requires == .workspaceAdmin { parts.append("Admins only") }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }

    /// Why `text` cannot be sent for this option's input, or `nil` when it
    /// can. The core checks the same three things (`checked_input`).
    func inputProblem(_ text: String) -> String? {
        guard let input else { return nil }
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty {
            return input.required ? "\(input.label) is needed." : nil
        }
        if trimmed.count > Int(input.maxLength) {
            return "At most \(input.maxLength) characters."
        }
        if input.kind == .url, !trimmed.lowercased().hasPrefix("https://") || trimmed.count <= 8 {
            return "The link must start with https://."
        }
        return nil
    }

    /// What the receipt names.
    var record: CustomEventDecisionOption { CustomEventDecisionOption(label: label, id: id) }

    /// Whether `text` leaves a required comment unwritten.
    func lacksComment(_ text: String) -> Bool {
        comment == .required && text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }
}

/// A gate.v2 subject's preview — what the reader approves, exactly.
///
/// Every string is drawn with `Text(verbatim:)`, so nothing in a post is
/// read as markdown or a localisation key, and its line breaks stand: the
/// charter's rule is that a person approves bytes they can see, character
/// for character. A link block's URL already passed the core's `safe_url`
/// (`https://`, printable ASCII), so it may be opened; its label is text.
private struct GatePreviewBlocks: View {
    let blocks: [GatePreviewBlock]

    var body: some View {
        if !blocks.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                ForEach(Array(blocks.enumerated()), id: \.offset) { _, block in
                    switch block {
                    case let .text(label, text):
                        VStack(alignment: .leading, spacing: 4) {
                            if let label {
                                Text(verbatim: label)
                                    .metaFace()
                                    .foregroundStyle(Theme.contentMuted)
                            }
                            Text(verbatim: text)
                                .font(Theme.body)
                                .foregroundStyle(Theme.content)
                                .fixedSize(horizontal: false, vertical: true)
                                .textSelection(.enabled)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding(10)
                                .background(
                                    Theme.surfaceSunken,
                                    in: RoundedRectangle(cornerRadius: Metrics.radiusControl))
                                .accessibilityIdentifier("gate-preview-text")
                        }
                    case let .field(label, value):
                        VStack(alignment: .leading, spacing: 2) {
                            Text(verbatim: label)
                                .metaFace()
                                .foregroundStyle(Theme.contentMuted)
                            Text(verbatim: value)
                                .font(.callout)
                                .foregroundStyle(Theme.content)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    case let .link(label, url):
                        if let destination = URL(string: url) {
                            Link(destination: destination) {
                                Label {
                                    Text(verbatim: label)
                                } icon: {
                                    Image(systemName: "arrow.up.forward.square")
                                }
                                .font(.footnote)
                            }
                        } else {
                            Text(verbatim: "\(label): \(url)").font(.footnote)
                        }
                    }
                }
            }
        }
    }
}

/// A gate's choice of versions (charter →
/// `decisions/2026-11-09-a-gate-may-offer-a-choice-of-variants.md`): one
/// section per version — a radio mark, its label and its preview, drawn
/// verbatim as any subject's — and at most one picked. A version the core
/// could not show whole is still shown as far as it goes, says it is chosen
/// on the board, and never enables an approval here.
private struct VariantPicker: View {
    let variants: [GateVariantV2]
    @Binding var selection: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            ForEach(Array(variants.enumerated()), id: \.offset) { _, variant in
                let picked = selection == variant.id
                VStack(alignment: .leading, spacing: 8) {
                    Button {
                        selection = variant.id
                    } label: {
                        HStack(spacing: 8) {
                            Image(systemName: picked ? "largecircle.fill.circle" : "circle")
                                .foregroundStyle(picked ? Theme.content : Theme.contentMuted)
                                .accessibilityHidden(true)
                            Text(verbatim: variant.label)
                                .font(.subheadline.weight(.semibold))
                                .foregroundStyle(Theme.content)
                                .fixedSize(horizontal: false, vertical: true)
                            Spacer(minLength: 0)
                        }
                        // The tap target a text row does not get for free.
                        .frame(minHeight: 44)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(picked ? .isSelected : [])
                    .accessibilityIdentifier("gate-variant-\(variant.id)")
                    GatePreviewBlocks(blocks: variant.subject.preview)
                    if let withheld = variant.withheld {
                        Text(withheld)
                            .font(.footnote)
                            .foregroundStyle(Theme.contentMuted)
                            .fixedSize(horizontal: false, vertical: true)
                            .accessibilityIdentifier("gate-variant-withheld")
                    }
                }
                .padding(10)
                .overlay(
                    RoundedRectangle(cornerRadius: Metrics.radiusControl)
                        .stroke(picked ? Theme.content : Theme.border, lineWidth: 1))
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
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
    /// The option's reach ("Once", "Always · Admins only"), when it said.
    var caption: String? = nil
    @Environment(\.isEnabled) private var isEnabled

    var body: some View {
        // Disabled, a button loses its tint and sits on the system's own
        // disabled fill — where `signal-soft`, chosen for amber, all but
        // vanished. So a disabled label drops to content, dimmed: still
        // legible, plainly not pressable.
        VStack(spacing: 2) {
            Text(text)
            if let caption {
                Text(caption)
                    .font(.caption)
                    .accessibilityLabel(caption)
            }
        }
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
    /// The gate.v2 the card was drawn from, when it was one. Answered with
    /// `Session.answerGateV2`, which hands it back to the core.
    var gateV2: GateV2Decision? = nil
    /// The question.v2 the card was drawn from — answered with
    /// `Session.answerQuestionV2`. An empty `optionId` is a free-text answer.
    var questionV2: QuestionV2Decision? = nil
    /// The delivery.v2 follow-up the card was drawn from — answered with
    /// `Session.answerDeliveryV2`.
    var deliveryV2: GateV2Decision? = nil
    /// The harness permission.v2 the card was drawn from — answered with
    /// `Session.answerPermissionV2`, bound to the request the card showed.
    var permissionV2: PermissionV2Decision? = nil
    /// The card waiting on a person (attention.v2) the card was drawn from —
    /// answered with `Session.answerAttentionV2`; a resume's instruction is
    /// `text`.
    var attentionV2: AttentionV2Decision? = nil
    /// An option's input, or a free-text answer.
    var text: String? = nil
    /// The version picked on a gate.v2 that offers a choice of them, sent with
    /// an approval of it. `nil` on every other answer.
    var chosenVariant: String? = nil
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

// Resolved: the hub said in the room that the board accepted an answer, and
// who gave it — here someone other than the reader. No buttons, no amber, and
// no "waiting": this is the state that survives leaving the room.
#Preview("Resolved in the room") {
    PreviewGround(width: 360) {
        CustomEventCard(
            view: DecisionRevampFixtures.gateResolved, label: "Approval",
            eventType: "dev.superpipeline.gate.v1", senderName: "Superpipeline — Delivery",
            onDecide: { _ in true })
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
