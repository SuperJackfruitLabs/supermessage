#if DEBUG
import SupermessageFFI

// Turns for previews and tests, built the way the core builds them.
//
// A fixture folds `TurnInput`s through the core's own `foldTurn`, so the card
// a preview shows is the one the rules produce, not a second copy of them.
// `#if DEBUG`, so none of it ships (`scripts/tests/test_ios_preview_leak.sh`).

extension TurnStep {
    /// A tool call's step, with the status words the core would give it.
    public static func fixture(
        id: String, title: String, phase: ToolPhase, seq: UInt64, kind: String? = nil,
        locations: [String] = [], input: String? = nil, output: String? = nil
    ) -> TurnStep {
        let words: (status: String, label: String)
        switch phase {
        case .queued: words = ("pending", "Queued")
        case .running: words = ("in_progress", "Running")
        case .done: words = ("completed", "Done")
        case .failed: words = ("failed", "Failed")
        case .unknown: words = ("mystery", "Working")
        }
        return TurnStep(
            id: id, title: title, kind: kind, status: words.status, phase: phase, statusLabel: words.label,
            locations: locations, input: input, output: output, seq: seq,
            hasDetail: input != nil || output != nil || !locations.isEmpty)
    }
}
#endif
