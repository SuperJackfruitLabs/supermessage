#if DEBUG
import Foundation
import SupermessageFFI
import SupermessageKit

/// Fixtures for the revamp's composer, activity card and acknowledgement
/// states (C1–C4, A2, A3, D12).
///
/// Its own file rather than more of `PreviewFixtures`, which other work edits
/// concurrently. Everything here is fixed — a fixed epoch, a clock that
/// advances only when told — because these previews are snapshotted and a
/// frame that depends on `Date()` is a diff on every run.
enum ComposerRevampFixtures {
    /// 2026-09-12, the same day `PreviewFixtures`' timestamps sit on.
    static let epoch = Date(timeIntervalSince1970: 1_757_700_000)

    static var stagedAudio: StagedFile {
        StagedFile(
            token: "staged-voice", filename: "Voice message.m4a", sizeBytes: 48_128,
            mime: "audio/mp4", width: nil, height: nil)
    }

    static var sendFailure: StagedAttachment.Failure {
        StagedAttachment.Failure(
            filename: "Voice message.m4a", message: "Can't reach the homeserver.")
    }

    /// A clock a fixture moves by hand.
    @MainActor
    final class Clock {
        var now: Date
        init(_ now: Date) { self.now = now }
    }

    /// A turn that has been running for 1m 04s: two steps done, one failed,
    /// one running.
    @MainActor
    static func longTurn(finished: Bool = false) -> LiveStore {
        let clock = Clock(epoch)
        let live = LiveStore(clock: { clock.now })
        let room = PreviewFixtures.roomId
        live.focus(room)
        live.handleThought(
            roomId: room, seq: 1, text: "The failing test is in the token emitter.", done: false)
        clock.now = epoch.addingTimeInterval(9)
        live.handleTool(
            roomId: room, seq: 2, toolCallId: "t1", title: "read scripts/tokens/model.py",
            kind: "read", status: "completed", phase: .done, statusLabel: "Done",
            locations: ["scripts/tokens/model.py"], input: nil, output: nil)
        clock.now = epoch.addingTimeInterval(22)
        live.handleTool(
            roomId: room, seq: 3, toolCallId: "t2", title: "run pytest scripts/tests",
            kind: "execute", status: "completed", phase: .done, statusLabel: "Done",
            locations: [], input: nil, output: "41 passed")
        clock.now = epoch.addingTimeInterval(40)
        live.handleTool(
            roomId: room, seq: 4, toolCallId: "t3", title: "write design/tokens.toml",
            kind: "edit", status: "failed", phase: .failed, statusLabel: "Failed",
            locations: ["design/tokens.toml"], input: nil, output: "permission denied")
        clock.now = epoch.addingTimeInterval(64)
        live.handleTool(
            roomId: room, seq: 5, toolCallId: "t4", title: "regenerate the token targets",
            kind: "execute", status: "in_progress", phase: .running, statusLabel: "Running",
            locations: [], input: nil, output: nil)
        if finished {
            clock.now = epoch.addingTimeInterval(71)
            live.handleTool(
                roomId: room, seq: 6, toolCallId: "t4", title: "regenerate the token targets",
                kind: "execute", status: "completed", phase: .done, statusLabel: "Done",
                locations: [], input: nil, output: nil)
            live.handleLive(roomId: room, seq: 7, text: "", done: true)
        }
        return live
    }
}
#endif
