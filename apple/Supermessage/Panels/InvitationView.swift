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
                        .foregroundStyle(.secondary)
                }
            }

            if let failure {
                Text(failure).metaFace().foregroundStyle(Theme.danger)
            }

            HStack(spacing: 12) {
                Button("Decline") { Task { await respond(accept: false) } }
                    .buttonStyle(.bordered)
                Button("Accept") { Task { await respond(accept: true) } }
                    .buttonStyle(.borderedProminent)
                    .tint(Theme.accent)
            }
            .disabled(busy)
        }
        .padding(20)
        .frame(maxWidth: .infinity)
        .background(.bar)
        .task(id: roomId) { await loadInviter() }
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
