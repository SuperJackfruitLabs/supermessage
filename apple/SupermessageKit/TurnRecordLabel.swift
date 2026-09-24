import Foundation

/// What a finished turn's record says when it is collapsed.
///
/// "Thought for 12s · 2 steps", "Thought for 12s", or "2 steps · 12s" for a
/// turn that only used tools — the shape ChatGPT and Claude use, because the
/// question a reader has about an agent's working is first *whether* it
/// thought, then for how long. The time is the turn's own, from its first
/// event to its last.
public enum TurnRecordLabel {
    public static func text(thought: Bool, steps: Int, seconds: TimeInterval?) -> String {
        let took = seconds.map(duration)
        let stepText = steps == 1 ? "1 step" : "\(steps) steps"
        let thoughtText = "Thought" + (took.map { " for \($0)" } ?? "")
        switch (thought, steps > 0) {
        case (true, true): return "\(thoughtText) · \(stepText)"
        case (true, false): return thoughtText
        default: return [stepText, took].compactMap { $0 }.joined(separator: " · ")
        }
    }

    /// "12s", or "2m 5s" past a minute. Never "0s": a turn that finished
    /// took some time, and zero reads as a fault.
    public static func duration(_ seconds: TimeInterval) -> String {
        let whole = max(1, Int(seconds.rounded()))
        return whole < 60 ? "\(whole)s" : "\(whole / 60)m \(whole % 60)s"
    }
}
