import Foundation

/// How long a turn has been running, as the activity card says it.
///
/// Short and fixed-width in the part that ticks: "8s", "1m 04s", "1h 02m".
/// The seconds are zero-padded past the first minute so the label does not
/// change width every tick and nudge the text beside it — with monospaced
/// digits in the view, only the digits move.
public enum ElapsedTime {
    public static func label(_ interval: TimeInterval) -> String {
        let total = max(0, Int(interval.rounded(.down)))
        let hours = total / 3600
        let minutes = (total % 3600) / 60
        let seconds = total % 60
        if hours > 0 { return "\(hours)h " + pad(minutes) + "m" }
        if minutes > 0 { return "\(minutes)m " + pad(seconds) + "s" }
        return "\(seconds)s"
    }

    private static func pad(_ value: Int) -> String {
        value < 10 ? "0\(value)" : "\(value)"
    }
}
