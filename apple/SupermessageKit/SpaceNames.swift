import Foundation

/// How a space's name reads to a person.
///
/// A provisioned runtime's space is named after its node, and that node is
/// named with an id — sometimes already cut short, `9247e5…` — so the space
/// filter offered a choice called that (2026-09-24). AgentPod should name it
/// properly (agentpod#554). Until it does, an id-shaped name is shown as
/// `Runtime 9247e5`: still the id's distinguishing part, but said to be a
/// runtime rather than left as a string of hex.
///
/// A host's name is kept, minus `.local`: `Rakeshs-MacBook-Pro.local` is the
/// mDNS spelling of a machine the reader knows as `Rakeshs-MacBook-Pro`, and
/// the suffix alone was enough to wrap it onto two lines.
public enum SpaceNames {
    public static func display(_ name: String) -> String {
        var trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        if let id = generatedId(trimmed) { return "Runtime \(id.prefix(6))" }
        if trimmed.lowercased().hasSuffix(".local"), trimmed.count > 6 {
            trimmed.removeLast(6)
        }
        return trimmed
    }

    /// The id, when `name` is one: hex and dashes with at least one digit,
    /// either long enough to be an id (8+) or visibly cut short (a trailing
    /// `…` or `...` after 4+). `cafe-bead` is somebody's joke, not an id.
    static func generatedId(_ name: String) -> String? {
        var core = name
        var truncated = false
        for ellipsis in ["…", "..."] where core.hasSuffix(ellipsis) {
            core.removeLast(ellipsis.count)
            truncated = true
        }
        let isHexish = !core.isEmpty && core.allSatisfy { $0.isHexDigit || $0 == "-" }
            && core.contains(where: \.isNumber)
        guard isHexish, core.count >= (truncated ? 4 : 8) else { return nil }
        return core
    }

    static func looksGenerated(_ name: String) -> Bool { generatedId(name) != nil }
}
