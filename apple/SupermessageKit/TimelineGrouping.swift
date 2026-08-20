import Foundation
import SupermessageFFI

/// Whether a row continues the run above it.
///
/// Written natively rather than ported: grouping thresholds are presentation,
/// and a phone may legitimately differ from a workstation. What is *not*
/// negotiable is the shape of the rule, which is the desktop's — a run breaks
/// on a different sender, on a gap, and on anything that is not an ordinary
/// message.
public enum TimelineGrouping {
    /// How close two messages from one sender must be to read as one turn.
    public static let runWindowMs: UInt64 = 5 * 60 * 1000

    /// Whether `row` should drop its header because the row above it already
    /// carries one.
    public static func continuesRun(_ row: TimelineRow, after previous: TimelineRow?) -> Bool {
        guard let previous else { return false }
        // Only ordinary messages group. A system line, a card or an image
        // carries its own header and ends the run above it — otherwise a
        // message after a card would look like the card's author said it.
        guard isGroupable(row), isGroupable(previous) else { return false }
        guard row.item.sender == previous.item.sender else { return false }
        guard row.item.isOwn == previous.item.isOwn else { return false }
        guard let now = row.item.timestampMs, let then = previous.item.timestampMs else {
            return false
        }
        return now >= then && now - then <= runWindowMs
    }

    private static func isGroupable(_ row: TimelineRow) -> Bool {
        if case .bubble = row.view { return true }
        return false
    }

    /// Whether one agent does all the talking here.
    ///
    /// A room with a single speaker repeats `(OpenClaw on Ashram)` under every
    /// message, where it never changes; a room with several needs it to tell
    /// them apart. Counts *peers* — your own messages are attributed by
    /// position rather than by name, so they say nothing about this.
    ///
    /// Stops at two: the answer cannot change after that, and this runs over
    /// every row on every update.
    public static func hasSingleSpeaker(_ rows: [TimelineRow]) -> Bool {
        var seen = Set<String>()
        for row in rows where !row.item.isOwn {
            guard let sender = row.item.sender else { continue }
            seen.insert(sender)
            if seen.count > 1 { return false }
        }
        return true
    }

    /// How many people a grouped membership line names before it counts.
    ///
    /// Matches the desktop's `MAX_NAMED`. Two is enough to recognise a run and
    /// short enough that the sentence stays one line.
    static let maxNamed = 2

    /// Collapse consecutive membership changes that share a verb.
    ///
    /// Ported from the desktop's `groupTimelineItems`, which iOS never had —
    /// so a room drew every single one, ten identical "updated their
    /// membership" lines deep in Ganesha's history.
    ///
    /// Runs break on a **different verb**, so "three joined" and "one left"
    /// stay two sentences rather than becoming one that is true of neither.
    /// A run of exactly one reads exactly like the ungrouped line the core
    /// already composes, never "Alice and 0 others".
    public static func collapseMembershipRuns(_ rows: [TimelineRow]) -> [DisplayRow] {
        var out: [DisplayRow] = []
        var run: [TimelineRow] = []

        // `ItemView.none` is the core saying "draw nothing". A row for it is
        // still a row: a cell with no content does not reliably collapse to no
        // height, and one turned up on screen as roughly three hundred points
        // of blank in the middle of two different rooms. Deliberately silent
        // should mean *absent*, not empty.
        let rows = rows.filter { row in
            if case .none = row.view { return false }
            return true
        }

        func flush() {
            guard let first = run.first else { return }
            out.append(
                .membershipRun(id: "group:\(first.item.id)", text: text(for: run), rows: run))
            run = []
        }

        for row in rows {
            guard row.item.kind == "membership" else {
                flush()
                out.append(.row(row))
                continue
            }
            if let first = run.first, first.item.detail != row.item.detail {
                flush()
            }
            run.append(row)
        }
        flush()
        return out
    }

    /// The sentence for one run.
    ///
    /// Both halves come from the core: the verb is carried on the row *apart*
    /// from the rendered sentence precisely so a run can be composed from many
    /// names and one verb without parsing that sentence back apart.
    static func text(for run: [TimelineRow]) -> String {
        let verb = run.first?.membershipVerb ?? "updated their membership"
        let names = run.map(\.senderShort)
        if names.count <= maxNamed {
            return "\(joined(names)) \(verb)"
        }
        let named = names.prefix(maxNamed).joined(separator: ", ")
        let remaining = names.count - maxNamed
        return "\(named) and \(remaining) \(remaining == 1 ? "other" : "others") \(verb)"
    }

    private static func joined(_ names: [String]) -> String {
        switch names.count {
        case 0: return "Someone"
        case 1: return names[0]
        default: return "\(names.dropLast().joined(separator: ", ")) and \(names.last!)"
        }
    }
}

/// A row as the timeline draws it: one item, or a collapsed run of membership
/// changes that would otherwise be a wall of near-identical lines.
public enum DisplayRow: Identifiable, Sendable {
    case row(TimelineRow)
    case membershipRun(id: String, text: String, rows: [TimelineRow])

    public var id: String {
        switch self {
        case let .row(row): return row.item.id
        case let .membershipRun(id, _, _): return id
        }
    }
}
