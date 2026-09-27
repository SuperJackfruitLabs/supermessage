import SupermessageFFI
import SupermessageKit
import SwiftUI

/// Report a message, a room, or someone — and, in the same step, block them.
///
/// App Store Review Guideline 1.2 asks for exactly this pair (issue #60). The
/// report goes to the administrators of the homeserver, never to the person
/// reported, and the sheet says so before anything is typed: a reader deciding
/// whether to report someone is deciding whether that person will find out.
///
/// ## What happens on Send
///
/// A confirmation, then `Session.report`: the report first, and only when the
/// homeserver has it the message is hidden here and — if the toggle is on —
/// the sender blocked. A failure leaves the sheet open with the reason still
/// in the field and the refusal under it; nothing is hidden and nobody is
/// blocked on the strength of a report that did not go.
///
/// The reason's limit is the core's (`reportReasonRemaining`), counted the
/// same way the check that would refuse it counts, so the counter and the
/// refusal cannot disagree about where the line is.
struct ReportSheet: View {
    let session: Session
    let subject: ReportSubject
    let onClose: () -> Void

    @State private var reason = ""
    @State private var alsoBlock: Bool
    @State private var confirming = false
    @State private var sending = false
    @State private var failure: String?
    @FocusState private var editing: Bool

    init(session: Session, subject: ReportSubject, onClose: @escaping () -> Void) {
        self.session = session
        self.subject = subject
        self.onClose = onClose
        // On by default wherever there is someone to block: a reader
        // reporting what someone said almost always also wants to stop hearing
        // from them, and a second trip to the member list to do it is the kind
        // of friction that leaves an abusive user unblocked. Someone already
        // blocked gets no toggle at all (see `body`), and `Session.report`
        // skips the block for them either way.
        _alsoBlock = State(initialValue: subject.blockable != nil)
    }

    #if DEBUG
    /// The sheet with a reason already typed, for previews.
    init(
        session: Session, subject: ReportSubject, reason: String, failure: String? = nil,
        onClose: @escaping () -> Void
    ) {
        self.init(session: session, subject: subject, onClose: onClose)
        _reason = State(initialValue: reason)
        _failure = State(initialValue: failure)
    }
    #endif

    private var remaining: Int64 { reportReasonRemaining(reason: reason) }
    private var tooLong: Bool { remaining < 0 }

