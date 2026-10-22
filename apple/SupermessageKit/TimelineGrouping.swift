import Foundation
import SupermessageFFI

/// How the timeline draws the core's grouping.
///
/// The rules themselves (sender runs, reply quotes, runtime suffixes,
/// membership runs and stretches) are the core's, decided once in
/// `timeline_grouping.rs` and carried on every `TimelineRow`. This file keeps
/// the one thing a host owns: which collapsed stretches the reader has opened,
/// and the mechanical walk that turns row fields into entries to draw.
public enum TimelineGrouping {
    /// Whether a membership-run id is a collapsed stretch the reader can open.
    public static func isStretch(_ id: String) -> Bool { id.hasPrefix("stretch:") }

    /// The entries to draw for `rows`, in order.
    ///
    /// - A row the reader hid (`hidden`) is left out; the core has already
    ///   grouped the others as if it were absent.
    /// - A row whose view is `.none` draws nothing and gets no entry. A cell
    ///   with no content does not reliably collapse to no height, so
    ///   deliberately silent has to mean absent, not empty.
    /// - A membership row with a `membership` placement joins a run entry
    ///   (`group:<first row>`, the sentence on the run's first row). When its
    ///   stretch is collapsed (and not in `expanded`) the whole stretch is
    ///   one entry (`stretch:<first row>`).
    /// - A membership row with no placement, which the core did not settle, is
    ///   drawn as the ordinary row it is.
    public static func displayRows(_ rows: [TimelineRow], expanded: Set<String>) -> [DisplayRow] {
        var out: [DisplayRow] = []
        // Where each open entry sits in `out`, so later rows of the same run
        // or stretch land in it.
        var entryIndex: [String: Int] = [:]

        func join(_ id: String, _ text: String?, _ row: TimelineRow) {
            if let index = entryIndex[id], case let .membershipRun(_, kept, rows) = out[index] {
                out[index] = .membershipRun(id: id, text: kept, rows: rows + [row])
            } else if let text {
                entryIndex[id] = out.count
                out.append(.membershipRun(id: id, text: text, rows: [row]))
            }
        }

        for row in rows {
            if row.hidden { continue }
            if case .none = row.view { continue }
            guard row.item.kind == "membership", let placement = row.membership else {
                out.append(.row(row))
                continue
            }
            if let stretch = placement.stretchId, !expanded.contains(stretch) {
                join(stretch, placement.stretchText, row)
            } else {
                join(placement.runId, placement.runText, row)
            }
        }
        return out
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
