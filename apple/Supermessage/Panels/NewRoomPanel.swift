import SupermessageFFI
import SupermessageKit
import SwiftUI

/// Start a conversation.
///
/// The version this replaced asked for a raw `@someone:server`, offered the
/// wire format as a placeholder, and had no way at all to start a conversation
/// with an agent — on an app whose whole purpose is talking to agents. The
/// people this account already talks to are now the screen, and typing an
/// address is what you fall back to.
struct NewRoomPanel: View {
    let session: Session
    let onOpen: (String) -> Void
    let onClose: () -> Void

    @State private var people: [PersonDto] = []
    @State private var loading = true
    @State private var query = ""
    @State private var busyWith: String?
    @State private var failure: String?
    @State private var showsAddress = false

    private var matches: [PersonDto] {
        peopleMatching(people: people, query: query)
    }

    var body: some View {
        NavigationStack {
            List {
                if let failure {
                    Section {
                        Text(failure).metaFace().foregroundStyle(Theme.danger)
                    }
                }

                if loading {
                    Section { ProgressRow(label: "Looking for who you know") }
                } else if matches.isEmpty {
                    Section {
                        ContentUnavailableView(
                            query.isEmpty ? "Nobody yet" : "No one matching \(query)",
                            systemImage: "person.2",
                            description: Text(
                                query.isEmpty
                                    ? "Agents and people you share a room with appear here."
                                    : "Try a name, a machine, or a full address."))
                    }
                } else {
                    Section(query.isEmpty ? "Who you know" : "Matches") {
                        ForEach(matches, id: \.userId) { person in
                            Button { Task { await open(person) } } label: {
                                PersonRow(person: person, busy: busyWith == person.userId)
                            }
                            .buttonStyle(.plain)
                            .disabled(busyWith != nil)
                        }
                    }
                }

                Section {
                    // A row that opens a screen, not a form squeezed into a
                    // list: joining by address is the rare path, and giving it
                    // equal weight was half of why this screen read as a
                    // settings page rather than a way to start talking.
                    Button { showsAddress = true } label: {
                        Label("Join by address", systemImage: "number")
                    }
                }
            }
            .navigationTitle("New conversation")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) { Button("Cancel", action: onClose) }
            }
        }
        .searchable(text: $query, prompt: "Name, machine, or @user:server")
        .task { await load() }
        .sheet(isPresented: $showsAddress) {
            JoinByAddress(session: session, onOpen: onOpen, onDone: { showsAddress = false })
                .presentationDetents([.medium])
        }
    }

    private func load() async {
        people = await session.people()
        loading = false
    }

    private func open(_ person: PersonDto) async {
        busyWith = person.userId
        defer { busyWith = nil }
        switch await session.openConversation(with: person) {
        case let .success(roomId):
            onOpen(roomId)
            onClose()
        case let .failure(message):
            failure = message
        }
    }
}

/// One row of the directory: who they are, and where they run.
private struct PersonRow: View {
    let person: PersonDto
    let busy: Bool

    var body: some View {
        HStack(spacing: 10) {
            ZStack {
                Circle().fill(.quaternary)
                Image(systemName: person.runtime == nil ? "person.fill" : "cpu")
                    .imageScale(.small)
                    .foregroundStyle(.secondary)
            }
            .frame(width: 30, height: 30)

            VStack(alignment: .leading, spacing: 1) {
                Text(person.name).foregroundStyle(.primary)
                // The runtime where there is one, the address where there is
                // not. Both answer "which one is this" — an agent by the
                // machine it runs on, a person by where their account lives.
                Text(subtitle)
                    .metaFace()
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .truncationMode(.middle)
            }

            Spacer(minLength: 8)

            // Busy on the row that is busy, not a spinner over the whole
            // screen: a slow homeserver should not make the other rows look
            // broken.
            if busy {
                ProgressView()
            } else {
                Image(systemName: "chevron.right")
                    .imageScale(.small)
                    .foregroundStyle(.tertiary)
            }
        }
        .contentShape(Rectangle())
    }

    private var subtitle: String {
        guard let runtime = person.runtime else { return person.userId }
        return "\(runtime.harness) on \(runtime.host)"
    }
}

/// Join a room you already know the address of.
///
/// Its own sheet because it is the rare path. The placeholder is an example
/// rather than the grammar — `#room:server or !id:server` is the wire format,
/// which tells a reader who already knows nothing.
private struct JoinByAddress: View {
    let session: Session
    let onOpen: (String) -> Void
    let onDone: () -> Void

    @State private var address = ""
    @State private var busy = false
    @State private var failure: String?
    @FocusState private var focused: Bool

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("#general:supermessage.dev", text: $address)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .focused($focused)
                        .onSubmit { Task { await join() } }
                } header: {
                    Text("Room address")
                } footer: {
                    Text("An alias like #general:supermessage.dev, or a room id starting with !.")
                }

                if let failure {
                    Text(failure).metaFace().foregroundStyle(Theme.danger)
                }

                Section {
                    Button {
                        Task { await join() }
                    } label: {
                        HStack {
                            Text("Join")
                            Spacer()
                            // A busy state on the action itself. Without one,
                            // a slow homeserver is indistinguishable from a
                            // dead button.
                            if busy { ProgressView() }
                        }
                    }
                    .disabled(busy || address.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
            .navigationTitle("Join a room")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) { Button("Cancel", action: onDone) }
            }
            .task { focused = true }
        }
    }

    private func join() async {
        let trimmed = address.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        busy = true
        defer { busy = false }
        switch await session.joinByAlias(trimmed) {
        case let .success(roomId):
            onOpen(roomId)
            onDone()
        case let .failure(message):
            failure = message
        }
    }
}

/// A list row that is working on something.
private struct ProgressRow: View {
    let label: String

    var body: some View {
        HStack(spacing: 10) {
            ProgressView()
            Text(label).metaFace().foregroundStyle(.secondary)
        }
    }
}
