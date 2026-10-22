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

    /// A turn that has been running for 1m 04s: two steps done, one failed,
    /// one running.
    @MainActor
    static func longTurn(finished: Bool = false) -> LiveStore {
        let live = LiveStore()
        let room = PreviewFixtures.roomId
        live.select(room)
        // The core's own rules fold these into the card, at fixed times.
        let base = UInt64(epoch.timeIntervalSince1970 * 1000)
        func at(_ seconds: UInt64) -> UInt64 { base + seconds * 1000 }
        let regenerate = TurnStep.fixture(
            id: "t4", title: "regenerate the token targets", phase: .running, seq: 5,
            kind: "execute")
        var inputs: [TurnInput] = [
            .thought(
                seq: 1, text: "The failing test is in the token emitter.", done: false, atMs: at(0)),
            .tool(
                step: .fixture(
                    id: "t1", title: "read scripts/tokens/model.py", phase: .done, seq: 2,
                    kind: "read", locations: ["scripts/tokens/model.py"]),
                atMs: at(9)),
            .tool(
                step: .fixture(
                    id: "t2", title: "run pytest scripts/tests", phase: .done, seq: 3,
                    kind: "execute", output: "41 passed"),
                atMs: at(22)),
            .tool(
                step: .fixture(
                    id: "t3", title: "write design/tokens.toml", phase: .failed, seq: 4,
                    kind: "edit", locations: ["design/tokens.toml"], output: "permission denied"),
                atMs: at(40)),
            .tool(step: regenerate, atMs: at(64)),
        ]
        if finished {
            inputs.append(
                .tool(
                    step: .fixture(
                        id: "t4", title: "regenerate the token targets", phase: .done, seq: 6,
                        kind: "execute"),
                    atMs: at(71)))
            inputs.append(.answer(seq: 7, done: true, atMs: at(71)))
        }
        live.restore(foldTurn(roomId: room, inputs: inputs))
        return live
    }
}
#endif