    var body: some View {
        NavigationStack {
            Form {
                Group {
                    Section {
                        Text(explanation)
                            .font(.callout)
                            .foregroundStyle(Theme.contentMuted)
                            .fixedSize(horizontal: false, vertical: true)
                    }

                    Section {
                        TextField("What's wrong? (optional)", text: $reason, axis: .vertical)
                            .lineLimit(4...10)
                            .focused($editing)
                            .accessibilityLabel("Reason for the report")
                            .accessibilityHint("Optional. Seen only by the homeserver's administrators.")
                    } header: {
                        Text("Reason")
                    } footer: {
                        // Only once it matters: a counter from the first key
                        // is noise, and 2000 characters is a lot of reason.
                        if remaining < 200 {
                            Text(
                                tooLong
                                    ? "\(-remaining) characters over the limit"
                                    : "\(remaining) characters left"
                            )
                            .foregroundStyle(tooLong ? Theme.danger : Theme.contentMuted)
                            .monospacedDigit()
                        }
                    }

                    if let target = subject.blockable {
                        Section {
                            if session.safety.isBlocked(target.userId) {
                                Label("\(target.name) is blocked", systemImage: "hand.raised")
                                    .foregroundStyle(Theme.contentMuted)
                            } else {
                                Toggle("Also block \(target.name)", isOn: $alsoBlock)
                            }
                        } footer: {
                            Text(Self.blockConsequence(isAgent: target.isAgent))
                        }
                    }

                    if let failure {
                        Section {
                            Label(failure, systemImage: "exclamationmark.triangle")
                                .foregroundStyle(Theme.danger)
                                .font(.callout)
                        }
                    }
                }
                .listRowBackground(Theme.surface)
            }
            .paletteGroupedGround()
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel", action: onClose)
                }
                ToolbarItem(placement: .confirmationAction) {
                    if sending {
                        ProgressView()
                    } else {
                        Button("Send") {
                            editing = false
                            confirming = true
                        }
                        .disabled(tooLong)
                        .accessibilityHint("Asks before sending")
                    }
                }
            }
            .confirmationDialog(
                confirmationTitle, isPresented: $confirming, titleVisibility: .visible
            ) {
                Button(sendLabel, role: .destructive) {
                    Task { await send() }
                }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("The homeserver's administrators will see it. \(subjectName) won't be told.")
            }
            .interactiveDismissDisabled(sending)
            // Warning when the report did not land, so a refusal is felt as
            // well as read. Success is fired in `send`, because the sheet is
            // gone by the time a SwiftUI trigger would be read.
            .sensoryFeedback(.warning, trigger: failure) { _, now in now != nil }
        }
        .paletteSheet()
    }

    private func send() async {
        sending = true
        failure = nil
        let refused = await session.report(
            subject, reason: reason, alsoBlock: alsoBlock && subject.blockable != nil)
        sending = false
        if let refused {
            failure = refused
        } else {
            // Success (M2): the report landed.
            UINotificationFeedbackGenerator().notificationOccurred(.success)
            onClose()
        }
    }

    // MARK: - Words

    /// What blocking does, said differently for an agent: its messages stop,
    /// its work does not — a reader must not believe blocking an agent stopped
    /// a task it is running for someone else.
    static func blockConsequence(isAgent: Bool) -> String {
        isAgent
            ? "Its messages are hidden everywhere, on all your devices. The agent keeps running and isn't told."
            : "You won't see their messages in any room, on any of your devices. They aren't told."
    }

    private var title: String {
        switch subject {
        case .message: "Report message"
        case .room: "Report room"
        case .user: "Report"
        }
    }

    private var subjectName: String {
        switch subject {
        case let .message(_, _, _, senderName, _): senderName
        case let .room(_, name): name
        case let .user(_, name, _): name
        }
    }

    /// Who receives the report, and — first — who does not.
    ///
    /// No promise about response time here: the report goes to whichever
    /// homeserver the reader signed in to, and only ours is ours to promise
    /// for. `supermessage.dev/support` states the 24-hour commitment for it.
    private var explanation: String {
        switch subject {
        case let .message(_, _, _, senderName, _):
            "Your report goes to your homeserver's administrators, not to \(senderName). The message is hidden from you once the report is sent."
        case let .room(_, name):
            "Your report about \(name) goes to your homeserver's administrators, not to the people in it."
        case let .user(_, name, isAgent):
            "Your report goes to your homeserver's administrators, not to \(name)."
                + (isAgent ? " Reporting an agent doesn't stop it running." : "")
        }
    }

    private var confirmationTitle: String {
        switch subject {
        case .message: "Report this message?"
        case let .room(_, name): "Report \(name)?"
        case let .user(_, name, _): "Report \(name)?"
        }
    }

    private var sendLabel: String {
        alsoBlock && subject.blockable != nil ? "Report and block" : "Report"
    }
}

#if DEBUG
// A message from an agent, reported with a reason and the block toggle on —
// the agent wording ("keeps running") is the part worth looking at.
#Preview("Report a message") {
    ReportSheet(
        session: PreviewFixtures.session(),
        subject: PreviewFixtures.reportedMessage,
        reason: "Posted a customer's phone number in a shared room.",
        onClose: {}
    )
    .previewChrome()
}

// Reporting a person, and a homeserver that refused: the reason stays in the
// field and the refusal is said under it.
#Preview("Report a person, refused") {
    ReportSheet(
        session: PreviewFixtures.session(),
        subject: .user(userId: "@krishna:example.org", name: "Krishna", isAgent: false),
        reason: "Repeated unwanted messages after I asked them to stop.",
        failure: "This homeserver doesn't accept person reports. Nothing was sent.",
        onClose: {}
    )
    .previewChrome()
}

// A room has no one to block, so there is no toggle.
#Preview("Report a room") {
    ReportSheet(
        session: PreviewFixtures.session(),
        subject: .room(roomId: "!estate:example.org", name: "Estate Planning"),
        reason: "",
        onClose: {}
    )
    .previewChrome()
}
#endif
