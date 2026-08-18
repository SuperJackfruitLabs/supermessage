import Foundation
import SwiftUI

/// What the probe knows, and how it learned it.
@MainActor
final class ProbeModel: ObservableObject {
    @Published var connection: String = "…"
    @Published var rooms: [RoomSummary] = []
    @Published var eventLog: [String] = []
    @Published var error: String?
    @Published var busy = false

    /// The last sequence number applied, per subject.
    ///
    /// Not decoration: it is how the probe answers the question it exists to
    /// ask. If UniFFI ever delivers a diff out of order, this catches it as a
    /// visible "OUT OF ORDER" line rather than as a subtly wrong room list.
    private var lastSeq: [String: UInt64] = [:]

    private let core: Core

    init() {
        // The app container. The core puts its stores under here and does not
        // look outside it.
        let dir = FileManager.default
            .urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("supermessage", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)

        core = Core(dataDir: dir.path)
        connection = core.connectionState().state
    }

    private func makeSink() -> Sink {
        Sink { [weak self] event in
            Task { @MainActor in self?.apply(event) }
        }
    }

    /// Try to pick up a stored session. `false` means there is none — an
    /// ordinary first-launch outcome, not a failure.
    func restore() {
        run {
            let restored = try self.core.restoreSession(sink: self.makeSink())
            self.note(restored ? "restored a session" : "no stored session")
            if restored { self.loadRooms() }
        }
    }

    func login(homeserver: String, username: String, password: String) {
        run {
            try self.core.login(
                homeserver: homeserver,
                username: username,
                password: password,
                sink: self.makeSink()
            )
            self.note("signed in")
            self.loadRooms()
        }
    }

    func loadRooms() {
        run {
            let snapshot = try self.core.roomsSnapshot()
            self.rooms = snapshot.rooms
            self.note("snapshot: \(snapshot.rooms.count) rooms at seq \(snapshot.seq)")
        }
    }

    /// Runs `work` off the main actor and reports whatever it throws.
    ///
    /// The FFI calls are synchronous and can take seconds — a login is a
    /// network round trip — so calling them on the main actor would freeze the
    /// UI. That is a real finding about this boundary, not an incidental
    /// detail: the Rust side is `async` internally but `Core` blocks on its
    /// own runtime, so every call is blocking from Swift's point of view.
    private func run(_ work: @escaping () throws -> Void) {
        busy = true
        error = nil
        Task.detached { [weak self] in
            do {
                try work()
            } catch {
                await MainActor.run { self?.error = String(describing: error) }
            }
            await MainActor.run { self?.busy = false }
        }
    }

    private func note(_ line: String) {
        eventLog.insert(line, at: 0)
        if eventLog.count > 40 { eventLog.removeLast() }
    }

    private func apply(_ event: FfiEvent) {
        switch event {
        case .connection(let state):
            connection = state.state
            note("connection: \(state.state)")

        case .roomsDiff(let envelope):
            checkOrder(subject: envelope.subject, seq: envelope.seq, label: "rooms")
            note("rooms diff seq=\(envelope.seq) ops=\(envelope.ops.count)")
            loadRooms()

        case .timelineDiff(let envelope):
            checkOrder(subject: envelope.subject, seq: envelope.seq, label: "timeline")
            note("timeline diff seq=\(envelope.seq) ops=\(envelope.ops.count)")

        case .typing(let roomId, let users):
            note("typing in \(roomId): \(users.count)")

        case .live(_, let seq, let text, let done):
            note("live seq=\(seq) done=\(done) \(text.prefix(24))")

        case .thought(_, let seq, _, let done):
            note("thought seq=\(seq) done=\(done)")

        case .tool(_, let seq, _, let title, let status):
            note("tool seq=\(seq) \(status) \(title.prefix(24))")

        case .attachmentStaged(_, let filename, let sizeBytes, _):
            note("staged \(filename) (\(sizeBytes)B)")
        }
    }

    /// The whole point of the probe.
    private func checkOrder(subject: String, seq: UInt64, label: String) {
        let key = "\(label):\(subject)"
        if let previous = lastSeq[key], seq <= previous {
            note("OUT OF ORDER \(key): \(seq) after \(previous)")
        }
        lastSeq[key] = seq
    }
}
