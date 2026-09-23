import Foundation
import Observation

/// Paces an agent's answer onto the screen.
///
/// **The network must not decide the animation speed.** A model that emits
/// twenty tokens in one frame and then pauses produces bursts — half a
/// paragraph appearing at once, then nothing — and a slow model produces a
/// stutter. Both look like a fault in the app rather than in the model.
///
/// So deltas go into a buffer and are revealed on this type's own clock. What
/// the view renders is `text`; what arrived is `pending`, and the gap between
/// them is what keeps the reveal steady whatever the model does.
///
/// **The reveal is timed to the next delta, not to the backlog.** Each delta
/// is spread over the time the next one is expected to take (a running
/// average of the gaps so far), so the text finishes just as more arrives.
/// The version this replaced chose its speed from the backlog alone: a
/// sentence-sized delta ran out in 0.3s when deltas came every 0.5s, and the
/// text went stop-go — the second half of the 2026-09-23 Guild stutter.
///
/// The core already de-duplicates and orders the stream (`live::accept`), and
/// each delta is the **whole answer so far** rather than an increment — so
/// this takes the full text and works out what is new, rather than appending.
@MainActor
@Observable
public final class StreamingText {
    /// What is on screen.
    public private(set) var text = ""
    /// How many characters of `text` are new enough to still be animating in.
    ///
    /// The view fades exactly these. Without it the whole paragraph would
    /// re-animate on every tick — the trap with a plain `contentTransition`,
    /// which transitions far more of the string than intended.
    public private(set) var revealed = 0

    private var pending = ""
    private var task: Task<Void, Never>?
    /// When the reveal of what is pending should be done: the next delta's
    /// expected arrival.
    private var deadline = ContinuousClock.now
    private var lastArrival: ContinuousClock.Instant?
    /// The running estimate of the gap between deltas.
    private var interval = StreamingText.defaultInterval
    /// Fractional characters owed, so a rate below one per tick still
    /// reveals evenly rather than rounding up and finishing early.
    private var credit = 0.0

    /// How long between reveals. Short enough to read as motion rather than
    /// as steps, long enough that each tick is a frame's worth of work.
    static let tick = Duration.milliseconds(20)

    /// The gap assumed before two deltas have been seen. About what both
    /// the hub and the Hermes plugin produce: a sentence at a time.
    static let defaultInterval = 0.5
    /// Bounds on the estimate. Below: a burst of deltas should not make the
    /// reveal frantic. Above: a model that pauses to think should not make
    /// the next sentence crawl out over seconds.
    static let intervalRange = 0.15...1.5

    public init() {}

    /// Accept the answer as it stands. Idempotent: the same text twice does
    /// nothing.
    public func accept(_ full: String) {
        guard full != text + pending else { return }

        // A stream that rewrote its history rather than extending it — a
        // resend after a reconnect, say. Nothing sensible can be animated
        // out of that, so it lands whole.
        guard full.hasPrefix(text) else {
            finish(full)
            return
        }

        let now = ContinuousClock.now
        if let last = lastArrival {
            interval = Self.nextInterval(previous: interval, gap: (now - last).seconds)
        }
        lastArrival = now
        deadline = now + .seconds(interval)
        pending = String(full.dropFirst(text.count))
        start()
    }

    /// The turn ended. Drain whatever is left immediately: the reader is now
    /// waiting on an animation rather than on a model.
    public func finish(_ full: String? = nil) {
        task?.cancel()
        task = nil
        if let full { text = full } else { text += pending }
        pending = ""
        revealed = 0
        credit = 0
    }

    public func clear() {
        task?.cancel()
        task = nil
        text = ""
        pending = ""
        revealed = 0
        credit = 0
        lastArrival = nil
        interval = Self.defaultInterval
    }

    private func start() {
        guard task == nil else { return }
        task = Task { [weak self] in
            while let self, !self.pending.isEmpty {
                let ticksLeft = (self.deadline - ContinuousClock.now).seconds / Self.tick.seconds
                let take = Self.take(
                    backlog: self.pending.count, ticksLeft: ticksLeft, credit: &self.credit)
                if take > 0 {
                    let end = self.pending.index(self.pending.startIndex, offsetBy: take)
                    self.text += self.pending[..<end]
                    self.pending.removeSubrange(..<end)
                    self.revealed = take
                }
                try? await Task.sleep(for: Self.tick)
                if Task.isCancelled { return }
            }
            self?.revealed = 0
            self?.task = nil
        }
    }

    /// How many characters to reveal this tick.
    ///
    /// The backlog spread evenly over the ticks left before the next delta
    /// is due, carried as fractional `credit` between ticks. Two floors:
    /// never slower than one character every other tick — past the deadline
    /// the text must still move — and never slower than `batch`'s catch-up
    /// speed once the backlog is large, so a model far ahead of the screen
    /// is not held back by an optimistic estimate.
    static func take(backlog: Int, ticksLeft: Double, credit: inout Double) -> Int {
        guard backlog > 0 else { return 0 }
        var rate = max(0.5, Double(backlog) / max(1, ticksLeft))
        if backlog >= 400 { rate = max(rate, Double(batch(forBacklog: backlog))) }
        credit += rate
        let whole = min(backlog, Int(credit))
        credit -= Double(whole)
        return whole
    }

    /// The next estimate of the gap between deltas: a running average,
    /// weighted to the recent, within `intervalRange`.
    static func nextInterval(previous: Double, gap: Double) -> Double {
        let clamped = min(max(gap, intervalRange.lowerBound), intervalRange.upperBound)
        return previous * 0.6 + clamped * 0.4
    }

    /// The catch-up speed for a large backlog: how many characters a tick
    /// may reveal at least, whatever the estimate says.
    static func batch(forBacklog backlog: Int) -> Int {
        let size: Int
        switch backlog {
        case ..<20: size = 1
        case ..<100: size = 2
        case ..<400: size = 4
        default: size = 12
        }
        return min(size, backlog)
    }
}

extension Duration {
    /// This duration in seconds, as a `Double`.
    var seconds: Double {
        let (whole, attos) = components
        return Double(whole) + Double(attos) / 1e18
    }
}
