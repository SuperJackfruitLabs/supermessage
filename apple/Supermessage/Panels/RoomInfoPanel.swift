import SupermessageFFI
import SupermessageKit
import SwiftUI

/// Who is in a room, and what it is called.
struct RoomInfoPanel: View {
    let session: Session
    let roomId: String
    let onClose: () -> Void

    @State private var info: RoomInfoDto?
    @State private var failure: String?

    var body: some View {
        NavigationStack {
            Group {
                if let info {
                    List {
                        Section {
                            HStack(spacing: 12) {
                                ZStack {
                                    Circle().fill(.quaternary)
                                    // The initial the core derived from the
                                    // *parsed* name, never the raw string's
                                    // first character — for a structured room
                                    // that is the glyph.
                                    Text(info.identity.initial)
                                }
                                .frame(width: 44, height: 44)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(info.identity.name).font(.headline)
                                    if let role = info.identity.role {
                                        Text(role).metaFace().foregroundStyle(.secondary)
                                    }
                                }
                            }
                            // The runtime, when this room is an agent's — the
                            // thing you open this panel to find. The core has
                            // already read it out of the topic and suppressed
                            // the raw line, so there is nothing to decide here.
                            if let runtime = info.runtime {
                                LabeledContent("Harness", value: runtime.harness)
                                LabeledContent("Machine", value: runtime.host)
                            }
                            if let topic = info.topic, !topic.isEmpty {
                                Text(topic).font(.callout)
                            }
                        }

                        Section("Members (\(info.activeMemberCount))") {
                            ForEach(info.members, id: \.userId) { member in
                                VStack(alignment: .leading, spacing: 1) {
                                    Text(member.displayName ?? member.userId)
                                    if member.displayName != nil {
                                        // One line, truncated in the middle:
                                        // an agent id is `@agent_<host>_<harness>-<name>:<server>`
                                        // and both ends carry meaning, so
                                        // trimming the tail would throw away
                                        // the server and keep the padding.
                                        Text(member.userId)
                                            .metaFace()
                                            .foregroundStyle(.secondary)
                                            .lineLimit(1)
                                            .truncationMode(.middle)
                                    }
                                }
                            }
                        }

                        Section {
                            Button("Leave room", role: .destructive) {
                                Task {
                                    _ = await session.leaveRoom(roomId)
                                    onClose()
                                }
                            }
                        }
                    }
                } else if let failure {
                    ContentUnavailableView(
                        "Couldn't load", systemImage: "exclamationmark.triangle",
                        description: Text(failure))
                } else {
                    ProgressView()
                }
            }
            .navigationTitle("Room info")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done", action: onClose)
                }
            }
        }
        .task(id: roomId) {
            do { info = try await session.roomInfo(roomId) } catch { failure = "\(error)" }
        }
    }
}
