import SupermessageKit
import SwiftUI

/// What an invited room shows in place of a composer.
///
/// Chosen by `row.affordance == .respondToInvitation`, which is the core's
/// decision about this membership — not a membership check written again here.
/// Offering a composer instead would produce a send that fails at the
/// homeserver, which reads as the app being broken.
struct InvitationView: View {
    let session: Session
    let roomId: String
    let roomName: String

    @State private var inviter: String?
    @State private var busy = false
    @State private var failure: String?

    init(session: Session, roomId: String, roomName: String) {
        self.session = session
        self.roomId = roomId
        self.roomName = roomName
    }

    #if DEBUG
    /// The invitation with its inviter already known.
    ///
    /// This frame was **not** on the unstable list, and it should have been:
    /// it loads the inviter in a `.task` exactly like the panels that were.
    /// It surfaced only when the seven known ones were fixed and the same
    /// five-render check was run again, disagreeing by 60,654 pixels — the
    /// whole card reflowing around a line of text that had or had not
    /// arrived.
    ///
    /// Worth saying plainly: the exclusion list was **evidence of what had
    /// been caught, not of what was flaky**, and a frame it missed was being
    /// gated against a baseline it could flip away from at any time.
    init(session: Session, roomId: String, roomName: String, inviter: String?) {
        self.session = session
        self.roomId = roomId
        self.roomName = roomName
        _inviter = State(initialValue: inviter)
    }
    #endif

    var body: some View {
        VStack(spacing: 12) {
            VStack(spacing: 3) {
                Text("You have been invited to \(roomName).")
                    .font(Theme.body)
                    .multilineTextAlignment(.center)
                // By whom — the thing you would want before accepting, and
                // the one thing this screen did not say.
                if let inviter {
                    Text("from \(inviter)")
                        .metaFace()
                        .foregroundStyle(Theme.contentMuted)
                }
            }

            if let failure {
                Text(failure).metaFace().foregroundStyle(Theme.danger)
            }

            HStack(spacing: 12) {
                Button("Decline") { Task { await respond(accept: false) } }
                    .buttonStyle(.bordered)
                Button { Task { await respond(accept: true) } } label: {
                    Text("Accept").foregroundStyle(Theme.accentContent)
                }
                    .buttonStyle(.borderedProminent)
                    .tint(Theme.accent)
            }
            .disabled(busy)
        }
        .padding(20)
        .frame(maxWidth: .infinity)
        .background(Theme.surfaceSunken)
        .task(id: roomId) { if inviter == nil { await loadInviter() } }
    }

    /// Asked once, for the one invitation on screen — see
    /// `Session::room_inviter` for why this is not carried on every roster row.
    private func loadInviter() async {
        inviter = await session.inviter(of: roomId)
    }

    private func respond(accept: Bool) async {
        busy = true
        defer { busy = false }
        failure = accept
            ? await session.joinRoom(roomId)
            : await session.leaveRoom(roomId)
    }
}

/// What the timeline shows for an invitation, in place of history.
///
/// An invited room has no readable history — membership is `invite`, so the
/// homeserver sends state and nothing else — and the one event that does come
/// through renders as "… created the room", which reads like a broken room
/// rather than an unopened one. Saying so plainly is the whole fix.
struct InvitationEmptyTimeline: View {
    var body: some View {
        ContentUnavailableView(
            "Not joined yet", systemImage: "envelope",
            description: Text("Accept the invitation to see this room's messages."))
    }
}

#if DEBUG
// An invitation, with its inviter resolved.
//
// The stub answers `@krishna:example.org` for `roomInviter`, which is the
// interesting case rather than a display name: an invitation from someone
// whose profile this account has never seen is the common one, and the raw id
// is what the reader is asked to make a decision about.
#Preview("Invitation") {
    InvitationView(
        session: PreviewFixtures.session(), roomId: "!estate:example.org",
        roomName: "Estate Planning", inviter: "Krishna")
        .previewChrome()
}

// The empty timeline behind an invitation — a room the reader cannot read yet.
#Preview("Empty timeline") {
    InvitationEmptyTimeline()
        .previewChrome()
}
#endif
