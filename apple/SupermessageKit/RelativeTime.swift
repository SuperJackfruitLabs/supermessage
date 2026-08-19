import Foundation

/// How a roster says when.
///
/// Coarsened the way a person would: minutes for the last hour, a clock time
/// for today, a weekday for this week, a date beyond that. Never "0m" and
/// never a precision nobody asked for.
///
/// Takes `now` rather than reading the clock, so the rules can be tested at a
/// fixed instant instead of being restated in the test.
public enum RelativeTime {
    public static func label(for ms: UInt64?, now: Date, calendar: Calendar = .current) -> String {
        guard let ms else { return "" }
        let then = Date(timeIntervalSince1970: Double(ms) / 1000)
        let elapsed = now.timeIntervalSince(then)

        // A message from a second ago is "now", not "0m". The unit only starts
        // being useful once there is one of them.
        if elapsed < 60 { return "now" }
        if elapsed < 60 * 60 { return "\(Int(elapsed / 60))m" }

        if calendar.isDate(then, inSameDayAs: now) {
            let time = DateFormatter()
            time.locale = .current
            time.setLocalizedDateFormatFromTemplate("jmm")
            return time.string(from: then)
        }

        // A weekday only means something while it is unambiguous. Beyond six
        // days "Tuesday" could be either of two Tuesdays.
        if elapsed < 60 * 60 * 24 * 6 {
            let weekday = DateFormatter()
            weekday.locale = .current
            weekday.setLocalizedDateFormatFromTemplate("EEE")
            return weekday.string(from: then)
        }

        let date = DateFormatter()
        date.locale = .current
        // No year within this one: it is noise on all but a handful of rows.
        let sameYear = calendar.component(.year, from: then) == calendar.component(.year, from: now)
        date.setLocalizedDateFormatFromTemplate(sameYear ? "d MMM" : "d MMM yyyy")
        return date.string(from: then)
    }
}
