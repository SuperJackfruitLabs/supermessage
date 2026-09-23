import Foundation

/// How a space's name reads to a person.
///
/// A provisioned runtime's space is named after its node, and that node is
/// named with an id, so the space filter offered a choice called `9247e5…`
/// (2026-09-24). AgentPod should name it properly (agentpod#554). Until it
/// does, an id-shaped name is shown as `Runtime 9247e5`: still the id's
/// distinguishing part, but said to be a runtime rather than left as a
/// string of hex.
public enum SpaceNames {
    public static func display(_ name: String) -> String {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard looksGenerated(trimmed) else { return trimmed }
        return "Runtime \(trimmed.prefix(6))"
    }

    /// Hex and dashes only, long enough to be an id rather than a word, and
    /// with at least one digit — `cafe-bead` is somebody's joke, not an id.
    static func looksGenerated(_ name: String) -> Bool {
        name.count >= 8
            && name.allSatisfy { $0.isHexDigit || $0 == "-" }
            && name.contains(where: \.isNumber)
    }
}
