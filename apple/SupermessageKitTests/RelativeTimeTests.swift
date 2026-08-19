import Foundation
import Testing

@testable import SupermessageKit

struct RelativeTimeTests {
    /// Fixed rather than the device's, so these rules are tested at one
    /// instant in one zone rather than wherever the runner happens to be.
    static func calendar() -> Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        return calendar
    }
    /// A Wednesday, mid-afternoon UTC.
    static let now = Date(timeIntervalSince1970: 1_755_700_000)

    static func ago(_ seconds: Double) -> UInt64 {
        UInt64((now.timeIntervalSince1970 - seconds) * 1000)
    }

    static func label(_ seconds: Double) -> String {
        RelativeTime.label(for: ago(seconds), now: now, calendar: calendar())
    }

    @Test("the first minute is now, not zero")
    func neverZeroMinutes() {
        // "0m" is a unit reporting that it has nothing to report.
        #expect(Self.label(5) == "now")
        #expect(Self.label(59) == "now")
    }

    @Test("the first hour counts in minutes")
    func minutes() {
        #expect(Self.label(60) == "1m")
        #expect(Self.label(18 * 60) == "18m")
    }

    @Test("later today is a clock time")
    func today() {
        // Not "7h": within a day, when something happened is more useful than
        // how long ago it was.
        let label = Self.label(7 * 3600)
        #expect(label.contains(":"), "expected a clock time, got \(label)")
    }

    @Test("this week is a weekday, beyond it is a date")
    func weekdayThenDate() {
        // A weekday stops meaning anything once there could be two of them.
        let recent = Self.label(3 * 24 * 3600)
        #expect(recent.count <= 4, "expected a weekday, got \(recent)")

        let older = Self.label(20 * 24 * 3600)
        #expect(older.count > 4, "expected a date, got \(older)")
    }

    @Test("nothing to say says nothing")
    func noTimestamp() {
        #expect(RelativeTime.label(for: nil, now: Self.now, calendar: Self.calendar()) == "")
    }
}
