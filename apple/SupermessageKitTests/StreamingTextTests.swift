import Testing

@testable import SupermessageKit

/// The pacing behind the streaming reveal.
///
/// The rule this exists to hold: **the network does not decide the animation
/// speed.** A model that emits twenty tokens in one frame and then pauses
/// would otherwise dump half a paragraph at once and then stall, which reads
/// as a fault in the app rather than in the model.
@MainActor
struct StreamingTextTests {
    @Test("a small backlog reveals a character at a time")
    func slowStream() {
        #expect(StreamingText.batch(forBacklog: 1) == 1)
        #expect(StreamingText.batch(forBacklog: 19) == 1)
    }

    @Test("a bigger backlog reveals faster, so a quick model is not held back")
    func fastStream() {
        #expect(StreamingText.batch(forBacklog: 50) == 2)
        #expect(StreamingText.batch(forBacklog: 200) == 4)
        #expect(StreamingText.batch(forBacklog: 5_000) == 12)
    }

    @Test("a batch never overruns what is actually waiting")
    func neverOverruns() {
        // The subscript that reveals a batch would trap past the end.
        for backlog in [0, 1, 2, 3] {
            #expect(StreamingText.batch(forBacklog: backlog) <= backlog)
        }
    }

    @Test("the same text twice changes nothing")
    func idempotent() {
        let s = StreamingText()
        s.accept("Hello")
        s.finish()
        let before = s.text
        s.accept("Hello")
        #expect(s.text == before)
    }

    @Test("finishing drains whatever was still waiting")
    func finishDrains() {
        // The turn has ended, so the reader is waiting on an animation rather
        // than on a model — the rest should land at once.
        let s = StreamingText()
        s.accept("The whole answer, arriving in one go.")
        s.finish()
        #expect(s.text == "The whole answer, arriving in one go.")
        #expect(s.revealed == 0, "nothing should still be animating once it has landed")
    }

    @Test("a stream that rewrites itself lands whole rather than animating nonsense")
    func rewriteLandsWhole() {
        // A resend after a reconnect: the new text is not an extension of
        // what is on screen, so there is no meaningful "new" part to fade in.
        let s = StreamingText()
        s.accept("First attempt")
        s.finish()
        s.accept("Completely different text")
        #expect(s.text == "Completely different text")
    }

    @Test("clearing forgets the turn entirely")
    func clearing() {
        let s = StreamingText()
        s.accept("Something")
        s.clear()
        #expect(s.text.isEmpty)
        #expect(s.revealed == 0)
    }

    // ─── Timed to the next delta ─────────────────────────────────────────

    /// Run the per-tick rule the way the reveal loop does, and report the
    /// tick each character landed on.
    private func reveal(backlog: Int, ticks: Double) -> [Int] {
        var credit = 0.0
        var left = backlog
        var landed: [Int] = []
        var tick = 0
        while left > 0, tick < 10_000 {
            let n = StreamingText.take(backlog: left, ticksLeft: ticks - Double(tick), credit: &credit)
            landed += Array(repeating: tick, count: n)
            left -= n
            tick += 1
        }
        return landed
    }

    @Test("a delta is spread over the gap to the next, not dumped at the backlog's speed")
    func spreadsOverTheGap() {
        // 60 characters, next delta due in 25 ticks (0.5s). The old rule
        // took its speed from the backlog alone, so the finish time was
        // luck: a 120-character delta at 4 a tick was done by tick 30 of a
        // 50-tick gap, and the text stood still for the rest.
        let landed = reveal(backlog: 60, ticks: 25)
        #expect(landed.count == 60)
        #expect(landed.last! >= 22, "finished at tick \(landed.last!), idling before the next delta")
        #expect(landed.last! <= 25, "still revealing at tick \(landed.last!), past the next delta")
    }

    @Test("a slow rate stays even rather than bursting")
    func evenAtSlowRates() {
        // 10 characters over 25 ticks: a character every 2-3 ticks, never
        // two in one tick and never a long hole.
        let landed = reveal(backlog: 10, ticks: 25)
        #expect(Set(landed).count == landed.count, "two characters landed in one tick")
        let gaps = zip(landed.dropFirst(), landed).map { $0 - $1 }
        #expect(gaps.allSatisfy { $0 <= 3 }, "uneven gaps \(gaps)")
    }

    @Test("past the deadline the text still moves")
    func movesPastTheDeadline() {
        var credit = 0.0
        var total = 0
        for _ in 0..<10 { total += StreamingText.take(backlog: 50, ticksLeft: -5, credit: &credit) }
        #expect(total >= 5)
    }

    @Test("a large backlog catches up whatever the estimate")
    func catchesUp() {
        var credit = 0.0
        #expect(StreamingText.take(backlog: 5_000, ticksLeft: 1_000, credit: &credit) >= 12)
    }

    @Test("the gap estimate follows the stream, within bounds")
    func intervalEstimate() {
        var estimate = StreamingText.defaultInterval
        for _ in 0..<20 { estimate = StreamingText.nextInterval(previous: estimate, gap: 0.3) }
        #expect(abs(estimate - 0.3) < 0.01)
        #expect(StreamingText.nextInterval(previous: 0.5, gap: 30) <= StreamingText.intervalRange.upperBound)
        #expect(StreamingText.nextInterval(previous: 0.5, gap: 0) >= StreamingText.intervalRange.lowerBound * 0.4)
    }

    @Test("a take never overruns what is waiting")
    func takeNeverOverruns() {
        for backlog in 0..<5 {
            var credit = 3.7
            #expect(StreamingText.take(backlog: backlog, ticksLeft: 0.1, credit: &credit) <= backlog)
        }
    }
}
