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
}
