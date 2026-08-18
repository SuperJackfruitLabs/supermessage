import SwiftUI

/// Deliberately unstyled. If this starts looking like an app, it has outlived
/// its purpose — see `apple/project.yml`.
struct ProbeView: View {
    @ObservedObject var model: ProbeModel

    @State private var homeserver = "https://id.agentpod.dev"
    @State private var username = ""
    @State private var password = ""

    var body: some View {
        NavigationStack {
            List {
                Section("connection") {
                    Text(model.connection)
                    if model.busy { Text("working…").foregroundStyle(.secondary) }
                    if let error = model.error {
                        Text(error).foregroundStyle(.red).font(.caption)
                    }
                }

                Section("sign in") {
                    TextField("homeserver", text: $homeserver)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    TextField("username", text: $username)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    SecureField("password", text: $password)
                    Button("sign in") {
                        model.login(
                            homeserver: homeserver,
                            username: username,
                            password: password
                        )
                    }
                    .disabled(model.busy || username.isEmpty || password.isEmpty)
                }

                Section("rooms (\(model.rooms.count))") {
                    ForEach(model.rooms, id: \.id) { room in
                        VStack(alignment: .leading, spacing: 2) {
                            Text(room.name)
                            if let last = room.lastMessage {
                                Text(last)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                    .lineLimit(1)
                            }
                        }
                    }
                }

                // The reason the probe exists: a live view of what crosses the
                // boundary, including any "OUT OF ORDER" the model detects.
                Section("events") {
                    ForEach(Array(model.eventLog.enumerated()), id: \.offset) { _, line in
                        Text(line)
                            .font(.system(.caption, design: .monospaced))
                            .foregroundStyle(line.hasPrefix("OUT OF ORDER") ? .red : .primary)
                    }
                }
            }
            .navigationTitle("SM Probe")
            .task { model.restore() }
        }
    }
}
