import Testing

@testable import SupermessageKit

/// Fitting the core's bars to a row, and which of the core's times to show.
struct VoiceNotePresentationTests {
    typealias P = VoiceNotePresentation

    @Test("bars fit the width at a fixed pitch")
    func barCount() {
        // 3pt bar + 2pt gap: 60 bars in 298pt (60×3 + 59×2).
        #expect(P.barCount(width: 298) == 60)
        #expect(P.barCount(width: 297) == 59)
        #expect(P.barCount(width: 0) == 1)
        #expect(P.barCount(width: .infinity) == 1)
    }

    @Test("a long waveform keeps its peaks when squeezed")
    func squeezeKeepsPeaks() {
        let levels: [Float] = [0, 0, 0, 0.9, 0, 0, 0, 0]
        #expect(P.bars(levels, count: 2) == [0.9, P.barFloor])
    }

    @Test("a short waveform is stretched, not padded with silence")
    func stretch() {
        #expect(P.bars([0.2, 0.8], count: 4) == [0.2, 0.2, 0.8, 0.8])
    }

    @Test("silence still draws a stub, and nothing exceeds full height")
    func floorAndCeiling() {
        #expect(P.bars([0, 1, 1.5], count: 3) == [P.barFloor, 1, 1])
    }

    @Test("no waveform draws the even placeholder row")
    func placeholder() {
        #expect(P.bars(nil, count: 3) == [P.placeholderLevel, P.placeholderLevel, P.placeholderLevel])
        #expect(P.bars([], count: 2) == [P.placeholderLevel, P.placeholderLevel])
        #expect(P.bars([0.5], count: 0).isEmpty)
    }

    @Test("played bars follow progress, and never overrun")
    func playedBars() {
        #expect(P.playedBars(progress: 0, count: 60) == 0)
        #expect(P.playedBars(progress: 0.5, count: 60) == 30)
        #expect(P.playedBars(progress: 0.499, count: 60) == 29)
        #expect(P.playedBars(progress: 1, count: 60) == 60)
        #expect(P.playedBars(progress: 1.4, count: 60) == 60)
        #expect(P.playedBars(progress: -1, count: 60) == 0)
        #expect(P.playedBars(progress: .nan, count: 60) == 0)
    }

    @Test("at rest the length, underway the clock — both the core's words")
    func timeLabel() {
        #expect(P.timeLabel(state: .idle, lengthLabel: "0:07") == "0:07")
        #expect(P.timeLabel(state: .loading, lengthLabel: "0:07") == "0:07")
        #expect(
            P.timeLabel(state: .playing(elapsedMs: 3_900, durationMs: 7_400), lengthLabel: "0:07")
                == "0:03")
        #expect(
            P.timeLabel(state: .paused(elapsedMs: 65_000, durationMs: 90_000), lengthLabel: "1:30")
                == "1:05")
        // Finished: back at rest.
        #expect(
            P.timeLabel(state: .paused(elapsedMs: 0, durationMs: 7_400), lengthLabel: "0:07") == "0:07")
        // A note that never said how long it is learns it from the player.
        #expect(P.timeLabel(state: .paused(elapsedMs: 0, durationMs: 12_000), lengthLabel: nil) == "0:12")
        #expect(P.timeLabel(state: .idle, lengthLabel: nil) == "")
    }

    @Test("VoiceOver hears what a double-tap will do")
    func accessibility() {
        #expect(P.accessibilityHint(state: .idle) == "Double-tap to play.")
        #expect(P.accessibilityHint(state: .playing(elapsedMs: 1, durationMs: 2)) == "Double-tap to pause.")
        #expect(P.accessibilityHint(state: .failed("x")) == "Double-tap to try again.")
        #expect(P.accessibilityValue(state: .playing(elapsedMs: 3_000, durationMs: 7_000)) == "Playing, 0:03")
        #expect(P.accessibilityValue(state: .paused(elapsedMs: 3_000, durationMs: 7_000)) == "Paused at 0:03")
        #expect(P.accessibilityValue(state: .paused(elapsedMs: 0, durationMs: 7_000)) == "")
    }
}
