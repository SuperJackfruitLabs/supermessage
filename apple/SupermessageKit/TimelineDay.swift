import Foundation

/// The words a day divider says.
///
/// "Today", "Yesterday", then the day itself — "15 September", with the year
/// only when it is not this one. Sentence case, and the order the reader's
/// locale puts a day and a month in: "15 September" in Britain and India,
/// "September 15" in the US.
///
/// **Here rather than in the core** because it reads a clock, a calendar and
/// a locale, none of which the core can honestly know; the core sends the
/// timestamp. Here rather than in the view so it can be tested at a fixed
/// "now" in a fixed zone — a divider that says "Yesterday" about today is a
/// timezone bug, and those only show up when the zone is pinned.
public enum TimelineDay {
    public static func label(
        _ ms: UInt64?, now: Date = Date(), calendar: Calendar = .current,
        locale: Locale = .current
    ) -> String {
        guard let ms else { return "" }
        let date = Date(timeIntervalSince1970: Double(ms) / 1000)
        if calendar.isDate(date, inSameDayAs: now) { return "Today" }
        if let yesterday = calendar.date(byAdding: .day, value: -1, to: now),
            calendar.isDate(date, inSameDayAs: yesterday)
        {
            return "Yesterday"
        }
        let formatter = DateFormatter()
        formatter.calendar = calendar
        formatter.timeZone = calendar.timeZone
        formatter.locale = locale
        let sameYear =
            calendar.component(.year, from: date) == calendar.component(.year, from: now)
        formatter.setLocalizedDateFormatFromTemplate(sameYear ? "dMMMM" : "dMMMMy")
        return formatter.string(from: date)
    }
}
