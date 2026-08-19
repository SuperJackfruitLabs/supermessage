import SupermessageKit
import SwiftUI

/// Start a conversation, or join one by address.
struct NewRoomPanel: View {
    let session: Session
    let onOpen: (String) -> Void
    let onClose: () -> Void

    @State private var name = ""
    @State private var invitee = ""
    @State private var alias = ""
    @State private var busy = false
    @State private var failure: String?

    var body: some View {
        NavigationStack {
            Form {
                Section("New room") {
                    TextField("Name", text: $name)
                    TextField("Invite (user id)", text: $invitee)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    Button("Create") { Task { await create() } }
                        .disabled(busy || name.isEmpty)
                }

                Section("Join by address") {
                    TextField("#room:server or !id:server", text: $alias)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    Button("Join") { Task { await join() } }
                        .disabled(busy || alias.isEmpty)
                }

                if let failure {
                    Text(failure).font(Theme.meta).foregroundStyle(Theme.danger)
                }
            }
            .navigationTitle("New conversation")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done", action: onClose) } }
        }
    }

    private func create() async {
        busy = true
        defer { busy = false }
        let invite = invitee.isEmpty ? [] : [invitee]
        switch await session.createRoom(name: name, invite: invite) {
        case let .success(roomId):
            onOpen(roomId)
            onClose()
        case let .failure(message):
            failure = message
        }
    }

    private func join() async {
        busy = true
        defer { busy = false }
        switch await session.joinByAlias(alias) {
        case let .success(roomId):
            onOpen(roomId)
            onClose()
        case let .failure(message):
            failure = message
        }
    }
}
