import Foundation
import Testing

@testable import SupermessageKit
import SupermessageFFI

/// The walk from the core's grouping to the entries the timeline draws. The
/// grouping rules themselves are the core's, tested in
/// `timeline_grouping.rs`; these use the real `groupTimelineRows` so they
/// would notice the two disagreeing about what a stretch or a run is.
@MainActor
struct DisplayRowTests {
    static func item(_ id: String, kind: String, sender: String, at ms: UInt64) -> TimelineItemDto {
        TimelineItemDto(
            id: id, eventId: id, kind: kind, msgtype: kind == "message" ? "m.text" : nil,
            detail: nil, sender: sender, senderDisplayName: nil, senderAvatar: nil, body: "hi",
            formattedBody: nil, media: nil, customPayload: nil, timestampMs: ms, isOwn: false,
            sendState: nil, replyTo: nil, edited: false, reactions: [], readBy: [],
            editable: false, membershipSubject: nil)
    }

    static func message(_ id: String, at ms: UInt64 = 1) -> TimelineRow {
        TimelineRow(
            item: item(id, kind: "message", sender: "@a:x", at: ms),
            view: .bubble(muted: false, blocks: [], voice: nil), senderName: "a", senderShort: "a",
            senderInitial: "A", membershipVerb: nil, replyQuote: nil, canReplyOrReact: true,
            replyPreview: nil)
    }

    static func silent(_ id: String) -> TimelineRow {
        TimelineRow(
            item: item(id, kind: "state", sender: "@a:x", at: 1), view: .none, senderName: "a",
            senderShort: "a", senderInitial: "A", membershipVerb: nil, replyQuote: nil,
            canReplyOrReact: false, replyPreview: nil)
    }

    static func membership(_ id: String, _ name: String, _ verb: String) -> TimelineRow {
        TimelineRow(
            item: item(id, kind: "membership", sender: "@\(name):x", at: 1),
            view: .system(
                kind: .membershipChanged(who: name, detail: verb), text: "\(name) \(verb)"),
            senderName: name, senderShort: name, senderInitial: "?", membershipVerb: verb,
            replyQuote: nil, canReplyOrReact: false, replyPreview: nil)
    }

    /// "<row id>" for a plain row, "<entry id>: <text> (<n>)" for a run.
    static func describe(_ entries: [DisplayRow]) -> [String] {
        entries.map { entry in
            switch entry {
            case let .row(row): return "<row \(row.item.id)>"
            case let .membershipRun(id, text, rows): return "\(id): \(text) (\(rows.count))"
            }
        }
    }

    static let churn = [
        membership("1", "Alice", "joined the room"),
        membership("2", "Bob", "left the room"),
        membership("3", "Carol", "was invited"),
    ]

    @Test("a collapsed stretch is one entry until it is opened")
    func collapsedStretch() {
        let rows = groupTimelineRows(rows: [Self.message("m0")] + Self.churn + [Self.message("m9")])
        let out = TimelineGrouping.displayRows(rows, expanded: [])
        #expect(
            Self.describe(out) == [
                "<row m0>", "stretch:1: 3 membership changes · Alice, Bob and 1 other (3)", "<row m9>",
            ])
        #expect(TimelineGrouping.isStretch(out[1].id))
    }

    @Test("an opened stretch shows each run's line")
    func openedStretch() {
        let rows = groupTimelineRows(rows: Self.churn)
        let out = TimelineGrouping.displayRows(rows, expanded: ["stretch:1"])
        #expect(
            Self.describe(out) == [
                "group:1: Alice joined the room (1)", "group:2: Bob left the room (1)",
                "group:3: Carol was invited (1)",
            ])
        #expect(!out.contains { TimelineGrouping.isStretch($0.id) })
    }

    @Test("a run of one change is one entry holding every row of it")
    func runIsOneEntry() {
        let rows = groupTimelineRows(rows: [
            Self.membership("1", "Alice", "joined the room"),
            Self.membership("2", "Bob", "joined the room"),
        ])
        #expect(
            Self.describe(TimelineGrouping.displayRows(rows, expanded: []))
                == ["group:1: Alice and Bob joined the room (2)"])
    }

    @Test("a row that draws nothing gets no entry")
    func silentRowHasNoEntry() {
        let rows = groupTimelineRows(rows: [Self.message("a"), Self.silent("s"), Self.message("b")])
        #expect(Self.describe(TimelineGrouping.displayRows(rows, expanded: [])) == ["<row a>", "<row b>"])
        // And it does not break the run either: the core skips it too.
        #expect(rows[2].continuesRun)
    }

    @Test("a hidden row gets no entry, and the row after it is grouped as if it were absent")
    func hiddenRowHasNoEntry() {
        var head = Self.message("a", at: 1_000)
        head.hidden = true
        let rows = groupTimelineRows(rows: [head, Self.message("b", at: 2_000)])
        #expect(Self.describe(TimelineGrouping.displayRows(rows, expanded: [])) == ["<row b>"])
        // The core settled it: with its head hidden, "b" starts the run.
        #expect(!rows[1].continuesRun)
    }
}

/// What a day divider says.
struct TimelineDayTests {
    static func calendar(_ zone: String) -> Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: zone)!
        return calendar
    }

    static let british = Locale(identifier: "en_GB")

    /// 2026-09-23 10:00 UTC.
    static let now = Date(timeIntervalSince1970: 1_790_157_600)

    static func ms(_ iso: String) -> UInt64 {
        UInt64(ISO8601DateFormatter().date(from: iso)!.timeIntervalSince1970 * 1000)
    }

    static func label(_ iso: String, zone: String = "UTC", now: Date = now) -> String {
        TimelineDay.label(ms(iso), now: now, calendar: calendar(zone), locale: british)
    }

    @Test("today and yesterday are words")
    func words() {
        #expect(Self.label("2026-09-23T00:05:00Z") == "Today")
        #expect(Self.label("2026-09-22T23:55:00Z") == "Yesterday")
    }

    @Test("an older day this year is a day and a month, in sentence case")
    func thisYear() {
        #expect(Self.label("2026-09-15T12:00:00Z") == "15 September")
        #expect(Self.label("2026-09-21T12:00:00Z") == "21 September")
    }

    @Test("a day in another year says which")
    func anotherYear() {
        #expect(Self.label("2025-09-15T12:00:00Z") == "15 September 2025")
    }

    @Test("today is the reader's today, not UTC's")
    func readersZone() {
        // 20:00 UTC on the 22nd is 01:30 on the 23rd in Kolkata, where it is
        // 07:30 now. One timestamp, two true answers.
        let early = Date(timeIntervalSince1970: 1_790_128_800)  // 2026-09-23 02:00 UTC
        #expect(Self.label("2026-09-22T20:00:00Z", zone: "Asia/Kolkata", now: early) == "Today")
        #expect(Self.label("2026-09-22T20:00:00Z", zone: "UTC", now: early) == "Yesterday")
    }

    @Test("no timestamp, no words")
    func missing() {
        #expect(TimelineDay.label(nil) == "")
    }
}
