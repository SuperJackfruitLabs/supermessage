import Foundation
import SupermessageFFI

/// The arithmetic a voice note's row needs, kept out of the view so it can be
/// tested without one.
///
/// Nothing here decides what the note *is* — that is `AudioView`, from the
/// core. This is fitting the core's bars to the width a row was given, and
/// choosing which of two core-formatted times to show.
public enum VoiceNotePresentation {
    /// A bar's width and the gap after it, in points.
    public static let barWidth: Double = 3
    public static let barGap: Double = 2
    /// The shortest a bar is drawn, as a fraction of the full height, so a
    /// silent stretch still reads as part of the note rather than a gap in it.
    public static let barFloor: Float = 0.12
    /// The level every bar is drawn at when the sender gave no waveform: an
    /// even row that says "audio" without pretending to know its shape.
    public static let placeholderLevel: Float = 0.3

    /// How many bars fit across `width` points.
    public static func barCount(width: Double) -> Int {
        guard width.isFinite, width > barWidth else { return 1 }
        return max(1, Int((width + barGap) / (barWidth + barGap)))
    }

    /// `waveform` resampled to `count` bars, each between ``barFloor`` and 1.
    ///
    /// Longer than `count`: each bar is the loudest of the levels it covers, so
    /// a short peak survives being squeezed. Shorter: levels are repeated
    /// across the bars they cover. `nil` or empty: the placeholder row.
    public static func bars(_ waveform: [Float]?, count: Int) -> [Float] {
        guard count > 0 else { return [] }
        guard let waveform, !waveform.isEmpty else {
            return Array(repeating: placeholderLevel, count: count)
        }
        return (0..<count).map { bar in
            let start = bar * waveform.count / count
            let end = max(start + 1, (bar + 1) * waveform.count / count)
            let peak = waveform[start..<min(end, waveform.count)].max() ?? 0
            return max(barFloor, min(1, peak))
        }
    }

    /// How many of `count` bars are drawn as played at `progress` (0…1).
    public static func playedBars(progress: Double, count: Int) -> Int {
        guard progress.isFinite, count > 0 else { return 0 }
        return min(count, max(0, Int((progress * Double(count)).rounded(.down))))
    }

    /// The time under the bars: the note's length at rest, and the clock
    /// while it is underway. Both formatted by the core.
    ///
    /// A note whose sender never said how long it is shows nothing at rest
    /// rather than "0:00", until the player has opened it and knows.
    public static func timeLabel(state: VoicePlayer.State, lengthLabel: String?) -> String {
        switch state {
        case let .playing(elapsed, _):
            return audioClockLabel(ms: elapsed)
        case let .paused(elapsed, duration):
            if elapsed > 0 { return audioClockLabel(ms: elapsed) }
            return lengthLabel ?? (duration > 0 ? audioClockLabel(ms: duration) : "")
        default:
            return lengthLabel ?? ""
        }
    }

    /// What VoiceOver says the player is doing, after the core's label.
    public static func accessibilityValue(state: VoicePlayer.State) -> String {
        switch state {
        case .idle: return ""
        case .loading: return "Loading"
        case let .playing(elapsed, _): return "Playing, \(audioClockLabel(ms: elapsed))"
        case let .paused(elapsed, _):
            return elapsed > 0 ? "Paused at \(audioClockLabel(ms: elapsed))" : ""
        case let .failed(message): return message
        }
    }

    /// The hint: what a double-tap will do.
    public static func accessibilityHint(state: VoicePlayer.State) -> String {
        switch state {
        case .playing: return "Double-tap to pause."
        case .failed: return "Double-tap to try again."
        case .loading: return ""
        default: return "Double-tap to play."
        }
    }
}
