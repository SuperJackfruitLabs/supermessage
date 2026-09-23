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

    /// Collapse consecutive membership changes into as few lines as are true.
    ///
    /// Ported from the desktop's `groupTimelineItems`, which iOS never had —
    /// so a room drew every single one, ten identical "updated their
    /// membership" lines deep in Ganesha's history.
    ///
    /// Two rules, applied to each unbroken stretch of membership rows:
    ///
    /// 1. **One person's churn is one line**, whatever the verbs. An agent
    ///    whose bridge reconnects joins, leaves and joins again; three lines
    ///    about that were three lines of nothing. Consecutive changes about
    ///    the same person become "Strategy Sam joined the room, then left the
    ///    room", or "Strategy Sam made 3 membership changes".
    /// 2. **Different people sharing a verb are one sentence.** Runs of those
    ///    still break on a **different verb**, so "three joined" and "one
    ///    left" stay two sentences rather than becoming one that is true of
    ///    neither.
    ///
    /// A run of exactly one reads exactly like the ungrouped line the core
    /// already composes, never "Alice and 0 others".
    public static func collapseMembershipRuns(_ rows: [TimelineRow]) -> [DisplayRow] {
        var out: [DisplayRow] = []
        var stretch: [TimelineRow] = []

        // `ItemView.none` is the core saying "draw nothing". A row for it is
        // still a row: a cell with no content does not reliably collapse to no
        // height, and one turned up on screen as roughly three hundred points
        // of blank in the middle of two different rooms. Deliberately silent
        // should mean *absent*, not empty.
        let rows = rows.filter { row in
            if case .none = row.view { return false }
            return true
        }

        func emit(_ run: [TimelineRow], _ text: String) {
            guard let first = run.first else { return }
            out.append(.membershipRun(id: "group:\(first.item.id)", text: text, rows: run))
        }

        func flushStretch() {
            guard !stretch.isEmpty else { return }
            // Rule 1: consecutive changes about one person.
            var segments: [[TimelineRow]] = []
            for row in stretch {
                if let last = segments.last?.last, person(last) == person(row) {
                    segments[segments.count - 1].append(row)
                } else {
                    segments.append([row])
                }
            }
            // Rule 2: neighbours that each made one kind of change, sharing it.
            var run: [TimelineRow] = []
            for segment in segments {
                let verbs = Set(segment.map(verb))
                if verbs.count == 1 {
                    if let first = run.first, verb(first) != verb(segment[0]) {
                        emit(run, text(for: run))
                        run = []
                    }
                    run.append(contentsOf: segment)
                } else {
                    emit(run, text(for: run))
                    run = []
                    emit(segment, churnText(for: segment))
                }
            }
            emit(run, text(for: run))
            stretch = []
        }

        for row in rows {
            guard row.item.kind == "membership" else {
                flushStretch()
                out.append(.row(row))
                continue
            }
            stretch.append(row)
        }
        flushStretch()
        return out
    }

    /// Who a membership change is about, for telling one person's churn from
    /// a crowd's: the name the line opens with. The subject rather than the
    /// sender, so "Rakesh invited Sam" and "Sam joined" are both about Sam.
    private static func person(_ row: TimelineRow) -> String { name(row) }

    /// The name a membership line opens with: the person it is about.
    private static func name(_ row: TimelineRow) -> String {
        row.item.membershipSubject ?? row.senderShort
    }

    private static func verb(_ row: TimelineRow) -> String {
        row.membershipVerb ?? "updated their membership"
    }

    /// The sentence for one person's churn — two or more changes that are
    /// not all the same.
    ///
    /// Two read as what happened, in order, in the core's own verbs. Past
    /// that a list of verbs is a paragraph, and the count is what a reader
    /// wanted: that nothing about this person's place in the room is news.
    static func churnText(for run: [TimelineRow]) -> String {
        guard let first = run.first else { return "" }
        let verbs = run.map(verb)
        if verbs.count == 2 {
            return "\(name(first)) \(verbs[0]), then \(verbs[1])"
        }
        return "\(name(first)) made \(verbs.count) membership changes"
    }

    /// The sentence for one run of a single verb.
    ///
    /// Both halves come from the core: the verb is carried on the row *apart*
    /// from the rendered sentence precisely so a run can be composed from many
    /// names and one verb without parsing that sentence back apart.
    static func text(for run: [TimelineRow]) -> String {
        let verb = run.first.map(verb) ?? "updated their membership"
        // Distinct people, in order. One member can make several changes in
        // a row, and the desktop and Android both learned this from a real
        // room: "Annapurna, Annapurna and 1 other updated their membership".
        var seen = Set<String>()
        let names = run.map(name).filter { seen.insert($0).inserted }
        if names.count <= maxNamed {
            return "\(joined(names)) \(verb)"
        }
        let named = names.prefix(maxNamed).joined(separator: ", ")
        let remaining = names.count - maxNamed
        return "\(named) and \(remaining) \(remaining == 1 ? "other" : "others") \(verb)"
    }

    // MARK: - Replies

    /// Whether `row`'s reply quote only repeats the row drawn directly above
    /// it.
    ///
    /// A quote exists to put the parent back in front of the reader. When
    /// the parent *is* in front of them — the very next row up — the quote is
    /// the same words twice, one of them in grey. `previous` is the row the
    /// list actually draws above this one, `nil` when something else (a
    /// collapsed run, the top of the history) sits there.
    public static func quoteRepeatsPrevious(_ row: TimelineRow, after previous: TimelineRow?) -> Bool {
        guard let parent = row.item.replyTo?.eventId, let previous,
            let above = previous.item.eventId
        else { return false }
        return parent == above
    }

    // MARK: - Agents

    /// Whether this row was sent by an agent.
    ///
    /// **A stopgap, stated as one.** The core does not yet mark agents on a
    /// timeline row; AgentPod's bridge gives every agent a Matrix id under
    /// the `@agent_` namespace (see `RoomInfoPanel`), and that namespace is
    /// the only signal a row carries today. When the core grows a field this
    /// should read it and nothing else.
    public static func isAgent(_ row: TimelineRow) -> Bool {
        guard !row.item.isOwn, let sender = row.item.sender else { return false }
        return sender.hasPrefix("@agent_")
    }

    // MARK: - Long reads

    /// Past this many characters a message is a report rather than a line,
    /// and gets a "Read" affordance onto the long-read view.
    public static let longReadCharacters = 600

    /// Whether `row` is long enough to be offered as a long read.
    ///
    /// Counted in `Character`s — what a reader sees — rather than UTF-16
    /// units, so a report in Devanagari or emoji is not cut short by its
    /// encoding. Your own messages never are: you know what you wrote.
    public static func isLongRead(_ row: TimelineRow) -> Bool {
        guard !row.item.isOwn, let body = row.item.body else { return false }
        return body.count > longReadCharacters
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
